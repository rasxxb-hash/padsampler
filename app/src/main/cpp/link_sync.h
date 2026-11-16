// Ableton Link: sinhronizacija tempa, takta i start/stop-a sa drugim aplikacijama/uređajima na istoj Wi-Fi mreži,
// i slanje master miksa preko Link Audio-a (kanal "PadSampler").
// Link je GPL v2+ (isto kao Rubber Band), lična upotreba. Vidi link-lib/README.txt.
//
// Niti: enable/setTempo/peers/requestStart... iz UI/JNI niti; query() i onOutput() ISKLJUČIVO iz audio niti
// (realtime-safe).
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>

#include "mixer.h"

namespace ableton {
class LinkAudio;
class LinkAudioSink;
}

namespace padsampler {

class LinkSync : public ClockSource, public OutputTap {
public:
    LinkSync();
    ~LinkSync() override;

    // Uključi/isključi. Pri prvom uključenju kreira Link sesiju sa tempom bpm (naš tempo), da se tempo ne vrati na 120.
    // Ako već postoje drugi učesnici, Link sam usklađuje sesiju.
    void enable(bool on, double bpm);
    // Potpuno uništi Link sesiju i njene niti (poziva se pri zatvaranju aplikacije, kad audio više ne radi).
    void shutdown();
    bool enabled() const { return mEnabled.load(std::memory_order_relaxed); }
    int peers() const;  // broj DRUGIH učesnika; -1 ako je isključen

    // Promjena tempa iz aplikacije (svi učesnici je preuzimaju).
    void setTempo(double bpm);

    // ---- start/stop (kao Ableton). Mikser kome se šalje "stop" kad neko drugi zaustavi transport.
    void setStopTarget(Mixer* mixer) { mStopTarget.store(mixer, std::memory_order_release); }
    // Transport kreće na početku sljedećeg takta Linka (ako već ne svira). Zove se kad se ovdje pokrene klip.
    void requestStart();
    // Transport staje odmah (STOP ALL ovdje zaustavlja i druge).
    void requestStop();
    bool transportPlaying() const;

    // ---- Link Audio: slanje master miksa. Pamti se i dok je Link isključen.
    void setAudioSend(bool on);
    bool audioSend() const { return mAudioWanted.load(std::memory_order_relaxed); }

    // Ukupno kašnjenje izlaza (mikrosekunde) i ručni pomak (mikrosekunde, može biti negativan).
    void setOutputLatencyUs(int64_t us) { mLatencyUs.store(us, std::memory_order_relaxed); }
    // Ili: stalni izvor kašnjenja (npr. AudioEngine) koji se čita u svakom query(); pokazivač mora živjeti zauvijek.
    void setLatencySource(const std::atomic<int64_t>* src) { mLatencySrc.store(src, std::memory_order_release); }
    void setOffsetUs(int64_t us) { mOffsetUs.store(us, std::memory_order_relaxed); }

    // ClockSource (audio nit)
    bool query(int32_t numFrames, int sampleRate, double* bpm, double* barPhase) override;
    // OutputTap (audio nit): master miks ide u Link Audio (ako je uključeno i neko sluša)
    void onOutput(const float* interleavedStereo, int32_t numFrames, int sampleRate) override;

private:
    struct AudioThreadState;  // stanje sesije iz zadnjeg query() (samo audio nit)

    int64_t latencyUs() const;
    void applyAudioLocked();
    void onTransport(bool playing);  // Link nit

    std::mutex mMutex;  // samo za kreiranje i enable/disable
    std::unique_ptr<ableton::LinkAudio> mOwned;
    std::unique_ptr<ableton::LinkAudioSink> mSink;          // živi do shutdown()
    std::atomic<ableton::LinkAudio*> mLink{nullptr};
    std::atomic<ableton::LinkAudioSink*> mSinkPtr{nullptr};
    std::atomic<bool> mEnabled{false};
    std::atomic<bool> mAudioWanted{false};
    std::atomic<bool> mLastPlaying{false};
    std::atomic<Mixer*> mStopTarget{nullptr};
    std::atomic<int64_t> mLatencyUs{0};
    std::atomic<const std::atomic<int64_t>*> mLatencySrc{nullptr};
    std::atomic<int64_t> mOffsetUs{0};
    std::unique_ptr<AudioThreadState> mRt;
};

}  // namespace padsampler
