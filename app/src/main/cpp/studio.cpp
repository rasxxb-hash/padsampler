#include "studio.h"

#include <algorithm>

#include "synth.h"
#include <cmath>
#include <cstring>

namespace padsampler {

namespace {
constexpr double kChokeFadeSec = 0.003;   // nova nota na istoj traci utiša prethodnu
constexpr double kPanicFadeSec = 0.005;
constexpr double kGainSmoothSec = 0.010;
constexpr double kPeakDecaySec = 0.15;

inline bool validPart(int p) { return p >= 0 && p < kStudioParts; }

// Svira pattern između tickova t0 i t1 (t1 isključivo, bez prelaza preko kraja patterna) u buf (+=):
// note se okidaju na tačnom frameu. Isti kod za uživo i za offline render.
// [active] (može biti nullptr): note koje je pattern upalio, a još ih nije ugasio.
void renderPattern(Instrument& inst, const Pattern& p, float* buf, int32_t frames, double t0, double t1,
                   double ticksPerFrame, int sampleRate, std::array<bool, 128>* active = nullptr) {
    if (frames <= 0) return;
    const auto& tr = p.triggers;
    size_t lo = 0, hi = tr.size();
    while (lo < hi) {
        const size_t mid = (lo + hi) / 2;
        if (static_cast<double>(tr[mid].tick) < t0) lo = mid + 1; else hi = mid;
    }
    int32_t done = 0;
    for (size_t i = lo; i < tr.size() && static_cast<double>(tr[i].tick) < t1; ++i) {
        int32_t f = static_cast<int32_t>(std::ceil((static_cast<double>(tr[i].tick) - t0) / ticksPerFrame - 1e-9));
        f = std::max(done, std::min(frames, f));
        if (f > done) {
            inst.render(buf + 2 * static_cast<size_t>(done), f - done, sampleRate);
            done = f;
        }
        if (tr[i].on) inst.noteOn(tr[i].note, tr[i].velocity); else inst.noteOff(tr[i].note);
        if (active != nullptr && tr[i].note >= 0 && tr[i].note < 128) (*active)[tr[i].note] = tr[i].on;
    }
    if (done < frames) inst.render(buf + 2 * static_cast<size_t>(done), frames - done, sampleRate);
}
}  // namespace

// ------------------------------------------------------------------ Pattern

std::shared_ptr<const Pattern> Pattern::make(int bars, const NoteEvent* events, int count) {
    auto p = std::make_shared<Pattern>();
    p->bars = std::max(1, std::min(kMaxPatternBars, bars));
    const int32_t len = p->lengthTicks();
    for (int i = 0; i < count; ++i) {
        NoteEvent e = events[i];
        if (e.tick < 0 || e.tick >= len || e.note < 0 || e.note > 127) continue;
        e.length = std::max(1, std::min(len, e.length));
        e.velocity = std::max(0.0f, std::min(1.0f, e.velocity));
        p->notes.push_back(e);
    }
    std::stable_sort(p->notes.begin(), p->notes.end(),
                     [](const NoteEvent& a, const NoteEvent& b) { return a.tick < b.tick; });
    p->triggers.reserve(p->notes.size() * 2);
    for (const auto& e : p->notes) {
        p->triggers.push_back({e.tick, e.note, e.velocity, true});
        p->triggers.push_back({(e.tick + e.length) % len, e.note, 0.0f, false});
    }
    std::stable_sort(p->triggers.begin(), p->triggers.end(), [](const Trigger& a, const Trigger& b) {
        if (a.tick != b.tick) return a.tick < b.tick;
        return !a.on && b.on;
    });
    return p;
}

// ------------------------------------------------------------------ DrumKit

DrumKit::DrumKit() {
    for (auto& g : mGain) g.store(1.0f, std::memory_order_relaxed);
}

void DrumKit::setSample(int lane, std::shared_ptr<const Sample> s) {
    if (lane < 0 || lane >= kDrumLanes) return;
    if (s && (s->frames <= 0 || s->sampleRate <= 0)) s = nullptr;
    mSamples[lane].publish(std::move(s));
}

std::shared_ptr<const Sample> DrumKit::sample(int lane) const {
    if (lane < 0 || lane >= kDrumLanes) return nullptr;
    return mSamples[lane].get();
}

void DrumKit::setLaneGain(int lane, float linear) {
    if (lane < 0 || lane >= kDrumLanes) return;
    mGain[lane].store(std::max(0.0f, std::min(4.0f, linear)), std::memory_order_relaxed);
}

float DrumKit::laneGain(int lane) const {
    if (lane < 0 || lane >= kDrumLanes) return 0.0f;
    return mGain[lane].load(std::memory_order_relaxed);
}

void DrumKit::noteOn(int note, float velocity) {
    if (note < 0 || note >= kDrumLanes || velocity <= 0.0f) return;
    const Sample* s = mSamples[note].audio();
    if (s == nullptr) return;
    for (auto& v : mVoices) {
        if (v.active && v.lane == note) v.fading = true;
    }
    Voice* slot = nullptr;
    for (auto& v : mVoices) {
        if (!v.active) { slot = &v; break; }
    }
    if (slot == nullptr) {  // ukradi najstariji glas
        slot = &mVoices[0];
        for (auto& v : mVoices) {
            if (v.age < slot->age) slot = &v;
        }
    }
    slot->s = s;
    slot->lane = note;
    slot->pos = 0.0;
    slot->velocity = velocity;
    slot->env = 1.0f;
    slot->fading = false;
    slot->active = true;
    slot->age = ++mAge;
}

void DrumKit::silence() {
    for (auto& v : mVoices) {
        if (v.active) v.fading = true;
    }
}

void DrumKit::render(float* out, int32_t frames, int sampleRate) {
    const float chokeStep = static_cast<float>(1.0 / (kChokeFadeSec * sampleRate));
    for (auto& v : mVoices) {
        if (!v.active) continue;
        // Sempl trake je zamijenjen: stari se više ne smije čitati (može biti oslobođen).
        if (mSamples[v.lane].audio() != v.s) {
            v.active = false;
            continue;
        }
        const Sample* s = v.s;
        const double ratio = static_cast<double>(s->sampleRate) / sampleRate;
        const double len = static_cast<double>(s->frames);
        const float* d = s->data.data();
        const float g = v.velocity * mGain[v.lane].load(std::memory_order_relaxed);
        for (int32_t f = 0; f < frames; ++f) {
            if (v.pos >= len) { v.active = false; break; }
            if (v.fading) {
                v.env -= chokeStep;
                if (v.env <= 0.0f) { v.active = false; break; }
            }
            const int64_t i0 = static_cast<int64_t>(v.pos);
            const float frac = static_cast<float>(v.pos - static_cast<double>(i0));
            const int64_t i1 = (i0 + 1 < s->frames) ? i0 + 1 : i0;
            const float gg = g * v.env;
            out[2 * f] += (d[2 * i0] * (1.0f - frac) + d[2 * i1] * frac) * gg;
            out[2 * f + 1] += (d[2 * i0 + 1] * (1.0f - frac) + d[2 * i1 + 1] * frac) * gg;
            v.pos += ratio;
        }
    }
}

// ------------------------------------------------------------------ Studio: red komandi

bool Studio::Queue::push(const Cmd& c) {
    std::lock_guard<std::mutex> lock(mMutex);
    const size_t h = mHead.load(std::memory_order_relaxed);
    const size_t next = (h + 1) % kSize;
    if (next == mTail.load(std::memory_order_acquire)) return false;
    mBuf[h] = c;
    mHead.store(next, std::memory_order_release);
    return true;
}

bool Studio::Queue::pop(Cmd& c) {
    const size_t t = mTail.load(std::memory_order_relaxed);
    if (t == mHead.load(std::memory_order_acquire)) return false;
    c = mBuf[t];
    mTail.store((t + 1) % kSize, std::memory_order_release);
    return true;
}

// ------------------------------------------------------------------ Studio: kontrola

Studio::Studio()
    : mSynth(std::make_unique<Synth>()),
      mArp(std::make_unique<Arpeggiator>(*mSynth, *mSynth)),
      mFx(std::make_unique<KeysFx>(*mSynth)) {
    for (auto& p : mPosOut) p.store(-1.0f, std::memory_order_relaxed);
    for (auto& t : mTransportOut) t.store(kStudioStopped, std::memory_order_relaxed);
}

Studio::~Studio() = default;

void Studio::setPattern(int part, int bars, const NoteEvent* events, int count) {
    if (!validPart(part)) return;
    mPatterns[part].publish(Pattern::make(bars, events, events ? count : 0));
}

std::shared_ptr<const Pattern> Studio::pattern(int part) const {
    if (!validPart(part)) return nullptr;
    return mPatterns[part].get();
}

void Studio::play(int part) { if (part == -1 || validPart(part)) mQueue.push({Cmd::Play, part, 0, 0.0f}); }
void Studio::stop(int part) { if (part == -1 || validPart(part)) mQueue.push({Cmd::Stop, part, 0, 0.0f}); }

int Studio::transport(int part) const {
    if (validPart(part)) return mTransportOut[part].load(std::memory_order_relaxed);
    int t = kStudioStopped;
    for (int p = 0; p < kStudioParts; ++p) t = std::max(t, mTransportOut[p].load(std::memory_order_relaxed));
    return t;
}
void Studio::panic() { mQueue.push({Cmd::Panic, -1, 0, 0.0f}); }

void Studio::noteOn(int part, int note, float velocity) {
    if (validPart(part)) mQueue.push({Cmd::NoteOn, part, note, velocity});
}
void Studio::noteOff(int part, int note) {
    if (validPart(part)) mQueue.push({Cmd::NoteOff, part, note, 0.0f});
}
void Studio::setPartGain(int part, float linear) {
    if (validPart(part)) mQueue.push({Cmd::Gain, part, 0, std::max(0.0f, std::min(4.0f, linear))});
}
void Studio::setPartMute(int part, bool mute) {
    if (validPart(part)) mQueue.push({Cmd::Mute, part, 0, mute ? 1.0f : 0.0f});
}

float Studio::positionTicks(int part) const {
    if (!validPart(part)) return -1.0f;
    return mPosOut[part].load(std::memory_order_relaxed);
}

Instrument* Studio::instrument(int part) {
    if (part == kPartDrums) return &mDrums;
    if (part == kPartKeys) return mArp.get();
    return nullptr;
}

// ------------------------------------------------------------------ Studio: offline render

std::vector<float> Studio::renderOffline(int part, double bpm, int sampleRate) {
    if (!validPart(part) || bpm <= 0.0 || sampleRate <= 0) return {};
    const auto pat = pattern(part);
    if (!pat) return {};

    // Vlastita kopija instrumenta (isti zvuk), da render ne dira ono što svira uživo.
    std::unique_ptr<DrumKit> kit;
    std::unique_ptr<Synth> syn;
    std::unique_ptr<Arpeggiator> arp;
    std::unique_ptr<KeysFx> fx;
    Instrument* inst = nullptr;
    if (part == kPartDrums) {
        kit = std::make_unique<DrumKit>();
        for (int lane = 0; lane < kDrumLanes; ++lane) {
            kit->setSample(lane, mDrums.sample(lane));
            kit->setLaneGain(lane, mDrums.laneGain(lane));
        }
        inst = kit.get();
    } else if (part == kPartKeys) {
        syn = std::make_unique<Synth>();
        syn->copyParamsFrom(*mSynth);          // uključuje i parametre arpeggiatora
        arp = std::make_unique<Arpeggiator>(*syn, *syn);
        fx = std::make_unique<KeysFx>(*syn);     // efekti: rep iz prvog prolaza se čuje na početku (loop bez šava)
        inst = arp.get();
    }
    if (inst == nullptr) return {};

    const double framesPerBar = sampleRate * 240.0 / bpm;
    const int64_t loopFrames = std::llround(pat->bars * framesPerBar);
    if (loopFrames <= 0) return {};
    const double lenTicks = static_cast<double>(pat->lengthTicks());
    const double tpf = lenTicks / static_cast<double>(loopFrames);

    std::vector<float> out(static_cast<size_t>(loopFrames) * 2, 0.0f);
    std::vector<float> tmp(2 * kScratch);
    // Prvi prolaz samo "zagrije" glasove: rep nota sa kraja patterna se tako čuje na početku drugog prolaza,
    // pa loop nema šava.
    for (int pass = 0; pass < 2; ++pass) {
        for (int64_t f = 0; f < loopFrames;) {
            const int32_t n = static_cast<int32_t>(std::min<int64_t>(kScratch, loopFrames - f));
            float* buf = pass == 1 ? out.data() + 2 * f : tmp.data();
            if (pass == 0) std::fill(tmp.begin(), tmp.begin() + 2 * n, 0.0f);
            const double t0 = static_cast<double>(f) * tpf;
            const double t1 = std::min(lenTicks, static_cast<double>(f + n) * tpf);
            // arp: neprekidna pozicija kroz oba prolaza (pattern je cijeli broj taktova, mreža se poklapa)
            if (arp) arp->sync((pass * lenTicks + t0) / kTicksPerBeat, tpf / kTicksPerBeat);
            renderPattern(*inst, *pat, buf, n, t0, t1, tpf, sampleRate);
            if (fx) fx->process(buf, n, sampleRate, kTicksPerBeat / tpf);
            f += n;
        }
    }
    return out;
}

// ------------------------------------------------------------------ Studio: audio nit

void Studio::apply(const Cmd& c) {
    switch (c.type) {
        case Cmd::Play:
            break;   // renderAux (treba broj takta)
        case Cmd::Stop:
        case Cmd::Panic:
            for (int p = 0; p < kStudioParts; ++p) {
                if (c.part != -1 && c.part != p) continue;
                mTransport[p] = kStudioStopped;
                mParts[p].patNotes.fill(false);   // releaseAll ispod pušta i note patterna
                if (Instrument* inst = instrument(p)) {
                    if (c.type == Cmd::Panic) inst->silence(); else inst->releaseAll();
                }
            }
            break;
        case Cmd::NoteOn:
            if (Instrument* inst = instrument(c.part)) inst->noteOn(c.note, c.f);
            break;
        case Cmd::NoteOff:
            if (Instrument* inst = instrument(c.part)) inst->noteOff(c.note);
            break;
        case Cmd::Gain:
            mParts[c.part].gain = c.f;
            break;
        case Cmd::Mute:
            mParts[c.part].mute = c.f != 0.0f;
            break;
    }
}

void Studio::renderAux(float* out, int32_t frames, int sampleRate, int64_t barCount, double barPos,
                       double framesPerBar) {
    if (sampleRate <= 0 || framesPerBar <= 0.0) return;
    Cmd c;
    while (mQueue.pop(c)) {
        if (c.type == Cmd::Play) {
            for (int p = 0; p < kStudioParts; ++p) {
                if ((c.part == -1 || c.part == p) && mTransport[p] == kStudioStopped) {
                    mTransport[p] = kStudioQueued;
                    mQueuedAt[p] = barCount;
                }
            }
        }
        apply(c);
    }
    // Play kreće na prvom sljedećem taktu (segment nikad ne prelazi granicu takta).
    for (int p = 0; p < kStudioParts; ++p) {
        if (mTransport[p] == kStudioQueued && barCount > mQueuedAt[p]) {
            mTransport[p] = kStudioPlaying;
            mStartBar[p] = barCount;
        }
        mTransportOut[p].store(mTransport[p], std::memory_order_relaxed);
    }

    mPeak *= static_cast<float>(std::exp(-static_cast<double>(frames) / (sampleRate * kPeakDecaySec)));
    int32_t off = 0;
    while (off < frames) {
        const int32_t n = std::min(frames - off, kScratch);
        renderPiece(out + 2 * static_cast<size_t>(off), n, sampleRate, barCount, barPos + off, framesPerBar);
        off += n;
    }
    mLevelOut.store(mPeak, std::memory_order_relaxed);
    renderClick(out, frames, sampleRate, barPos, framesPerBar);
}

// Metronom (odbrojavanje i snimanje u Keys): kratak sinusni "tik" na svaku četvrtinu, viši na prvoj dobi.
// Ide direktno u master (ne u nivo Studija); offline render ga nema.
void Studio::renderClick(float* out, int32_t frames, int sampleRate, double barPos, double framesPerBar) {
    const bool on = mClickOn.load(std::memory_order_relaxed);
    if (!on && mClickPos < 0) return;
    const double beatLen = framesPerBar / 4.0;
    const int32_t clickLen = static_cast<int32_t>(sampleRate * 0.03);
    const double decay = 0.006 * sampleRate;
    for (int32_t f = 0; f < frames; ++f) {
        const double pos = barPos + f;
        if (on && std::fmod(pos, beatLen) < 1.0) {
            mClickPos = 0;
            mClickPhase = 0.0;
            mClickInc = 2.0 * 3.14159265358979 * (pos < 1.0 ? 1760.0 : 1175.0) / sampleRate;
        }
        if (mClickPos < 0) continue;
        const float s = static_cast<float>(0.3 * std::exp(-mClickPos / decay) * std::sin(mClickPhase));
        mClickPhase += mClickInc;
        out[2 * f] += s;
        out[2 * f + 1] += s;
        if (++mClickPos >= clickLen) mClickPos = -1;
    }
}

void Studio::renderPiece(float* out, int32_t frames, int sampleRate, int64_t barCount, double barPos,
                         double framesPerBar) {
    const float smoothCoef = static_cast<float>(1.0 - std::exp(-1.0 / (kGainSmoothSec * sampleRate)));
    const double tpf = static_cast<double>(kTicksPerBar) / framesPerBar;

    for (int p = 0; p < kStudioParts; ++p) {
        Part& part = mParts[p];
        Instrument* inst = instrument(p);
        const Pattern* pat = mPatterns[p].audio();
        if (pat != part.lastPattern) {
            // Novi pattern dok svira: note starog patterna koje zvuče se puste (inače bi ostale bez note off).
            // Samo one: note koje korisnik drži uživo sviraju dalje. Kad ne svira, stop je već sve pustio.
            // (stari pattern se ne čita: možda je već oslobođen; zato se pamte note koje je upalio)
            if (inst != nullptr) {
                for (int n = 0; n < 128; ++n) {
                    if (part.patNotes[n]) inst->noteOff(n);
                }
            }
            part.patNotes.fill(false);
            part.lastPattern = pat;
        }
        if (inst == nullptr) {
            mPosOut[p].store(-1.0f, std::memory_order_relaxed);
            continue;
        }

        float* buf = mScratch.data();
        std::fill_n(buf, static_cast<size_t>(frames) * 2, 0.0f);
        // arp prati globalni takt (i kad pattern ne svira): apsolutna pozicija u dobama
        if (p == kPartKeys) {
            mArp->sync(static_cast<double>(barCount) * 4.0 + barPos * 4.0 / framesPerBar, 4.0 / framesPerBar);
        }
        if (mTransport[p] == kStudioPlaying && pat != nullptr) {
            const int64_t rel = barCount - mStartBar[p];
            const int64_t patBar = ((rel % pat->bars) + pat->bars) % pat->bars;
            const double barStart = static_cast<double>(patBar * kTicksPerBar);
            // Takt koji nije cijeli broj frameova: prvi segment takta počinje ostatkom < 1 frame (granica je
            // bila usred frame-a). Note na samom početku takta pripadaju ovom segmentu.
            const double t0 = barPos < 1.0 ? barStart : barStart + barPos * tpf;
            const double t1 = std::min(barStart + kTicksPerBar, t0 + frames * tpf);
            renderPattern(*inst, *pat, buf, frames, t0, t1, tpf, sampleRate, &part.patNotes);
            mPosOut[p].store(static_cast<float>(t1), std::memory_order_relaxed);
        } else {
            inst->render(buf, frames, sampleRate);
            mPosOut[p].store(-1.0f, std::memory_order_relaxed);
        }

        // Keys: efekti (rep reverba i delaya teče i kad ništa ne svira)
        if (p == kPartKeys) mFx->process(buf, frames, sampleRate, framesPerBar / 4.0);
        const float target = part.mute ? 0.0f : part.gain;
        for (int32_t f = 0; f < frames; ++f) {
            part.gainSmooth += (target - part.gainSmooth) * smoothCoef;
            const float l = buf[2 * f] * part.gainSmooth;
            const float r = buf[2 * f + 1] * part.gainSmooth;
            mPeak = std::max(mPeak, std::max(std::fabs(l), std::fabs(r)));
            out[2 * f] += l;
            out[2 * f + 1] += r;
        }
    }
}

}  // namespace padsampler
