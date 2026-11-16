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
            continue;
        }
        const float* buf = mTrackBuf[t].data();
        float peak = mPeak[t];
        for (int32_t f = 0; f < seg; ++f) {
            tr.gainSmooth += (target - tr.gainSmooth) * smoothCoef;
            tr.panL += (panLTarget - tr.panL) * smoothCoef;
            tr.panR += (panRTarget - tr.panR) * smoothCoef;
            const float l = buf[2 * f] * tr.gainSmooth * tr.panL;
            const float r = buf[2 * f + 1] * tr.gainSmooth * tr.panR;
            peak = std::max(peak, std::max(std::fabs(l), std::fabs(r)));
            out[2 * f] += l;
            out[2 * f + 1] += r;
        }
        mPeak[t] = peak;
    }

    const float masterTarget = mMasterMute ? 0.0f : mMasterGain;
    for (int32_t f = 0; f < seg; ++f) {
        mMasterSmooth += (masterTarget - mMasterSmooth) * smoothCoef;
        float l = out[2 * f] * mMasterSmooth;
        float r = out[2 * f + 1] * mMasterSmooth;
        l = std::max(-1.0f, std::min(1.0f, l));
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
