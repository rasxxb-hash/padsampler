// Test sinhronizacije sa vanjskim satom (Mixer) i Ableton Linka (bez drugih učesnika), na običnom računaru:
//   g++ -std=c++17 -O1 -g -fsanitize=address,undefined -DLINK_PLATFORM_LINUX=1 -DASIO_NO_TYPEID=1 -DASIO_STANDALONE=1 \
//     -DASIO_VERSION_NAMESPACE=link_asio_1_38_2 -I../app/src/main/cpp -I../app/src/main/cpp/link-lib \
//     -I../app/src/main/cpp/link-lib/asio-include link_test.cpp ../app/src/main/cpp/mixer.cpp \
//     ../app/src/main/cpp/link_sync.cpp -lpthread -o link_test && ./link_test
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <thread>
#include <vector>

#include "link_sync.h"
#include "mixer.h"

using namespace padsampler;

#define CHECK(cond)                                                   \
    do {                                                              \
        if (!(cond)) {                                                \
            std::printf("NEUSPJEH linija %d: %s\n", __LINE__, #cond); \
            std::abort();                                             \
        }                                                             \
    } while (0)

static const int kRate = 48000;

// Lažni vanjski sat: tempo bpm, faza takta teče sa sopstvenim brojačem frameova (+ ofset).
struct FakeClock : ClockSource {
    bool active = true;
    double bpm = 120.0;
    double phaseOffset = 0.0;   // 0..1 takta
    double driftPpm = 0.0;
    double frames = 0.0;
    bool query(int32_t numFrames, int rate, double* b, double* p) override {
        if (!active) return false;
        *b = bpm;
        const double fpb = 240.0 * rate / bpm;
        double ph = std::fmod(frames / fpb + phaseOffset, 1.0);
        if (ph < 0) ph += 1.0;
        *p = ph;
        frames += numFrames * (1.0 + driftPpm * 1e-6);
        return true;
    }
};

static std::vector<float> dc(int64_t frames, float v) { return std::vector<float>(static_cast<size_t>(frames) * 2, v); }

static void run(Mixer& m, int64_t frames) {
    std::vector<float> buf(512 * 2);
    for (int64_t done = 0; done < frames; done += 512) m.render(buf.data(), 512);
}

// Render u stvarnom vremenu (za Link, čiji sat ide po pravom vremenu): 512 frameova pa pauza.
static void runRealtime(Mixer& m, int ms) {
    std::vector<float> buf(512 * 2);
    const auto end = std::chrono::steady_clock::now() + std::chrono::milliseconds(ms);
    auto next = std::chrono::steady_clock::now();
    while (std::chrono::steady_clock::now() < end) {
        m.render(buf.data(), 512);
        next += std::chrono::microseconds(512 * 1000000LL / kRate);
        std::this_thread::sleep_until(next);
    }
}

static double phaseOf(Mixer& m) {
    float ph = 0;
    m.readState(nullptr, nullptr, nullptr, &ph, nullptr);
    return ph;
}

int main() {
    std::setvbuf(stdout, nullptr, _IONBF, 0);  // ispis odmah (i kad test pukne)
    // 1) Prvo poravnanje: faza se postavi na fazu vanjskog sata, tempo se preuzme
    {
        Mixer m;
        m.setOutputSampleRate(kRate);
        FakeClock c;
        c.bpm = 128.0;
        c.phaseOffset = 0.37;
        m.setClockSource(&c);
        run(m, 512);
        CHECK(std::fabs(m.bpm() - 128.0) < 1e-9);
        CHECK(m.clockLocked());
        // faza miksera (na kraju buffera) = faza sata na početku + 512 frameova
        const double fpb = 240.0 * kRate / 128.0;
        double expect = std::fmod(0.37 + 512.0 / fpb, 1.0);
        double d = std::fabs(phaseOf(m) - expect);
        d = std::min(d, 1.0 - d);
        CHECK(d * fpb < 1.5);
        std::printf("ok 1: prvo poravnanje (greska %.2f frameova)\n", d * fpb);
    }
    // 2) Mali drift (50 ppm) se klizanjem prati i greška ostaje mala dugo vremena
    {
        Mixer m;
        m.setOutputSampleRate(kRate);
        FakeClock c;
        c.driftPpm = 50.0;
        m.setClockSource(&c);
        run(m, kRate * 120);  // 2 minute
        const double fpb = 240.0 * kRate / 120.0;
        double expect = std::fmod(c.frames / fpb + 512.0 / fpb * 0.0, 1.0);
        double d = std::fabs(phaseOf(m) - std::fmod(expect, 1.0));
        d = std::min(d, 1.0 - d);
        std::printf("   drift greska: %.2f frameova (%.3f ms)\n", d * fpb, d * fpb * 1000.0 / kRate);
        CHECK(d * fpb < 40.0);  // < ~1 ms
        std::printf("ok 2: drift 50 ppm slijedi\n");
    }
    // 3) Promjena tempa vanjskog sata se preuzima odmah; tempo se ne vraća
    {
        Mixer m;
        m.setOutputSampleRate(kRate);
        FakeClock c;
        m.setClockSource(&c);
        run(m, 4096);
        c.bpm = 97.5;
        run(m, 4096);
        CHECK(std::fabs(m.bpm() - 97.5) < 1e-9);
        std::printf("ok 3: promjena tempa\n");
    }
    // 4) Isključivanje: mikser zadrži zadnji tempo, nastavlja sam; ponovo uključivanje opet poravnava
    {
        Mixer m;
        m.setOutputSampleRate(kRate);
        FakeClock c;
        c.bpm = 140.0;
        m.setClockSource(&c);
        run(m, 4096);
        CHECK(m.clockLocked());
        c.active = false;
        run(m, 4096);
        CHECK(!m.clockLocked());
        CHECK(std::fabs(m.bpm() - 140.0) < 1e-9);
        m.setClockSource(nullptr);
        run(m, 4096);
        c.active = true;
        c.phaseOffset = 0.8;
        m.setClockSource(&c);
        run(m, 512);
        CHECK(m.clockLocked());
        std::printf("ok 4: iskljuci / ponovo ukljuci\n");
    }
    // 5) Petlja koja svira ostaje poravnata sa taktom nakon skoka faze (hard sync)
    {
        Mixer m;
        m.setOutputSampleRate(kRate);
        m.setBpm(120.0);
        const int64_t bar = 96000;
        // saw petlja od 1 takta, jedinstvene vrijednosti po poziciji
        std::vector<float> d(static_cast<size_t>(bar) * 2);
        for (int64_t i = 0; i < bar; ++i) { d[2 * i] = d[2 * i + 1] = static_cast<float>(i) / bar; }
        m.loadSample(0, d.data(), bar, kRate, 1);
        m.setSlotLoop(0, true);
        m.launch(0);
        run(m, bar * 2);   // svira 1+ takt
        FakeClock c;
        c.phaseOffset = 0.25;   // vanjski sat je 1/4 takta ispred
        m.setClockSource(&c);
        // Poslije skoka vrijednost sempla u ovom trenutku mora odgovarati fazi takta (petlja = 1 takt)
        std::vector<float> buf(512 * 2);
        m.render(buf.data(), 512);
        const double ph = phaseOf(m);
        const double sampleVal = buf[2 * 511];
        double dd = std::fabs(sampleVal - ph);
        dd = std::min(dd, 1.0 - dd);
        std::printf("   petlja %.4f vs faza %.4f\n", sampleVal, ph);
        CHECK(dd < 0.003);
        std::printf("ok 5: petlja ostaje u fazi poslije skoka\n");
    }
    // 6) Ableton Link (sam, bez drugih učesnika): uključi, tempo, faza, isključi
    {
        LinkSync link;
        CHECK(link.peers() == -1);
        link.enable(true, 124.0);
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        CHECK(link.enabled());
        CHECK(link.peers() == 0);
        double bpm = 0, ph = -1;
        CHECK(link.query(512, kRate, &bpm, &ph));
        std::printf("   Link bpm=%.3f faza=%.3f\n", bpm, ph);
        CHECK(std::fabs(bpm - 124.0) < 0.01);
        CHECK(ph >= 0.0 && ph < 1.0);
        link.setTempo(100.0);
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        CHECK(link.query(512, kRate, &bpm, &ph));
        CHECK(std::fabs(bpm - 100.0) < 0.01);

        // faza napreduje u skladu sa tempom: 1 takt = 2.4 s na 100 BPM
        double p0 = 0, p1 = 0;
        link.query(512, kRate, &bpm, &p0);
        std::this_thread::sleep_for(std::chrono::milliseconds(240));
        link.query(512, kRate, &bpm, &p1);
        double adv = p1 - p0; if (adv < 0) adv += 1.0;
        std::printf("   faza napredovala %.3f takta za 240 ms (ocekivano 0.100)\n", adv);
        CHECK(std::fabs(adv - 0.100) < 0.02);

        // Mixer + Link zajedno
        Mixer m;
        m.setOutputSampleRate(kRate);
        m.setClockSource(&link);
        run(m, 4096);
        CHECK(std::fabs(m.bpm() - 100.0) < 0.01);
        CHECK(m.clockLocked());

        link.enable(false, 100.0);
        CHECK(link.peers() == -1);
        CHECK(!link.query(512, kRate, &bpm, &ph));
        // ponovo uključi: tempo ostaje naš
        link.enable(true, 111.0);
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        CHECK(link.query(512, kRate, &bpm, &ph));
        std::printf("   poslije ponovnog ukljucenja bpm=%.3f\n", bpm);
        link.enable(false, 111.0);
        std::printf("ok 6: Link sam (ukljuci, tempo, faza, iskljuci)\n");
    }
    // 7) Start/stop (kao Ableton) i Link Audio (slanje mastera; bez slušaoca se ništa ne šalje, ali ne smije pući)
    {
        LinkSync link;
        Mixer m;
        m.setOutputSampleRate(kRate);
        link.setStopTarget(&m);
        link.setAudioSend(true);  // prije uključenja: pamti se
        link.enable(true, 120.0);
        m.setClockSource(&link);
        m.setOutputTap(&link);
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        CHECK(link.audioSend());
        CHECK(!link.transportPlaying());

        auto loop = dc(kRate * 2, 0.25f);  // 1 takt na 120 BPM
        CHECK(m.loadSample(0, loop.data(), kRate * 2, kRate));
        m.setSlotLoop(0, true);
        link.requestStart();               // prvi klip pokreće transport
        m.launch(0);
        CHECK(link.transportPlaying());
        runRealtime(m, 2600);              // Link sat ide u stvarnom vremenu: > 1 takt (2 s) da klip krene
        uint8_t st[kNumSlots] = {};
        m.readState(st, nullptr, nullptr, nullptr, nullptr);
        CHECK(st[0] == kPlaying);
        link.requestStart();               // već svira: ništa
        CHECK(link.transportPlaying());

        // STOP (ovdje ili kod drugog učesnika) -> callback na Link niti -> svi klipovi staju
        link.requestStop();
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
        CHECK(!link.transportPlaying());
        runRealtime(m, 300);               // kratak fade
        m.readState(st, nullptr, nullptr, nullptr, nullptr);
        std::printf("   stanje poslije STOP: %d\n", st[0]);
        CHECK(st[0] == kIdle);

        // Link Audio isključen/uključen dok render radi
        link.setAudioSend(false);
        run(m, 4096);
        link.setAudioSend(true);
        run(m, 4096);
        link.enable(false, 120.0);
        run(m, 4096);
        m.setOutputTap(nullptr);
        m.setClockSource(nullptr);
        link.shutdown();
        std::printf("ok 7: Link start/stop i Link Audio\n");
    }
    std::printf("SVI TESTOVI PROSLI\n");
    return 0;
}
