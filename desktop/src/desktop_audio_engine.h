// Desktop verzija AudioEngine-a (RtAudio: WASAPI/ASIO) sa ISTIM interfejsom kao Oboe verzija
// (app/src/main/cpp/audio_engine.h), pa jni_bridge.cpp radi nepromijenjen.
//
// Sve RtAudio operacije (otvaranje, start, stop, lista uređaja) idu kroz JEDNU kontrolnu nit koja živi koliko i
// aplikacija (Windows: COM STA + obrada poruka), jer ASIO drajveri to očekuju. Ta nit je i "čuvar": ako drajver
// zatvori stream (reset zbog promjene frekvencije, isključen uređaj), stream se ponovo otvori; ako ni to ne ide,
// prelazi se na podrazumijevani izlaz (WASAPI), da zvuk ne nestane usred nastupa.
//
// Podešavanje: iz aplikacije (setConfig, ekran podešavanja). Prazna polja uzimaju varijable okruženja:
//   PADSAMPLER_API     wasapi | asio | null   (null = bez zvuka, nit koja samo računa; za testove)
//   PADSAMPLER_DEVICE  ID uređaja (vidi audio_test --list)
//   PADSAMPLER_BUFFER  veličina bafera u frameovima (podrazumijevano 256)
//   PADSAMPLER_RATE    npr. 48000
#pragma once

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "mixer.h"

class RtAudio;

namespace padsampler {

// Izbor izlaza. Uređaj se pamti po IMENU (RtAudio ID-jevi se mijenjaju između pokretanja).
// Prazno / 0 = podrazumijevano (varijabla okruženja, pa sistemski izlaz, 256 frameova, 48000 Hz ako ide).
struct AudioConfig {
    std::string api;     // "wasapi", "asio", "null" ili prazno
    std::string device;  // ime uređaja ili prazno
    unsigned buffer = 0;
    unsigned rate = 0;
};

struct AudioDeviceEntry {
    std::string name;
    bool isDefault = false;
    std::vector<unsigned> rates;
};

class AudioEngine {
public:
    AudioEngine();
    ~AudioEngine();

    bool start();
    void stop();

    Mixer& mixer() { return mMixer; }

    int32_t sampleRate() const { return mSampleRate.load(); }
    int32_t framesPerBurst() const { return mFramesPerBurst.load(); }
    int32_t bufferSize() const { return mBufferSize.load(); }
    const std::atomic<int64_t>* latencySource() const { return &mLatencyUs; }
    int64_t outputLatencyUs() const { return mLatencyUs.load(std::memory_order_relaxed); }

    // ---- podešavanja (desktop). Važi od sljedećeg start().
    void setConfig(const AudioConfig& c);
    AudioConfig config();
    std::string currentApi();     // API koji trenutno radi ("" ako je zaustavljen)
    std::string currentDevice();  // ime uređaja koji trenutno radi
    std::string notice();         // npr. "ASIO stopped, switched to WASAPI" (prazno ako je sve u redu)
    // Izlazni uređaji (stereo) za API. Ako taj API trenutno radi, vraća njegovu listu (ASIO ne dozvoljava
    // ponovno pretraživanje dok je stream otvoren).
    std::vector<AudioDeviceEntry> listDevices(const std::string& api);
    static std::vector<std::string> compiledApis();  // npr. {"wasapi", "asio"}

private:
    static int rtCallback(void* out, void* in, unsigned frames, double t, unsigned status, void* user);
    void nullLoop();

    // kontrolna nit
    void controlLoop();
    template <typename F>
    auto onControl(F&& f) -> decltype(f());
    bool startImpl();
    void stopImpl();
    void watchdog();
    std::vector<AudioDeviceEntry> listDevicesImpl(const std::string& api);

    std::thread mControl;
    std::thread::id mControlId;
    std::mutex mTaskMutex;
    std::condition_variable mTaskCv;
    std::deque<std::function<void()>> mTasks;
    bool mControlStop = false;   // zaštićeno sa mTaskMutex

    std::mutex mLock;             // mConfig, mCurrent*, mNotice, mAudio (pokazivač)
    std::unique_ptr<RtAudio> mAudio;
    std::thread mNullThread;
    std::atomic<bool> mNullRun{false};
    Mixer mMixer;
    AudioConfig mConfig;
    std::string mCurrentApi;
    std::string mCurrentDevice;
    std::string mNotice;

    // čuvar (samo kontrolna nit, osim mDisconnected)
    std::atomic<bool> mDisconnected{false};  // RtAudio javio da je drajver zatvorio stream
    bool mWantRunning = false;
    int mRetries = 0;
    unsigned mRateOverride = 0;              // frekvencija koju je čuvar izabrao jer drajver nije prihvatio traženu
    std::vector<unsigned> mTriedRates;       // frekvencije na kojima je drajver resetovao stream
    std::vector<unsigned> mDeviceRates;      // frekvencije koje uređaj podržava (iz zadnjeg otvaranja)
    std::chrono::steady_clock::time_point mStartedAt{};

    std::atomic<int32_t> mSampleRate{48000};
    std::atomic<int32_t> mFramesPerBurst{0};
    std::atomic<int32_t> mBufferSize{0};
    std::atomic<int64_t> mLatencyUs{0};
    std::atomic<int> mUnderruns{0};
};

}  // namespace padsampler
