#include "synth.h"

#include <algorithm>
#include <cmath>

namespace padsampler {

namespace {
constexpr int kBlock = 16;           // pitch, glide i cutoff se računaju po bloku od 16 sampleova
constexpr float kFastRelease = 0.005f;

const SynthParamInfo kInfo[kSynthParamCount] = {
    {0, kWaveCount - 1, kWaveSaw}, {-2, 2, 0}, {0, 1, 1.0f},                // osc 1
    {0, kWaveCount - 1, kWaveSaw}, {-2, 2, 0}, {-12, 12, 0.07f}, {0, 1, 0.8f},  // osc 2
    {0, kWaveCount - 1, kWaveSquare}, {-2, 2, -1}, {-12, 12, -0.05f}, {0, 1, 0.0f},  // osc 3
    {0, 1, 0.0f},                                                          // šum
    {20, 18000, 1200}, {0, 1, 0.3f}, {0, 1, 0.4f}, {0, 1, 0.5f},           // filter
    {0.0005f, 10, 0.005f}, {0.0005f, 10, 0.4f}, {0, 1, 0.3f}, {0.0005f, 10, 0.25f},  // filter ADSR
    {0.0005f, 10, 0.003f}, {0.0005f, 10, 0.3f}, {0, 1, 0.8f}, {0.0005f, 10, 0.2f},   // jačina ADSR
    {0, 2, 0.0f},                                                          // glide
    {1, Synth::kMaxVoices, 1},                                             // glasovi (1 = mono)
    {0, 1, 0.7f},                                                          // volume
    {0, 1, 0}, {0, kArpModeCount - 1, kArpUp}, {0, kArpRateCount - 1, 3}, {1, 4, 1}, {0.05f, 1, 0.5f}, {0, 1, 0},  // arp
    {0, 1, 0}, {0, 1, 0}, {0, 5, 2}, {0, 0.9f, 0.35f}, {0, 1, 0}, {0, 1, 0.5f}, {0, 1, 0},  // efekti
};

inline float coef(float timeSec, double rate) {
    return static_cast<float>(1.0 - std::exp(-1.0 / (std::max(0.0005, static_cast<double>(timeSec)) * rate)));
}

inline float fastTanh(float x) {
    if (x > 3.0f) return 1.0f;
    if (x < -3.0f) return -1.0f;
    const float x2 = x * x;
    return x * (27.0f + x2) / (27.0f + 9.0f * x2);
}

// PolyBLEP korekcija skoka (t = faza 0..1, dt = korak faze)
inline double blep(double t, double dt) {
    if (t < dt) {
        t /= dt;
        return t + t - t * t - 1.0;
    }
    if (t > 1.0 - dt) {
        t = (t - 1.0) / dt;
        return t * t + t + t + 1.0;
    }
    return 0.0;
}

inline double osc(int wave, double ph, double dt) {
    switch (wave) {
        case kWaveTri:
            return 4.0 * std::fabs(ph - 0.5) - 1.0;
        case kWaveSaw:
            return 2.0 * ph - 1.0 - blep(ph, dt);
        default: {
            const double pw = wave == kWaveSquare ? 0.5 : (wave == kWavePulseWide ? 0.3 : 0.12);
            double v = ph < pw ? 1.0 : -1.0;
            v += blep(ph, dt);
            double t2 = ph + 1.0 - pw;
            if (t2 >= 1.0) t2 -= 1.0;
            v -= blep(t2, dt);
            return v;
        }
    }
}
}  // namespace

const SynthParamInfo& synthParamInfo(int id) {
    static const SynthParamInfo none{0, 0, 0};
    return (id >= 0 && id < kSynthParamCount) ? kInfo[id] : none;
}

Synth::Synth() {
    for (int i = 0; i < kSynthParamCount; ++i) mParams[i].store(kInfo[i].def, std::memory_order_relaxed);
}

void Synth::setParam(int id, float value) {
    if (id < 0 || id >= kSynthParamCount || !std::isfinite(value)) return;
    mParams[id].store(std::max(kInfo[id].min, std::min(kInfo[id].max, value)), std::memory_order_relaxed);
}

float Synth::param(int id) const {
    return (id >= 0 && id < kSynthParamCount) ? mParams[id].load(std::memory_order_relaxed) : 0.0f;
}

void Synth::setPitchBend(float semitones) {
    if (std::isfinite(semitones)) mBend.store(std::max(-24.0f, std::min(24.0f, semitones)), std::memory_order_relaxed);
}

void Synth::setModWheel(float amount) {
    if (std::isfinite(amount)) mMod.store(std::max(0.0f, std::min(1.0f, amount)), std::memory_order_relaxed);
}

void Synth::copyParamsFrom(const Synth& other) {
    for (int i = 0; i < kSynthParamCount; ++i) mParams[i].store(other.param(i), std::memory_order_relaxed);
}

int Synth::activeVoices() const {
    int n = 0;
    for (const auto& v : mVoices) n += v.active ? 1 : 0;
    return n;
}

float Synth::Env::next(float a, float d, float s, float r) {
    switch (stage) {
        case Attack:
            value += (1.3f - value) * a;
            if (value >= 1.0f) {
                value = 1.0f;
                stage = Decay;
            }
            break;
        case Decay:
            value += (s - value) * d;
            if (std::fabs(value - s) < 1e-4f) {
                value = s;
                stage = Sustain;
            }
            break;
        case Sustain:
            value = s;
            break;
        case Release:
            value -= value * r;
            if (value < 1e-4f) {   // -80 dB
                value = 0.0f;
                stage = Idle;
            }
            break;
        case Idle:
            value = 0.0f;
            break;
    }
    return value;
}

float Synth::noise() {
    mNoise ^= mNoise << 13;
    mNoise ^= mNoise >> 17;
    mNoise ^= mNoise << 5;
    return static_cast<float>(static_cast<int32_t>(mNoise)) * (1.0f / 2147483648.0f);
}

void Synth::startVoice(Voice& v, int note, float velocity, bool retrigger, bool glide) {
    if (!v.active) {
        v.phase = {0.0, 0.25, 0.5};
        for (float& st : v.s) st = 0.0f;
        v.fenv = Env{};
        v.aenv = Env{};
    }
    v.note = note;
    v.velocity = velocity;
    v.target = note;
    if (!glide || !v.active) v.pitch = note;
    if (retrigger) {
        v.fenv.gate(true);
        v.aenv.gate(true);
    }
    v.fastRelease = false;
    v.active = true;
    v.age = ++mAge;
}

void Synth::noteOn(int note, float velocity) {
    if (note < 0 || note > 127 || velocity <= 0.0f) return;
    const bool mono = param(kVoices) < 1.5f;
    const bool glide = param(kGlide) > 0.0f;

    if (mono) {
        // stog držanih nota (bez duplikata), zadnja ima prednost
        int k = 0;
        for (int i = 0; i < mHeldCount; ++i) if (mHeld[i] != note) mHeld[k++] = mHeld[i];
        mHeldCount = k;
        if (mHeldCount == static_cast<int>(mHeld.size())) {
            std::copy(mHeld.begin() + 1, mHeld.end(), mHeld.begin());
            --mHeldCount;
        }
        mHeld[mHeldCount++] = note;
        Voice& v = mVoices[0];
        for (int i = 1; i < kMaxVoices; ++i) mVoices[i].active = false;
        const bool legato = v.active && v.aenv.stage != Env::Release && v.aenv.stage != Env::Idle && mHeldCount > 1;
        startVoice(v, note, velocity, !legato, glide);
        return;
    }

    Voice* slot = nullptr;
    for (auto& v : mVoices) if (v.active && v.note == note) { slot = &v; break; }
    if (slot == nullptr) for (auto& v : mVoices) if (!v.active) { slot = &v; break; }
    if (slot == nullptr) {
        // ukradi: prvo glas koji se gasi (najtiši), inače najstariji
        for (auto& v : mVoices) {
            if (v.aenv.stage == Env::Release && (slot == nullptr || v.aenv.value < slot->aenv.value)) slot = &v;
        }
        if (slot == nullptr) {
            slot = &mVoices[0];
            for (auto& v : mVoices) if (v.age < slot->age) slot = &v;
        }
    }
    // poly glide: od zadnje odsvirane note
    double from = slot->pitch;
    if (glide) {
        uint32_t best = 0;
        for (const auto& v : mVoices) if (v.active && v.age >= best) { best = v.age; from = v.pitch; }
    }
    const bool wasActive = slot->active;
    startVoice(*slot, note, velocity, true, glide);
    if (glide && !wasActive) slot->pitch = from;
}

void Synth::noteOff(int note) {
    const bool mono = param(kVoices) < 1.5f;
    if (mono) {
        int k = 0;
        for (int i = 0; i < mHeldCount; ++i) if (mHeld[i] != note) mHeld[k++] = mHeld[i];
        mHeldCount = k;
        Voice& v = mVoices[0];
        if (!v.active) return;
        if (mHeldCount == 0) {
            v.fenv.gate(false);
            v.aenv.gate(false);
        } else if (v.note == note) {
            v.note = mHeld[mHeldCount - 1];   // legato nazad na zadnju držanu notu
            v.target = v.note;
            if (param(kGlide) <= 0.0f) v.pitch = v.target;
        }
        return;
    }
    for (auto& v : mVoices) {
        if (v.active && v.note == note && v.aenv.stage != Env::Release) {
            v.fenv.gate(false);
            v.aenv.gate(false);
        }
    }
}

void Synth::releaseAll() {
    mHeldCount = 0;
    for (auto& v : mVoices) {
        if (!v.active) continue;
        v.fenv.gate(false);
        v.aenv.gate(false);
    }
}

void Synth::silence() {
    releaseAll();
    for (auto& v : mVoices) v.fastRelease = true;
}

void Synth::render(float* out, int32_t frames, int sampleRate) {
    if (sampleRate <= 0) return;
    const double fs = sampleRate;
    float p[kSynthParamCount];
    for (int i = 0; i < kSynthParamCount; ++i) p[i] = mParams[i].load(std::memory_order_relaxed);

    const int wave[3] = {static_cast<int>(p[kOsc1Wave] + 0.5f), static_cast<int>(p[kOsc2Wave] + 0.5f),
                         static_cast<int>(p[kOsc3Wave] + 0.5f)};
    const double offs[3] = {12.0 * std::round(p[kOsc1Octave]), 12.0 * std::round(p[kOsc2Octave]) + p[kOsc2Detune],
                            12.0 * std::round(p[kOsc3Octave]) + p[kOsc3Detune]};
    const float lvl[3] = {p[kOsc1Level], p[kOsc2Level], p[kOsc3Level]};
    const float fa = coef(p[kFAttack], fs), fd = coef(p[kFDecay], fs), fr = coef(p[kFRelease], fs);
    const float aa = coef(p[kAAttack], fs), ad = coef(p[kADecay], fs), ar = coef(p[kARelease], fs);
    const float fastR = coef(kFastRelease, fs);
    const double glideCoef = p[kGlide] > 0.0f ? 1.0 - std::exp(-kBlock / (p[kGlide] * fs * 0.3)) : 1.0;
    const float res = p[kResonance];
    const float gain = p[kVolume] * 0.9f;
    const double bend = mBend.load(std::memory_order_relaxed);
    const double modOct = 3.0 * mMod.load(std::memory_order_relaxed);

    for (auto& v : mVoices) {
        if (!v.active) continue;
        for (int32_t b = 0; b < frames; b += kBlock) {
            const int32_t n = std::min<int32_t>(kBlock, frames - b);
            // ---- kontrolna brzina (blok od 16)
            v.pitch += (v.target - v.pitch) * glideCoef;
            double dt[3];
            for (int o = 0; o < 3; ++o) {
                const double f = 440.0 * std::exp2((v.pitch + offs[o] + bend - 69.0) / 12.0);
                dt[o] = std::min(0.45, f / fs);
            }
            const double cutoff = p[kCutoff] *
                std::exp2(5.0 * p[kFilterEnv] * v.fenv.value + p[kKeyTrack] * (v.pitch - 60.0) / 12.0 + modOct);
            // ZDF ladder na 2x frekvenciji (fs2 = 2 fs): g = tan(pi fc / fs2), G = g / (1 + g)
            const double fc = std::min(cutoff, 0.45 * fs * 2.0);
            const float g = static_cast<float>(std::tan(3.14159265358979 * fc / (2.0 * fs)));
            const float G = g / (1.0f + g);
            const float inv = 1.0f / (1.0f + g);
            const float k = res * 3.98f;
            const float G2 = G * G, G3 = G2 * G, G4 = G2 * G2;
            const float norm = 1.0f / (1.0f + k * G4);
            const float comp = 1.0f + 0.3f * k;   // Moog gubi bas uz rezonancu: blaga kompenzacija

            for (int32_t i = 0; i < n; ++i) {
                double mix = 0.0;
                for (int o = 0; o < 3; ++o) {
                    if (lvl[o] > 0.0f) mix += lvl[o] * osc(wave[o], v.phase[o], dt[o]);
                    v.phase[o] += dt[o];
                    if (v.phase[o] >= 1.0) v.phase[o] -= 1.0;
                }
                float x = static_cast<float>(mix) * 0.33f + p[kNoiseLevel] * 0.33f * noise();

                float y = 0.0f;
                for (int os = 0; os < 2; ++os) {
                    // procjena izlaza bez kašnjenja (zero-delay feedback), pa 4 pasivna TPT stepena
                    const float S = (G3 * v.s[0] + G2 * v.s[1] + G * v.s[2] + v.s[3]) * inv;
                    float u = fastTanh((x - k * S) * norm);
                    for (float& st : v.s) {
                        const float w = (u - st) * G;
                        const float lp = w + st;
                        st = lp + w;
                        u = lp;
                    }
                    y += u;
                }
                y *= 0.5f * comp;

                v.fenv.next(fa, fd, p[kFSustain], v.fastRelease ? fastR : fr);
                const float amp = v.aenv.next(aa, ad, p[kASustain], v.fastRelease ? fastR : ar);
                const float s = y * amp * v.velocity * gain;
                out[2 * (b + i)] += s;
                out[2 * (b + i) + 1] += s;
            }
            if (v.aenv.stage == Env::Idle) {
                v.active = false;
                break;
            }
        }
    }
}

}  // namespace padsampler

// ------------------------------------------------------------------ Arpeggiator

namespace padsampler {

double arpRateBeats(int rate) {
    switch (rate) {
        case 0: return 1.0;          // 1/4
        case 1: return 0.5;          // 1/8
        case 2: return 1.0 / 3.0;    // 1/8 triola
        case 4: return 1.0 / 6.0;    // 1/16 triola
        case 5: return 0.125;        // 1/32
        default: return 0.25;        // 1/16
    }
}

Arpeggiator::Arpeggiator(Instrument& target, const Synth& params) : mTarget(target), mParams(params) {}

bool Arpeggiator::enabled() const { return mParams.param(kArpOn) >= 0.5f; }

void Arpeggiator::sync(double beat, double beatsPerFrame) {
    mBpf = beatsPerFrame > 0.0 ? beatsPerFrame : 0.0;
    if (std::fabs(beat - mBeat) > 1e-6) {
        // skok (prvi poziv, novi prolaz offline rendera): mreža od nove pozicije, trenutna nota se završi odmah
        mBeat = beat;
        mNextStep = std::ceil(beat / mStepLen - 1e-9) * mStepLen;
        if (mOffAt >= 0.0) mOffAt = beat;
    }
}

void Arpeggiator::noteOn(int note, float velocity) {
    if (note < 0 || note > 127) return;
    if (!enabled()) {
        mTarget.noteOn(note, velocity);
        mThru[note] = true;
        return;
    }
    const bool hold = mParams.param(kArpHold) >= 0.5f;
    bool anyDown = false;
    for (int i = 0; i < mCount; ++i) anyDown = anyDown || mHeld[i].down;
    if (hold && !anyDown) mCount = 0;   // HOLD: novi akord (sve tipke su bile puštene) zamijeni stari
    const bool wasIdle = mCount == 0 && mPlaying < 0;
    for (int i = 0; i < mCount; ++i) {
        if (mHeld[i].note == note) {          // ista nota ponovo: ostaje na svom mjestu
            mHeld[i].velocity = velocity;
            mHeld[i].down = true;
            return;
        }
    }
    if (mCount < static_cast<int>(mHeld.size())) mHeld[mCount++] = {note, velocity, true};
    if (wasIdle) {
        mIndex = 0;
        // malo poslije koraka (do 1/4 koraka): kreće odmah (sviranje uživo malo kasni); inače čeka sljedeći korak
        const double prev = mNextStep - mStepLen;
        if (mBpf > 0.0 && mBeat >= prev && mBeat - prev < 0.25 * mStepLen) mFireNow = true;
    }
}

void Arpeggiator::noteOff(int note) {
    if (note < 0 || note > 127) return;
    if (mThru[note]) {
        mTarget.noteOff(note);
        mThru[note] = false;
    }
    const bool hold = mParams.param(kArpHold) >= 0.5f;
    for (int i = 0; i < mCount; ++i) {
        if (mHeld[i].note != note) continue;
        if (hold) {
            mHeld[i].down = false;
        } else {
            for (int j = i; j + 1 < mCount; ++j) mHeld[j] = mHeld[j + 1];
            --mCount;
        }
        return;
    }
}

void Arpeggiator::endNote() {
    if (mPlaying >= 0) mTarget.noteOff(mPlaying);
    mPlaying = -1;
    mOffAt = -1.0;
}

void Arpeggiator::releaseAll() {
    endNote();
    mCount = 0;
    mThru.fill(false);
    mFireNow = false;
    mTarget.releaseAll();
}

void Arpeggiator::silence() {
    endNote();
    mCount = 0;
    mThru.fill(false);
    mFireNow = false;
    mTarget.silence();
}

void Arpeggiator::stepNow() {
    endNote();
    if (mCount <= 0) return;
    const int octaves = std::max(1, std::min(4, static_cast<int>(mParams.param(kArpOctaves) + 0.5f)));
    const int mode = static_cast<int>(mParams.param(kArpMode) + 0.5f);
    // redoslijed nota: po visini (ili kako su svirane), pa ponovljeno u višim oktavama
    std::array<Held, 16> base;
    for (int i = 0; i < mCount; ++i) base[i] = mHeld[i];
    if (mode != kArpPlayed) {
        std::sort(base.begin(), base.begin() + mCount, [](const Held& a, const Held& b) { return a.note < b.note; });
    }
    std::array<int, 64> seq;
    std::array<float, 64> vel;
    int n = 0;
    for (int o = 0; o < octaves; ++o) {
        for (int i = 0; i < mCount; ++i) {
            const int note = base[i].note + 12 * o;
            if (note > 127) continue;
            seq[n] = note;
            vel[n] = base[i].velocity;
            ++n;
        }
    }
    if (n == 0) return;
    int i;
    switch (mode) {
        case kArpDown: i = n - 1 - mIndex % n; break;
        case kArpUpDown: {
            const int period = n > 1 ? 2 * n - 2 : 1;
            const int k = mIndex % period;
            i = k < n ? k : period - k;
            break;
        }
        case kArpRandom:
            mRnd ^= mRnd << 13; mRnd ^= mRnd >> 17; mRnd ^= mRnd << 5;
            i = static_cast<int>(mRnd % static_cast<uint32_t>(n));
            break;
        default: i = mIndex % n; break;   // gore, kako su svirane
    }
    ++mIndex;
    if (mIndex > 1000000) mIndex = 0;
    mTarget.noteOn(seq[i], vel[i]);
    mPlaying = seq[i];
    const double gate = std::max(0.05, std::min(1.0, static_cast<double>(mParams.param(kArpGate))));
    // gate 100 %: nota traje do sljedećeg koraka (tamo se pušta prije nove)
    mOffAt = gate >= 0.999 ? -1.0 : (mNextStep - mStepLen) + gate * mStepLen;
}

void Arpeggiator::render(float* out, int32_t frames, int sampleRate) {
    if (frames <= 0) return;
    if (!enabled()) {
        if (mWasOn) {                 // isključen dok svira: trenutna nota se pusti, držane se zaborave
            endNote();
            mCount = 0;
            mFireNow = false;
            mWasOn = false;
        }
        mTarget.render(out, frames, sampleRate);
        mBeat += frames * mBpf;
        return;
    }
    if (!mWasOn) {
        mWasOn = true;
        mNextStep = std::ceil(mBeat / mStepLen - 1e-9) * mStepLen;
    }
    if (mParams.param(kArpHold) < 0.5f) {   // HOLD isključen: puštene tipke izlaze iz arpa
        int w = 0;
        for (int i = 0; i < mCount; ++i) if (mHeld[i].down) mHeld[w++] = mHeld[i];
        mCount = w;
    }
    const double step = arpRateBeats(static_cast<int>(mParams.param(kArpRate) + 0.5f));
    if (std::fabs(step - mStepLen) > 1e-9) {
        mStepLen = step;
        mNextStep = std::ceil(mBeat / step - 1e-9) * step;
    }
    if (mFireNow) {
        mFireNow = false;
        stepNow();
    }
    if (mBpf <= 0.0) {                // nema sata (ne bi se smjelo desiti): samo zvuk
        mTarget.render(out, frames, sampleRate);
        return;
    }
    int32_t done = 0;
    while (done < frames) {
        double ev = mNextStep;
        bool isOff = false;
        if (mOffAt >= 0.0 && mOffAt <= ev) {
            ev = mOffAt;
            isOff = true;
        }
        const double d = std::ceil((ev - mBeat) / mBpf - 1e-9);
        const int32_t left = frames - done;
        if (d >= static_cast<double>(left)) {
            mTarget.render(out + 2 * static_cast<size_t>(done), left, sampleRate);
            mBeat += left * mBpf;
            break;
        }
        const int32_t f = d > 0.0 ? static_cast<int32_t>(d) : 0;
        if (f > 0) {
            mTarget.render(out + 2 * static_cast<size_t>(done), f, sampleRate);
            done += f;
            mBeat += f * mBpf;
        }
        if (isOff) {
            endNote();
        } else {
            mNextStep += mStepLen;
            if (mCount > 0) stepNow(); else endNote();
        }
    }
}

// ------------------------------------------------------------------ KeysFx

namespace {
constexpr int kFxMaxRate = 96000;
constexpr double kChorusMaxSec = 0.05;
constexpr double kDelayMaxSec = 2.1;           // 1/2 note na 60 BPM = 2 s
constexpr double kPi = 3.14159265358979;
const int kCombTune[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};   // Freeverb (44.1 kHz)
const int kApTune[4] = {556, 441, 341, 225};
constexpr int kStereoSpread = 23;

double delayBeats(int idx) {
    switch (idx) {
        case 0: return 0.25;    // 1/16
        case 1: return 0.5;     // 1/8
        case 3: return 1.0;     // 1/4
        case 4: return 1.5;     // 1/4 sa tačkom
        case 5: return 2.0;     // 1/2
        default: return 0.75;   // 1/8 sa tačkom
    }
}
}  // namespace

KeysFx::KeysFx(const Synth& params) : mParams(params) {
    mChoBuf.assign(static_cast<size_t>(kFxMaxRate * kChorusMaxSec) * 2 + 4, 0.0f);
    mDlyBuf.assign(static_cast<size_t>(kFxMaxRate * kDelayMaxSec) * 2 + 4, 0.0f);
    const double scale = static_cast<double>(kFxMaxRate) / 44100.0;
    for (int i = 0; i < 8; ++i) {
        mCombL[i].buf.assign(static_cast<size_t>((kCombTune[i] + kStereoSpread) * scale) + 2, 0.0f);
        mCombR[i].buf.assign(mCombL[i].buf.size(), 0.0f);
    }
    for (int i = 0; i < 4; ++i) {
        mApL[i].buf.assign(static_cast<size_t>((kApTune[i] + kStereoSpread) * scale) + 2, 0.0f);
        mApR[i].buf.assign(mApL[i].buf.size(), 0.0f);
    }
}

void KeysFx::setupReverb(int sampleRate) {
    const double scale = static_cast<double>(sampleRate) / 44100.0;
    auto fit = [](size_t cap, double want) { return std::max(1, std::min(static_cast<int>(cap), static_cast<int>(want))); };
    for (int i = 0; i < 8; ++i) {
        mCombL[i].len = fit(mCombL[i].buf.size(), kCombTune[i] * scale);
        mCombR[i].len = fit(mCombR[i].buf.size(), (kCombTune[i] + kStereoSpread) * scale);
        mCombL[i].pos = mCombR[i].pos = 0;
    }
    for (int i = 0; i < 4; ++i) {
        mApL[i].len = fit(mApL[i].buf.size(), kApTune[i] * scale);
        mApR[i].len = fit(mApR[i].buf.size(), (kApTune[i] + kStereoSpread) * scale);
        mApL[i].pos = mApR[i].pos = 0;
    }
}

void KeysFx::clearReverb() {
    for (int i = 0; i < 8; ++i) {
        std::fill(mCombL[i].buf.begin(), mCombL[i].buf.end(), 0.0f);
        std::fill(mCombR[i].buf.begin(), mCombR[i].buf.end(), 0.0f);
        mCombL[i].store = mCombR[i].store = 0.0f;
    }
    for (int i = 0; i < 4; ++i) {
        std::fill(mApL[i].buf.begin(), mApL[i].buf.end(), 0.0f);
        std::fill(mApR[i].buf.begin(), mApR[i].buf.end(), 0.0f);
    }
}

void KeysFx::reset() {
    std::fill(mChoBuf.begin(), mChoBuf.end(), 0.0f);
    std::fill(mDlyBuf.begin(), mDlyBuf.end(), 0.0f);
    clearReverb();
    mDlyLpL = mDlyLpR = 0.0f;
    mChoClean = mDlyClean = mRevClean = true;
}

void KeysFx::process(float* buf, int32_t frames, int sampleRate, double framesPerBeat) {
    if (frames <= 0 || sampleRate <= 0) return;
    if (sampleRate != mRate) {
        mRate = sampleRate;
        setupReverb(std::min(sampleRate, kFxMaxRate));
        reset();
    }
    const float drive = mParams.param(kFxDrive);
    const float chorus = mParams.param(kFxChorus);
    const float dlyMix = mParams.param(kFxDelayMix);
    const float feedback = mParams.param(kFxDelayFeedback);
    const float revMix = mParams.param(kFxReverbMix);
    const float revSize = mParams.param(kFxReverbSize);
    const int dlyIdx = static_cast<int>(mParams.param(kFxDelayTime) + 0.5f);
    const int choLen = static_cast<int>(mChoBuf.size() / 2);
    const int dlyLen = static_cast<int>(mDlyBuf.size() / 2);
    const double dlyTarget = std::max(1.0, std::min(static_cast<double>(dlyLen - 2),
                                                    delayBeats(dlyIdx) * std::max(1.0, framesPerBeat)));
    if (mDelayFrames <= 0.0) mDelayFrames = dlyTarget;

    const float k = static_cast<float>(1.0 - std::exp(-1.0 / (0.02 * sampleRate)));   // ~20 ms
    const double kd = 1.0 - std::exp(-1.0 / (0.08 * sampleRate));                     // vrijeme delaya sporije
    const bool useCho = chorus > 0.001f || mChorus > 0.001f;
    const bool useDly = dlyMix > 0.001f || mDelayMix > 0.001f;
    const bool useRev = revMix > 0.001f || mRevMix > 0.001f;
    // efekat koji je bio isključen kreće od tišine (bez starog repa)
    if (useCho && mChoClean) { std::fill(mChoBuf.begin(), mChoBuf.end(), 0.0f); mChoClean = false; }
    if (useDly && mDlyClean) { std::fill(mDlyBuf.begin(), mDlyBuf.end(), 0.0f); mDlyLpL = mDlyLpR = 0.0f; mDlyClean = false; }
    if (useRev && mRevClean) { clearReverb(); mRevClean = false; }

    const double lfoInc = 2.0 * kPi * 0.45 / sampleRate;
    const float dlyLp = static_cast<float>(1.0 - std::exp(-2.0 * kPi * 4500.0 / sampleRate));

    for (int32_t f = 0; f < frames; ++f) {
        float l = buf[2 * f], r = buf[2 * f + 1];
        mDrive += (drive - mDrive) * k;
        mChorus += (chorus - mChorus) * k;
        mDelayMix += (dlyMix - mDelayMix) * k;
        mFeedback += (feedback - mFeedback) * k;
        mRevMix += (revMix - mRevMix) * k;
        mRevSize += (revSize - mRevSize) * k;
        mDelayFrames += (dlyTarget - mDelayFrames) * kd;

        // ---- drive (tanh; kompenzacija da glasnoća ostane približno ista)
        if (mDrive > 0.001f) {
            const float g = 1.0f + mDrive * 9.0f;
            const float norm = 1.0f / (1.0f + mDrive * 2.0f);
            l = std::tanh(l * g) * norm;
            r = std::tanh(r * g) * norm;
        }
        // ---- chorus (modulisano kašnjenje 7..17 ms, L/R u suprotnoj fazi)
        if (useCho) {
            mChoBuf[2 * mChoPos] = l;
            mChoBuf[2 * mChoPos + 1] = r;
            mLfo += lfoInc;
            if (mLfo > 2.0 * kPi) mLfo -= 2.0 * kPi;
            const double base = 0.012 * sampleRate, depth = 0.005 * sampleRate;
            float wet[2];
            for (int ch = 0; ch < 2; ++ch) {
                const double d = base + depth * std::sin(mLfo + ch * kPi);
                double rp = mChoPos - d;
                while (rp < 0) rp += choLen;
                const int i0 = static_cast<int>(rp) % choLen;
                const int i1 = (i0 + 1) % choLen;
                const float fr = static_cast<float>(rp - std::floor(rp));
                wet[ch] = mChoBuf[2 * i0 + ch] * (1.0f - fr) + mChoBuf[2 * i1 + ch] * fr;
            }
            const float m = mChorus * 0.5f;
            l = l * (1.0f - m) + wet[0] * m;
            r = r * (1.0f - m) + wet[1] * m;
            if (++mChoPos >= choLen) mChoPos = 0;
        }
        // ---- delay (vezan za tempo; ping-pong, povratna veza kroz blagi low-pass)
        if (useDly) {
            double rp = mDlyPos - mDelayFrames;
            while (rp < 0) rp += dlyLen;
            const int i0 = static_cast<int>(rp) % dlyLen;
            const int i1 = (i0 + 1) % dlyLen;
            const float fr = static_cast<float>(rp - std::floor(rp));
            const float dl = mDlyBuf[2 * i0] * (1.0f - fr) + mDlyBuf[2 * i1] * fr;
            const float dr = mDlyBuf[2 * i0 + 1] * (1.0f - fr) + mDlyBuf[2 * i1 + 1] * fr;
            mDlyLpL += (dl - mDlyLpL) * dlyLp;
            mDlyLpR += (dr - mDlyLpR) * dlyLp;
            mDlyBuf[2 * mDlyPos] = l * 0.7f + mDlyLpR * mFeedback;
            mDlyBuf[2 * mDlyPos + 1] = r * 0.7f + mDlyLpL * mFeedback;
            if (++mDlyPos >= dlyLen) mDlyPos = 0;
            l += dl * mDelayMix;
            r += dr * mDelayMix;
        }
        // ---- reverb (Freeverb: 8 comb + 4 allpass po kanalu)
        if (useRev) {
            const float in = (l + r) * 0.015f;
            const float room = 0.7f + mRevSize * 0.28f;
            const float damp = 0.2f + (1.0f - mRevSize) * 0.3f;
            float ol = 0.0f, orr = 0.0f;
            for (int i = 0; i < 8; ++i) {
                Comb& cl = mCombL[i];
                const float a = cl.buf[cl.pos];
                cl.store = a * (1.0f - damp) + cl.store * damp;
                cl.buf[cl.pos] = in + cl.store * room;
                if (++cl.pos >= cl.len) cl.pos = 0;
                ol += a;
                Comb& cr = mCombR[i];
                const float b = cr.buf[cr.pos];
                cr.store = b * (1.0f - damp) + cr.store * damp;
                cr.buf[cr.pos] = in + cr.store * room;
                if (++cr.pos >= cr.len) cr.pos = 0;
                orr += b;
            }
            for (int i = 0; i < 4; ++i) {
                Allpass& al = mApL[i];
                const float a = al.buf[al.pos];
                al.buf[al.pos] = ol + a * 0.5f;
                ol = a - ol;
                if (++al.pos >= al.len) al.pos = 0;
                Allpass& ar = mApR[i];
                const float b = ar.buf[ar.pos];
                ar.buf[ar.pos] = orr + b * 0.5f;
                orr = b - orr;
                if (++ar.pos >= ar.len) ar.pos = 0;
            }
            const float wet = mRevMix * 3.0f;
            const float dry = 1.0f - mRevMix * 0.5f;
            l = l * dry + ol * wet;
            r = r * dry + orr * wet;
        }
        buf[2 * f] = l;
        buf[2 * f + 1] = r;
    }
    if (!useCho) mChoClean = true;
    if (!useDly) mDlyClean = true;
    if (!useRev) mRevClean = true;
}

}  // namespace padsampler
