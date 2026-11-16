#include "desktop_audio_engine.h"

#include <algorithm>
#include <chrono>
#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <future>
#include <string>
#include <vector>

#ifdef _WIN32
#include <objbase.h>
#include <windows.h>
#endif

#include "RtAudio.h"

namespace padsampler {

namespace {
constexpr int kMaxRetries = 3;  // ponovna otvaranja prije prelaska na podrazumijevani izlaz

std::string envStr(const char* name) {
    const char* v = std::getenv(name);
    return v ? std::string(v) : std::string();
}
}  // namespace

AudioEngine::AudioEngine() {
    mControl = std::thread([this] { controlLoop(); });
    mControlId = mControl.get_id();
}

AudioEngine::~AudioEngine() {
    // Pri izlazu iz procesa (statički objekat u DLL-u) se ne čeka kontrolna nit: Kotlin je već zaustavio zvuk
    // (NativeAudio.stop()), a čekanje niti dok se DLL isključuje može zaglaviti proces.
    {
        std::lock_guard<std::mutex> l(mTaskMutex);
        mControlStop = true;
    }
    mTaskCv.notify_all();
    if (mControl.joinable()) mControl.detach();
    if (mNullRun.exchange(false) && mNullThread.joinable()) mNullThread.join();
}

// ---------------------------------------------------------------- kontrolna nit

template <typename F>
auto AudioEngine::onControl(F&& f) -> decltype(f()) {
    using R = decltype(f());
    if (std::this_thread::get_id() == mControlId) return f();
    auto task = std::make_shared<std::packaged_task<R()>>(std::forward<F>(f));
    std::future<R> result = task->get_future();
    {
        std::lock_guard<std::mutex> l(mTaskMutex);
        mTasks.push_back([task] { (*task)(); });
    }
    mTaskCv.notify_one();
    return result.get();
}

void AudioEngine::controlLoop() {
#ifdef _WIN32
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);  // ASIO drajveri su COM objekti (STA)
#endif
    auto lastCheck = std::chrono::steady_clock::now();
    while (true) {
        std::deque<std::function<void()>> tasks;
        {
            std::unique_lock<std::mutex> l(mTaskMutex);
            mTaskCv.wait_for(l, std::chrono::milliseconds(20), [this] { return mControlStop || !mTasks.empty(); });
            if (mControlStop) break;
            tasks.swap(mTasks);
        }
        for (auto& t : tasks) t();
#ifdef _WIN32
        // STA nit mora obrađivati poruke (neki drajveri ih koriste)
        MSG msg;
        while (PeekMessage(&msg, nullptr, 0, 0, PM_REMOVE)) {
            TranslateMessage(&msg);
            DispatchMessage(&msg);
        }
#endif
        const auto now = std::chrono::steady_clock::now();
        if (now - lastCheck > std::chrono::milliseconds(250)) {
            lastCheck = now;
            watchdog();
        }
    }
#ifdef _WIN32
    CoUninitialize();
#endif
}

void AudioEngine::watchdog() {
    if (!mWantRunning || mNullRun.load()) return;
    bool dead;
    {
        std::lock_guard<std::mutex> lock(mLock);
        dead = mAudio && (mDisconnected.load() || !mAudio->isStreamOpen());
    }
    if (!dead) {
        if (mRetries > 0 && std::chrono::steady_clock::now() - mStartedAt > std::chrono::seconds(3)) mRetries = 0;
        return;
    }
    // Drajver je zatvorio stream (npr. reset nakon promjene frekvencije). RtAudio to radi u svojoj niti:
    // pričekaj da završi, pa otvori ponovo. Prvi put na istoj frekvenciji (neki drajveri resetuju samo jednom),
    // zatim na drugoj koju uređaj podržava (npr. UMC drži 44100 Hz dok ga Windows koristi na toj frekvenciji).
    std::fprintf(stderr, "[PadSampler] audio: drajver je zatvorio stream, ponovno otvaranje (%d)\n", mRetries + 1);
    std::this_thread::sleep_for(std::chrono::milliseconds(400));
    const unsigned failedRate = unsigned(mSampleRate.load());
    stopImpl();
    if (mRetries > 0 && std::find(mTriedRates.begin(), mTriedRates.end(), failedRate) == mTriedRates.end()) {
        mTriedRates.push_back(failedRate);
    }
    if (mRetries > 0) {
        // sljedeća frekvencija: prvo uobičajene (44100, 48000), pa ostale koje uređaj podržava
        std::vector<unsigned> order = {44100, 48000};
        for (unsigned r : mDeviceRates) if (r >= 22050 && r <= 192000) order.push_back(r);
        mRateOverride = 0;
        for (unsigned r : order) {
            const bool supported = mDeviceRates.empty() ||
                                   std::find(mDeviceRates.begin(), mDeviceRates.end(), r) != mDeviceRates.end();
            if (supported && std::find(mTriedRates.begin(), mTriedRates.end(), r) == mTriedRates.end()) {
                mRateOverride = r;
                break;
            }
        }
        if (mRateOverride == 0) mRetries = kMaxRetries;  // nema više kandidata
    }
    if (mRetries++ < kMaxRetries && startImpl()) {
        if (mRateOverride != 0) {
            std::lock_guard<std::mutex> lock(mLock);
            char buf[160];
            std::snprintf(buf, sizeof buf, "The driver did not accept %.1f kHz, using %.1f kHz", failedRate / 1000.0,
                          mRateOverride / 1000.0);
            mNotice = buf;
        }
        return;
    }

    // Ne ide: podrazumijevani izlaz (WASAPI, sistemski uređaj), da zvuk ne nestane. Podešavanja ostaju sačuvana.
    std::string failed;
    {
        std::lock_guard<std::mutex> lock(mLock);
        failed = mConfig.api.empty() ? "audio" : mConfig.api;
        mConfig.api.clear();
        mConfig.device.clear();
        mConfig.rate = 0;
    }
    mRateOverride = 0;
    mTriedRates.clear();
    const bool ok = startImpl();
    mRetries = 0;
    std::lock_guard<std::mutex> lock(mLock);
    for (auto& c : failed) c = char(std::toupper(static_cast<unsigned char>(c)));
    mNotice = ok ? failed + " stopped, switched to the default output" : failed + " stopped, no audio output";
}

// ---------------------------------------------------------------- audio

int AudioEngine::rtCallback(void* out, void*, unsigned frames, double, unsigned status, void* user) {
    auto* e = static_cast<AudioEngine*>(user);
    if (status & RTAUDIO_OUTPUT_UNDERFLOW) e->mUnderruns.fetch_add(1, std::memory_order_relaxed);
    e->mMixer.render(static_cast<float*>(out), static_cast<int32_t>(frames));
    return 0;
}

void AudioEngine::nullLoop() {
    // Bez zvučne kartice: računa u pravom vremenu (za testove na mašinama bez zvuka)
    const int frames = mBufferSize.load();
    const int rate = mSampleRate.load();
    std::vector<float> buf(size_t(frames) * 2);
    auto next = std::chrono::steady_clock::now();
    while (mNullRun.load()) {
        mMixer.render(buf.data(), frames);
        next += std::chrono::microseconds(int64_t(1000000.0 * frames / rate));
        std::this_thread::sleep_until(next);
    }
}

void AudioEngine::setConfig(const AudioConfig& c) {
    std::lock_guard<std::mutex> lock(mLock);
    mConfig = c;
}

AudioConfig AudioEngine::config() {
    std::lock_guard<std::mutex> lock(mLock);
    return mConfig;
}

std::string AudioEngine::currentApi() {
    std::lock_guard<std::mutex> lock(mLock);
    return mCurrentApi;
}

std::string AudioEngine::currentDevice() {
    std::lock_guard<std::mutex> lock(mLock);
    return mCurrentDevice;
}

std::string AudioEngine::notice() {
    std::lock_guard<std::mutex> lock(mLock);
    return mNotice;
}

std::vector<std::string> AudioEngine::compiledApis() {
    std::vector<RtAudio::Api> apis;
    RtAudio::getCompiledApi(apis);
    std::vector<std::string> out;
    for (auto a : apis) {
        if (a == RtAudio::RTAUDIO_DUMMY) continue;
        out.push_back(RtAudio::getApiName(a));
    }
    return out;
}

static std::vector<AudioDeviceEntry> devicesOf(RtAudio& audio) {
    std::vector<AudioDeviceEntry> out;
    const unsigned def = audio.getDefaultOutputDevice();
    for (unsigned id : audio.getDeviceIds()) {
        const RtAudio::DeviceInfo info = audio.getDeviceInfo(id);
        if (info.outputChannels < 2) continue;
        AudioDeviceEntry e;
        e.name = info.name;
        e.isDefault = (id == def);
        e.rates = info.sampleRates;
        out.push_back(std::move(e));
    }
    return out;
}

std::vector<AudioDeviceEntry> AudioEngine::listDevices(const std::string& api) {
    return onControl([this, api] { return listDevicesImpl(api); });
}

std::vector<AudioDeviceEntry> AudioEngine::listDevicesImpl(const std::string& api) {
    if (api.empty() || api == "null") return {};
    {
        std::lock_guard<std::mutex> lock(mLock);
        if (mAudio && RtAudio::getApiName(mAudio->getCurrentApi()) == api) return devicesOf(*mAudio);
    }
    const RtAudio::Api a = RtAudio::getCompiledApiByName(api);
    if (a == RtAudio::UNSPECIFIED) return {};
    RtAudio tmp(a, [](RtAudioErrorType, const std::string&) {});  // upozorenja pri pretrazi (npr. Ableton Push) bez ispisa
    return devicesOf(tmp);
}

bool AudioEngine::start() {
    return onControl([this] {
        mWantRunning = true;
        mRetries = 0;
        mRateOverride = 0;
        mTriedRates.clear();
        {
            std::lock_guard<std::mutex> lock(mLock);
            mNotice.clear();
        }
        return startImpl();
    });
}

void AudioEngine::stop() {
    onControl([this] {
        mWantRunning = false;
        stopImpl();
    });
}

bool AudioEngine::startImpl() {
    std::lock_guard<std::mutex> lock(mLock);
    if (mAudio || mNullRun.load()) return true;
    mDisconnected.store(false);

    unsigned buffer = mConfig.buffer;
    if (buffer == 0) {
        buffer = 256;
        if (auto b = envStr("PADSAMPLER_BUFFER"); !b.empty()) buffer = unsigned(std::atoi(b.c_str()));
    }

    const std::string apiName = !mConfig.api.empty() ? mConfig.api : envStr("PADSAMPLER_API");
    if (apiName == "null") {
        mSampleRate = 48000;
        mBufferSize = int32_t(buffer);
        mFramesPerBurst = int32_t(buffer);
        mMixer.setOutputSampleRate(48000);
        mLatencyUs = int64_t(1000000.0 * buffer / 48000);
        mNullRun = true;
        mNullThread = std::thread([this] { nullLoop(); });
        mCurrentApi = "null";
        mCurrentDevice = "No audio";
        std::fprintf(stderr, "[PadSampler] audio: null (bez zvuka), bafer %u\n", buffer);
        return true;
    }

    // Podrazumijevano WASAPI (RtAudio bi sam odabrao ASIO). ASIO samo kad se traži.
    RtAudio::Api api = RtAudio::getCompiledApiByName(apiName.empty() ? "wasapi" : apiName);
    if (!apiName.empty() && api == RtAudio::UNSPECIFIED) {
        std::fprintf(stderr, "[PadSampler] drajver '%s' nije dostupan\n", apiName.c_str());
        return false;
    }  // prazno i nema WASAPI-ja (npr. Linux): UNSPECIFIED = bilo koji

    auto audio = std::make_unique<RtAudio>(api, [this](RtAudioErrorType type, const std::string& text) {
        // Zove se i iz RtAudio-ove niti (ASIO reset): samo zastavica, čuvar radi ostalo.
        if (type == RTAUDIO_DEVICE_DISCONNECT) mDisconnected.store(true);
        if (type != RTAUDIO_WARNING) std::fprintf(stderr, "[PadSampler] RtAudio: %s\n", text.c_str());
    });
    unsigned dev = audio->getDefaultOutputDevice();
    if (!mConfig.device.empty()) {
        // po imenu; ako ga više nema, ostaje podrazumijevani izlaz
        bool found = false;
        for (unsigned id : audio->getDeviceIds()) {
            const RtAudio::DeviceInfo i = audio->getDeviceInfo(id);
            if (i.outputChannels >= 2 && i.name == mConfig.device) { dev = id; found = true; break; }
        }
        if (!found) std::fprintf(stderr, "[PadSampler] uređaj '%s' nije pronađen, koristi se podrazumijevani\n",
                                 mConfig.device.c_str());
    } else if (auto d = envStr("PADSAMPLER_DEVICE"); !d.empty()) {
        dev = unsigned(std::atol(d.c_str()));
    }
    const RtAudio::DeviceInfo info = audio->getDeviceInfo(dev);
    if (info.outputChannels < 2) {
        std::fprintf(stderr, "[PadSampler] uređaj %u nema stereo izlaz\n", dev);
        return false;
    }

    // 48000 ako ga uređaj podržava, inače njegova preferirana frekvencija
    unsigned rate = info.preferredSampleRate ? info.preferredSampleRate : 48000;
    for (unsigned r : info.sampleRates) if (r == 48000) rate = 48000;
    if (mConfig.rate > 0) {
        rate = mConfig.rate;
    } else if (auto r = envStr("PADSAMPLER_RATE"); !r.empty()) {
        rate = unsigned(std::atoi(r.c_str()));
    }
    if (mRateOverride > 0) rate = mRateOverride;  // čuvar: drajver nije prihvatio traženu frekvenciju
    mDeviceRates = info.sampleRates;

    RtAudio::StreamParameters out;
    out.deviceId = dev;
    out.nChannels = 2;
    out.firstChannel = 0;
    RtAudio::StreamOptions opts;
    opts.flags = RTAUDIO_MINIMIZE_LATENCY;
    unsigned frames = buffer;
    if (audio->openStream(&out, nullptr, RTAUDIO_FLOAT32, rate, &frames, &AudioEngine::rtCallback, this, &opts) !=
        RTAUDIO_NO_ERROR) {
        std::fprintf(stderr, "[PadSampler] openStream: %s\n", audio->getErrorText().c_str());
        return false;
    }
    mSampleRate = int32_t(rate);
    mFramesPerBurst = int32_t(frames);
    mBufferSize = int32_t(frames);
    mMixer.setOutputSampleRate(int(rate));
    if (audio->startStream() != RTAUDIO_NO_ERROR) {
        std::fprintf(stderr, "[PadSampler] startStream: %s\n", audio->getErrorText().c_str());
        if (audio->isStreamOpen()) audio->closeStream();
        return false;
    }
    const long lat = audio->getStreamLatency();
    const double latFrames = lat > 0 ? double(lat) : double(frames);  // WASAPI često ne javlja: pretpostavi bafer
    mLatencyUs = int64_t(1000000.0 * latFrames / rate);
    mCurrentApi = RtAudio::getApiName(audio->getCurrentApi());
    mCurrentDevice = info.name;
    mStartedAt = std::chrono::steady_clock::now();
    std::fprintf(stderr, "[PadSampler] audio: %s, [%u] %s, %u Hz, bafer %u\n",
                 RtAudio::getApiDisplayName(audio->getCurrentApi()).c_str(), dev, info.name.c_str(), rate, frames);
    mAudio = std::move(audio);
    return true;
}

void AudioEngine::stopImpl() {
    if (mNullRun.exchange(false) && mNullThread.joinable()) mNullThread.join();
    std::unique_ptr<RtAudio> audio;
    {
        std::lock_guard<std::mutex> lock(mLock);
        audio = std::move(mAudio);
        mCurrentApi.clear();
        mCurrentDevice.clear();
    }
    if (audio) {
        if (audio->isStreamRunning()) audio->stopStream();
        if (audio->isStreamOpen()) audio->closeStream();
        audio.reset();
    }
}

}  // namespace padsampler
