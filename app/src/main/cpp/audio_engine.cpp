#include "audio_engine.h"

#include <android/log.h>

#define LOG_TAG "PadSampler"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace padsampler {

oboe::DataCallbackResult AudioEngine::DataCallback::onAudioReady(oboe::AudioStream* stream,
                                                                 void* audioData,
                                                                 int32_t numFrames) {
    // Kašnjenje izlaza se mjeri povremeno (ne u svakom callbacku); koristi ga Ableton Link za poravnanje
    if (stream != nullptr && (mCount++ % 64) == 0) {
        auto lat = stream->calculateLatencyMillis();
        if (lat) {
            mEngine->mLatencyUs.store(static_cast<int64_t>(lat.value() * 1000.0), std::memory_order_relaxed);
        } else if (mEngine->mLatencyUs.load(std::memory_order_relaxed) == 0 && stream->getSampleRate() > 0) {
            // nema vremenske oznake: pretpostavi veličinu buffera
            mEngine->mLatencyUs.store(
                static_cast<int64_t>(stream->getBufferSizeInFrames()) * 1000000 / stream->getSampleRate(),
                std::memory_order_relaxed);
        }
    }
    mMixer->render(static_cast<float*>(audioData), numFrames);
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::ErrorCallback::onErrorAfterClose(oboe::AudioStream* /*stream*/,
                                                   oboe::Result error) {
    LOGE("Audio stream greska: %s", oboe::convertToText(error));
    mEngine->restart();  // npr. slušalice izvučene / promijenjen uređaj
}

AudioEngine::AudioEngine()
    : mDataCallback(std::make_shared<DataCallback>(&mMixer, this)),
      mErrorCallback(std::make_shared<ErrorCallback>(this)) {}

bool AudioEngine::start() {
    std::lock_guard<std::mutex> lock(mLock);
    mWanted = true;
    return openAndStartLocked();
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> lock(mLock);
    mWanted = false;
    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
    }
}

void AudioEngine::restart() {
    std::lock_guard<std::mutex> lock(mLock);
    if (!mWanted) return;
    mStream.reset();
    openAndStartLocked();
}

bool AudioEngine::openAndStartLocked() {
    if (mStream) return true;  // već radi

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Exclusive);  // ako nije moguće, Oboe pada na Shared
    builder.setFormat(oboe::AudioFormat::Float);
    builder.setChannelCount(oboe::ChannelCount::Stereo);
    builder.setUsage(oboe::Usage::Media);
    builder.setDataCallback(mDataCallback);
    builder.setErrorCallback(mErrorCallback);

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK) {
        LOGE("openStream nije uspio: %s", oboe::convertToText(result));
        mStream.reset();
        return false;
    }

    mSampleRate = mStream->getSampleRate();
    mFramesPerBurst = mStream->getFramesPerBurst();
    mMixer.setOutputSampleRate(mSampleRate);

    // Najmanji siguran buffer: 2 burst-a. Ako ima pucketanja, povećaj na 3 ili 4.
    mStream->setBufferSizeInFrames(mStream->getFramesPerBurst() * 2);
    mBufferSize = mStream->getBufferSizeInFrames();

    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("requestStart nije uspio: %s", oboe::convertToText(result));
        mStream->close();
        mStream.reset();
        return false;
    }

    LOGI("Audio pokrenut: rate=%d burst=%d buffer=%d sharing=%s", mSampleRate.load(),
         mFramesPerBurst.load(), mBufferSize.load(),
         mStream->getSharingMode() == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared");
    return true;
}

}  // namespace padsampler
