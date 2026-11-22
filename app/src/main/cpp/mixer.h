// Mixer: čisti C++ (bez Oboe-a i Androida), pa se može testirati i na običnom računaru.
//
// Model (vidi docs/SPECIFIKACIJA.md):
//  - 8 trackova (kolone) x 6 scena (redovi) = 48 slotova. slot = scena * 8 + track.
//  - Globalni sat 4/4. Klipovi počinju i staju na granici takta (sample-accurate).
//  - U jednom tracku svira najviše jedan klip.
//
// Niti: sve komande i čitanje stanja smiju iz bilo koje niti (UI, MIDI).
// render() se zove ISKLJUČIVO iz audio niti i ne smije alocirati memoriju ni zaključavati mutex.
#pragma once

#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

namespace padsampler {

constexpr int kTracks = 8;
constexpr int kScenes = 6;
constexpr int kNumSlots = kTracks * kScenes;  // slot = scena * kTracks + track

enum SlotState : uint8_t {
    kEmpty = 0,        // nema sempla
    kIdle = 1,         // ima sempl, miruje
    kQueuedStart = 2,  // čeka početak takta da krene
    kPlaying = 3,      // svira
    kQueuedStop = 4    // svira, staje na početku sljedećeg takta
};

struct Sample {
    std::vector<float> data;  // interleaved stereo (L,R,L,R...)
    int64_t frames = 0;
    int sampleRate = 48000;
    int32_t lengthBars = 0;   // dužina loopa u taktovima (0 = izračunaj iz trajanja i BPM-a, -1 = slobodan loop: ne poravnava se na takt)
    double durationSec() const { return sampleRate > 0 ? double(frames) / sampleRate : 0.0; }
};

struct Command {
    enum Type : uint8_t {
        Launch,
        LaunchScene,
        StopTrack,
        StopAll,
        KillSlot,
        SetBpm,
        SetSlotLoop,
        SetTrackGain,
        SetTrackPan,
        SetMasterFilter,
        SetTrackSend,
        SetTrackEq,
        SetTrackMute,
        SetTrackSolo,
        SetMasterGain,
        SetMasterMute,
        PreviewStart,
        PreviewStop
    };
    Type type = Launch;
    int32_t a = 0;  // slot / scena / track
    int32_t b = 0;  // bool vrijednost
    float f = 0.0f;
};

// Red čekanja: više producera (zaštićeni mutexom), jedan consumer (audio nit, bez zaključavanja).
class CommandQueue {
public:
    bool push(const Command& c) {
        std::lock_guard<std::mutex> lock(mPushMutex);
        const size_t h = mHead.load(std::memory_order_relaxed);
        const size_t next = (h + 1) % kSize;
        if (next == mTail.load(std::memory_order_acquire)) return false;  // pun, odbaci
        mBuf[h] = c;
        mHead.store(next, std::memory_order_release);
        return true;
    }
    bool pop(Command& c) {
        const size_t t = mTail.load(std::memory_order_relaxed);
        if (t == mHead.load(std::memory_order_acquire)) return false;
        c = mBuf[t];
        mTail.store((t + 1) % kSize, std::memory_order_release);
        return true;
    }

private:
    static constexpr size_t kSize = 512;
    std::array<Command, kSize> mBuf{};
    std::atomic<size_t> mHead{0};
    std::atomic<size_t> mTail{0};
    std::mutex mPushMutex;
};

// Vanjski sat (npr. Ableton Link). Zove se iz audio niti, jednom po render() pozivu, i ne smije alocirati ni zaključavati.
class ClockSource {
public:
    virtual ~ClockSource() = default;
    // Vraća false ako nije aktivan. Inače: bpm i faza takta (0..1) na početku buffera od numFrames frameova
    // (u trenutku kad će se taj buffer čuti).
    virtual bool query(int32_t numFrames, int sampleRate, double* bpm, double* barPhase) = 0;
};

// Prima gotov master miks (bez preslušavanja), npr. za slanje preko Link Audio-a. Zove se iz audio niti, jednom po
// render() pozivu, i ne smije alocirati ni zaključavati.
class OutputTap {
public:
    virtual ~OutputTap() = default;
    virtual void onOutput(const float* interleavedStereo, int32_t numFrames, int sampleRate) = 0;
};

// Dodatni izvor zvuka koji svira sinhrono sa taktom (Studio: drums/sintisajzer). Zove se iz audio niti za svaki
// segment; segment nikad ne prelazi granicu takta. Dodaje (+=) u out; ide kroz master (i OutputTap).
// Ne smije alocirati ni zaključavati.
class AuxSource {
public:
    virtual ~AuxSource() = default;
    // barCount = taktovi od starta miksera, barPos = frame unutar takta na početku segmenta.
    virtual void renderAux(float* out, int32_t frames, int sampleRate, int64_t barCount, double barPos,
                           double framesPerBar) = 0;
};

// Zajednički send efekti miksera (prostor): reverb (Freeverb, samo povrat) i delay vezan za tempo (1/8 sa tačkom,
// ping-pong, povratna veza kroz blagi low-pass). Baferi se alociraju u konstruktoru (do 96 kHz); process() ne alocira.
// Samo audio nit.
class SendFx {
public:
    SendFx();
    // revIn / dlyIn: stereo sabirnice (ono što kanali pošalju); povrat se DODAJE (+=) u out.
    void process(const float* revIn, const float* dlyIn, float* out, int32_t frames, int sampleRate, double framesPerBeat);
    void clear();   // briše repove (kad su efekti bili ugašeni, da ne iskoči stari rep)

private:
    struct Comb { std::vector<float> buf; int len = 1, pos = 0; float store = 0.0f; };
    struct Allpass { std::vector<float> buf; int len = 1, pos = 0; };
    void setup(int sampleRate);

    int mRate = 0;
    std::array<Comb, 8> mCombL, mCombR;
    std::array<Allpass, 4> mApL, mApR;
    std::vector<float> mDly;   // stereo
    int mDlyPos = 0;
    double mDlyFrames = 0.0;
    float mDlyLpL = 0.0f, mDlyLpR = 0.0f;
};

class Mixer {
public:
    Mixer();

    // ---- izlaz za vanjskog primaoca. nullptr = ništa.
    void setOutputTap(OutputTap* tap) { mTap.store(tap, std::memory_order_release); }
    // ---- dodatni izvor (Studio). nullptr = ništa.
    void setAuxSource(AuxSource* aux) { mAux.store(aux, std::memory_order_release); }

    // ---- vanjski sat. nullptr = samo unutrašnji sat. Dok je aktivan, tempo i fazu takta diktira on.
    void setClockSource(ClockSource* source) { mClock.store(source, std::memory_order_release); }
    // Trenutni tempo miksera (prati i vanjski sat). Za UI.
    double bpm() const { return mBpmOut.load(std::memory_order_relaxed); }
    bool clockLocked() const { return mClockLockedOut.load(std::memory_order_relaxed); }

    void setOutputSampleRate(int rate) { mOutRate.store(rate, std::memory_order_relaxed); }

    // ---- semplovi (smije se zvati dok stream radi). Podaci su interleaved stereo float.
    // lengthBars > 0: loop traje tačno toliko taktova (sempl je već razvučen na tempo). 0 = iz trajanja i BPM-a.
    // -1 = slobodan loop (dužina nije cijeli broj taktova): svira do kraja pa ispočetka, bez poravnanja na takt.
    // Ako glas već svira prethodnu verziju sempla u tom slotu, preuzima novu na sljedećem taktu.
    bool loadSample(int slot, const float* interleavedStereo, int64_t frames, int sampleRate,
                    int32_t lengthBars = 0);
    void clearSample(int slot);
    double sampleDurationSec(int slot) const;

    // ---- preslušavanje (audicija): jedan glas, kreće odmah (bez kvantizacije), ide mimo miksera i mastera.
    // Prethodno preslušavanje se zamjenjuje. Zove se iz bilo koje niti.
    bool startPreview(const float* interleavedStereo, int64_t frames, int sampleRate, bool loop);
    void stopPreview();
    // napredak 0..1, ili -1 ako preslušavanje ne svira
    float previewProgress() const { return mPreviewProgressOut.load(std::memory_order_relaxed); }

    // ---- komande (bilo koja nit)
    void launch(int slot);       // klip: kreće na sljedeći takt; ako svira, staje na sljedećem taktu
    void launchScene(int scene); // svi klipovi scene kreću na sljedeći takt
    void stopTrack(int track);   // staje na sljedećem taktu
    void stopAll();              // odmah (kratak fade)
    void killSlot(int slot);     // odmah (kratak fade), za zamjenu/uklanjanje sempla
    void setBpm(double bpm);     // 20..400
    void setSlotLoop(int slot, bool loop);
    void setTrackGain(int track, float linear);
    void setTrackPan(int track, float pan);   // -1 lijevo .. 0 sredina .. +1 desno (balance, sredina = bez promjene)
    void setTrackMute(int track, bool mute);
    void setTrackSolo(int track, bool solo);
    void setMasterGain(float linear);
    void setMasterMute(bool mute);
    // DJ filter na masteru (sve: trackovi, Drums, Keys): -1 = low-pass do kraja (tamno) .. 0 = isključen .. +1 = high-pass
    void setMasterFilter(float amount);
    // Send po kanalu u zajednički reverb / delay (0..1, poslije fadera i pana, utišan kad je kanal mute)
    void setTrackReverbSend(int track, float amount);
    void setTrackDelaySend(int track, float amount);
    // EQ kanala (prije fadera): band 0 = LOW (shelf 200 Hz), 1 = MID (peak 1 kHz), 2 = HIGH (shelf 5 kHz);
    // amount -1..1 = -12..+12 dB, 0 = ravno (ne računa se)
    void setTrackEq(int track, int band, float amount);

    // ---- stanje za UI (bilo koja nit)
    // slotStates[kNumSlots], trackLevels[kTracks] (peak 0..1), trackProgress[kTracks] (0..1),
    // barPhase (0..1 unutar takta), masterLevel (peak)
    void readState(uint8_t* slotStates, float* trackLevels, float* trackProgress, float* barPhase,
                   float* masterLevel) const;
    int64_t barCount() const { return mBarCountOut.load(std::memory_order_relaxed); }

    // ---- audio nit. Piše numFrames stereo frameova u out (prepisuje sadržaj).
    void render(float* out, int32_t numFrames);

    // Samo za testove.
    void setRetireDelayMs(int ms) { mRetireDelayMs.store(ms); }
    size_t ownedSampleCount();

private:
    struct Owned {
        std::unique_ptr<Sample> sample;
        bool retired = false;
        std::chrono::steady_clock::time_point retiredAt;
    };
    struct Voice {
        const Sample* sample = nullptr;
        double pos = 0.0;
        bool active = false;
        bool loop = false;
        float env = 0.0f;
        float envTarget = 1.0f;
    };
    struct Preview {
        const Sample* sample = nullptr;
        double pos = 0.0;
        bool active = false;
        bool loop = false;
        float env = 0.0f;
        float envTarget = 1.0f;
    };
    struct Track {
        int playing = -1;       // slot koji trenutno svira
        int queued = -1;        // slot koji kreće na sljedeći takt
        bool stopQueued = false;
        float gain = 1.0f;
        float gainSmooth = 1.0f;
        float pan = 0.0f;       // -1..1
        float panL = 1.0f, panR = 1.0f;   // izglađeno (bez klikova)
        bool mute = false;
        bool solo = false;
        float sendRev = 0.0f, sendDly = 0.0f;      // cilj 0..1
        float revSmooth = 0.0f, dlySmooth = 0.0f;  // izglađeno
        float eq[3] = {0.0f, 0.0f, 0.0f};        // cilj -1..1 (LOW, MID, HIGH)
        float eqSmooth[3] = {0.0f, 0.0f, 0.0f};  // izglađeno (koeficijenti po segmentu)
        float eqZ[3][2][2] = {};                 // stanje biquada [band][kanal][z1,z2]
    };

    static constexpr int kChunk = 512;

    void apply(const Command& c);
    void onBar();
    bool startVoice(int slot);
    void adoptSample(int slot, const Sample* next);
    void stopVoiceNow(int slot);
    void endVoice(int slot);
    int64_t lengthBars(const Sample& s) const;
    void recomputeBar();
    void renderChunk(float* out, int32_t frames);
    void renderSegment(float* out, int32_t seg, int64_t untilBar);
    void applyMasterFilter(float* buf, int32_t seg, float smoothCoef, double outRate);
    void applyTrackEq(Track& tr, float* buf, int32_t seg, double outRate);
    void retireLocked(const Sample* old);
    void renderPreview(float* out, int32_t frames);
    void syncToClock(double bpm, double barPhase, int32_t numFrames);

    // ---- dijeljeno između niti
    std::array<std::atomic<const Sample*>, kNumSlots> mSamples;
    std::array<std::atomic<const Sample*>, kNumSlots> mInUse;  // šta svaki glas koristi
    std::atomic<const Sample*> mPreviewSample{nullptr};         // sempl za sljedeće preslušavanje
    std::atomic<const Sample*> mPreviewInUse{nullptr};          // šta preslušavanje trenutno svira
    CommandQueue mQueue;
    std::mutex mOwnerMutex;
    std::vector<Owned> mOwned;
    std::atomic<int> mRetireDelayMs{2000};
    std::atomic<int> mOutRate{48000};
    std::atomic<ClockSource*> mClock{nullptr};
    std::atomic<OutputTap*> mTap{nullptr};
    std::atomic<AuxSource*> mAux{nullptr};
    std::atomic<double> mBpmOut{120.0};
    std::atomic<bool> mClockLockedOut{false};

    // ---- stanje za UI (piše audio nit)
    std::array<std::atomic<uint8_t>, kNumSlots> mSlotStateOut;
    std::array<std::atomic<float>, kTracks> mLevelOut;
    std::array<std::atomic<float>, kTracks> mProgressOut;
    std::atomic<float> mBarPhaseOut{0.0f};
    std::atomic<float> mMasterLevelOut{0.0f};
    std::atomic<float> mPreviewProgressOut{-1.0f};
    std::atomic<int64_t> mBarCountOut{0};

    // ---- samo audio nit
    Preview mPreview;
    std::array<Voice, kNumSlots> mVoices{};
    std::array<Track, kTracks> mTracks{};
    std::array<bool, kNumSlots> mSlotLoop{};
    std::array<int64_t, kNumSlots> mStartBar{};
    std::array<std::array<float, 2 * kChunk>, kTracks> mTrackBuf{};
    std::array<float, kTracks> mPeak{};
    std::array<bool, kTracks> mHasVoice{};
    float mMasterPeak = 0.0f;

    bool mClockLocked = false;  // true kad je faza već jednom poravnata sa vanjskim satom
    int mRate = 48000;
    double mBpm = 120.0;
    double mFramesPerBar = 96000.0;
    double mBarPos = 0.0;  // frameova od početka takta
    int64_t mBarCount = 0;
    float mMasterGain = 1.0f;
    float mMasterSmooth = 1.0f;
    float mLimGain = 1.0f;   // limiter mastera (1 = ne radi)
    bool mMasterMute = false;
    // DJ filter mastera (samo audio nit)
    float mFilter = 0.0f;        // cilj -1..1
    float mFilterSmooth = 0.0f;  // izglađeno
    float mSvfIc1[2] = {0.0f, 0.0f};  // stanje filtera (L, R)
    float mSvfIc2[2] = {0.0f, 0.0f};
    // send efekti (samo audio nit): sabirnice, efekti, rep poslije zadnjeg slanja
    SendFx mSendFx;
    std::array<float, 2 * kChunk> mRevBus{};
    std::array<float, 2 * kChunk> mDlyBus{};
    int64_t mFxTail = 0;     // još koliko frameova računati rep (0 = efekti ne rade)
    bool mFxClean = true;    // repovi obrisani
};

}  // namespace padsampler
