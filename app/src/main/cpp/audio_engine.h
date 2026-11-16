// Tanki omotač oko Oboe-a: otvara low-latency stereo izlaz i poziva Mixer::render().
#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <memory>
#include <mutex>

#include "mixer.h"

namespace padsampler {

class AudioEngine {
public:
    AudioEngine();

    bool start();
    void stop();

    Mixer& mixer() { return mMixer; }

    int32_t sampleRate() const { return mSampleRate.load(); }
    int32_t framesPerBurst() const { return mFramesPerBurst.load(); }
    int32_t bufferSize() const { return mBufferSize.load(); }
    // Procijenjeno kašnjenje izlaza (od audio callbacka do zvučnika), mikrosekunde. 0 ako nije poznato.
    const std::atomic<int64_t>* latencySource() const { return &mLatencyUs; }
    int64_t outputLatencyUs() const { return mLatencyUs.load(std::memory_order_relaxed); }

private:
    class DataCallback : public oboe::AudioStreamDataCallback {
    public:
        DataCallback(Mixer* mixer, AudioEngine* engine) : mMixer(mixer), mEngine(engine) {}
        oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                              int32_t numFrames) override;

    private:
        Mixer* mMixer;
        AudioEngine* mEngine;
        int mCount = 0;
    };

    class ErrorCallback : public oboe::AudioStreamErrorCallback {
    public:
        explicit ErrorCallback(AudioEngine* engine) : mEngine(engine) {}
        void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

    private:
        AudioEngine* mEngine;
    };

    bool openAndStartLocked();
    void restart();

    std::mutex mLock;
    std::shared_ptr<oboe::AudioStream> mStream;
    std::shared_ptr<DataCallback> mDataCallback;
    std::shared_ptr<ErrorCallback> mErrorCallback;
    Mixer mMixer;
    bool mWanted = false;  // true između start() i stop(); odlučuje o auto-restartu

    std::atomic<int32_t> mSampleRate{48000};
    std::atomic<int32_t> mFramesPerBurst{0};
    std::atomic<int32_t> mBufferSize{0};
    std::atomic<int64_t> mLatencyUs{0};
};

}  // namespace padsampler
