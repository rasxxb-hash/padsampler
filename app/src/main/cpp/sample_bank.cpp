#include "sample_bank.h"

#include <algorithm>
#include <cmath>

#include "stretcher.h"

namespace padsampler {

namespace {
inline bool validSlot(int s) { return s >= 0 && s < kNumSlots; }

// Je li trajanje (blizu) cijelog broja taktova (tolerancija 3%)?
bool nearWholeBars(double bars, int64_t* nearestOut) {
    const int64_t nearest = std::llround(bars);
    if (nearestOut != nullptr) *nearestOut = nearest;
    return nearest >= 1 && std::fabs(bars - static_cast<double>(nearest)) <= 0.03 * static_cast<double>(nearest);
}
}  // namespace

SampleBank::SampleBank(Mixer& mixer) : mMixer(mixer) {
    mRendered.fill(-1.0);
    // Razvlačenje je teško za CPU: koristi više jezgri (2 do 4 niti), ali ostavi jezgre za audio i UI.
    const unsigned hc = std::thread::hardware_concurrency();
    const int n = static_cast<int>(std::min(4u, std::max(2u, hc / 2)));
    for (int i = 0; i < n; ++i) mWorkers.emplace_back([this] { workerLoop(); });
}

SampleBank::~SampleBank() {
    {
        std::lock_guard<std::mutex> lock(mMutex);
        mStop = true;
    }
    mCv.notify_all();
    for (auto& t : mWorkers) if (t.joinable()) t.join();
}

// ------------------------------------------------------------------ javni API

int64_t SampleBank::setSample(int slot, const float* data, int64_t frames, int sampleRate,
                              double srcBpm, int loopMode) {
    if (!validSlot(slot) || data == nullptr || frames <= 0 || sampleRate <= 0) return 0;
    if (srcBpm > 0.0 && !(srcBpm >= 20.0 && srcBpm <= 400.0)) return 0;

    if (srcBpm <= 0.0) {
        // BPM nepoznat: procijeni iz dužine (samo za loop; za auto-način strože), inače pretpostavi trenutni tempo
        double target;
        {
            std::lock_guard<std::mutex> lock(mMutex);
            target = mTarget;
        }
        double inferred = 0.0;
        const double sec = double(frames) / sampleRate;
        if (loopMode == kLoop) inferred = inferBpm(sec, target, true, 0.0);
        else if (loopMode == kAuto) inferred = inferBpm(sec, target, false, 0.1);
        srcBpm = inferred > 0.0 ? inferred : target;
    }

    auto orig = std::make_shared<Original>();
    orig->data.assign(data, data + frames * 2);
    orig->frames = frames;
    orig->rate = sampleRate;
    orig->srcBpm = srcBpm;
    if (loopMode == kAuto) {
        orig->loop = nearWholeBars(barsOf(double(frames) / sampleRate, srcBpm), nullptr);
    } else {
        orig->loop = loopMode == kLoop;
    }

    {
        std::lock_guard<std::mutex> lock(mMutex);
        mOrig[slot] = orig;
        mRendered[slot] = -1.0;
    }
    mMixer.setSlotLoop(slot, orig->loop);
    if (!renderSlot(slot, true)) {
        std::lock_guard<std::mutex> lock(mMutex);
        if (mOrig[slot] == orig) mOrig[slot].reset();
        return 0;
    }
    return frames;
}

void SampleBank::remove(int slot) {
    if (!validSlot(slot)) return;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        mOrig[slot].reset();
        mRendered[slot] = -1.0;
        mReady[slot].reset();
    }
    mMixer.clearSample(slot);
}

void SampleBank::setSrcBpm(int slot, double srcBpm) {
    if (!validSlot(slot) || !(srcBpm >= 20.0 && srcBpm <= 400.0)) return;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        if (!mOrig[slot]) return;
        auto copy = std::make_shared<Original>(*mOrig[slot]);
        copy->srcBpm = srcBpm;
        mOrig[slot] = copy;
        mRendered[slot] = -1.0;
        mLastChange = std::chrono::steady_clock::now();
    }
    mCv.notify_all();
}

void SampleBank::setLoop(int slot, bool loop) {
    if (!validSlot(slot)) return;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        if (!mOrig[slot]) return;
        if (mOrig[slot]->loop == loop) return;
        auto copy = std::make_shared<Original>(*mOrig[slot]);
        copy->loop = loop;
        mOrig[slot] = copy;
        mRendered[slot] = -1.0;
        mLastChange = std::chrono::steady_clock::now();
    }
    mMixer.setSlotLoop(slot, loop);
    mCv.notify_all();
}

bool SampleBank::isLoop(int slot) const {
    if (!validSlot(slot)) return false;
    std::lock_guard<std::mutex> lock(mMutex);
    return mOrig[slot] && mOrig[slot]->loop;
}

double SampleBank::srcBpm(int slot) const {
    if (!validSlot(slot)) return 0.0;
    std::lock_guard<std::mutex> lock(mMutex);
    return mOrig[slot] ? mOrig[slot]->srcBpm : 0.0;
}

double SampleBank::inferBpm(double durationSec, double target, bool allowOneBar, double maxOffInt) {
    if (!(durationSec > 0.0)) return 0.0;
    static const int kBars[] = {1, 2, 4, 8, 16};
    double best = 0.0;
    double bestDist = 1e30;
    for (int bars : kBars) {
        if (bars == 1 && !allowOneBar) continue;
        const double bpm = bars * 240.0 / durationSec;
        if (bpm < 70.0 || bpm > 180.0) continue;
        if (maxOffInt > 0.0 && std::fabs(bpm - std::round(bpm)) > maxOffInt) continue;
        const double dist = std::fabs(std::log(bpm / std::max(1.0, target)));
        if (dist < bestDist) {
            bestDist = dist;
            best = bpm;
        }
    }
    return best;
}

double SampleBank::originalDurationSec(int slot) const {
    if (!validSlot(slot)) return 0.0;
    std::lock_guard<std::mutex> lock(mMutex);
    return mOrig[slot] ? double(mOrig[slot]->frames) / mOrig[slot]->rate : 0.0;
}

void SampleBank::setTargetBpm(double bpm) {
    bpm = std::min(400.0, std::max(20.0, bpm));
    {
        std::lock_guard<std::mutex> lock(mMutex);
        if (bpm == mTarget) return;
        mTarget = bpm;
        mLastChange = std::chrono::steady_clock::now();
    }
    mCv.notify_all();
}

double SampleBank::targetBpm() const {
    std::lock_guard<std::mutex> lock(mMutex);
    return mTarget;
}

int SampleBank::pending() const {
    std::lock_guard<std::mutex> lock(mMutex);
    int n = 0;
    for (int i = 0; i < kNumSlots; ++i) n += dirtyLocked(i) ? 1 : 0;
    return n;
}

bool SampleBank::waitIdle(int timeoutMs) {
    const auto end = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    while (std::chrono::steady_clock::now() < end) {
        if (pending() == 0 && mBusy.load() == 0) return true;
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    return pending() == 0 && mBusy.load() == 0;
}

// ------------------------------------------------------------------ razvlačenje

bool SampleBank::dirtyLocked(int slot) const {
    return mOrig[slot] != nullptr && mRendered[slot] != mTarget;
}

// Razvučena verzija koja čeka zamjenu vrijedi samo ako je za isti sempl i isti tempo.
bool SampleBank::readyLocked(int slot) const {
    const Ready* r = mReady[slot].get();
    return r != nullptr && r->orig == mOrig[slot] && r->target == mTarget;
}

// Prvo slotovi koji sviraju ili čekaju početak (da se čuje odmah), pa ostali. Preskače one koje neka nit
// već razvlači i one koje su razvučene a čekaju zamjenu.
int SampleBank::pickDirtyLocked() const {
    uint8_t states[kNumSlots];
    mMixer.readState(states, nullptr, nullptr, nullptr, nullptr);
    int fallback = -1;
    for (int i = 0; i < kNumSlots; ++i) {
        if (!dirtyLocked(i) || mInFlight[i] || readyLocked(i)) continue;
        if (states[i] == kPlaying || states[i] == kQueuedStart || states[i] == kQueuedStop) return i;
        if (fallback < 0) fallback = i;
    }
    return fallback;
}

void SampleBank::commitLocked(int slot) {
    Ready& r = *mReady[slot];
    mMixer.loadSample(slot, r.data.data(), r.frames, r.orig->rate, r.lenBars);
    mRendered[slot] = r.target;
    mReady[slot].reset();
}

// Slotovi koji nisu čujni mijenjaju se čim su spremni. Slotovi koji sviraju (ili čekaju početak/kraj) mijenjaju se
// TEK kad su svi takvi slotovi spremni, i to zajedno, pa ne sviraju dva različita tempa istovremeno.
void SampleBank::tryCommitLocked() {
    uint8_t states[kNumSlots];
    mMixer.readState(states, nullptr, nullptr, nullptr, nullptr);
    auto audible = [&](int i) {
        return states[i] == kPlaying || states[i] == kQueuedStart || states[i] == kQueuedStop;
    };
    bool allAudibleReady = true;
    for (int i = 0; i < kNumSlots; ++i) {
        if (dirtyLocked(i) && audible(i) && !readyLocked(i)) { allAudibleReady = false; break; }
    }
    for (int i = 0; i < kNumSlots; ++i) {
        if (!readyLocked(i) || !dirtyLocked(i)) continue;
        if (!audible(i) || allAudibleReady) commitLocked(i);
    }
}

// Razvuče originalni sempl na trenutni tempo i stavi ga u Mixer. Vraća false samo ako razvlačenje ne uspije.
bool SampleBank::renderSlot(int slot, bool immediate) {
    std::shared_ptr<Original> orig;
    double target;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        orig = mOrig[slot];
        target = mTarget;
    }
    if (!orig) return true;  // uklonjen u međuvremenu

    mBusy.fetch_add(1);
    const double srcSec = double(orig->frames) / orig->rate;
    const double bars = barsOf(srcSec, orig->srcBpm);
    int64_t nearest = 0;
    const bool fit = orig->loop && nearWholeBars(bars, &nearest);
    if (!fit) nearest = std::max<int64_t>(1, std::llround(bars));

    // Dužina izlaza (u frameovima originalnog sample ratea)
    const double outSec = fit ? double(nearest) * 240.0 / target : srcSec * orig->srcBpm / target;
    const int64_t outFrames = std::max<int64_t>(1, std::llround(outSec * orig->rate));

    std::vector<float> stretched;
    const int64_t diff = outFrames > orig->frames ? outFrames - orig->frames : orig->frames - outFrames;
    bool ok = true;
    if (diff <= std::max<int64_t>(2, orig->frames / 500)) {
        // Praktično isto trajanje (razlika < 0.2%): samo odreži ili dopuni tišinom, bez obrade.
        stretched.assign(static_cast<size_t>(outFrames) * 2, 0.0f);
        const int64_t n = std::min(outFrames, orig->frames);
        std::copy(orig->data.begin(), orig->data.begin() + n * 2, stretched.begin());
    } else {
        ok = stretchStereo(orig->data.data(), orig->frames, orig->rate, outFrames, stretched);
    }

    {
        std::lock_guard<std::mutex> lock(mMutex);
        if (ok) {
            // Upiši samo ako je sempl još isti i tempo se u međuvremenu nije promijenio.
            if (mOrig[slot] == orig && mTarget == target) {
                // Loop koji nije cijeli broj taktova je "slobodan" (-1): svira do kraja pa ispočetka, ne reže se na taktu
                const int32_t lenBars = !orig->loop ? 0 : (fit ? static_cast<int32_t>(std::max<int64_t>(1, nearest)) : -1);
                if (immediate) {
                    mMixer.loadSample(slot, stretched.data(), outFrames, orig->rate, lenBars);
                    mRendered[slot] = target;
                    mReady[slot].reset();
                } else {
                    auto r = std::make_unique<Ready>();
                    r->orig = orig;
                    r->target = target;
                    r->data = std::move(stretched);
                    r->frames = outFrames;
                    r->lenBars = lenBars;
                    mReady[slot] = std::move(r);
                    tryCommitLocked();
                }
            }
        } else if (mOrig[slot] == orig) {
            mRendered[slot] = target;  // ne pokušavaj beskonačno
            if (!immediate) tryCommitLocked();   // slot koji je odustao više ne blokira zajedničku zamjenu
        }
    }
    mBusy.fetch_sub(1);
    mCv.notify_all();
    return ok;
}

void SampleBank::workerLoop() {
    std::unique_lock<std::mutex> lock(mMutex);
    for (;;) {
        mCv.wait(lock, [&] { return mStop || pickDirtyLocked() >= 0; });
        if (mStop) return;

        // Debounce: sačekaj da se tempo/postavke prestanu mijenjati.
        const auto quietFor = std::chrono::milliseconds(mDebounceMs.load());
        const auto until = mLastChange + quietFor;
        if (std::chrono::steady_clock::now() < until) {
            mCv.wait_until(lock, until);
            continue;
        }

        const int slot = pickDirtyLocked();
        if (slot < 0) continue;
        mInFlight[slot] = true;
        lock.unlock();
        renderSlot(slot, false);
        lock.lock();
        mInFlight[slot] = false;
        mCv.notify_all();
    }
}

bool startPreview(Mixer& mixer, const float* data, int64_t frames, int sampleRate, double srcBpm,
                  double targetBpm, int loopMode) {
    if (data == nullptr || frames <= 0 || sampleRate <= 0) return false;
    const double srcSec = double(frames) / sampleRate;
    const bool haveBpm = srcBpm >= 20.0 && srcBpm <= 400.0;

    int64_t nearest = 0;
    bool wholeBars = haveBpm && nearWholeBars(SampleBank::barsOf(srcSec, srcBpm), &nearest);
    bool loop = loopMode == SampleBank::kLoop || (loopMode == SampleBank::kAuto && wholeBars);

    if (haveBpm && targetBpm >= 20.0 && targetBpm <= 400.0) {
        const bool fit = loop && wholeBars;
        const double outSec = fit ? double(nearest) * 240.0 / targetBpm : srcSec * srcBpm / targetBpm;
        const int64_t outFrames = std::max<int64_t>(1, std::llround(outSec * sampleRate));
        const int64_t diff = outFrames > frames ? outFrames - frames : frames - outFrames;
        if (diff > std::max<int64_t>(2, frames / 500)) {
            std::vector<float> stretched;
            if (stretchStereo(data, frames, sampleRate, outFrames, stretched)) {
                return mixer.startPreview(stretched.data(), outFrames, sampleRate, loop);
            }
            // razvlačenje nije uspjelo: sviraj original
        }
    }
    return mixer.startPreview(data, frames, sampleRate, loop);
}

}  // namespace padsampler
