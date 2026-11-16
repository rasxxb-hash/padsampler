#include "link_sync.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <optional>

#include <ableton/LinkAudio.hpp>

namespace padsampler {

namespace {
constexpr double kQuantum = 4.0;          // jedan takt = 4 doba
constexpr size_t kSinkSamples = 2 * 4096; // početni kapacitet bafera Link Audio-a (stereo), raste po potrebi
const char* kPeerName = "PadSampler";
const char* kChannelName = "PadSampler Master";
}  // namespace

// Stanje sesije iz zadnjeg query() i doba na početku buffera: Link Audio ih traži iste kao za lokalni render.
struct LinkSync::AudioThreadState {
    std::optional<ableton::LinkAudio::SessionState> state;
    double beatsAtBufferBegin = 0.0;
};

LinkSync::LinkSync() : mRt(std::make_unique<AudioThreadState>()) {}

LinkSync::~LinkSync() {
    mEnabled.store(false);
    mSinkPtr.store(nullptr);
    mLink.store(nullptr);
}

int64_t LinkSync::latencyUs() const {
    const std::atomic<int64_t>* src = mLatencySrc.load(std::memory_order_acquire);
    const int64_t latency = src != nullptr ? src->load(std::memory_order_relaxed)
                                           : mLatencyUs.load(std::memory_order_relaxed);
    return latency + mOffsetUs.load(std::memory_order_relaxed);
}

void LinkSync::enable(bool on, double bpm) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (on) {
        if (!mOwned) {
            mOwned = std::make_unique<ableton::LinkAudio>(std::min(999.0, std::max(20.0, bpm)), kPeerName);
            mOwned->enableStartStopSync(true);
            mOwned->setStartStopCallback([this](bool playing) { onTransport(playing); });
            mLink.store(mOwned.get(), std::memory_order_release);
        } else if (!mOwned->isEnabled()) {
            // Naš tempo postaje početni tempo lokalne sesije (ako se pridružimo tuđoj, ona ima prednost)
            auto state = mOwned->captureAppSessionState();
            state.setTempo(std::min(999.0, std::max(20.0, bpm)), mOwned->clock().micros());
            mOwned->commitAppSessionState(state);
        }
        mLastPlaying.store(mOwned->captureAppSessionState().isPlaying());
        mOwned->enable(true);
        mEnabled.store(true, std::memory_order_release);
        applyAudioLocked();
    } else {
        mEnabled.store(false, std::memory_order_release);
        if (mOwned) {
            mOwned->enableLinkAudio(false);
            mOwned->enable(false);
        }
    }
}

void LinkSync::shutdown() {
    std::lock_guard<std::mutex> lock(mMutex);
    mEnabled.store(false, std::memory_order_release);
    mSinkPtr.store(nullptr, std::memory_order_release);
    mLink.store(nullptr, std::memory_order_release);
    mSink.reset();
    if (mOwned) {
        mOwned->enableLinkAudio(false);
        mOwned->enable(false);
        mOwned.reset();  // zaustavlja Link niti
    }
}

int LinkSync::peers() const {
    ableton::LinkAudio* l = mLink.load(std::memory_order_acquire);
    if (l == nullptr || !mEnabled.load(std::memory_order_relaxed)) return -1;
    return static_cast<int>(l->numPeers());
}

void LinkSync::setTempo(double bpm) {
    ableton::LinkAudio* l = mLink.load(std::memory_order_acquire);
    if (l == nullptr || !mEnabled.load(std::memory_order_relaxed)) return;
    auto state = l->captureAppSessionState();
    state.setTempo(std::min(999.0, std::max(20.0, bpm)), l->clock().micros());
    l->commitAppSessionState(state);
}

// ---------------------------------------------------------------- start/stop

void LinkSync::onTransport(bool playing) {
    // Neko je zaustavio transport (ili mi sa STOP ALL): svi klipovi staju, kao u Abletonu.
    // Samo prelaz svira -> stoji; pridruživanje sesiji koja stoji ne zaustavlja ništa.
    const bool was = mLastPlaying.exchange(playing);
    if (was && !playing) {
        if (Mixer* m = mStopTarget.load(std::memory_order_acquire)) m->stopAll();
    }
}

void LinkSync::requestStart() {
    ableton::LinkAudio* l = mLink.load(std::memory_order_acquire);
    if (l == nullptr || !mEnabled.load(std::memory_order_relaxed)) return;
    auto state = l->captureAppSessionState();
    if (state.isPlaying()) return;
    // Početak sljedećeg takta, onako kako ga čuje mikser (isti trenutak kao u query())
    const auto now = l->clock().micros() + std::chrono::microseconds(latencyUs());
    const double nextBar = std::ceil(state.beatAtTime(now, kQuantum) / kQuantum) * kQuantum;
    state.setIsPlaying(true, state.timeAtBeat(nextBar, kQuantum));
    l->commitAppSessionState(state);
    mLastPlaying.store(true);
}

void LinkSync::requestStop() {
    ableton::LinkAudio* l = mLink.load(std::memory_order_acquire);
    if (l == nullptr || !mEnabled.load(std::memory_order_relaxed)) return;
    auto state = l->captureAppSessionState();
    if (!state.isPlaying()) return;
    state.setIsPlaying(false, l->clock().micros());
    l->commitAppSessionState(state);
}

bool LinkSync::transportPlaying() const {
    ableton::LinkAudio* l = mLink.load(std::memory_order_acquire);
    if (l == nullptr || !mEnabled.load(std::memory_order_relaxed)) return false;
    return l->captureAppSessionState().isPlaying();
}

// ---------------------------------------------------------------- Link Audio

void LinkSync::setAudioSend(bool on) {
    std::lock_guard<std::mutex> lock(mMutex);
    mAudioWanted.store(on, std::memory_order_relaxed);
    if (mOwned && mEnabled.load(std::memory_order_relaxed)) applyAudioLocked();
}

void LinkSync::applyAudioLocked() {
    const bool on = mAudioWanted.load(std::memory_order_relaxed);
    if (on && !mSink) {
        mSink = std::make_unique<ableton::LinkAudioSink>(*mOwned, kChannelName, kSinkSamples);
        mSinkPtr.store(mSink.get(), std::memory_order_release);
    }
    mOwned->enableLinkAudio(on);
}

void LinkSync::onOutput(const float* out, int32_t numFrames, int sampleRate) {
    if (!mEnabled.load(std::memory_order_acquire) || !mAudioWanted.load(std::memory_order_relaxed)) return;
    ableton::LinkAudioSink* sink = mSinkPtr.load(std::memory_order_acquire);
    AudioThreadState& rt = *mRt;
    if (sink == nullptr || !rt.state || numFrames <= 0 || sampleRate <= 0) return;

    const size_t needed = static_cast<size_t>(numFrames) * 2;
    if (needed > sink->maxNumSamples()) {
        sink->requestMaxNumSamples(needed);  // realtime-safe; važi od sljedećih buffera
        return;
    }
    ableton::LinkAudioSink::BufferHandle h(*sink);
    if (!h || h.maxNumSamples < needed) return;  // niko ne sluša ili nema slobodnog buffera
    for (size_t i = 0; i < needed; ++i) {
        const float v = std::max(-1.0f, std::min(1.0f, out[i]));
        h.samples[i] = static_cast<int16_t>(std::lrint(v * 32767.0f));
    }
    h.commit(*rt.state, rt.beatsAtBufferBegin, kQuantum, static_cast<size_t>(numFrames), 2,
             static_cast<uint32_t>(sampleRate));
}

// ---------------------------------------------------------------- sat (audio nit)

bool LinkSync::query(int32_t /*numFrames*/, int /*sampleRate*/, double* bpm, double* barPhase) {
    if (!mEnabled.load(std::memory_order_acquire)) return false;
    ableton::LinkAudio* l = mLink.load(std::memory_order_acquire);
    if (l == nullptr) return false;

    const auto state = l->captureAudioSessionState();  // realtime-safe
    const auto when = l->clock().micros() + std::chrono::microseconds(latencyUs());
    *bpm = state.tempo();
    *barPhase = state.phaseAtTime(when, kQuantum) / kQuantum;
    mRt->state = state;
    mRt->beatsAtBufferBegin = state.beatAtTime(when, kQuantum);
    return true;
}

}  // namespace padsampler
