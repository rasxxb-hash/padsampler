// audio_test: provjera zvuka na računaru (RtAudio: WASAPI, kasnije i ASIO).
// Svira petlju (kick + hi-hat, 2 takta) kroz isti Mixer kao Android verzija.
//
//   audio_test --list                       lista drajvera i uređaja
//   audio_test [--api wasapi|asio|alsa|...] [--device ID] [--rate 48000] [--buffer 256] [--seconds 20]
// Za uređaj koji ne navedeš koristi se podrazumijevani izlaz.
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#endif
#include "RtAudio.h"
#include "mixer.h"

using namespace padsampler;

static constexpr double kPi = 3.14159265358979323846;

struct Ctx {
    Mixer* mixer;
    unsigned rate = 48000;
    std::atomic<int> underruns{0};
    std::atomic<long long> callbacks{0};
    std::atomic<double> maxLoadPct{0.0};   // najduže trajanje callbacka u % trajanja bafera
    std::atomic<double> avgLoadAcc{0.0};
};

static int audioCb(void* out, void*, unsigned nFrames, double, RtAudioStreamStatus status, void* user) {
    auto* c = static_cast<Ctx*>(user);
    const auto t0 = std::chrono::steady_clock::now();
    if (status & RTAUDIO_OUTPUT_UNDERFLOW) c->underruns.fetch_add(1, std::memory_order_relaxed);
    c->mixer->render(static_cast<float*>(out), static_cast<int32_t>(nFrames));
    const double us = std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - t0).count();
    const double pct = 100.0 * us / (1e6 * nFrames / c->rate);
    if (pct > c->maxLoadPct.load(std::memory_order_relaxed)) c->maxLoadPct.store(pct, std::memory_order_relaxed);
    c->avgLoadAcc.store(c->avgLoadAcc.load(std::memory_order_relaxed) + pct, std::memory_order_relaxed);
    c->callbacks.fetch_add(1, std::memory_order_relaxed);
    return 0;
}

// 2 takta pri 120 BPM (4 s): kick na svakoj dobi, hi-hat na "i".
static std::vector<float> makeLoop(int rate, double bpm, int bars) {
    const double beat = 60.0 / bpm;
    const int64_t frames = int64_t(std::llround(beat * 4 * bars * rate));
    std::vector<float> d(size_t(frames) * 2, 0.0f);
    auto add = [&](int64_t at, int64_t len, auto fn) {
        for (int64_t i = 0; i < len && at + i < frames; ++i) {
            const float v = fn(double(i) / rate);
            d[size_t(at + i) * 2] += v;
            d[size_t(at + i) * 2 + 1] += v;
        }
    };
    uint32_t rng = 12345;
    for (int b = 0; b < 4 * bars; ++b) {
        const int64_t at = int64_t(std::llround(b * beat * rate));
        double phase = 0.0;
        add(at, rate / 4, [&](double t) {  // kick: sinus s padom visine
            const double f = 45.0 + 110.0 * std::exp(-t * 28.0);
            phase += 2.0 * kPi * f / rate;
            const double fade = std::min(1.0, (rate / 4 - t * rate) / (0.01 * rate));  // mekan kraj, bez klika
            return float(0.9 * std::sin(phase) * std::exp(-t * 9.0) * fade);
        });
        const int64_t hat = at + int64_t(std::llround(beat * 0.5 * rate));
        float prev = 0.0f;
        add(hat, rate / 20, [&](double t) {  // hat: šum kroz grubi high-pass
            rng = rng * 1664525u + 1013904223u;
            const float n = (float(rng >> 8) / 8388608.0f) - 1.0f;
            const float hp = n - prev;
            prev = n;
            return float(0.25 * hp * std::exp(-t * 90.0));
        });
    }
    return d;
}

static void listDevices(RtAudio::Api api) {
    RtAudio audio(api);
    std::printf("\n== %s ==\n", RtAudio::getApiDisplayName(audio.getCurrentApi()).c_str());
    const unsigned def = audio.getDefaultOutputDevice();
    for (unsigned id : audio.getDeviceIds()) {
        const RtAudio::DeviceInfo info = audio.getDeviceInfo(id);
        if (info.outputChannels == 0) continue;
        std::printf("  ID %-5u %s%s\n           izlaz: %u kanala, preferirano %u Hz\n", id, info.name.c_str(),
                    id == def ? "   [podrazumijevani]" : "", info.outputChannels, info.preferredSampleRate);
    }
}

int main(int argc, char** argv) {
#ifdef _WIN32
    SetConsoleOutputCP(CP_UTF8);  // ispravni č, ć, š, ž, đ u konzoli
#endif
    bool list = false;
    std::string apiName;
    long devId = -1;
    unsigned rate = 48000, buffer = 256;
    int seconds = 20;
    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        auto next = [&](const char* what) -> const char* {
            if (i + 1 >= argc) { std::fprintf(stderr, "Nedostaje vrijednost za %s\n", what); std::exit(2); }
            return argv[++i];
        };
        if (a == "--list") list = true;
        else if (a == "--api") apiName = next("--api");
        else if (a == "--device") devId = std::atol(next("--device"));
        else if (a == "--rate") rate = unsigned(std::atoi(next("--rate")));
        else if (a == "--buffer") buffer = unsigned(std::atoi(next("--buffer")));
        else if (a == "--seconds") seconds = std::atoi(next("--seconds"));
        else { std::fprintf(stderr, "Nepoznat argument: %s\n", a.c_str()); return 2; }
    }

    std::vector<RtAudio::Api> apis;
    RtAudio::getCompiledApi(apis);
    std::printf("Dostupni drajveri:");
    for (auto a : apis) std::printf(" %s", RtAudio::getApiName(a).c_str());
    std::printf("\n");

    if (list) {
        for (auto a : apis) {
            if (a == RtAudio::RTAUDIO_DUMMY) continue;
            listDevices(a);
        }
        return 0;
    }

    RtAudio::Api api = RtAudio::UNSPECIFIED;
    if (!apiName.empty()) {
        api = RtAudio::getCompiledApiByName(apiName);
        if (api == RtAudio::UNSPECIFIED) { std::fprintf(stderr, "Drajver '%s' nije dostupan.\n", apiName.c_str()); return 2; }
    } else {
        for (auto a : apis) if (a != RtAudio::RTAUDIO_DUMMY) { api = a; break; }
    }

    RtAudio audio(api);
    unsigned dev = devId >= 0 ? unsigned(devId) : audio.getDefaultOutputDevice();
    const RtAudio::DeviceInfo info = audio.getDeviceInfo(dev);
    std::printf("Drajver: %s\nUređaj : [%u] %s\n", RtAudio::getApiDisplayName(audio.getCurrentApi()).c_str(), dev,
                info.name.c_str());

    Mixer mixer;
    mixer.setOutputSampleRate(int(rate));
    mixer.setBpm(120.0);
    auto loop = makeLoop(int(rate), 120.0, 2);
    mixer.loadSample(0, loop.data(), int64_t(loop.size() / 2), int(rate), 2);

    Ctx ctx;
    ctx.mixer = &mixer;
    ctx.rate = rate;

    RtAudio::StreamParameters out;
    out.deviceId = dev;
    out.nChannels = 2;
    out.firstChannel = 0;
    RtAudio::StreamOptions opts;
    opts.flags = RTAUDIO_MINIMIZE_LATENCY;
    unsigned bufFrames = buffer;
    if (audio.openStream(&out, nullptr, RTAUDIO_FLOAT32, rate, &bufFrames, &audioCb, &ctx, &opts) != RTAUDIO_NO_ERROR) {
        std::fprintf(stderr, "Ne mogu otvoriti stream: %s\n", audio.getErrorText().c_str());
        return 1;
    }
    if (audio.startStream() != RTAUDIO_NO_ERROR) {
        std::fprintf(stderr, "Ne mogu pokrenuti stream: %s\n", audio.getErrorText().c_str());
        return 1;
    }
    const long lat = audio.getStreamLatency();
    std::printf("Stream : %u Hz, bafer %u frameova (%.1f ms)", rate, bufFrames, 1000.0 * bufFrames / rate);
    if (lat > 0) std::printf(", prijavljeno kašnjenje %ld frameova (%.1f ms)", lat, 1000.0 * lat / rate);
    std::printf("\nSvira petlju %d s. Čuješ kick na svakoj dobi i hi-hat između.\n", seconds);

    mixer.setSlotLoop(0, true);
    mixer.launch(0);
    for (int s = 0; s < seconds && audio.isStreamRunning(); ++s) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
        const long long n = ctx.callbacks.load();
        std::printf("\r  %2d/%d s   pucketanja: %d   opterećenje: prosjek %.1f%%, vrh %.1f%%   ", s + 1, seconds,
                    ctx.underruns.load(), n ? ctx.avgLoadAcc.load() / double(n) : 0.0, ctx.maxLoadPct.load());
        std::fflush(stdout);
    }
    audio.stopStream();
    if (audio.isStreamOpen()) audio.closeStream();
    std::printf("\nGotovo. Pucketanja (underrun): %d. %s\n", ctx.underruns.load(),
                ctx.underruns.load() == 0 ? "Sve čisto." : "Probaj veći --buffer (npr. 512).");
    return ctx.underruns.load() == 0 ? 0 : 3;
}
