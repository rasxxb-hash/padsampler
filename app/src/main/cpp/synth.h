// Sintisajzer u stilu Minimooga (Studio, dio Keys). Čisti C++, testira se na hostu (native-tests/synth_test.cpp).
//
// - 3 oscilatora (trougao, saw, kvadrat, širok puls, uzak puls; PolyBLEP bez aliasinga), oktava 32'..2',
//   detune osc 2 i 3; šum.
// - Moog ladder filter 24 dB/okt (ZDF/TPT, Zavalishin; 2x oversampling, tanh na ulazu), rezonanca do
//   samooscilovanja,
//   envelope amount (do +5 oktava) i key tracking.
// - 2 ADSR (filter, jačina), glide, mono (legato, zadnja nota ima prednost) ili poly do 8 glasova.
//
// Parametri su atomski (setParam iz bilo koje niti); noteOn/noteOff/render zove jedna nit (audio ili offline),
// bez alokacija.
#pragma once

#include <array>
#include <vector>
#include <atomic>
#include <cstdint>

#include "studio.h"

namespace padsampler {

enum SynthParam : int {
    kOsc1Wave = 0, kOsc1Octave, kOsc1Level,
    kOsc2Wave, kOsc2Octave, kOsc2Detune, kOsc2Level,
    kOsc3Wave, kOsc3Octave, kOsc3Detune, kOsc3Level,
    kNoiseLevel,
    kCutoff,        // Hz
    kResonance,     // 0..1 (1 = samooscilovanje)
    kFilterEnv,     // 0..1 (do +5 oktava)
    kKeyTrack,      // 0..1
    kFAttack, kFDecay, kFSustain, kFRelease,   // sekunde / 0..1
    kAAttack, kADecay, kASustain, kARelease,
    kGlide,         // sekunde
    kVoices,        // 1 = mono legato, 2..8 = poly
    kVolume,        // 0..1
    // arpeggiator (Arpeggiator čita ove parametre; dio su zvuka, pa idu i u offline render)
    kArpOn,         // 0/1
    kArpMode,       // ArpMode
    kArpRate,       // 0 = 1/4, 1 = 1/8, 2 = 1/8T, 3 = 1/16, 4 = 1/16T, 5 = 1/32
    kArpOctaves,    // 1..4
    kArpGate,       // 0.05..1 (dio koraka)
    kArpHold,       // 0/1: svira i kad se tipke puste (novi akord zamijeni stari)
    // efekti (KeysFx, poslije sintisajzera): drive -> chorus -> delay -> reverb
    kFxDrive,       // 0..1
    kFxChorus,      // 0..1 (mix i dubina)
    kFxDelayTime,   // 0 = 1/16, 1 = 1/8, 2 = 1/8., 3 = 1/4, 4 = 1/4., 5 = 1/2 (vezano za tempo)
    kFxDelayFeedback,  // 0..0.9
    kFxDelayMix,    // 0..1
    kFxReverbSize,  // 0..1
    kFxReverbMix,   // 0..1
    // izraz (dodato 22. 11. 2026, na kraju zbog starih preseta): vibrato kreće malo poslije note; jačina udarca otvara filter
    kVibrato,       // 0..1 (do ±0.6 polutona, 5.5 Hz)
    kVelTone,       // 0..1 (jače = svjetlije; 0 = boja ne zavisi od jačine)
    kSynthParamCount
};

enum SynthWave : int { kWaveTri = 0, kWaveSaw, kWaveSquare, kWavePulseWide, kWavePulseNarrow, kWaveCount };

struct SynthParamInfo {
    float min, max, def;
};
const SynthParamInfo& synthParamInfo(int id);

class Synth : public Instrument {
public:
    static constexpr int kMaxVoices = 8;

    Synth();
    // ---- bilo koja nit
    void setParam(int id, float value);   // vrijednost se ograniči na opseg parametra
    float param(int id) const;
    void copyParamsFrom(const Synth& other);
    // Sviranje uživo (MIDI klavijatura; ne spremaju se i ne idu u offline render): pitch bend u polutonovima,
    // modulacioni točak 0..1 (otvara filter do +3 oktave).
    void setPitchBend(float semitones);
    void setModWheel(float amount);

    // ---- audio / offline nit
    void noteOn(int note, float velocity) override;
    void noteOff(int note) override;
    void releaseAll() override;
    void silence() override;
    void render(float* out, int32_t frames, int sampleRate) override;

    int activeVoices() const;  // za testove

private:
    struct Env {
        enum Stage : uint8_t { Idle, Attack, Decay, Sustain, Release };
        Stage stage = Idle;
        float value = 0.0f;
        void gate(bool on) { stage = on ? Attack : (stage == Idle ? Idle : Release); }
        float next(float a, float d, float s, float r);  // koeficijenti po sampleu
    };
    struct Voice {
        bool active = false;
        int note = -1;
        float velocity = 1.0f;
        double pitch = 60.0;       // trenutna nota (glide)
        double target = 60.0;
        std::array<double, 3> phase{};
        // ladder filter (stanje 4 TPT stepena)
        float s[4] = {0, 0, 0, 0};
        Env fenv, aenv;
        bool fastRelease = false;
        uint32_t age = 0;
        double vibPhase = 0.0;     // vibrato (0..1)
        double vibTime = 0.0;      // sekundi od početka note (vibrato kreće sa zakašnjenjem)
    };

    void startVoice(Voice& v, int note, float velocity, bool retrigger, bool glide);
    float noise();

    std::array<std::atomic<float>, kSynthParamCount> mParams;
    std::atomic<float> mBend{0.0f};
    std::atomic<float> mMod{0.0f};
    std::array<Voice, kMaxVoices> mVoices{};
    uint32_t mAge = 0;
    uint32_t mNoise = 0x12345678u;
    // mono: držane note (zadnja ima prednost)
    std::array<int, 16> mHeld{};
    int mHeldCount = 0;
};

}  // namespace padsampler

namespace padsampler {

enum ArpMode : int { kArpUp = 0, kArpDown, kArpUpDown, kArpRandom, kArpPlayed, kArpModeCount };
constexpr int kArpRateCount = 6;
// Dužina koraka u dobama (četvrtinama) za kArpRate.
double arpRateBeats(int rate);

// Arpeggiator: između ulaza (sviranje uživo, note patterna) i instrumenta. Isključen = note idu direktno.
// Uključen: držane note se sviraju jedna po jedna na mreži takta (sync prije svakog render-a: apsolutna pozicija
// u dobama). Parametri se čitaju iz [params] (Synth: kArpOn...). Jedna nit (audio ili offline), bez alokacija.
class Arpeggiator : public Instrument {
public:
    Arpeggiator(Instrument& target, const Synth& params);
    void sync(double beat, double beatsPerFrame);

    void noteOn(int note, float velocity) override;
    void noteOff(int note) override;
    void releaseAll() override;
    void silence() override;
    void render(float* out, int32_t frames, int sampleRate) override;

    int heldCount() const { return mCount; }   // za testove

private:
    struct Held { int note; float velocity; bool down; };
    bool enabled() const;
    void stepNow();
    void endNote();

    Instrument& mTarget;
    const Synth& mParams;
    std::array<Held, 16> mHeld{};
    int mCount = 0;
    std::array<bool, 128> mThru{};      // note koje su prošle direktno (arp isključen) i još zvuče
    double mBeat = 0.0;
    double mBpf = 0.0;                  // doba po frameu (0 = nema sata)
    double mStepLen = 0.25;
    double mNextStep = 0.0;             // doba sljedećeg koraka
    double mOffAt = -1.0;               // doba kad se trenutna nota pušta (-1 = nema)
    int mPlaying = -1;
    int mIndex = 0;
    uint32_t mRnd = 0x2545F491u;
    bool mWasOn = false;
    bool mFireNow = false;
};

// Efekti dijela Keys (poslije arpa i sintisajzera): drive -> chorus -> delay (tempo) -> reverb (Freeverb).
// Baferi se alociraju u konstruktoru (do 96 kHz); process() ne alocira. Jedna nit (audio ili offline).
class KeysFx {
public:
    explicit KeysFx(const Synth& params);
    // buf: interleaved stereo, obrađuje se na mjestu. framesPerBeat: za delay vezan za tempo.
    void process(float* buf, int32_t frames, int sampleRate, double framesPerBeat);
    void reset();   // obriše repove (bafere)

private:
    struct Comb {
        std::vector<float> buf;
        int len = 1, pos = 0;
        float store = 0.0f;
    };
    struct Allpass {
        std::vector<float> buf;
        int len = 1, pos = 0;
    };
    void setupReverb(int sampleRate);
    void clearReverb();

    const Synth& mParams;
    int mRate = 0;
    // izglađeni parametri (bez pucketanja)
    float mDrive = 0.0f, mChorus = 0.0f, mDelayMix = 0.0f, mFeedback = 0.0f, mRevMix = 0.0f, mRevSize = 0.5f;
    double mDelayFrames = 0.0;
    // chorus
    std::vector<float> mChoBuf;   // stereo
    int mChoPos = 0;
    double mLfo = 0.0;
    // delay
    std::vector<float> mDlyBuf;   // stereo
    int mDlyPos = 0;
    float mDlyLpL = 0.0f, mDlyLpR = 0.0f;
    // reverb
    std::array<Comb, 8> mCombL, mCombR;
    std::array<Allpass, 4> mApL, mApR;
    bool mChoClean = true, mDlyClean = true, mRevClean = true;
};

}  // namespace padsampler
