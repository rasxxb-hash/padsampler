// Test Mixera (sat, klipovi na takt, trackovi, mikser) na običnom računaru:
//   g++ -std=c++17 -O1 -g -fsanitize=address,undefined -I../app/src/main/cpp mixer_test.cpp ../app/src/main/cpp/mixer.cpp -o mixer_test && ./mixer_test
// BPM 120 @ 48 kHz => takt = 96000 frameova.
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <vector>

#include "mixer.h"

using namespace padsampler;

#define CHECK(cond)                                                          \
    do {                                                                     \
        if (!(cond)) {                                                       \
            std::printf("NEUSPJEH linija %d: %s\n", __LINE__, #cond);        \
            std::abort();                                                    \
        }                                                                    \
    } while (0)

static const int kRate = 48000;
static const int64_t kBar = 96000;  // 120 BPM

static std::vector<float> dc(int64_t frames, float v) {
    std::vector<float> d(static_cast<size_t>(frames) * 2, v);
    return d;
}
static std::vector<float> saw(int64_t frames) {  // jedinstvena vrijednost po poziciji
    std::vector<float> d(static_cast<size_t>(frames) * 2);
    for (int64_t i = 0; i < frames; ++i) {
        const float v = 0.1f + 0.8f * static_cast<float>(i % 997) / 997.0f;
        d[2 * i] = v;
        d[2 * i + 1] = v;
    }
    return d;
}

struct Harness {
    Mixer m;
    std::vector<float> L;  // lijevi kanal svih renderovanih frameova
    std::vector<float> R;  // desni kanal
    int pattern = 0;

    Harness() { m.setOutputSampleRate(kRate); }

    // renderuje tačno 'frames' frameova, u blokovima raznih veličina (i većim od internog chunka)
    void run(int64_t frames) {
        static const int sizes[] = {192, 333, 700, 64, 1024, 257};
        std::vector<float> buf(2048 * 2);
        while (frames > 0) {
            int n = sizes[pattern++ % 6];
            if (n > frames) n = static_cast<int>(frames);
            m.render(buf.data(), n);
            for (int i = 0; i < n; ++i) { L.push_back(buf[2 * i]); R.push_back(buf[2 * i + 1]); }
            frames -= n;
        }
    }
    int64_t now() const { return static_cast<int64_t>(L.size()); }
    uint8_t state(int slot) {
        uint8_t st[kNumSlots];
        m.readState(st, nullptr, nullptr, nullptr, nullptr);
        return st[slot];
    }
    void load(int slot, const std::vector<float>& d, bool loop, int rate = kRate) {
        CHECK(m.loadSample(slot, d.data(), static_cast<int64_t>(d.size() / 2), rate));
        m.setSlotLoop(slot, loop);
    }
    bool silent(int64_t from, int64_t to, float eps = 1e-6f) {
        for (int64_t i = from; i < to; ++i) if (std::fabs(L[i]) > eps) return false;
        return true;
    }
};

int main() {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    // 1) klip počinje TAČNO na početku sljedećeg takta (sample-accurate)
    {
        Harness h;
        h.load(0, dc(kBar, 0.5f), true);
        h.run(1000);
        CHECK(h.state(0) == kIdle);
        h.m.launch(0);
        h.run(500);
        CHECK(h.state(0) == kQueuedStart);
        h.run(kBar + 4000 - h.now());
        CHECK(h.silent(0, kBar));                        // do granice takta tišina
        CHECK(std::fabs(h.L[kBar - 1]) < 1e-6f);
        CHECK(h.L[kBar] > 0.0f);                          // prvi frame tačno na granici
        CHECK(std::fabs(h.L[kBar + 100] - 0.5f) < 1e-4f); // puna jačina poslije fade-ina
        CHECK(h.state(0) == kPlaying);
        std::printf("1) start na takt: OK (prvi frame = %lld)\n", (long long)kBar);
    }

    // 2) loop je tačan: ista pozicija u svakom taktu, bez drifta
    {
        Harness h;
        h.load(0, saw(kBar), true);
        h.m.launch(0);
        h.run(kBar * 4 + 1000);
        for (int k = 1; k <= 2; ++k) {
            for (int i = 50; i < 400; i += 7) {
                CHECK(std::fabs(h.L[kBar * (k + 1) + i] - h.L[kBar * k + i]) < 1e-6f);
            }
        }
        std::printf("2) loop poravnat po taktu: OK\n");
    }

    // 3) ponovni pritisak zaustavlja na sljedećem taktu, uz fade out prije granice
    {
        Harness h;
        h.load(0, dc(kBar, 0.5f), true);
        h.m.launch(0);
        h.run(kBar + kBar / 2);               // usred drugog takta, svira
        CHECK(h.state(0) == kPlaying);
        h.m.launch(0);                         // stop
        h.run(1000);
        CHECK(h.state(0) == kQueuedStop);
        h.run(kBar * 2 + 2000 - h.now());
        CHECK(std::fabs(h.L[kBar * 2 - 2000] - 0.5f) < 1e-4f);  // još svira punom jačinom
        CHECK(std::fabs(h.L[kBar * 2 - 1]) < 0.01f);            // utišan do granice
        CHECK(h.silent(kBar * 2, kBar * 2 + 2000));
        CHECK(h.state(0) == kIdle);
        std::printf("3) stop na takt + fade out: OK\n");
    }

    // 4) ponovni pritisak na klip koji čeka start poništava start; pritisak na klip koji staje poništava stop
    {
        Harness h;
        h.load(0, dc(kBar, 0.5f), true);
        h.m.launch(0);
        h.run(100);
        h.m.launch(0);  // poništi
        h.run(100);
        CHECK(h.state(0) == kIdle);
        h.run(kBar * 2 - h.now());
        CHECK(h.silent(0, kBar * 2));
        std::printf("4) poništavanje starta: OK\n");
    }

    // 5) jedan klip po koloni: novi gasi stari tačno na granici takta
    {
        Harness h;
        h.load(0, dc(kBar, 0.5f), true);     // scena 0, track 0
        h.load(8, dc(kBar, 0.25f), true);    // scena 1, track 0
        h.m.launch(0);
        h.run(kBar + 5000);
        h.m.launch(8);
        h.run(100);
        CHECK(h.state(0) == kPlaying);        // stari još svira
        CHECK(h.state(8) == kQueuedStart);
        h.run(kBar * 2 + 3000 - h.now());
        CHECK(std::fabs(h.L[kBar * 2 - 1]) < 0.01f);               // stari utišan
        CHECK(std::fabs(h.L[kBar * 2 + 200] - 0.25f) < 1e-4f);     // novi svira, bez ostatka starog
        for (int64_t i = kBar * 2 - 500; i < kBar * 2 + 200; ++i) CHECK(h.L[i] <= 0.5f + 1e-4f);
        CHECK(h.state(0) == kIdle);
        CHECK(h.state(8) == kPlaying);
        std::printf("5) jedan klip po koloni: OK\n");
    }

    // 6) klip koji nije loop svira do kraja pa staje sam
    {
        Harness h;
        h.load(1, dc(24000, 0.5f), false);  // 0.5 s
        h.m.launch(1);
        h.run(kBar + 12000);
        CHECK(h.state(1) == kPlaying);
        h.run(kBar + 30000 - h.now());
        CHECK(h.state(1) == kIdle);
        CHECK(h.silent(kBar + 24100, kBar + 30000));
        CHECK(std::fabs(h.L[kBar + 20000] - 0.5f) < 1e-4f);
        std::printf("6) one-shot do kraja: OK\n");
    }

    // 7) scena pokreće sve klipove reda, stopTrack staje samo jedan track
    {
        Harness h;
        h.load(2, dc(kBar, 0.1f), true);
        h.load(3, dc(kBar, 0.2f), true);
        h.load(10, dc(kBar, 0.4f), true);  // druga scena, ne smije se pokrenuti
        h.m.launchScene(0);
        h.run(kBar + 1000);
        CHECK(std::fabs(h.L[kBar + 500] - 0.3f) < 1e-4f);
        CHECK(h.state(2) == kPlaying && h.state(3) == kPlaying && h.state(10) == kIdle);
        h.m.stopTrack(2);
        h.run(kBar * 2 + 1000 - h.now());
        CHECK(std::fabs(h.L[kBar * 2 + 500] - 0.2f) < 1e-4f);
        CHECK(h.state(2) == kIdle && h.state(3) == kPlaying);
        std::printf("7) scena i stopTrack: OK\n");
    }

    // 8) stopAll odmah (kratak fade)
    {
        Harness h;
        h.load(0, dc(kBar, 0.5f), true);
        h.m.launch(0);
        h.run(kBar + 3000);
        h.m.stopAll();
        h.run(1000);
        CHECK(h.silent(kBar + 3000 + 400, kBar + 4000));
        CHECK(h.state(0) == kIdle);
        std::printf("8) stopAll: OK\n");
    }

    // 9) jačina, mute i solo po trackovima
    {
        Harness h;
        h.load(0, dc(kBar, 0.4f), true);
        h.load(1, dc(kBar, 0.2f), true);
        h.m.launchScene(0);
        h.run(kBar + 3000);
        CHECK(std::fabs(h.L[h.now() - 1] - 0.6f) < 1e-3f);
        h.m.setTrackGain(0, 0.5f);
        h.run(3000);
        CHECK(std::fabs(h.L[h.now() - 1] - (0.2f + 0.2f)) < 1e-3f);
        h.m.setTrackMute(1, true);
        h.run(3000);
        CHECK(std::fabs(h.L[h.now() - 1] - 0.2f) < 1e-3f);
        h.m.setTrackMute(1, false);
        h.m.setTrackSolo(1, true);
        h.run(3000);
        CHECK(std::fabs(h.L[h.now() - 1] - 0.2f) < 1e-3f);   // samo track 1 (0.2)
        h.m.setTrackSolo(1, false);
        h.m.setMasterGain(0.5f);
        h.run(3000);
        CHECK(std::fabs(h.L[h.now() - 1] - 0.2f) < 1e-3f);   // (0.2 + 0.2) * 0.5
        h.m.setMasterMute(true);
        h.run(8000);
        CHECK(std::fabs(h.L[h.now() - 1]) < 1e-4f);
        std::printf("9) gain/mute/solo/master: OK\n");
    }

    // 9b) pan (balance): sredina bez promjene, lijevo = desni kanal tiši, skroz desno = lijevi nula
    {
        Harness h;
        h.load(0, dc(kBar, 0.4f), true);
        h.m.launchScene(0);
        h.run(kBar + 3000);
        CHECK(std::fabs(h.L[h.now() - 1] - 0.4f) < 1e-3f && std::fabs(h.R[h.now() - 1] - 0.4f) < 1e-3f);
        h.m.setTrackPan(0, -0.5f);
        h.run(3000);
        CHECK(std::fabs(h.L[h.now() - 1] - 0.4f) < 1e-3f);
        CHECK(std::fabs(h.R[h.now() - 1] - 0.4f * std::cos(0.5f * 1.5707963f)) < 1e-3f);
        h.m.setTrackPan(0, 1.0f);
        h.run(3000);
        CHECK(std::fabs(h.L[h.now() - 1]) < 1e-3f && std::fabs(h.R[h.now() - 1] - 0.4f) < 1e-3f);
        // bez skoka: susjedni uzorci poslije promjene se razlikuju malo
        const int64_t at = h.now();
        h.m.setTrackPan(0, -1.0f);
        h.run(2000);
        for (int64_t i = at + 1; i < at + 2000; ++i) CHECK(std::fabs(h.R[i] - h.R[i - 1]) < 0.01f);
        std::printf("9b) pan: OK\n");
    }

    // 10) tempo: 240 BPM => takt 48000 frameova
    {
        Harness h;
        h.m.setBpm(240.0);
        h.load(0, dc(48000, 0.5f), true);
        h.run(100);
        h.m.launch(0);
        h.run(48000 + 2000 - h.now());
        CHECK(h.silent(0, 48000));
        CHECK(h.L[48000] > 0.0f);
        std::printf("10) BPM 240: OK\n");
    }

    // 11) promjena tempa usred takta čuva fazu (pola takta ostaje pola takta)
    {
        Harness h;
        h.load(0, dc(kBar, 0.5f), true);
        h.run(48000);              // pola takta @120
        h.m.setBpm(240.0);         // takt je sad 48000, faza 0.5 => 24000 do granice
        h.run(10);
        h.m.launch(0);
        h.run(48000 + 24000 - h.now() + 2000);
        CHECK(h.silent(0, 72000 - 2));
        CHECK(h.L[72000 + 5] > 0.0f);
        std::printf("11) promjena tempa čuva fazu: OK\n");
    }

    // 12) sempl druge brzine uzorkovanja (44.1 kHz) svira ispravno
    {
        Harness h;
        // 1 takt @ 120 BPM = 2 s = 88200 frameova @ 44.1 kHz
        h.load(0, dc(88200, 0.5f), true, 44100);
        h.m.launch(0);
        h.run(kBar * 3 + 100);
        CHECK(std::fabs(h.L[kBar * 2 + 77] - 0.5f) < 1e-4f);
        CHECK(std::fabs(h.L[kBar * 3 - 1] - 0.5f) < 1e-4f);   // bez rupe na kraju loopa
        std::printf("12) 44.1 kHz sempl: OK\n");
    }

    // 13) zamjena sempla: stari se oslobađa tek kad ga niko ne svira
    {
        Harness h;
        h.m.setRetireDelayMs(0);
        auto a = dc(kBar, 0.5f);
        h.load(5, a, true);
        CHECK(h.m.ownedSampleCount() == 1);
        h.m.launch(5);
        h.run(kBar + 1000);
        h.load(5, a, true);                         // zamjena dok svira
        CHECK(h.m.ownedSampleCount() == 2);         // stari je u upotrebi
        h.run(1000);                                 // i dalje svira bez rušenja
        CHECK(std::fabs(h.L[h.now() - 1] - 0.5f) < 1e-4f);
        h.m.killSlot(5);
        h.run(2000);
        CHECK(h.state(5) == kIdle);
        h.load(5, a, true);                         // sweep
        CHECK(h.m.ownedSampleCount() == 1);
        std::printf("13) zamjena i oslobađanje sempla: OK\n");
    }

    // 14) izlaz je uvijek u [-1, 1], faza takta u [0, 1), napredak klipa raste
    {
        Harness h;
        for (int t = 0; t < kTracks; ++t) h.load(t, dc(kBar, 0.9f), true);
        h.m.launchScene(0);
        h.run(kBar + 20000);
        for (int64_t i = 0; i < h.now(); ++i) CHECK(h.L[i] >= -1.0f && h.L[i] <= 1.0f);
        float levels[kTracks], progress[kTracks], phase = -1, master = -1;
        h.m.readState(nullptr, levels, progress, &phase, &master);
        CHECK(phase >= 0.0f && phase < 1.0f);
        CHECK(master > 0.5f && levels[0] > 0.5f);
        CHECK(progress[0] > 0.1f && progress[0] < 0.4f);   // 20000 / 96000 = 0.21
        std::printf("14) clamp, faza, napredak: OK\n");
    }

    // 15) prazan slot se ne može pokrenuti, neispravni parametri se odbacuju
    {
        Harness h;
        h.m.launch(7);
        h.m.launch(-1);
        h.m.launch(48);
        h.m.launchScene(9);
        h.m.stopTrack(8);
        h.run(kBar + 1000);
        CHECK(h.silent(0, h.now()));
        float d[2] = {0, 0};
        CHECK(!h.m.loadSample(-1, d, 1, 44100));
        CHECK(!h.m.loadSample(48, d, 1, 44100));
        CHECK(!h.m.loadSample(0, d, 0, 44100));
        std::printf("15) neispravni parametri: OK\n");
    }

    // 16) loop: nova (razvučena) verzija sempla se preuzima na sljedećem taktu, na istoj muzičkoj poziciji
    {
        Harness h;
        auto ramp = [](int64_t frames) {
            std::vector<float> d(static_cast<size_t>(frames) * 2);
            for (int64_t i = 0; i < frames; ++i) {
                const float v = 0.1f + 0.8f * static_cast<float>(i) / static_cast<float>(frames);
                d[2 * i] = v; d[2 * i + 1] = v;
            }
            return d;
        };
        auto oldS = dc(2 * kBar, 0.2f);
        CHECK(h.m.loadSample(0, oldS.data(), 2 * kBar, kRate, 2));
        h.m.setSlotLoop(0, true);
        h.run(1000);
        h.m.launch(0);
        h.run(kBar - 1000 + 50000);                       // sredina prvog takta klipa
        auto newS = ramp(2 * kBar);
        CHECK(h.m.loadSample(0, newS.data(), 2 * kBar, kRate, 2));
        h.run(kBar - 50000 + 2000);                       // preko granice takta (192000)
        CHECK(std::fabs(h.L[192000 - 100] - 0.2f) < 1e-4f);   // još stara verzija
        const float expect = 0.1f + 0.8f * (96000.0f + 100.0f) / 192000.0f;
        CHECK(std::fabs(h.L[192000 + 100] - expect) < 1e-3f);  // nova, na poziciji takta 2 od 2
        std::printf("16) preuzimanje nove verzije loopa na taktu: OK\n");
    }

    // 17) one-shot: preuzimanje nove verzije proporcionalno poziciji
    {
        Harness h;
        auto ramp = [](int64_t frames) {
            std::vector<float> d(static_cast<size_t>(frames) * 2);
            for (int64_t i = 0; i < frames; ++i) {
                const float v = 0.1f + 0.8f * static_cast<float>(i) / static_cast<float>(frames);
                d[2 * i] = v; d[2 * i + 1] = v;
            }
            return d;
        };
        auto oldS = ramp(3 * kBar);
        CHECK(h.m.loadSample(0, oldS.data(), 3 * kBar, kRate));
        h.m.setSlotLoop(0, false);
        h.run(1000);
        h.m.launch(0);
        h.run(kBar - 1000 + 10);                          // klip je krenuo u frameu 96000
        auto newS = ramp(2 * kBar);                       // ista "pjesma", brža verzija
        CHECK(h.m.loadSample(0, newS.data(), 2 * kBar, kRate));
        h.run(kBar + 1000);                               // preko granice takta 192000
        const float before = h.L[192000 - 10];
        const float after = h.L[192000 + 200];
        CHECK(std::fabs(before - (0.1f + 0.8f * 96000.0f / 288000.0f)) < 1e-3f);
        CHECK(std::fabs(after - before) < 5e-3f);         // nastavlja otprilike od iste vrijednosti
        std::printf("17) one-shot preuzima novu verziju proporcionalno: OK\n");
    }

    // 18) lengthBars iz sempla (ne iz BPM-a): sempl od 1.2 takta sa lengthBars=2 ne restartuje se poslije 1 takta
    {
        Harness h;
        auto d = saw(115200);
        CHECK(h.m.loadSample(0, d.data(), 115200, kRate, 2));
        h.m.setSlotLoop(0, true);
        h.run(1000);
        h.m.launch(0);
        h.run(kBar - 1000);                               // start u frameu 96000
        h.run(2 * kBar + 100);
        auto sv = [&](int64_t i) { return 0.1f + 0.8f * static_cast<float>(i % 997) / 997.0f; };
        CHECK(std::fabs(h.L[96000 + 96000 + 10] - sv(96010)) < 1e-4f);        // bez restarta na +1 takt
        CHECK(std::fabs(h.L[96000 + 192000 + 10] - sv(10)) < 1e-4f);          // tvrdo poravnanje na +2 takta
        std::printf("18) lengthBars iz sempla: OK\n");
    }

    // 19) DJ filter (master): sredina = bit-exact bez filtera; LP guši visoke, HP guši niske; vrtnja dok svira bez NaN/klipova
    {
        auto sine = [](double hz, int64_t frames) {
            std::vector<float> d(static_cast<size_t>(frames) * 2);
            for (int64_t i = 0; i < frames; ++i) {
                const float v = 0.5f * static_cast<float>(std::sin(2.0 * 3.14159265358979 * hz * i / kRate));
                d[2 * i] = v;
                d[2 * i + 1] = v;
            }
            return d;
        };
        auto rms = [](const std::vector<float>& x, int64_t from, int64_t to) {
            double s = 0;
            for (int64_t i = from; i < to; ++i) s += double(x[i]) * x[i];
            return std::sqrt(s / double(to - from));
        };
        const int64_t from = 2 * kBar + 1000, to = 3 * kBar;
        // a) filter 0 (i vraćen na 0) = isto kao bez filtera
        {
            // na heapu: pod ASan-om (MSVC) svi Harness-i iz main-a zajedno prekorače stek
            auto pa = std::make_unique<Harness>(), pb = std::make_unique<Harness>();
            Harness& a = *pa; Harness& b = *pb;
            a.load(0, saw(kBar), true);
            b.load(0, saw(kBar), true);
            b.m.setMasterFilter(-0.7f);
            a.m.launch(0);
            b.m.launch(0);
            a.run(kBar / 2);
            b.run(kBar / 2);
            b.m.setMasterFilter(0.0f);  // vrati na sredinu prije starta klipa
            a.run(3 * kBar);
            b.run(3 * kBar);
            for (int64_t i = from; i < to; ++i) CHECK(a.L[i] == b.L[i] && a.R[i] == b.R[i]);
        }
        // b) LP do kraja (100 Hz): 5 kHz pada > 30 dB, 60 Hz ostaje
        {
            auto p1 = std::make_unique<Harness>(), p2 = std::make_unique<Harness>(), p3 = std::make_unique<Harness>();
            Harness& hi = *p1; Harness& lo = *p2; Harness& ref = *p3;
            hi.load(0, sine(5000, kBar), true);
            lo.load(0, sine(60, kBar), true);
            ref.load(0, sine(5000, kBar), true);
            hi.m.setMasterFilter(-1.0f);
            lo.m.setMasterFilter(-1.0f);
            for (Harness* h : {&hi, &lo, &ref}) { h->m.launch(0); h->run(3 * kBar); }
            const double r = rms(ref.L, from, to);
            CHECK(rms(hi.L, from, to) < r * 0.03);          // < -30 dB
            CHECK(rms(lo.L, from, to) > r * 0.7);           // bas prolazi
        }
        // c) HP do kraja (8 kHz): 100 Hz pada > 30 dB
        {
            auto p1 = std::make_unique<Harness>(), p2 = std::make_unique<Harness>();
            Harness& lo = *p1; Harness& ref = *p2;
            lo.load(0, sine(100, kBar), true);
            ref.load(0, sine(100, kBar), true);
            lo.m.setMasterFilter(1.0f);
            for (Harness* h : {&lo, &ref}) { h->m.launch(0); h->run(3 * kBar); }
            CHECK(rms(lo.L, from, to) < rms(ref.L, from, to) * 0.03);
        }
        // d) vrtnja s kraja na kraj dok svira (i preko sredine): konačno, bez velikih skokova
        {
            auto ph = std::make_unique<Harness>();
            Harness& h = *ph;
            h.load(0, saw(kBar), true);
            h.m.launch(0);
            h.run(kBar + 100);
            for (int k = 0; k < 200; ++k) {
                h.m.setMasterFilter(std::sin(k * 0.15f));
                h.run(480);
            }
            for (int64_t i = kBar; i < h.now(); ++i) {
                CHECK(std::isfinite(h.L[i]) && std::isfinite(h.R[i]));
                CHECK(std::fabs(h.L[i]) <= 1.0f);
            }
        }
        std::printf("19) DJ filter na masteru: OK\n");
    }

    // 20) send efekti po kanalu (reverb + delay 1/8 sa tačkom, ping-pong)
    {
        auto burst = [](int64_t len, int64_t total) {   // kratak zvuk pa tišina (one-shot)
            std::vector<float> d(static_cast<size_t>(total) * 2, 0.0f);
            for (int64_t i = 0; i < len; ++i) d[2 * i] = d[2 * i + 1] = 0.5f;
            return d;
        };
        auto peakOf = [](const std::vector<float>& x, int64_t from, int64_t to) {
            float p = 0.0f;
            for (int64_t i = from; i < to; ++i) p = std::max(p, std::fabs(x[i]));
            return p;
        };
        // a) send uključen pa vraćen na 0 prije starta = isto kao bez sendova
        {
            auto pa = std::make_unique<Harness>(), pb = std::make_unique<Harness>();
            Harness& a = *pa; Harness& b = *pb;
            a.load(0, saw(kBar), true);
            b.load(0, saw(kBar), true);
            b.m.setTrackReverbSend(0, 0.8f);
            b.m.setTrackDelaySend(0, 0.8f);
            b.run(kBar / 4);
            b.m.setTrackReverbSend(0, 0.0f);
            b.m.setTrackDelaySend(0, 0.0f);
            a.run(kBar / 4);
            a.m.launch(0);
            b.m.launch(0);
            a.run(3 * kBar);
            b.run(3 * kBar);
            for (int64_t i = 0; i < a.now(); ++i) CHECK(a.L[i] == b.L[i] && a.R[i] == b.R[i]);
        }
        // b) reverb: poslije kratkog zvuka ostaje rep; bez senda tišina; rep se ugasi
        {
            auto p1 = std::make_unique<Harness>(), p2 = std::make_unique<Harness>();
            Harness& rev = *p1; Harness& dry = *p2;
            rev.load(0, burst(2400, kBar / 2), false);
            dry.load(0, burst(2400, kBar / 2), false);
            rev.m.setTrackReverbSend(0, 1.0f);
            for (Harness* h : {&rev, &dry}) { h->m.launch(0); h->run(kBar + 12 * kRate); }
            const int64_t t0 = kBar + 2400 + 4800;   // 100 ms poslije kraja zvuka
            CHECK(dry.silent(t0, t0 + kRate / 2));
            const float tail = peakOf(rev.L, t0, t0 + kRate / 2);
            std::printf("   reverb rep: %.4f\n", tail);
            CHECK(tail > 0.005f && tail < 0.5f);
            CHECK(peakOf(rev.R, t0, t0 + kRate / 2) > 0.005f);
            for (int64_t i = 0; i < rev.now(); ++i) CHECK(std::isfinite(rev.L[i]) && std::fabs(rev.L[i]) <= 1.0f);
            CHECK(rev.silent(rev.now() - kRate, rev.now(), 1e-4f));   // poslije ~12 s nema repa
        }
        // c) delay: prvi odjek lijevo poslije 3/16 takta (18000 frameova na 120 BPM), drugi desno
        {
            auto ph = std::make_unique<Harness>();
            Harness& h = *ph;
            h.load(0, burst(480, kBar / 2), false);
            h.m.setTrackDelaySend(0, 1.0f);
            h.m.launch(0);
            h.run(2 * kBar);
            const int64_t e1 = kBar + 18000, e2 = kBar + 36000;
            CHECK(h.silent(kBar + 1000, e1 - 100));                    // između zvuka i odjeka ništa
            CHECK(peakOf(h.L, e1 + 50, e1 + 400) > 0.3f);              // 1. odjek lijevo
            CHECK(peakOf(h.R, e1 + 50, e1 + 400) < 1e-4f);
            CHECK(peakOf(h.R, e2 + 50, e2 + 500) > 0.08f);             // 2. odjek desno, tiši
            CHECK(peakOf(h.R, e2 + 50, e2 + 500) < peakOf(h.L, e1 + 50, e1 + 400));
        }
        // d) vrtnja sendova dok svira: konačno, bez klipova
        {
            auto ph = std::make_unique<Harness>();
            Harness& h = *ph;
            h.load(0, saw(kBar), true);
            h.m.launch(0);
            h.run(kBar + 100);
            for (int k = 0; k < 200; ++k) {
                h.m.setTrackReverbSend(0, 0.5f + 0.5f * std::sin(k * 0.2f));
                h.m.setTrackDelaySend(0, 0.5f + 0.5f * std::cos(k * 0.13f));
                h.run(480);
            }
            for (int64_t i = kBar; i < h.now(); ++i) CHECK(std::isfinite(h.L[i]) && std::isfinite(h.R[i]));
        }
        std::printf("20) send efekti (reverb + delay): OK\n");
    }

    // 21) EQ kanala (LOW shelf 200 Hz, MID peak 1 kHz, HIGH shelf 5 kHz; -1..1 = -12..+12 dB)
    {
        auto tone = [](double hz, int64_t frames) {
            std::vector<float> d(static_cast<size_t>(frames) * 2);
            for (int64_t i = 0; i < frames; ++i) d[2 * i] = d[2 * i + 1] = 0.05f * static_cast<float>(std::sin(2.0 * 3.14159265358979 * hz * i / kRate));
            return d;
        };
        auto rms = [](const std::vector<float>& x, int64_t from, int64_t to) {
            double s = 0;
            for (int64_t i = from; i < to; ++i) s += double(x[i]) * x[i];
            return std::sqrt(s / double(to - from));
        };
        const int64_t from = 2 * kBar + 1000, to = 3 * kBar;
        // pojačanje tona hz kad je band postavljen na amount (u odnosu na ravan EQ)
        auto gainAt = [&](double hz, int band, float amount) {
            auto p1 = std::make_unique<Harness>(), p2 = std::make_unique<Harness>();
            Harness& e = *p1; Harness& ref = *p2;
            e.load(0, tone(hz, kBar), true);
            ref.load(0, tone(hz, kBar), true);
            e.m.setTrackEq(0, band, amount);
            for (Harness* h : {&e, &ref}) { h->m.launch(0); h->run(3 * kBar); }
            for (int64_t i = 0; i < e.now(); ++i) CHECK(std::isfinite(e.L[i]));
            return rms(e.L, from, to) / rms(ref.L, from, to);
        };
        CHECK(gainAt(60, 0, 1.0f) > 3.5);              // LOW +12 dB: bas ~4x
        CHECK(std::fabs(gainAt(8000, 0, 1.0f) - 1.0) < 0.05);   // visoki netaknuti
        CHECK(gainAt(1000, 1, 1.0f) > 3.5);            // MID +12 dB na 1 kHz
        CHECK(gainAt(10000, 2, -1.0f) < 0.3);          // HIGH -12 dB
        CHECK(std::fabs(gainAt(100, 2, -1.0f) - 1.0) < 0.05);   // bas netaknut
        // EQ uključen pa vraćen na 0 prije starta = bit-exact
        {
            auto pa = std::make_unique<Harness>(), pb = std::make_unique<Harness>();
            Harness& a = *pa; Harness& b = *pb;
            a.load(0, saw(kBar), true);
            b.load(0, saw(kBar), true);
            b.m.setTrackEq(0, 0, 0.7f);
            b.m.setTrackEq(0, 2, -0.5f);
            b.run(kBar / 4);
            b.m.setTrackEq(0, 0, 0.0f);
            b.m.setTrackEq(0, 2, 0.0f);
            a.run(kBar / 4);
            a.m.launch(0);
            b.m.launch(0);
            a.run(3 * kBar);
            b.run(3 * kBar);
            for (int64_t i = 0; i < a.now(); ++i) CHECK(a.L[i] == b.L[i] && a.R[i] == b.R[i]);
        }
        // vrtnja dok svira: konačno
        {
            auto ph = std::make_unique<Harness>();
            Harness& h = *ph;
            h.load(0, saw(kBar), true);
            h.m.setTrackGain(0, 0.3f);
            h.m.launch(0);
            h.run(kBar + 100);
            for (int k = 0; k < 200; ++k) {
                for (int b = 0; b < 3; ++b) h.m.setTrackEq(0, b, std::sin(k * 0.17f + b));
                h.run(480);
            }
            for (int64_t i = kBar; i < h.now(); ++i) CHECK(std::isfinite(h.L[i]) && std::isfinite(h.R[i]));
        }
        std::printf("21) EQ kanala: OK\n");
    }

    // 22) limiter na masteru: glasno ne prelazi prag i nije odsječeno; tiho ostaje bit-exact
    {
        auto sineAmp = [](double hz, double amp, int64_t frames) {
            std::vector<float> d(static_cast<size_t>(frames) * 2);
            for (int64_t i = 0; i < frames; ++i) {
                const float v = static_cast<float>(amp * std::sin(2.0 * 3.14159265358979 * hz * double(i) / kRate));
                d[2 * i] = d[2 * i + 1] = v;
            }
            return d;
        };
        // a) dva tracka po 0.9 (zbir 1.8): vrh <= prag, bez ravnih "odsječenih" vrhova
        {
            auto ph = std::make_unique<Harness>();
            Harness& h = *ph;
            h.load(0, sineAmp(100, 0.9, kBar), true);
            h.load(1, sineAmp(100, 0.9, kBar), true);
            h.m.launch(0);
            h.m.launch(1);
            h.run(3 * kBar);
            float pk = 0.0f;
            int flat = 0;
            for (int64_t i = kBar; i < h.now(); ++i) {
                CHECK(std::isfinite(h.L[i]));
                pk = std::max(pk, std::fabs(h.L[i]));
                if (std::fabs(h.L[i]) >= 0.9999f) ++flat;
            }
            CHECK(pk <= 0.9661f);
            CHECK(pk > 0.9f);      // glasno ostaje glasno
            CHECK(flat == 0);      // nije tvrdi klip
        }
        // b) tiho (0.5): limiter ne dira ništa (izlaz = ulaz * gain tracka, tačno)
        {
            auto ph = std::make_unique<Harness>();
            Harness& h = *ph;
            const auto d = sineAmp(440, 0.5, kBar);
            h.load(0, d, true);
            h.m.launch(0);
            h.run(2 * kBar);
            for (int64_t i = kBar + 100; i < 2 * kBar; ++i) CHECK(h.L[i] == d[2 * (i - kBar)]);
        }
        // c) poslije glasnog udarca gain se vrati (tiho opet nepromijenjeno nakon ~1 s)
        {
            auto ph = std::make_unique<Harness>();
            Harness& h = *ph;
            std::vector<float> d(static_cast<size_t>(kBar) * 2, 0.0f);
            for (int64_t i = 0; i < 4800; ++i) d[2 * i] = d[2 * i + 1] = 1.0f;          // glasan udarac (1.0 * 2 tracka)
            for (int64_t i = kRate; i < kBar; ++i) d[2 * i] = d[2 * i + 1] = 0.25f;    // pa tiho
            h.load(0, d, false);
            h.load(1, d, false);
            h.m.launch(0);
            h.m.launch(1);
            h.run(2 * kBar);
            CHECK(std::fabs(h.L[kBar + 100]) <= 0.9661f);
            CHECK(std::fabs(h.L[kBar + kBar - 100] - 0.5f) < 1e-4f);   // 0.25 + 0.25, gain se vratio na 1
        }
        std::printf("22) limiter na masteru: OK\n");
    }

    std::printf("SVI TESTOVI PROSLI\n");
    return 0;
}
