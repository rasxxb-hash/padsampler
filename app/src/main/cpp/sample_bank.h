// SampleBank: čuva originalne semplove i drži u Mixeru verziju razvučenu na trenutni tempo.
//
//  - Svaki sempl ima "originalni BPM" (srcBpm). Pri promjeni tempa pozadinska nit razvuče sempl
//    (Rubber Band, bez promjene visine tona) i zamijeni ga u Mixeru. Mixer ga preuzme na sljedećem taktu.
//  - Loop čija je dužina (blizu) cijelog broja taktova se razvlači tako da traje TAČNO taj broj taktova.
//  - One-shot se razvlači omjerom srcBpm / tempo.
//
// Čisti C++, thread-safe. Nikad se ne zove iz audio niti.
#pragma once

#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include "mixer.h"

namespace padsampler {

class SampleBank {
public:
    enum LoopMode { kOneShot = 0, kLoop = 1, kAuto = 2 };

    explicit SampleBank(Mixer& mixer);
    ~SampleBank();
    SampleBank(const SampleBank&) = delete;
    SampleBank& operator=(const SampleBank&) = delete;

    // Učita sempl u slot (sinhrono: kad se vrati, sempl je spreman za tempo koji je trenutno postavljen).
    // srcBpm <= 0: BPM nije poznat, pa se pokuša procijeniti iz dužine loopa (cijeli broj taktova, 70-180 BPM,
    // najbliže trenutnom tempu); ako ne ide, uzima se trenutni tempo (bez razvlačenja).
    // Vraća broj frameova originala ili 0 ako nije uspjelo.
    int64_t setSample(int slot, const float* interleavedStereo, int64_t frames, int sampleRate,
                      double srcBpm, int loopMode);
    void remove(int slot);

    void setSrcBpm(int slot, double srcBpm);
    void setLoop(int slot, bool loop);
    bool isLoop(int slot) const;
    double originalDurationSec(int slot) const;
    double srcBpm(int slot) const;  // originalni BPM sempla (poznat ili procijenjen), 0 ako nema sempla
    // Procjena BPM-a iz dužine: najbliži tempu target među 1,2,4,8,16 taktova u opsegu 70-180. maxOffInt > 0:
    // BPM mora biti na najviše toliko od cijelog broja (za automatsku procjenu). Vraća 0 ako nema kandidata.
    static double inferBpm(double durationSec, double target, bool allowOneBar, double maxOffInt);

    // Novi tempo: sve se (u pozadini) ponovo razvlači. Sitne promjene u nizu se spajaju (debounce).
    void setTargetBpm(double bpm);
    double targetBpm() const;

    // Koliko slotova još čeka razvlačenje na trenutni tempo (za prikaz u UI).
    int pending() const;

    // Za testove: čeka da pozadinska nit završi sve. Vraća false ako istekne timeout.
    bool waitIdle(int timeoutMs);
    void setDebounceMs(int ms) { mDebounceMs.store(ms); }

    // Računa dužinu u taktovima (zaokruženo) za originalno trajanje; javno radi testova.
    static double barsOf(double durationSec, double srcBpm) { return durationSec * srcBpm / 240.0; }

private:
    struct Original {
        std::vector<float> data;  // interleaved stereo
        int64_t frames = 0;
        int rate = 48000;
        double srcBpm = 120.0;
        bool loop = false;
    };

    // Razvučena verzija koja čeka zajedničku zamjenu u Mixeru (vidi tryCommitLocked).
    struct Ready {
        std::shared_ptr<Original> orig;
        double target = 0.0;
        std::vector<float> data;
        int64_t frames = 0;
        int32_t lenBars = 0;
    };

    // immediate = true: sempl se odmah stavi u Mixer (setSample). Inače se čeka da svi slotovi koji sviraju
    // budu spremni, pa se zamijene zajedno (da nikad ne sviraju dva različita tempa istovremeno).
    bool renderSlot(int slot, bool immediate);
    bool dirtyLocked(int slot) const;
    bool readyLocked(int slot) const;
    int pickDirtyLocked() const;
    void commitLocked(int slot);
    void tryCommitLocked();
    void workerLoop();

    Mixer& mMixer;
    mutable std::mutex mMutex;
    std::condition_variable mCv;
    std::array<std::shared_ptr<Original>, kNumSlots> mOrig;
    std::array<double, kNumSlots> mRendered;   // za koji je tempo trenutna verzija u Mixeru (< 0 = treba)
    double mTarget = 120.0;
    std::chrono::steady_clock::time_point mLastChange = std::chrono::steady_clock::now();
    std::atomic<int> mDebounceMs{250};
    std::atomic<int> mBusy{0};   // koliko niti trenutno razvlači
    bool mStop = false;
    std::array<std::unique_ptr<Ready>, kNumSlots> mReady;   // zaštićeno sa mMutex
    std::array<bool, kNumSlots> mInFlight{};                // slot se trenutno razvlači (zaštićeno sa mMutex)
    std::vector<std::thread> mWorkers;                      // više niti: razvlačenje semplova ide paralelno
};

// Preslušavanje sempla (npr. iz browsera). targetBpm > 0 i srcBpm > 0: razvuče se na taj tempo (bez promjene visine tona),
// inače svira original. loopMode kao kod SampleBank (0 jednom, 1 loop, 2 automatski iz srcBpm-a).
// Sinhrono (razvlačenje traje), zove se iz pozadinske niti, nikad iz audio niti. Vraća false ako nije uspjelo.
bool startPreview(Mixer& mixer, const float* interleavedStereo, int64_t frames, int sampleRate,
                  double srcBpm, double targetBpm, int loopMode);

}  // namespace padsampler
