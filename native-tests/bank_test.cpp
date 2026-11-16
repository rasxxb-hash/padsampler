// Test SampleBank (razvlačenje sempla na tempo, Rubber Band) zajedno sa Mixerom:
//   g++ -std=c++17 -O1 -g -fsanitize=address,undefined -I../app/src/main/cpp bank_test.cpp \
//     ../app/src/main/cpp/mixer.cpp ../app/src/main/cpp/sample_bank.cpp ../app/src/main/cpp/stretcher.cpp \
//     ../app/src/main/cpp/rubberband-lib/single/RubberBandSingle.cpp -o bank_test -lpthread && ./bank_test
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <thread>
#include <vector>

#include "mixer.h"
#include "sample_bank.h"

using namespace padsampler;

#define CHECK(cond)                                                          \
    do {                                                                     \
        if (!(cond)) {                                                       \
            std::printf("NEUSPJEH linija %d: %s\n", __LINE__, #cond);        \
            std::abort();                                                    \
        }                                                                    \
    } while (0)

static const int kRate = 44100;   // sempl je 44.1 kHz, izlaz 48 kHz (kao na tabletu)
static const int kOut = 48000;

static std::vector<float> tone(double sec, double hz = 220.0, float amp = 0.4f) {
    const int64_t n = static_cast<int64_t>(sec * kRate);
    std::vector<float> d(static_cast<size_t>(n) * 2);
    for (int64_t i = 0; i < n; ++i) {
        const float v = amp * static_cast<float>(std::sin(2.0 * M_PI * hz * i / kRate));
        d[2 * i] = v; d[2 * i + 1] = v;
    }
    return d;
}
static int64_t frames(const std::vector<float>& d) { return static_cast<int64_t>(d.size() / 2); }
static bool near(double a, double b, double tol) { return std::fabs(a - b) <= tol; }

int main() {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    Mixer m;
    m.setOutputSampleRate(kOut);
    SampleBank bank(m);
    bank.setDebounceMs(20);
    bank.setTargetBpm(120);

    // 1) loop od 4.02 takta pri 120 BPM (8.04 s): postaje tačno 4 takta = 8.0 s
    auto loop = tone(8.04);
    CHECK(bank.setSample(0, loop.data(), frames(loop), kRate, 120.0, SampleBank::kAuto) == frames(loop));
    CHECK(bank.isLoop(0));
    CHECK(near(m.sampleDurationSec(0), 8.0, 1.0 / kRate));
    std::printf("1) loop poravnat na cijeli broj taktova (8.0 s): OK\n");

    // 2) tempo 90: loop traje 4 takta = 10.667 s, jedna promjena = jedno razvlačenje
    m.setBpm(90);
    bank.setTargetBpm(90);
    CHECK(bank.waitIdle(20000));
    CHECK(near(m.sampleDurationSec(0), 4 * 240.0 / 90.0, 2.0 / kRate));
    std::printf("2) tempo 90 -> 10.667 s: OK\n");

    // 3) one-shot od 1 s pri 100 BPM, tempo 90 -> 1.111 s
    auto shot = tone(1.0, 440.0);
    CHECK(bank.setSample(1, shot.data(), frames(shot), kRate, 100.0, SampleBank::kOneShot) > 0);
    CHECK(!bank.isLoop(1));
    CHECK(near(m.sampleDurationSec(1), 100.0 / 90.0, 2.0 / kRate));
    std::printf("3) one-shot razvučen omjerom BPM-ova: OK\n");

    // 4) auto: kratki sempl (0.25 takta) nije loop
    auto kick = tone(0.5);
    CHECK(bank.setSample(2, kick.data(), frames(kick), kRate, 120.0, SampleBank::kAuto) > 0);
    CHECK(!bank.isLoop(2));
    std::printf("4) auto prepoznavanje loopa: OK\n");

    // 5) uključivanje loopa na semplu od tačno 2 takta pri 90 BPM (5.333 s) -> tačno 2 takta
    auto two = tone(2 * 240.0 / 90.0);
    CHECK(bank.setSample(3, two.data(), frames(two), kRate, 90.0, SampleBank::kOneShot) > 0);
    bank.setLoop(3, true);
    CHECK(bank.waitIdle(20000));
    CHECK(bank.isLoop(3));
    CHECK(near(m.sampleDurationSec(3), 2 * 240.0 / 90.0, 2.0 / kRate));
    std::printf("5) promjena loop/one-shot: OK\n");

    // 6) tempo na 150 pa uklanjanje: nema zaostalog posla
    m.setBpm(150);
    bank.setTargetBpm(150);
    bank.remove(2);
    CHECK(bank.waitIdle(30000));
    CHECK(m.sampleDurationSec(2) == 0.0);
    CHECK(near(m.sampleDurationSec(0), 4 * 240.0 / 150.0, 2.0 / kRate));
    CHECK(near(m.sampleDurationSec(1), 100.0 / 150.0, 2.0 / kRate));
    CHECK(bank.pending() == 0);
    std::printf("6) tempo 150 + uklanjanje: OK\n");

    // 7) loop svira dok se tempo mijenja (istovremeno sa pozadinskom niti): nema tišine ni prekida
    {
        Mixer mx;
        mx.setOutputSampleRate(kOut);
        SampleBank bk(mx);
        bk.setDebounceMs(20);
        bk.setTargetBpm(120);
        mx.setBpm(120);
        auto lp = tone(8.0, 220.0, 0.4f);   // 4 takta
        CHECK(bk.setSample(0, lp.data(), frames(lp), kRate, 120.0, SampleBank::kLoop) > 0);
        std::vector<float> buf(512 * 2);
        auto run = [&](int64_t n) {
            double minEnergy = 1e9;
            int64_t done = 0;
            while (done < n) {
                mx.render(buf.data(), 512);
                done += 512;
            }
            (void)minEnergy;
        };
        run(1000);
        mx.launch(0);
        run(kOut * 3);                       // klip svira
        mx.setBpm(100);
        bk.setTargetBpm(100);
        // renderuj ~14 s dok pozadinska nit razvlači; mjeri koliko je 100-frame prozora potpuno tiho nakon starta
        int64_t silentWindows = 0, windows = 0, t = 0;
        float peakSeen = 0;
        while (t < static_cast<int64_t>(kOut) * 14) {
            mx.render(buf.data(), 512);
            float pk = 0;
            for (int i = 0; i < 512; ++i) pk = std::fmax(pk, std::fabs(buf[2 * i]));
            peakSeen = std::fmax(peakSeen, pk);
            ++windows;
            if (pk < 1e-4f && t > 4000) ++silentWindows;   // sinusoida amp 0.4 ima nulte prolaze, ali ne 512 frameova
            t += 512;
            std::this_thread::sleep_for(std::chrono::microseconds(200));
        }
        CHECK(bk.waitIdle(20000));
        CHECK(near(mx.sampleDurationSec(0), 4 * 240.0 / 100.0, 2.0 / kRate));
        CHECK(silentWindows == 0);
        CHECK(peakSeen > 0.3f);
        std::printf("7) tempo se mijenja dok loop svira: OK (prozora %lld)\n", (long long)windows);
    }

    // 8) preslušavanje na tempu sesije: razvuče se, loop se poravna na takt, bez BPM-a svira original
    {
        Mixer mx;
        mx.setOutputSampleRate(kOut);
        auto lp = tone(8.04);   // ~4 takta pri 120 BPM
        std::vector<float> buf(512 * 2);
        auto drain = [&](int64_t n) { for (int64_t d = 0; d < n; d += 512) mx.render(buf.data(), 512); };
        CHECK(startPreview(mx, lp.data(), frames(lp), kRate, 120.0, 90.0, SampleBank::kAuto));
        drain(2048);
        CHECK(mx.previewProgress() >= 0.0f);
        // 4 takta pri 90 BPM = 10.667 s: progres poslije 5.333 s je oko 0.5 (loop, nije gotovo)
        drain(static_cast<int64_t>(5.333 * kOut));
        CHECK(near(mx.previewProgress(), 0.5, 0.02));
        // bez BPM-a: original, jednom
        CHECK(startPreview(mx, lp.data(), frames(lp), kRate, 0.0, 90.0, SampleBank::kOneShot));
        drain(2048);
        drain(static_cast<int64_t>(8.5 * kOut));
        CHECK(mx.previewProgress() < 0.0f);   // 8.04 s originala je završilo
        std::printf("8) preslušavanje na tempu sesije: OK\n");
    }

    // 10) loop koji NIJE cijeli broj taktova (2.7 takta pri 120 BPM) ostaje cijel, slobodan loop; ne reže se
    {
        Mixer m2;
        m2.setOutputSampleRate(kOut);
        SampleBank b2(m2);
        b2.setDebounceMs(20);
        b2.setTargetBpm(120);
        auto odd = tone(5.4);
        CHECK(b2.setSample(0, odd.data(), frames(odd), kRate, 120.0, SampleBank::kLoop) > 0);
        CHECK(b2.isLoop(0));
        CHECK(near(m2.sampleDurationSec(0), 5.4, 2.0 / kRate));
        b2.setTargetBpm(100);
        m2.setBpm(100);
        CHECK(b2.waitIdle(20000));
        CHECK(near(m2.sampleDurationSec(0), 5.4 * 120.0 / 100.0, 2.0 / kRate));
        std::printf("10) loop od 2.7 takta ostaje cijel (slobodan): OK\n");
    }

    // 11) nepoznat BPM: procjena iz dužine. 2 takta pri 90 BPM (5.333 s), tempo 120 => srcBpm 90, loop 4.0 s
    {
        Mixer m2;
        m2.setOutputSampleRate(kOut);
        SampleBank b2(m2);
        b2.setDebounceMs(20);
        b2.setTargetBpm(120);
        auto l2 = tone(2 * 240.0 / 90.0);
        CHECK(b2.setSample(0, l2.data(), frames(l2), kRate, 0.0, SampleBank::kAuto) > 0);
        CHECK(near(b2.srcBpm(0), 90.0, 0.01));
        CHECK(b2.isLoop(0));
        CHECK(near(m2.sampleDurationSec(0), 4.0, 2.0 / kRate));
        // kratak sempl (0.5 s) bez BPM-a: nije loop, srcBpm = trenutni tempo
        auto k = tone(0.5);
        CHECK(b2.setSample(1, k.data(), frames(k), kRate, 0.0, SampleBank::kAuto) > 0);
        CHECK(!b2.isLoop(1));
        CHECK(near(b2.srcBpm(1), 120.0, 0.01));
        // 2.0 s sempl sa ručno zadanim loopom: 1 takt na 120 BPM
        auto one = tone(2.0);
        CHECK(b2.setSample(2, one.data(), frames(one), kRate, 0.0, SampleBank::kLoop) > 0);
        CHECK(near(b2.srcBpm(2), 120.0, 0.01));
        std::printf("11) procjena BPM-a iz dužine: OK\n");
    }

    // 12) zajednička zamjena: semplovi koji sviraju dobiju novi tempo ZAJEDNO (ne jedan po jedan), ostali čim budu gotovi
    {
        Mixer mx;
        mx.setOutputSampleRate(kOut);
        SampleBank bk(mx);
        bk.setDebounceMs(20);
        bk.setTargetBpm(120);
        mx.setBpm(120);
        auto lp = tone(16.0, 220.0, 0.4f);   // 8 taktova pri 120 BPM
        auto longLp = tone(48.0, 330.0, 0.4f);   // slot 2 je 3x duži: završava puno kasnije od ostalih
        for (int s = 0; s < 6; ++s) {
            const auto& d = (s == 2) ? longLp : lp;
            CHECK(bk.setSample(s, d.data(), frames(d), kRate, 120.0, SampleBank::kLoop) > 0);
        }
        std::vector<float> buf(512 * 2);
        for (int i = 0; i < 4; ++i) mx.render(buf.data(), 512);
        for (int s = 0; s < 3; ++s) mx.launch(s);          // 0..2 sviraju, 3..5 miruju
        for (int i = 0; i < 400; ++i) mx.render(buf.data(), 512);
        mx.setBpm(100);
        bk.setTargetBpm(100);
        auto wantOf = [&](int s) { return (s == 2 ? 24 : 8) * 240.0 / 100.0; };
        double firstSeen[6] = {-1, -1, -1, -1, -1, -1};
        const auto t0 = std::chrono::steady_clock::now();
        for (;;) {
            mx.render(buf.data(), 512);
            const double now = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
            bool all = true;
            for (int s = 0; s < 6; ++s) {
                if (firstSeen[s] < 0 && near(mx.sampleDurationSec(s), wantOf(s), 4.0 / kRate)) firstSeen[s] = now;
                if (firstSeen[s] < 0) all = false;
            }
            if (all) break;
            CHECK(now < 60.0);
            std::this_thread::sleep_for(std::chrono::microseconds(300));
        }
        CHECK(bk.waitIdle(20000));
        double lo = 1e9, hi = -1;
        for (int s = 0; s < 3; ++s) { lo = std::fmin(lo, firstSeen[s]); hi = std::fmax(hi, firstSeen[s]); }
        std::printf("12) zajednička zamjena: sviraju 0..2 zamijenjeni u razmaku %.0f ms (prvi nakon %.2f s)\n",
                    (hi - lo) * 1000.0, lo);
        CHECK(hi - lo < 0.5);   // zajedno (bez zajedničke zamjene bi razmak bio nekoliko sekundi)
        // i nijedan sempl koji svira nije zamijenjen prije nego što su svi svirajući bili razvučeni
    }

    std::printf("SVI TESTOVI PROSLI\n");
    return 0;
}
