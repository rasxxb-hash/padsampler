// Studio: pattern sequencer + instrumenti (drum kit; kasnije sintisajzer). Čisti C++, testira se na hostu
// (native-tests/studio_test.cpp). Vidi docs/SPECIFIKACIJA.md, "Studio".
//
// - Pattern = note sa pozicijom u tickovima (96 po četvrtini, 4/4), dužine 1..8 taktova.
// - Studio je AuxSource miksera: svira sinhrono sa taktom miksera (i Linkom), kroz master.
// - Transport: play kreće na sljedećem taktu (kao klipovi), stop je odmah.
// - renderOffline: isti kod glasova, pattern renderovan u wav bez šava (dva prolaza, rep kraja se "zamota" na
//   početak).
//
// Niti: sve javne metode osim renderAux smiju iz bilo koje niti (ne iz audio niti). renderAux je audio nit:
// bez alokacija, mutexa i logovanja. Objekti koje audio nit čita (Pattern, Sample) su nepromjenjivi nakon
// objave i oslobađaju se tek nakon odgode (Published).
#pragma once

#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

#include "mixer.h"

namespace padsampler {

constexpr int kTicksPerBeat = 96;
constexpr int kTicksPerBar = 4 * kTicksPerBeat;
constexpr int kMaxPatternBars = 8;
constexpr int kDrumLanes = 8;
constexpr int kStudioParts = 2;
enum StudioPart { kPartDrums = 0, kPartKeys = 1 };
enum StudioTransport { kStudioStopped = 0, kStudioQueued = 1, kStudioPlaying = 2 };

struct NoteEvent {
    int32_t tick = 0;      // od početka patterna
    int32_t length = 1;    // u tickovima (drums: nebitno)
    int32_t note = 0;      // drums: traka 0..7; keys: MIDI nota
    float velocity = 1.0f; // 0..1
};

// Nepromjenjiv nakon objave.
struct Pattern {
    struct Trigger {
        int32_t tick;
        int32_t note;
        float velocity;
        bool on;
    };
    int32_t bars = 1;
    std::vector<NoteEvent> notes;    // sortirano po ticku
    std::vector<Trigger> triggers;   // note on/off, sortirano po ticku (off prije on na istom ticku)
    int32_t lengthTicks() const { return bars * kTicksPerBar; }
    // Neispravne note se odbace / poprave (tick u patternu, dužina 1..dužina patterna, nota 0..127).
    static std::shared_ptr<const Pattern> make(int bars, const NoteEvent* events, int count);
};

// Vrijednost koju čita audio nit: objavljuje se nova kopija, stara se oslobađa tek nakon odgode.
template <class T>
class Published {
public:
    void publish(std::shared_ptr<const T> p) {
        std::lock_guard<std::mutex> lock(mMutex);
        const auto now = std::chrono::steady_clock::now();
        if (mCurrent) mRetired.push_back({std::move(mCurrent), now});
        mCurrent = std::move(p);
        mPtr.store(mCurrent.get(), std::memory_order_release);
        const auto delay = std::chrono::milliseconds(retireDelayMs().load());
        size_t keep = 0;
        for (size_t i = 0; i < mRetired.size(); ++i) {
            if (now - mRetired[i].at < delay) mRetired[keep++] = std::move(mRetired[i]);
        }
        mRetired.resize(keep);
    }
    std::shared_ptr<const T> get() const {
        std::lock_guard<std::mutex> lock(mMutex);
        return mCurrent;
    }
    const T* audio() const { return mPtr.load(std::memory_order_acquire); }
    static std::atomic<int>& retireDelayMs() {
        static std::atomic<int> ms{2000};
        return ms;
    }

private:
    struct Retired {
        std::shared_ptr<const T> p;
        std::chrono::steady_clock::time_point at;
    };
    mutable std::mutex mMutex;
    std::shared_ptr<const T> mCurrent;
    std::vector<Retired> mRetired;
    std::atomic<const T*> mPtr{nullptr};
};

// Instrument: noteOn/noteOff/render zove jedna nit (audio, ili offline nit za render); bez alokacija.
class Instrument {
public:
    virtual ~Instrument() = default;
    virtual void noteOn(int note, float velocity) = 0;
    virtual void noteOff(int note) = 0;
    virtual void releaseAll() = 0;  // pusti sve note (prirodno utihnu)
    virtual void silence() = 0;     // brzo utišaj sve (kratak fade)
    // Dodaje (+=) frames stereo frameova.
    virtual void render(float* out, int32_t frames, int sampleRate) = 0;
};

// 8 traka, svaka jedan one-shot sempl. Nova nota na traci kratko utiša prethodnu na istoj traci (kao bubnjari).
class DrumKit : public Instrument {
public:
    DrumKit();
    // ---- bilo koja nit osim audio
    void setSample(int lane, std::shared_ptr<const Sample> s);  // nullptr = prazna traka
    std::shared_ptr<const Sample> sample(int lane) const;
    void setLaneGain(int lane, float linear);
    float laneGain(int lane) const;

    // ---- audio / offline nit
    void noteOn(int note, float velocity) override;
    void noteOff(int) override {}
    void releaseAll() override {}
    void silence() override;
    void render(float* out, int32_t frames, int sampleRate) override;

private:
    struct Voice {
        const Sample* s = nullptr;
        int lane = -1;
        double pos = 0.0;
        float velocity = 1.0f;
        float env = 1.0f;
        bool fading = false;
        bool active = false;
        uint32_t age = 0;
    };
    static constexpr int kVoices = 24;
    std::array<Published<Sample>, kDrumLanes> mSamples;
    std::array<std::atomic<float>, kDrumLanes> mGain;
    std::array<Voice, kVoices> mVoices{};
    uint32_t mAge = 0;
};

class Synth;
class Arpeggiator;
class KeysFx;

class Studio : public AuxSource {
public:
    Studio();
    ~Studio() override;

    DrumKit& drums() { return mDrums; }
    Synth& synth() { return *mSynth; }

    // ---- bilo koja nit osim audio
    void setPattern(int part, int bars, const NoteEvent* events, int count);
    std::shared_ptr<const Pattern> pattern(int part) const;
    // Svaki dio (Drums, Keys) ima svoj transport. part = -1: svi dijelovi.
    void play(int part = -1);  // kreće na sljedećem taktu
    void stop(int part = -1);  // odmah; note tog dijela se puste
    void panic(); // stop + brzo utišaj sve
    void noteOn(int part, int note, float velocity);  // odmah (preslušavanje / sviranje)
    void noteOff(int part, int note);
    void setPartGain(int part, float linear);
    void setPartMute(int part, bool mute);
    // Metronom (tik na svaku četvrtinu) za odbrojavanje i snimanje u Keys.
    void setClick(bool on) { mClickOn.store(on, std::memory_order_relaxed); }

    // Transport dijela; part = -1: "najživlji" od svih (svira > čeka > stoji).
    int transport(int part = -1) const;
    // Pozicija u patternu dijela (tickovi), -1 ako ne svira.
    float positionTicks(int part) const;
    float level() const { return mLevelOut.load(std::memory_order_relaxed); }

    // Pattern dijela renderovan offline (ne dira ono što svira): interleaved stereo, tačno
    // round(bars * framesPerBar) frameova. Prazno ako dio nema pattern ni instrument.
    std::vector<float> renderOffline(int part, double bpm, int sampleRate);

    // ---- audio nit
    void renderAux(float* out, int32_t frames, int sampleRate, int64_t barCount, double barPos,
                   double framesPerBar) override;

private:
    struct Cmd {
        enum Type : uint8_t { Play, Stop, Panic, NoteOn, NoteOff, Gain, Mute };
        Type type = Play;
        int32_t part = 0;
        int32_t note = 0;
        float f = 0.0f;
    };
    // Više producera (mutex), jedan consumer (audio nit, bez zaključavanja).
    class Queue {
    public:
        bool push(const Cmd& c);
        bool pop(Cmd& c);

    private:
        static constexpr size_t kSize = 512;
        std::array<Cmd, kSize> mBuf{};
        std::atomic<size_t> mHead{0};
        std::atomic<size_t> mTail{0};
        std::mutex mMutex;
    };
    struct Part {
        float gain = 1.0f;
        float gainSmooth = 1.0f;
        bool mute = false;
        const Pattern* lastPattern = nullptr;
        std::array<bool, 128> patNotes{};   // note koje je pattern upalio (za zamjenu patterna)
    };
    static constexpr int32_t kScratch = 1024;

    Instrument* instrument(int part);
    void apply(const Cmd& c);
    void renderPiece(float* out, int32_t frames, int sampleRate, int64_t barCount, double barPos,
                     double framesPerBar);
    void renderClick(float* out, int32_t frames, int sampleRate, double barPos, double framesPerBar);

    DrumKit mDrums;
    std::unique_ptr<Synth> mSynth;   // Keys (synth.h)
    std::unique_ptr<Arpeggiator> mArp;   // Keys: arpeggiator ispred sintisajzera (instrument dijela Keys)
    std::unique_ptr<KeysFx> mFx;         // Keys: efekti poslije sintisajzera
    std::array<Published<Pattern>, kStudioParts> mPatterns;
    Queue mQueue;

    std::array<std::atomic<int>, kStudioParts> mTransportOut{};
    std::array<std::atomic<float>, kStudioParts> mPosOut;
    std::atomic<float> mLevelOut{0.0f};
    std::atomic<bool> mClickOn{false};

    // ---- samo audio nit
    std::array<int, kStudioParts> mTransport{};      // kStudioStopped = 0
    std::array<int64_t, kStudioParts> mQueuedAt{};   // takt u kojem je stigao play
    std::array<int64_t, kStudioParts> mStartBar{};   // takt od kojeg pattern svira
    std::array<Part, kStudioParts> mParts{};
    std::array<float, 2 * kScratch> mScratch{};
    float mPeak = 0.0f;
    int32_t mClickPos = -1;      // frameova od početka tika, -1 = tišina
    double mClickPhase = 0.0;
    double mClickInc = 0.0;
};

}  // namespace padsampler
