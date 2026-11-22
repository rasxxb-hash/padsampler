#include "mixer.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace padsampler {

namespace {
constexpr int kStartFadeFrames = 32;     // ~0.7 ms fade in na početku klipa
constexpr double kBarFadeSec = 0.010;    // fade out koji se završi tačno na granici takta
constexpr double kPanicFadeSec = 0.005;  // fade kod STOP SVE / uklanjanja
constexpr double kGainSmoothSec = 0.010; // izglađivanje promjene jačine (bez "zipper" šuma)
constexpr double kPeakDecaySec = 0.15;

inline bool validSlot(int s) { return s >= 0 && s < kNumSlots; }
inline bool validTrack(int t) { return t >= 0 && t < kTracks; }
}  // namespace

// ------------------------------------------------------------------ send efekti (reverb + delay)

namespace {
constexpr int kFxMaxRate = 96000;
constexpr double kSendDelayMaxSec = 2.5;            // 1/8 sa tačkom do ~20 BPM
constexpr int kRevComb[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};   // Freeverb (44.1 kHz)
constexpr int kRevAp[4] = {556, 441, 341, 225};
constexpr int kRevSpread = 23;
constexpr float kRevRoom = 0.86f;      // veća prostorija, rep ~3 s
constexpr float kRevDamp = 0.35f;
constexpr float kRevReturn = 1.0f;     // kao Freeverb (wet 1/3 * 3)
constexpr double kDlyBeats = 0.75;     // 1/8 sa tačkom
constexpr float kDlyFeedback = 0.55f;  // korisnik: delay izraženiji (bilo 0.42)
constexpr float kDlyInput = 1.0f;      // (bilo 0.8)
constexpr float kLimThreshold = 0.966f;   // limiter mastera: -0.3 dBFS
constexpr double kLimReleaseSec = 0.12;
constexpr double kPiD = 3.14159265358979323846;
inline float flushDenormal(float x) { return std::fabs(x) < 1e-15f ? 0.0f : x; }
}  // namespace

SendFx::SendFx() {
    const double scale = static_cast<double>(kFxMaxRate) / 44100.0;
    for (int i = 0; i < 8; ++i) {
        mCombL[i].buf.assign(static_cast<size_t>((kRevComb[i] + kRevSpread) * scale) + 2, 0.0f);
        mCombR[i].buf.assign(mCombL[i].buf.size(), 0.0f);
    }
    for (int i = 0; i < 4; ++i) {
        mApL[i].buf.assign(static_cast<size_t>((kRevAp[i] + kRevSpread) * scale) + 2, 0.0f);
        mApR[i].buf.assign(mApL[i].buf.size(), 0.0f);
    }
    mDly.assign(static_cast<size_t>(kFxMaxRate * kSendDelayMaxSec) * 2 + 4, 0.0f);
}

void SendFx::setup(int sampleRate) {
    const double scale = static_cast<double>(std::min(sampleRate, kFxMaxRate)) / 44100.0;
    auto fit = [](size_t cap, double want) { return std::max(1, std::min(static_cast<int>(cap), static_cast<int>(want))); };
    for (int i = 0; i < 8; ++i) {
        mCombL[i].len = fit(mCombL[i].buf.size(), kRevComb[i] * scale);
        mCombR[i].len = fit(mCombR[i].buf.size(), (kRevComb[i] + kRevSpread) * scale);
        mCombL[i].pos = mCombR[i].pos = 0;
    }
    for (int i = 0; i < 4; ++i) {
        mApL[i].len = fit(mApL[i].buf.size(), kRevAp[i] * scale);
        mApR[i].len = fit(mApR[i].buf.size(), (kRevAp[i] + kRevSpread) * scale);
        mApL[i].pos = mApR[i].pos = 0;
    }
    mDlyPos = 0;
    mDlyFrames = 0.0;
}

void SendFx::clear() {
    for (int i = 0; i < 8; ++i) {
        std::fill(mCombL[i].buf.begin(), mCombL[i].buf.end(), 0.0f);
        std::fill(mCombR[i].buf.begin(), mCombR[i].buf.end(), 0.0f);
        mCombL[i].store = mCombR[i].store = 0.0f;
    }
    for (int i = 0; i < 4; ++i) {
        std::fill(mApL[i].buf.begin(), mApL[i].buf.end(), 0.0f);
        std::fill(mApR[i].buf.begin(), mApR[i].buf.end(), 0.0f);
    }
    std::fill(mDly.begin(), mDly.end(), 0.0f);
    mDlyLpL = mDlyLpR = 0.0f;
}

void SendFx::process(const float* revIn, const float* dlyIn, float* out, int32_t frames, int sampleRate,
                     double framesPerBeat) {
    if (frames <= 0 || sampleRate <= 0) return;
    if (sampleRate != mRate) {
        mRate = sampleRate;
        setup(sampleRate);
        clear();
    }
    const int dlyLen = static_cast<int>(mDly.size() / 2);
    const double dlyTarget = std::max(1.0, std::min(static_cast<double>(dlyLen - 2), kDlyBeats * std::max(1.0, framesPerBeat)));
    if (mDlyFrames <= 0.0) mDlyFrames = dlyTarget;
    const double kd = 1.0 - std::exp(-1.0 / (0.08 * sampleRate));   // promjena tempa: vrijeme klizi
    const float dlyLp = static_cast<float>(1.0 - std::exp(-2.0 * kPiD * 4500.0 / sampleRate));

    for (int32_t f = 0; f < frames; ++f) {
        // ---- delay: ping-pong (ulaz mono u lijevi, odjeci se smjenjuju L / D)
        mDlyFrames += (dlyTarget - mDlyFrames) * kd;
        double rp = mDlyPos - mDlyFrames;
        while (rp < 0) rp += dlyLen;
        const int i0 = static_cast<int>(rp) % dlyLen;
        const int i1 = (i0 + 1) % dlyLen;
        const float fr = static_cast<float>(rp - std::floor(rp));
        const float dl = mDly[2 * i0] * (1.0f - fr) + mDly[2 * i1] * fr;
        const float dr = mDly[2 * i0 + 1] * (1.0f - fr) + mDly[2 * i1 + 1] * fr;
        mDlyLpL = flushDenormal(mDlyLpL + (dl - mDlyLpL) * dlyLp);
        mDlyLpR = flushDenormal(mDlyLpR + (dr - mDlyLpR) * dlyLp);
        const float dIn = (dlyIn[2 * f] + dlyIn[2 * f + 1]) * 0.5f;
        mDly[2 * mDlyPos] = dIn * kDlyInput + mDlyLpR * kDlyFeedback;
        mDly[2 * mDlyPos + 1] = mDlyLpL * kDlyFeedback;
        if (++mDlyPos >= dlyLen) mDlyPos = 0;

        // ---- reverb (Freeverb: 8 comb + 4 allpass po kanalu), samo povrat
        const float in = (revIn[2 * f] + revIn[2 * f + 1]) * 0.015f;
        float ol = 0.0f, orr = 0.0f;
        for (int i = 0; i < 8; ++i) {
            Comb& cl = mCombL[i];
            const float a = cl.buf[cl.pos];
            cl.store = flushDenormal(a * (1.0f - kRevDamp) + cl.store * kRevDamp);
            cl.buf[cl.pos] = in + cl.store * kRevRoom;
            if (++cl.pos >= cl.len) cl.pos = 0;
            ol += a;
            Comb& cr = mCombR[i];
            const float b = cr.buf[cr.pos];
            cr.store = flushDenormal(b * (1.0f - kRevDamp) + cr.store * kRevDamp);
            cr.buf[cr.pos] = in + cr.store * kRevRoom;
            if (++cr.pos >= cr.len) cr.pos = 0;
            orr += b;
        }
        for (int i = 0; i < 4; ++i) {
            Allpass& al = mApL[i];
            const float a = al.buf[al.pos];
            al.buf[al.pos] = flushDenormal(ol + a * 0.5f);
            ol = a - ol;
            if (++al.pos >= al.len) al.pos = 0;
            Allpass& ar = mApR[i];
            const float b = ar.buf[ar.pos];
            ar.buf[ar.pos] = flushDenormal(orr + b * 0.5f);
            orr = b - orr;
            if (++ar.pos >= ar.len) ar.pos = 0;
        }
        out[2 * f] += ol * kRevReturn + dl;
        out[2 * f + 1] += orr * kRevReturn + dr;
    }
}

Mixer::Mixer() {
    for (auto& s : mSamples) s.store(nullptr, std::memory_order_relaxed);
    for (auto& s : mInUse) s.store(nullptr, std::memory_order_relaxed);
    for (auto& s : mSlotStateOut) s.store(kEmpty, std::memory_order_relaxed);
    for (auto& s : mLevelOut) s.store(0.0f, std::memory_order_relaxed);
    for (auto& s : mProgressOut) s.store(0.0f, std::memory_order_relaxed);
    mPeak.fill(0.0f);
    mHasVoice.fill(false);
    mSlotLoop.fill(false);
    mStartBar.fill(0);
}

// ------------------------------------------------------------------ semplovi

size_t Mixer::ownedSampleCount() {
    std::lock_guard<std::mutex> lock(mOwnerMutex);
    return mOwned.size();
}

void Mixer::retireLocked(const Sample* old) {
    const auto now = std::chrono::steady_clock::now();
    if (old != nullptr) {
        for (auto& o : mOwned) {
            if (o.sample.get() == old) {
                o.retired = true;
                o.retiredAt = now;
            }
        }
    }
    // Oslobodi povučene semplove koje niko ne svira i koji su dovoljno stari.
    const auto delay = std::chrono::milliseconds(mRetireDelayMs.load());
    mOwned.erase(std::remove_if(mOwned.begin(), mOwned.end(),
                                [&](const Owned& o) {
                                    if (!o.retired || now - o.retiredAt < delay) return false;
                                    const Sample* p = o.sample.get();
                                    for (const auto& inUse : mInUse) {
                                        if (inUse.load(std::memory_order_acquire) == p) return false;
                                    }
                                    if (mPreviewInUse.load(std::memory_order_acquire) == p) return false;
                                    if (mPreviewSample.load(std::memory_order_acquire) == p) return false;
                                    return true;
                                }),
                 mOwned.end());
}

bool Mixer::loadSample(int slot, const float* interleavedStereo, int64_t frames, int sampleRate,
                       int32_t lengthBars) {
    if (!validSlot(slot) || interleavedStereo == nullptr || frames <= 0 || sampleRate <= 0) {
        return false;
    }
    auto sample = std::make_unique<Sample>();
    sample->data.assign(interleavedStereo, interleavedStereo + frames * 2);
    sample->frames = frames;
    sample->sampleRate = sampleRate;
    sample->lengthBars = lengthBars < 0 ? -1 : lengthBars;
    const Sample* raw = sample.get();

    std::lock_guard<std::mutex> lock(mOwnerMutex);
    Owned owned;
    owned.sample = std::move(sample);
    mOwned.push_back(std::move(owned));
    const Sample* old = mSamples[slot].exchange(raw, std::memory_order_acq_rel);
    retireLocked(old);
    return true;
}

bool Mixer::startPreview(const float* interleavedStereo, int64_t frames, int sampleRate, bool loop) {
    if (interleavedStereo == nullptr || frames <= 0 || sampleRate <= 0) return false;
    auto sample = std::make_unique<Sample>();
    sample->data.assign(interleavedStereo, interleavedStereo + frames * 2);
    sample->frames = frames;
    sample->sampleRate = sampleRate;
    const Sample* raw = sample.get();
    {
        std::lock_guard<std::mutex> lock(mOwnerMutex);
        Owned owned;
        owned.sample = std::move(sample);
        mOwned.push_back(std::move(owned));
        const Sample* old = mPreviewSample.exchange(raw, std::memory_order_acq_rel);
        retireLocked(old);
    }
    Command c;
    c.type = Command::PreviewStart;
    c.b = loop ? 1 : 0;
    mQueue.push(c);
    return true;
}

void Mixer::stopPreview() {
    Command c;
    c.type = Command::PreviewStop;
    mQueue.push(c);
}

void Mixer::clearSample(int slot) {
    if (!validSlot(slot)) return;
    std::lock_guard<std::mutex> lock(mOwnerMutex);
    const Sample* old = mSamples[slot].exchange(nullptr, std::memory_order_acq_rel);
    retireLocked(old);
}

double Mixer::sampleDurationSec(int slot) const {
    if (!validSlot(slot)) return 0.0;
    const Sample* s = mSamples[slot].load(std::memory_order_acquire);
    return s != nullptr ? s->durationSec() : 0.0;
}

// ------------------------------------------------------------------ komande (bilo koja nit)

void Mixer::launch(int slot) {
    Command c;
    c.type = Command::Launch;
    c.a = slot;
    mQueue.push(c);
}
void Mixer::launchScene(int scene) {
    Command c;
    c.type = Command::LaunchScene;
    c.a = scene;
    mQueue.push(c);
}
void Mixer::stopTrack(int track) {
    Command c;
    c.type = Command::StopTrack;
    c.a = track;
    mQueue.push(c);
}
void Mixer::stopAll() {
    Command c;
    c.type = Command::StopAll;
    mQueue.push(c);
}
void Mixer::killSlot(int slot) {
    Command c;
    c.type = Command::KillSlot;
    c.a = slot;
    mQueue.push(c);
}
void Mixer::setBpm(double bpm) {
    Command c;
    c.type = Command::SetBpm;
    c.f = static_cast<float>(std::min(400.0, std::max(20.0, bpm)));
    mQueue.push(c);
}
void Mixer::setSlotLoop(int slot, bool loop) {
    Command c;
    c.type = Command::SetSlotLoop;
    c.a = slot;
    c.b = loop ? 1 : 0;
    mQueue.push(c);
}
void Mixer::setTrackGain(int track, float linear) {
    Command c;
    c.type = Command::SetTrackGain;
    c.a = track;
    c.f = linear;
    mQueue.push(c);
}
void Mixer::setTrackPan(int track, float pan) {
    Command c;
    c.type = Command::SetTrackPan;
    c.a = track;
    c.f = pan;
    mQueue.push(c);
}
void Mixer::setTrackReverbSend(int track, float amount) {
    Command c;
    c.type = Command::SetTrackSend;
    c.a = track;
    c.b = 0;
    c.f = amount;
    mQueue.push(c);
}
void Mixer::setTrackDelaySend(int track, float amount) {
    Command c;
    c.type = Command::SetTrackSend;
    c.a = track;
    c.b = 1;
    c.f = amount;
    mQueue.push(c);
}
void Mixer::setTrackEq(int track, int band, float amount) {
    Command c;
    c.type = Command::SetTrackEq;
    c.a = track;
    c.b = band;
    c.f = amount;
    mQueue.push(c);
}
void Mixer::setMasterFilter(float amount) {
    Command c;
    c.type = Command::SetMasterFilter;
    c.f = amount;
    mQueue.push(c);
}
void Mixer::setTrackMute(int track, bool mute) {
    Command c;
    c.type = Command::SetTrackMute;
    c.a = track;
    c.b = mute ? 1 : 0;
    mQueue.push(c);
}
void Mixer::setTrackSolo(int track, bool solo) {
    Command c;
    c.type = Command::SetTrackSolo;
    c.a = track;
    c.b = solo ? 1 : 0;
    mQueue.push(c);
}
void Mixer::setMasterGain(float linear) {
    Command c;
    c.type = Command::SetMasterGain;
    c.f = linear;
    mQueue.push(c);
}
void Mixer::setMasterMute(bool mute) {
    Command c;
    c.type = Command::SetMasterMute;
    c.b = mute ? 1 : 0;
    mQueue.push(c);
}

void Mixer::readState(uint8_t* slotStates, float* trackLevels, float* trackProgress,
                      float* barPhase, float* masterLevel) const {
    if (slotStates != nullptr) {
        for (int i = 0; i < kNumSlots; ++i) slotStates[i] = mSlotStateOut[i].load(std::memory_order_relaxed);
    }
    if (trackLevels != nullptr) {
        for (int i = 0; i < kTracks; ++i) trackLevels[i] = mLevelOut[i].load(std::memory_order_relaxed);
    }
    if (trackProgress != nullptr) {
        for (int i = 0; i < kTracks; ++i) trackProgress[i] = mProgressOut[i].load(std::memory_order_relaxed);
    }
    if (barPhase != nullptr) *barPhase = mBarPhaseOut.load(std::memory_order_relaxed);
    if (masterLevel != nullptr) *masterLevel = mMasterLevelOut.load(std::memory_order_relaxed);
}

// ------------------------------------------------------------------ audio nit

void Mixer::recomputeBar() {
    const double frac = mFramesPerBar > 0.0 ? mBarPos / mFramesPerBar : 0.0;
    mFramesPerBar = 240.0 * static_cast<double>(mRate) / mBpm;  // 4 doba * 60 s / BPM * rate
    mBarPos = frac * mFramesPerBar;
}

int64_t Mixer::lengthBars(const Sample& s) const {
    if (s.lengthBars > 0) return s.lengthBars;
    if (s.lengthBars < 0) return 0;  // slobodan loop: nema dužine u taktovima
    return std::max<int64_t>(1, std::llround(s.durationSec() * mBpm / 240.0));
}

bool Mixer::startVoice(int slot) {
    const Sample* s = mSamples[slot].load(std::memory_order_acquire);
    if (s == nullptr) return false;
    Voice& v = mVoices[slot];
    v.sample = s;
    mInUse[slot].store(s, std::memory_order_release);
    v.pos = 0.0;
    v.loop = mSlotLoop[slot];
    v.active = true;
    v.env = 0.0f;
    v.envTarget = 1.0f;
    mStartBar[slot] = mBarCount;
    return true;
}

void Mixer::stopVoiceNow(int slot) {
    mVoices[slot].active = false;
    mInUse[slot].store(nullptr, std::memory_order_release);
}

void Mixer::endVoice(int slot) {
    stopVoiceNow(slot);
    Track& tr = mTracks[slot % kTracks];
    if (tr.playing == slot) {
        tr.playing = -1;
        tr.stopQueued = false;
    }
}

// Glas koji svira prethodnu verziju sempla (npr. prije promjene tempa) prelazi na novu verziju,
// na istu muzičku poziciju. Zove se samo na granici takta.
void Mixer::adoptSample(int slot, const Sample* next) {
    Voice& v = mVoices[slot];
    const Sample* old = v.sample;
    if (old == nullptr || next == nullptr || old == next || next->frames <= 0) return;
    double newPos;
    if (v.loop && next->lengthBars >= 0) {
        const int64_t len = lengthBars(*next);
        const int64_t barsIn = (mBarCount - mStartBar[slot]) % len;
        const double ratio = static_cast<double>(next->sampleRate) / static_cast<double>(mRate);
        newPos = static_cast<double>(barsIn) * mFramesPerBar * ratio;
    } else {
        newPos = v.pos / static_cast<double>(old->frames) * static_cast<double>(next->frames);
    }
    if (newPos >= static_cast<double>(next->frames)) newPos = 0.0;
    v.sample = next;
    mInUse[slot].store(next, std::memory_order_release);
    v.pos = newPos;
    v.env = 0.0f;  // kratak fade-in (oko 0.7 ms) da prijelaz ne klikne
}

void Mixer::apply(const Command& c) {
    switch (c.type) {
        case Command::Launch: {
            if (!validSlot(c.a) || mSamples[c.a].load(std::memory_order_acquire) == nullptr) return;
            Track& t = mTracks[c.a % kTracks];
            if (t.playing == c.a) {
                if (t.stopQueued) {
                    t.stopQueued = false;  // ponovni pritisak poništava zaustavljanje
                } else {
                    t.stopQueued = true;   // staje na sljedećem taktu
                    t.queued = -1;
                }
            } else if (t.queued == c.a) {
                t.queued = -1;             // poništi start
            } else {
                t.queued = c.a;
                t.stopQueued = false;
            }
            break;
        }
        case Command::LaunchScene: {
            if (c.a < 0 || c.a >= kScenes) return;
            for (int tr = 0; tr < kTracks; ++tr) {
                const int slot = c.a * kTracks + tr;
                if (mSamples[slot].load(std::memory_order_acquire) == nullptr) continue;
                Track& t = mTracks[tr];
                if (t.playing == slot) {  // već svira: ostavi da svira
                    t.stopQueued = false;
                    t.queued = -1;
                } else {
                    t.queued = slot;
                    t.stopQueued = false;
                }
            }
            break;
        }
        case Command::StopTrack: {
            if (!validTrack(c.a)) return;
            Track& t = mTracks[c.a];
            t.queued = -1;
            if (t.playing >= 0) t.stopQueued = true;
            break;
        }
        case Command::StopAll:
            for (auto& v : mVoices) {
                if (v.active) v.envTarget = 0.0f;
            }
            for (auto& t : mTracks) {
                t.playing = -1;
                t.queued = -1;
                t.stopQueued = false;
            }
            break;
        case Command::KillSlot: {
            if (!validSlot(c.a)) return;
            if (mVoices[c.a].active) mVoices[c.a].envTarget = 0.0f;
            Track& t = mTracks[c.a % kTracks];
            if (t.playing == c.a) {
                t.playing = -1;
                t.stopQueued = false;
            }
            if (t.queued == c.a) t.queued = -1;
            break;
        }
        case Command::SetBpm:
            mBpm = std::min(400.0, std::max(20.0, static_cast<double>(c.f)));
            recomputeBar();
            mBpmOut.store(mBpm, std::memory_order_relaxed);
            break;
        case Command::SetSlotLoop:
            if (!validSlot(c.a)) return;
            mSlotLoop[c.a] = c.b != 0;
            if (mVoices[c.a].active) mVoices[c.a].loop = c.b != 0;
            break;
        case Command::SetTrackGain:
            if (validTrack(c.a)) mTracks[c.a].gain = std::max(0.0f, c.f);
            break;
        case Command::SetTrackPan:
            if (validTrack(c.a)) mTracks[c.a].pan = std::min(1.0f, std::max(-1.0f, c.f));
            break;
        case Command::SetTrackSend:
            if (validTrack(c.a) && std::isfinite(c.f)) {
                const float v = std::min(1.0f, std::max(0.0f, c.f));
                if (c.b == 0) mTracks[c.a].sendRev = v; else mTracks[c.a].sendDly = v;
            }
            break;
        case Command::SetTrackEq:
            if (validTrack(c.a) && c.b >= 0 && c.b < 3 && std::isfinite(c.f))
                mTracks[c.a].eq[c.b] = std::min(1.0f, std::max(-1.0f, c.f));
            break;
        case Command::SetMasterFilter:
            if (std::isfinite(c.f)) mFilter = std::min(1.0f, std::max(-1.0f, c.f));
            break;
        case Command::SetTrackMute:
            if (validTrack(c.a)) mTracks[c.a].mute = c.b != 0;
            break;
        case Command::SetTrackSolo:
            if (validTrack(c.a)) mTracks[c.a].solo = c.b != 0;
            break;
        case Command::SetMasterGain:
            mMasterGain = std::max(0.0f, c.f);
            break;
        case Command::SetMasterMute:
            mMasterMute = c.b != 0;
            break;
        case Command::PreviewStart: {
            const Sample* s = mPreviewSample.load(std::memory_order_acquire);
            if (s == nullptr) return;
            mPreview.sample = s;
            mPreviewInUse.store(s, std::memory_order_release);
            mPreview.pos = 0.0;
            mPreview.loop = c.b != 0;
            mPreview.active = true;
            mPreview.env = 0.0f;
            mPreview.envTarget = 1.0f;
            break;
        }
        case Command::PreviewStop:
            if (mPreview.active) mPreview.envTarget = 0.0f;
            break;
    }
}

// Poziva se tačno na početku svakog takta.
void Mixer::onBar() {
    ++mBarCount;
    mBarCountOut.store(mBarCount, std::memory_order_relaxed);

    for (int t = 0; t < kTracks; ++t) {
        Track& tr = mTracks[t];
        if (tr.queued >= 0) {
            const int next = tr.queued;
            if (tr.playing >= 0 && tr.playing != next) stopVoiceNow(tr.playing);  // već utišan prije granice
            tr.queued = -1;
            tr.stopQueued = false;
            if (startVoice(next)) {
                tr.playing = next;
            } else {
                tr.playing = -1;
            }
        } else if (tr.stopQueued) {
            if (tr.playing >= 0) stopVoiceNow(tr.playing);
            tr.playing = -1;
            tr.stopQueued = false;
        } else if (tr.playing >= 0) {
            // Loop se na svakoj granici dužine loopa tvrdo poravna na takt: bez ikakvog pomjeranja (drifta).
            Voice& v = mVoices[tr.playing];
            if (v.active) {
                const Sample* cur = mSamples[tr.playing].load(std::memory_order_acquire);
                if (cur != nullptr && cur != v.sample) adoptSample(tr.playing, cur);
            }
            if (v.active && v.loop && v.sample != nullptr && v.sample->lengthBars >= 0) {
                const int64_t len = lengthBars(*v.sample);
                if ((mBarCount - mStartBar[tr.playing]) % len == 0) v.pos = 0.0;
            }
        }
    }
}

// DJ filter mastera (trackovi + Drums + Keys), u mjestu, prije master fadera (audio nit: bez alokacije). Topology-preserving SVF (Zavalishin/Cytomic):
// stabilan i kad se frekvencija mijenja dok svira. Lijevo od sredine low-pass (20 kHz -> 100 Hz), desno high-pass
// (20 Hz -> 8 kHz), logaritamski. Prvih 10 % hoda potenciometra pretapa suho -> filtrirano, pa prelazak preko
// sredine (LP <-> HP) nema klik. Koeficijenti se računaju svakih 16 frameova.
void Mixer::applyMasterFilter(float* buf, int32_t seg, float smoothCoef, double outRate) {
    constexpr int kCoefEvery = 16;
    constexpr float kRes = 1.0f;  // k = 1/Q; Q = 1: blago naglašen rub, kao DJ mikseri
    const float maxFc = static_cast<float>(0.45 * outRate);
    float a1 = 1.0f, a2 = 0.0f, a3 = 0.0f, wet = 0.0f;
    bool hp = false;
    for (int32_t f = 0; f < seg; ++f) {
        if (f % kCoefEvery == 0) {
            // izglađivanje po bloku od 16 (isti nagib kao gain: smoothCoef po frameu)
            const float step = 1.0f - std::pow(1.0f - smoothCoef, static_cast<float>(kCoefEvery));
            mFilterSmooth += (mFilter - mFilterSmooth) * step;
            if (std::fabs(mFilter - mFilterSmooth) < 1e-4f) mFilterSmooth = mFilter;
            const float x = mFilterSmooth;
            const float a = std::fabs(x);
            hp = x > 0.0f;
            wet = std::min(1.0f, a * 10.0f);
            float fc = hp ? 20.0f * std::pow(400.0f, a) : 20000.0f * std::pow(0.005f, a);
            fc = std::min(fc, maxFc);
            const float g = static_cast<float>(std::tan(3.14159265358979 * fc / outRate));
            a1 = 1.0f / (1.0f + g * (g + kRes));
            a2 = g * a1;
            a3 = g * a2;
        }
        for (int ch = 0; ch < 2; ++ch) {
            const float v0 = buf[2 * f + ch];
            const float v3 = v0 - mSvfIc2[ch];
            const float v1 = a1 * mSvfIc1[ch] + a2 * v3;
            const float v2 = mSvfIc2[ch] + a2 * mSvfIc1[ch] + a3 * v3;
            mSvfIc1[ch] = 2.0f * v1 - mSvfIc1[ch];
            mSvfIc2[ch] = 2.0f * v2 - mSvfIc2[ch];
            const float y = hp ? (v0 - kRes * v1 - v2) : v2;
            buf[2 * f + ch] = v0 + (y - v0) * wet;
        }
    }
    // bez denormala (tiho stanje na x86 bez FTZ zna usporiti)
    for (int ch = 0; ch < 2; ++ch) {
        if (std::fabs(mSvfIc1[ch]) < 1e-20f) mSvfIc1[ch] = 0.0f;
        if (std::fabs(mSvfIc2[ch]) < 1e-20f) mSvfIc2[ch] = 0.0f;
    }
}

// EQ jednog kanala, u mjestu, prije fadera (audio nit: bez alokacije). Tri RBJ biquada (TDF2): low shelf 200 Hz,
// peak 1 kHz (Q 0.8), high shelf 5 kHz; -1..1 = -12..+12 dB. Pojačanje se izglađuje, koeficijenti jednom po segmentu.
void Mixer::applyTrackEq(Track& tr, float* buf, int32_t seg, double outRate) {
    const float k = static_cast<float>(1.0 - std::exp(-static_cast<double>(seg) / (0.02 * outRate)));
    for (int band = 0; band < 3; ++band) {
        tr.eqSmooth[band] += (tr.eq[band] - tr.eqSmooth[band]) * k;
        if (std::fabs(tr.eq[band] - tr.eqSmooth[band]) < 1e-4f) tr.eqSmooth[band] = tr.eq[band];
        if (tr.eqSmooth[band] == 0.0f && tr.eq[band] == 0.0f) {
            std::memset(tr.eqZ[band], 0, sizeof(tr.eqZ[band]));
            continue;   // ravno: ne računa se
        }
        static const double kFreq[3] = {200.0, 1000.0, 5000.0};
        const double fc = std::min(kFreq[band], 0.45 * outRate);
        const double A = std::pow(10.0, tr.eqSmooth[band] * 12.0 / 40.0);
        const double w = 2.0 * 3.14159265358979 * fc / outRate;
        const double cw = std::cos(w), sw = std::sin(w);
        double b0, b1, b2, a0, a1, a2;
        if (band == 1) {
            const double alpha = sw / (2.0 * 0.8);
            b0 = 1.0 + alpha * A; b1 = -2.0 * cw; b2 = 1.0 - alpha * A;
            a0 = 1.0 + alpha / A; a1 = -2.0 * cw; a2 = 1.0 - alpha / A;
        } else {
            const double beta = 2.0 * std::sqrt(A) * (sw / 2.0 * std::sqrt(2.0));   // shelf, S = 1
            if (band == 0) {
                b0 = A * ((A + 1) - (A - 1) * cw + beta);
                b1 = 2 * A * ((A - 1) - (A + 1) * cw);
                b2 = A * ((A + 1) - (A - 1) * cw - beta);
                a0 = (A + 1) + (A - 1) * cw + beta;
                a1 = -2 * ((A - 1) + (A + 1) * cw);
                a2 = (A + 1) + (A - 1) * cw - beta;
            } else {
                b0 = A * ((A + 1) + (A - 1) * cw + beta);
                b1 = -2 * A * ((A - 1) + (A + 1) * cw);
                b2 = A * ((A + 1) + (A - 1) * cw - beta);
                a0 = (A + 1) - (A - 1) * cw + beta;
                a1 = 2 * ((A - 1) - (A + 1) * cw);
                a2 = (A + 1) - (A - 1) * cw - beta;
            }
        }
        const float B0 = float(b0 / a0), B1 = float(b1 / a0), B2 = float(b2 / a0), A1 = float(a1 / a0), A2 = float(a2 / a0);
        for (int ch = 0; ch < 2; ++ch) {
            float z1 = tr.eqZ[band][ch][0], z2 = tr.eqZ[band][ch][1];
            for (int32_t f = 0; f < seg; ++f) {
                const float x = buf[2 * f + ch];
                const float y = B0 * x + z1;
                z1 = B1 * x - A1 * y + z2;
                z2 = B2 * x - A2 * y;
                buf[2 * f + ch] = y;
            }
            if (std::fabs(z1) < 1e-20f) z1 = 0.0f;
            if (std::fabs(z2) < 1e-20f) z2 = 0.0f;
            tr.eqZ[band][ch][0] = z1;
            tr.eqZ[band][ch][1] = z2;
        }
    }
}

void Mixer::renderSegment(float* out, int32_t seg, int64_t untilBar) {
    const double outRate = static_cast<double>(mRate);
    const float upStep = 1.0f / static_cast<float>(kStartFadeFrames);
    const float downStep = static_cast<float>(1.0 / (kPanicFadeSec * outRate));
    const double fadeFrames = kBarFadeSec * outRate;
    const float smoothCoef = static_cast<float>(1.0 - std::exp(-1.0 / (kGainSmoothSec * outRate)));

    std::array<bool, kTracks> used{};
    for (int t = 0; t < kTracks; ++t) {
        std::fill_n(mTrackBuf[t].data(), static_cast<size_t>(seg) * 2, 0.0f);
    }

    for (int slot = 0; slot < kNumSlots; ++slot) {
        Voice& v = mVoices[slot];
        if (!v.active) continue;

        const int t = slot % kTracks;
        Track& tr = mTracks[t];
        const Sample* s = v.sample;
        const double ratio = static_cast<double>(s->sampleRate) / outRate;
        const double framesD = static_cast<double>(s->frames);
        const float* d = s->data.data();
        // Klip koji će na granici takta stati ili biti zamijenjen: utišaj ga do tačno te granice.
        const bool ramp = (tr.playing == slot) && (tr.stopQueued || tr.queued >= 0);
        float* buf = mTrackBuf[t].data();
        used[t] = true;

        for (int32_t f = 0; f < seg; ++f) {
            if (v.pos >= framesD) {
                if (v.loop) {
                    v.pos = std::fmod(v.pos, framesD);
                } else {
                    endVoice(slot);
                    break;
                }
            }
            if (v.env < v.envTarget) {
                v.env = std::min(v.envTarget, v.env + upStep);
            } else if (v.env > v.envTarget) {
                v.env = std::max(v.envTarget, v.env - downStep);
                if (v.env <= 0.0f) {
                    endVoice(slot);
                    break;
                }
            }

            const int64_t i0 = static_cast<int64_t>(v.pos);
            const float frac = static_cast<float>(v.pos - static_cast<double>(i0));
            int64_t i1 = i0 + 1;
            if (i1 >= s->frames) i1 = v.loop ? 0 : i0;
            const float l = d[2 * i0] * (1.0f - frac) + d[2 * i1] * frac;
            const float r = d[2 * i0 + 1] * (1.0f - frac) + d[2 * i1 + 1] * frac;

            float g = v.env;
            if (ramp) {
                const double rem = static_cast<double>(untilBar - f);
                if (rem < fadeFrames) g *= static_cast<float>(rem / fadeFrames);
            }
            buf[2 * f] += l * g;
            buf[2 * f + 1] += r * g;
            v.pos += ratio;
        }
    }

    bool anySolo = false;
    for (const auto& tr : mTracks) anySolo = anySolo || tr.solo;
    bool anySend = false;   // neko je u ovom segmentu poslao u efekte (sabirnice očišćene)

    for (int t = 0; t < kTracks; ++t) {
        Track& tr = mTracks[t];
        const bool audible = !tr.mute && (!anySolo || tr.solo);
        const float target = audible ? tr.gain : 0.0f;
        // pan (balance, kao Ableton za stereo): sredina = oba kanala 1; u stranu se suprotni kanal stiša (kosinus)
        const float panLTarget = tr.pan > 0.0f ? std::cos(tr.pan * 1.5707963f) : 1.0f;
        const float panRTarget = tr.pan < 0.0f ? std::cos(-tr.pan * 1.5707963f) : 1.0f;
        if (!used[t]) {
            tr.gainSmooth = target;  // nema zvuka, nema šta da klikne
            tr.panL = panLTarget;
            tr.panR = panRTarget;
            tr.revSmooth = tr.sendRev;
            tr.dlySmooth = tr.sendDly;
            for (int b = 0; b < 3; ++b) tr.eqSmooth[b] = tr.eq[b];
            std::memset(tr.eqZ, 0, sizeof(tr.eqZ));
            continue;
        }
        float* buf = mTrackBuf[t].data();
        // EQ kanala (prije fadera); ravan EQ se ne računa
        if (tr.eq[0] != 0.0f || tr.eq[1] != 0.0f || tr.eq[2] != 0.0f ||
            tr.eqSmooth[0] != 0.0f || tr.eqSmooth[1] != 0.0f || tr.eqSmooth[2] != 0.0f) {
            applyTrackEq(tr, buf, seg, outRate);
        } else {
            std::memset(tr.eqZ, 0, sizeof(tr.eqZ));
        }
        float peak = mPeak[t];
        const bool sends = tr.sendRev > 0.0f || tr.revSmooth > 0.0f || tr.sendDly > 0.0f || tr.dlySmooth > 0.0f;
        if (sends && !anySend) {
            std::fill_n(mRevBus.data(), static_cast<size_t>(seg) * 2, 0.0f);
            std::fill_n(mDlyBus.data(), static_cast<size_t>(seg) * 2, 0.0f);
            anySend = true;
        }
        for (int32_t f = 0; f < seg; ++f) {
            tr.gainSmooth += (target - tr.gainSmooth) * smoothCoef;
            tr.panL += (panLTarget - tr.panL) * smoothCoef;
            tr.panR += (panRTarget - tr.panR) * smoothCoef;
            const float l = buf[2 * f] * tr.gainSmooth * tr.panL;
            const float r = buf[2 * f + 1] * tr.gainSmooth * tr.panR;
            peak = std::max(peak, std::max(std::fabs(l), std::fabs(r)));
            out[2 * f] += l;
            out[2 * f + 1] += r;
            if (sends) {
                tr.revSmooth += (tr.sendRev - tr.revSmooth) * smoothCoef;
                tr.dlySmooth += (tr.sendDly - tr.dlySmooth) * smoothCoef;
                if (tr.revSmooth < 1e-5f && tr.sendRev == 0.0f) tr.revSmooth = 0.0f;
                if (tr.dlySmooth < 1e-5f && tr.sendDly == 0.0f) tr.dlySmooth = 0.0f;
                mRevBus[2 * f] += l * tr.revSmooth;
                mRevBus[2 * f + 1] += r * tr.revSmooth;
                mDlyBus[2 * f] += l * tr.dlySmooth;
                mDlyBus[2 * f + 1] += r * tr.dlySmooth;
            }
        }
        mPeak[t] = peak;
    }

    // povrat send efekata (reverb + delay); kad niko ne šalje, računa se još rep, pa se gasi (ne troši procesor)
    if (anySend) {
        mFxTail = static_cast<int64_t>(std::max(8.0 * outRate, 12.0 * kDlyBeats * mFramesPerBar / 4.0));
    } else if (mFxTail > 0) {
        std::fill_n(mRevBus.data(), static_cast<size_t>(seg) * 2, 0.0f);
        std::fill_n(mDlyBus.data(), static_cast<size_t>(seg) * 2, 0.0f);
    }
    if (mFxTail > 0) {
        if (mFxClean) { mSendFx.clear(); mFxClean = false; }
        mSendFx.process(mRevBus.data(), mDlyBus.data(), out, seg, mRate, mFramesPerBar / 4.0);
        mFxTail -= seg;
        if (mFxTail <= 0) { mFxTail = 0; mFxClean = true; }
    }

    // DJ filter na cijelom miksu (out već ima i Drums/Keys iz AuxSource-a); na sredini se ne računa
    if (mFilter != 0.0f || mFilterSmooth != 0.0f) {
        applyMasterFilter(out, seg, smoothCoef, outRate);
    } else {
        mSvfIc1[0] = mSvfIc1[1] = mSvfIc2[0] = mSvfIc2[1] = 0.0f;
    }

    // Limiter na masteru (uvijek uključen, bez podešavanja): vrh nikad preko kLimThreshold. Napad trenutan (bez
    // kašnjenja, bez lookahead-a), otpuštanje ~120 ms; stereo vezano (slika se ne pomjera). Ispod praga: gain tačno 1.
    const float limRelease = static_cast<float>(1.0 - std::exp(-1.0 / (kLimReleaseSec * outRate)));
    const float masterTarget = mMasterMute ? 0.0f : mMasterGain;
    for (int32_t f = 0; f < seg; ++f) {
        mMasterSmooth += (masterTarget - mMasterSmooth) * smoothCoef;
        float l = out[2 * f] * mMasterSmooth;
        float r = out[2 * f + 1] * mMasterSmooth;
        const float pk = std::max(std::fabs(l), std::fabs(r));
        const float want = pk > kLimThreshold ? kLimThreshold / pk : 1.0f;
        if (want < mLimGain) {
            mLimGain = want;                                   // odmah utišaj (nema prebačaja)
        } else if (mLimGain < 1.0f) {
            mLimGain += (want - mLimGain) * limRelease;        // polako nazad
            if (mLimGain > 0.99999f) mLimGain = 1.0f;
        }
        if (mLimGain < 1.0f) {
            l *= mLimGain;
            r *= mLimGain;
        }
        l = std::max(-1.0f, std::min(1.0f, l));   // sigurnost (NaN/inf ne dolaze ovdje)
        r = std::max(-1.0f, std::min(1.0f, r));
        out[2 * f] = l;
        out[2 * f + 1] = r;
        mMasterPeak = std::max(mMasterPeak, std::max(std::fabs(l), std::fabs(r)));
    }
}

// Preslušavanje: dodaje se na izlaz poslije mastera (ne ovisi o masteru, mute-u ni solou).
void Mixer::renderPreview(float* out, int32_t frames) {
    Preview& p = mPreview;
    if (!p.active || p.sample == nullptr) {
        mPreviewProgressOut.store(-1.0f, std::memory_order_relaxed);
        return;
    }
    constexpr float kPreviewGain = 0.8f;
    const double outRate = static_cast<double>(mRate);
    const float step = static_cast<float>(1.0 / (0.005 * outRate));  // fade 5 ms
    const Sample* s = p.sample;
    const double ratio = static_cast<double>(s->sampleRate) / outRate;
    const double framesD = static_cast<double>(s->frames);
    const float* d = s->data.data();

    for (int32_t f = 0; f < frames; ++f) {
        if (p.pos >= framesD) {
            if (p.loop) {
                p.pos = std::fmod(p.pos, framesD);
            } else {
                p.active = false;
                break;
            }
        }
        if (p.env < p.envTarget) {
            p.env = std::min(p.envTarget, p.env + step);
        } else if (p.env > p.envTarget) {
            p.env = std::max(p.envTarget, p.env - step);
            if (p.env <= 0.0f) {
                p.active = false;
                break;
            }
        }
        const int64_t i0 = static_cast<int64_t>(p.pos);
        const float frac = static_cast<float>(p.pos - static_cast<double>(i0));
        int64_t i1 = i0 + 1;
        if (i1 >= s->frames) i1 = p.loop ? 0 : i0;
        const float l = d[2 * i0] * (1.0f - frac) + d[2 * i1] * frac;
        const float r = d[2 * i0 + 1] * (1.0f - frac) + d[2 * i1 + 1] * frac;
        const float g = p.env * kPreviewGain;
        out[2 * f] = std::max(-1.0f, std::min(1.0f, out[2 * f] + l * g));
        out[2 * f + 1] = std::max(-1.0f, std::min(1.0f, out[2 * f + 1] + r * g));
        p.pos += ratio;
    }
    if (!p.active) {
        mPreviewInUse.store(nullptr, std::memory_order_release);
        mPreviewProgressOut.store(-1.0f, std::memory_order_relaxed);
    } else {
        mPreviewProgressOut.store(static_cast<float>(p.pos / framesD), std::memory_order_relaxed);
    }
}

void Mixer::renderChunk(float* out, int32_t frames) {
    int32_t off = 0;
    while (off < frames) {
        int64_t until = static_cast<int64_t>(std::ceil(mFramesPerBar - mBarPos - 1e-9));
        if (until < 1) until = 1;
        const int32_t seg = static_cast<int32_t>(std::min<int64_t>(frames - off, until));
        if (AuxSource* aux = mAux.load(std::memory_order_acquire)) {
            aux->renderAux(out + 2 * static_cast<size_t>(off), seg, mRate, mBarCount, mBarPos, mFramesPerBar);
        }
        renderSegment(out + 2 * static_cast<size_t>(off), seg, until);
        mBarPos += seg;
        if (static_cast<int64_t>(seg) == until) {
            mBarPos -= mFramesPerBar;
            if (mBarPos < 0.0) mBarPos = 0.0;
            onBar();
        }
        off += seg;
    }
}

// Poravnava tempo i fazu takta sa vanjskim satom. Velika razlika (prvi put, ili > 20 ms): skok, a petlje
// koje sviraju se pomjeraju za isto toliko da ostanu u fazi sa taktom. Mala razlika: lagano klizanje
// (najviše 0.05% brzine), da se ne čuje.
void Mixer::syncToClock(double bpm, double barPhase, int32_t numFrames) {
    if (bpm >= 20.0 && bpm <= 400.0 && std::fabs(bpm - mBpm) > 1e-6) {
        mBpm = bpm;
        recomputeBar();
        mBpmOut.store(mBpm, std::memory_order_relaxed);
    }
    if (!(barPhase >= 0.0) || !std::isfinite(barPhase) || mFramesPerBar <= 0.0) return;

    double e = barPhase - mBarPos / mFramesPerBar;
    e -= std::floor(e + 0.5);  // najkraći put, [-0.5, 0.5)
    const double errFrames = e * mFramesPerBar;

    double shift;
    bool hard = false;
    if (!mClockLocked || std::fabs(errFrames) > 0.02 * static_cast<double>(mRate)) {
        shift = errFrames;
        hard = true;
        mClockLocked = true;
        mClockLockedOut.store(true, std::memory_order_relaxed);
    } else {
        const double limit = 0.0005 * static_cast<double>(numFrames);
        shift = std::max(-limit, std::min(limit, errFrames));
    }
    if (shift == 0.0) return;

    mBarPos += shift;
    if (mBarPos < 0.0) mBarPos += mFramesPerBar;
    else if (mBarPos >= mFramesPerBar) mBarPos -= mFramesPerBar;
    if (mBarPos < 0.0 || mBarPos >= mFramesPerBar) mBarPos = 0.0;

    if (hard) {
        for (int slot = 0; slot < kNumSlots; ++slot) {
            Voice& v = mVoices[slot];
            if (!v.active || !v.loop || v.sample == nullptr || v.sample->frames <= 0) continue;
            const double ratio = static_cast<double>(v.sample->sampleRate) / static_cast<double>(mRate);
            const double len = static_cast<double>(v.sample->frames);
            v.pos = std::fmod(v.pos + shift * ratio, len);
            if (v.pos < 0.0) v.pos += len;
        }
    }
}

void Mixer::render(float* out, int32_t numFrames) {
    std::memset(out, 0, sizeof(float) * 2 * static_cast<size_t>(numFrames));

    const int rate = mOutRate.load(std::memory_order_relaxed);
    if (rate != mRate) {
        mRate = rate;
        recomputeBar();
    }

    Command cmd;
    while (mQueue.pop(cmd)) apply(cmd);

    // Vanjski sat (Link) ima prednost nad unutrašnjim: tempo i faza takta
    if (ClockSource* cs = mClock.load(std::memory_order_acquire)) {
        double bpm = 0.0, phase = 0.0;
        if (cs->query(numFrames, mRate, &bpm, &phase)) {
            syncToClock(bpm, phase, numFrames);
        } else if (mClockLocked) {
            mClockLocked = false;
            mClockLockedOut.store(false, std::memory_order_relaxed);
        }
    } else if (mClockLocked) {
        mClockLocked = false;
        mClockLockedOut.store(false, std::memory_order_relaxed);
    }

    const float decay = static_cast<float>(
        std::exp(-static_cast<double>(numFrames) / (static_cast<double>(mRate) * kPeakDecaySec)));
    for (auto& p : mPeak) p *= decay;
    mMasterPeak *= decay;

    int32_t done = 0;
    while (done < numFrames) {
        const int32_t n = std::min(numFrames - done, kChunk);
        renderChunk(out + 2 * static_cast<size_t>(done), n);
        done += n;
    }
    // Master miks (prije preslušavanja, koje ide mimo mastera) za vanjskog primaoca, npr. Link Audio
    if (OutputTap* tap = mTap.load(std::memory_order_acquire)) tap->onOutput(out, numFrames, mRate);
    renderPreview(out, numFrames);

    // ---- stanje za UI
    for (int slot = 0; slot < kNumSlots; ++slot) {
        uint8_t st = kEmpty;
        if (mSamples[slot].load(std::memory_order_relaxed) != nullptr) {
            const Track& t = mTracks[slot % kTracks];
            if (t.playing == slot) {
                st = t.stopQueued ? kQueuedStop : kPlaying;
            } else if (t.queued == slot) {
                st = kQueuedStart;
            } else {
                st = kIdle;
            }
        }
        mSlotStateOut[slot].store(st, std::memory_order_relaxed);
    }
    for (int t = 0; t < kTracks; ++t) {
        float progress = 0.0f;
        const int p = mTracks[t].playing;
        if (p >= 0 && mVoices[p].active && mVoices[p].sample != nullptr) {
            progress = static_cast<float>(mVoices[p].pos / static_cast<double>(mVoices[p].sample->frames));
        }
        mProgressOut[t].store(progress, std::memory_order_relaxed);
        mLevelOut[t].store(mPeak[t], std::memory_order_relaxed);
    }
    mBarPhaseOut.store(static_cast<float>(mBarPos / mFramesPerBar), std::memory_order_relaxed);
    mMasterLevelOut.store(mMasterPeak, std::memory_order_relaxed);
}

}  // namespace padsampler
