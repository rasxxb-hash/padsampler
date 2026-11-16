// Test sintisajzera (Keys): visina tona, stabilnost filtera, mono legato/glide, poly krađa glasova, render.
//   g++ -std=c++17 -O1 -g -fsanitize=address,undefined -I../app/src/main/cpp synth_test.cpp ../app/src/main/cpp/synth.cpp ../app/src/main/cpp/studio.cpp ../app/src/main/cpp/mixer.cpp -o synth_test && ./synth_test
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "studio.h"
#include "synth.h"

using namespace padsampler;

#define CHECK(cond)                                                          \
    do {                                                                     \
        if (!(cond)) {                                                       \
            std::printf("NEUSPJEH linija %d: %s\n", __LINE__, #cond);        \
            std::abort();                                                    \
        }                                                                    \
    } while (0)

static const int kRate = 48000;

static std::vector<float> render(Synth& s, int frames, int block = 128) {
    std::vector<float> out(static_cast<size_t>(frames) * 2, 0.0f);
    for (int f = 0; f < frames; f += block) {
        const int n = std::min(block, frames - f);
        s.render(out.data() + 2 * f, n, kRate);
    }
    return out;
}

static float peak(const std::vector<float>& v) {
    float p = 0.0f;
    for (float x : v) {
        CHECK(std::isfinite(x));
        p = std::max(p, std::fabs(x));
    }
    return p;
}

// frekvencija iz prolazaka kroz nulu naviše (lijevi kanal)
static double freqOf(const std::vector<float>& v, int from, int to) {
    int up = 0, first = -1, last = -1;
    for (int i = from + 1; i < to; ++i) {
        if (v[2 * (i - 1)] < 0.0f && v[2 * i] >= 0.0f) {
            if (first < 0) first = i;
            last = i;
            ++up;
        }
    }
    if (up < 2) return 0.0;
    return (up - 1) * static_cast<double>(kRate) / (last - first);
}

static void cleanSaw(Synth& s) {
    s.setParam(kOsc1Wave, kWaveSaw);
    s.setParam(kOsc2Level, 0);
    s.setParam(kOsc3Level, 0);
    s.setParam(kNoiseLevel, 0);
    s.setParam(kCutoff, 18000);
    s.setParam(kResonance, 0);
    s.setParam(kFilterEnv, 0);
    s.setParam(kKeyTrack, 0);
    s.setParam(kASustain, 1);
}

static void testPitchAndRelease() {
    Synth s;
    cleanSaw(s);
    s.noteOn(69, 1.0f);
    auto a = render(s, kRate / 2);
    CHECK(peak(a) > 0.1f && peak(a) < 2.0f);
    const double f = freqOf(a, kRate / 10, kRate / 2);
    std::printf("  A4 = %.2f Hz\n", f);
    CHECK(std::fabs(f - 440.0) < 440.0 * 0.01);
    s.setParam(kOsc1Octave, 1);  // 4' = oktava više
    auto b = render(s, kRate / 2);
    CHECK(std::fabs(freqOf(b, kRate / 10, kRate / 2) - 880.0) < 880.0 * 0.01);
    s.noteOff(69);
    render(s, 2 * kRate);
    CHECK(s.activeVoices() == 0);
    auto c = render(s, 1000);
    CHECK(peak(c) == 0.0f);
    std::printf("ok visina tona i release\n");
}

static void testFilterStability() {
    Synth s;
    s.setParam(kVoices, 8);
    s.setParam(kNoiseLevel, 1);
    s.setParam(kOsc3Level, 1);
    s.setParam(kResonance, 1);
    s.setParam(kFilterEnv, 1);
    s.setParam(kKeyTrack, 1);
    for (int wave = 0; wave < kWaveCount; ++wave) {
        for (float cutoff : {20.0f, 300.0f, 5000.0f, 18000.0f}) {
            s.setParam(kOsc1Wave, wave);
            s.setParam(kOsc2Wave, wave);
            s.setParam(kCutoff, cutoff);
            for (int note : {0, 36, 60, 96, 127}) s.noteOn(note, 1.0f);
            auto v = render(s, kRate / 4, 37);
            if (peak(v) >= 4.0f) std::printf("  talas %d cutoff %.0f: peak %.2f\n", wave, cutoff, peak(v));
            CHECK(peak(v) < 4.0f);
            s.releaseAll();
        }
    }
    s.silence();
    render(s, kRate / 10);
    CHECK(s.activeVoices() == 0);
    std::printf("ok filter stabilan (rezonanca 1, svi talasi, 20 Hz..18 kHz)\n");
}

static void testMonoLegatoGlide() {
    Synth s;
    cleanSaw(s);
    s.setParam(kVoices, 1);
    s.setParam(kGlide, 0.2f);
    s.noteOn(57, 1.0f);   // A3 220 Hz
    auto a = render(s, kRate / 2);
    CHECK(std::fabs(freqOf(a, kRate / 4, kRate / 2) - 220.0) < 3.0);
    s.noteOn(69, 1.0f);   // legato na A4 sa glideom
    CHECK(s.activeVoices() == 1);
    auto b = render(s, kRate / 50);  // 20 ms: još je između
    const double mid = freqOf(b, 0, kRate / 50);
    std::printf("  glide nakon 20 ms: %.1f Hz\n", mid);
    CHECK(mid > 225.0 && mid < 430.0);
    auto c = render(s, kRate);
    CHECK(std::fabs(freqOf(c, kRate / 2, kRate) - 440.0) < 5.0);
    s.noteOff(69);        // nazad na držanu A3
    auto d = render(s, kRate);
    CHECK(s.activeVoices() == 1);
    CHECK(std::fabs(freqOf(d, kRate / 2, kRate) - 220.0) < 3.0);
    s.noteOff(57);
    render(s, 2 * kRate);
    CHECK(s.activeVoices() == 0);
    std::printf("ok mono legato i glide\n");
}

static void testPolySteal() {
    Synth s;
    s.setParam(kVoices, 8);
    for (int i = 0; i < 12; ++i) s.noteOn(48 + i, 1.0f);
    CHECK(s.activeVoices() == Synth::kMaxVoices);
    auto v = render(s, kRate / 4);
    CHECK(peak(v) > 0.05f && peak(v) < 4.0f);
    s.releaseAll();
    render(s, 3 * kRate);
    CHECK(s.activeVoices() == 0);
    std::printf("ok poly (8 glasova, krađa)\n");
}

static void testStudioKeys() {
    Studio st;
    st.synth().setParam(kARelease, 0.5f);
    NoteEvent ev[] = {{0, 96, 60, 1.0f}, {192, 96, 64, 1.0f}, {288, 96, 67, 1.0f}};  // zadnja nota zvoni preko kraja
    st.setPattern(kPartKeys, 1, ev, 3);
    auto out = st.renderOffline(kPartKeys, 120.0, kRate);
    CHECK(out.size() == 96000u * 2);
    CHECK(peak(out) > 0.05f);
    float head = 0.0f;  // rep zadnje note (release 0.5 s) zamotan na početak loopa
    for (int i = 0; i < 200; ++i) head = std::max(head, std::fabs(out[2 * i]));
    CHECK(head > 0.0f);
    std::printf("ok Studio Keys: offline render\n");
}

static void testPitchBend() {
    Synth s;
    cleanSaw(s);
    s.noteOn(69, 1.0f);
    s.setPitchBend(2.0f);   // +2 polutona: B4
    auto a = render(s, kRate / 2);
    CHECK(std::fabs(freqOf(a, kRate / 10, kRate / 2) - 493.88) < 493.88 * 0.01);
    s.setPitchBend(0.0f);
    s.setModWheel(1.0f);
    auto b = render(s, kRate / 2);
    CHECK(std::fabs(freqOf(b, kRate / 10, kRate / 2) - 440.0) < 440.0 * 0.01);
    CHECK(peak(b) < 2.0f);
    std::printf("ok pitch bend i mod\n");
}


// Lažni instrument: bilježi note (frame, nota, on/off) za test arpeggiatora.
struct RecInst : public Instrument {
    struct Ev { int64_t frame; int note; bool on; };
    std::vector<Ev> ev;
    int64_t frame = 0;
    void noteOn(int n, float) override { ev.push_back({frame, n, true}); }
    void noteOff(int n) override { ev.push_back({frame, n, false}); }
    void releaseAll() override {}
    void silence() override {}
    void render(float*, int32_t frames, int) override { frame += frames; }
};

static void testArp() {
    // 120 BPM @ 48 kHz: doba = 24000 frameova; 1/16 = 6000
    const double bpf = 1.0 / 24000.0;
    std::vector<float> buf(2 * 4096);
    auto run = [&](Arpeggiator& a, RecInst& r, int64_t frames) {
        while (frames > 0) {
            const int32_t n = static_cast<int32_t>(std::min<int64_t>(frames, 1000));
            a.sync(static_cast<double>(r.frame) * bpf, bpf);
            a.render(buf.data(), n, kRate);
            frames -= n;
        }
    };
    {   // isključen: note prolaze direktno
        Synth p; RecInst r; Arpeggiator a(r, p);
        a.noteOn(60, 1.0f); a.noteOff(60);
        CHECK(r.ev.size() == 2 && r.ev[0].on && !r.ev[1].on);
    }
    {   // UP 1/16, 1 oktava, gate 50 %: C E G C E ... tačno na mreži
        Synth p; RecInst r; Arpeggiator a(r, p);
        p.setParam(kArpOn, 1); p.setParam(kArpRate, 3); p.setParam(kArpGate, 0.5f);
        run(a, r, 3000);                       // usred koraka: čeka sljedeći (6000)
        a.noteOn(64, 1.0f); a.noteOn(60, 1.0f); a.noteOn(67, 1.0f);
        run(a, r, 30000);
        std::vector<RecInst::Ev> on;
        for (auto& e : r.ev) if (e.on) on.push_back(e);
        CHECK(on.size() >= 4);
        CHECK(on[0].frame == 6000 && on[0].note == 60);
        CHECK(on[1].frame == 12000 && on[1].note == 64);
        CHECK(on[2].frame == 18000 && on[2].note == 67);
        CHECK(on[3].frame == 24000 && on[3].note == 60);
        // gate: nota se pušta pola koraka poslije
        bool off = false;
        for (auto& e : r.ev) if (!e.on && e.note == 60 && e.frame == 9000) off = true;
        CHECK(off);
        // pusti tipke: arp staje (bez HOLD), nema visećih nota
        a.noteOff(60); a.noteOff(64); a.noteOff(67);
        run(a, r, 30000);
        int balance = 0;
        for (auto& e : r.ev) balance += e.on ? 1 : -1;
        CHECK(balance == 0);
    }
    {   // DOWN, 2 oktave, i start odmah ako je tipka malo poslije koraka
        Synth p; RecInst r; Arpeggiator a(r, p);
        p.setParam(kArpOn, 1); p.setParam(kArpRate, 3); p.setParam(kArpMode, kArpDown); p.setParam(kArpOctaves, 2);
        run(a, r, 6500);                       // 500 frameova poslije koraka (< 1/4 koraka)
        a.noteOn(60, 1.0f); a.noteOn(64, 1.0f);
        run(a, r, 13000);
        std::vector<int> notes;
        std::vector<int64_t> frames;
        for (auto& e : r.ev) if (e.on) { notes.push_back(e.note); frames.push_back(e.frame); }
        CHECK(notes.size() >= 3 && notes[0] == 76 && notes[1] == 72 && notes[2] == 64);
        CHECK(frames[0] == 6500 && frames[1] == 12000 && frames[2] == 18000);
    }
    {   // HOLD: svira i poslije puštanja; novi akord zamijeni stari
        Synth p; RecInst r; Arpeggiator a(r, p);
        p.setParam(kArpOn, 1); p.setParam(kArpRate, 3); p.setParam(kArpHold, 1);
        run(a, r, 100);
        a.noteOn(60, 1.0f); a.noteOff(60);
        run(a, r, 24000);
        CHECK(a.heldCount() == 1);
        a.noteOn(62, 1.0f);
        CHECK(a.heldCount() == 1);
        a.releaseAll();
        CHECK(a.heldCount() == 0);
    }
    {   // Studio offline render sa arpom: zvuk postoji i ponavlja se po taktu
        Studio st;
        st.synth().setParam(kArpOn, 1);
        NoteEvent e{0, kTicksPerBar, 60, 1.0f};
        st.setPattern(kPartKeys, 1, &e, 1);
        auto out = st.renderOffline(kPartKeys, 120.0, kRate);
        CHECK(!out.empty());
        float pk = 0.0f;
        for (float v : out) { CHECK(std::isfinite(v)); pk = std::max(pk, std::fabs(v)); }
        CHECK(pk > 0.01f);
    }
    std::printf("ok arpeggiator (mreža, gate, down/oktave, hold, offline)\n");
}
static void testFx() {
    Synth p;
    KeysFx fx(p);
    // isključeni efekti: zvuk prolazi nepromijenjen
    std::vector<float> b(2 * 512, 0.25f);
    fx.process(b.data(), 512, kRate, 24000.0);
    for (float v : b) CHECK(std::fabs(v - 0.25f) < 1e-6f);
    // delay 1/8 (120 BPM: 12000 frameova), mix 1, bez povratne veze: eho impulsa tačno poslije 1/8
    p.setParam(kFxDelayTime, 1); p.setParam(kFxDelayMix, 1); p.setParam(kFxDelayFeedback, 0);
    std::vector<float> s(2 * 48000, 0.0f);
    fx.process(s.data(), 48000, kRate, 24000.0);           // izglađivanje mixa i vremena delaya (klizi kao traka)
    std::fill(s.begin(), s.end(), 0.0f);
    s[0] = 1.0f; s[1] = 1.0f;
    fx.process(s.data(), 30000, kRate, 24000.0);
    int peakAt = 1; float pk = 0.0f;
    for (int f = 1; f < 30000; ++f) if (std::fabs(s[2 * f]) > pk) { pk = std::fabs(s[2 * f]); peakAt = f; }
    if (!(std::abs(peakAt - 12000) <= 1 && pk > 0.3f)) std::printf("  delay: peak %.3f na %d\n", pk, peakAt);
    CHECK(std::abs(peakAt - 12000) <= 1 && pk > 0.3f);
    // sve uključeno, glasno: konačno i ograničeno; reverb ima rep poslije note
    Synth q;
    q.setParam(kFxDrive, 1); q.setParam(kFxChorus, 1); q.setParam(kFxDelayMix, 1); q.setParam(kFxDelayFeedback, 0.9f);
    q.setParam(kFxReverbMix, 1); q.setParam(kFxReverbSize, 1);
    KeysFx all(q);
    std::vector<float> n(2 * 48000, 0.0f);
    for (int f = 0; f < 4800; ++f) n[2 * f] = n[2 * f + 1] = (f % 100 < 50) ? 0.9f : -0.9f;
    float mx = 0.0f, tail = 0.0f;
    for (int rep = 0; rep < 10; ++rep) {
        all.process(n.data(), 48000, kRate, 24000.0);
        for (int f = 0; f < 48000; ++f) {
            CHECK(std::isfinite(n[2 * f]) && std::isfinite(n[2 * f + 1]));
            mx = std::max(mx, std::fabs(n[2 * f]));
            if (rep == 0 && f > 10000) tail = std::max(tail, std::fabs(n[2 * f]));
        }
        std::fill(n.begin(), n.end(), 0.0f);
    }
    CHECK(mx < 8.0f);
    CHECK(tail > 0.001f);
    // Studio offline render sa efektima
    Studio st;
    st.synth().setParam(kFxReverbMix, 0.5f);
    st.synth().setParam(kFxDelayMix, 0.5f);
    NoteEvent e{0, kTicksPerBeat, 60, 1.0f};
    st.setPattern(kPartKeys, 1, &e, 1);
    auto out = st.renderOffline(kPartKeys, 120.0, kRate);
    CHECK(!out.empty());
    for (float v : out) CHECK(std::isfinite(v));
    std::printf("ok efekti (bypass, delay na tempu, sve uključeno stabilno, rep, offline)\n");
}

int main() {
    setvbuf(stdout, nullptr, _IONBF, 0);
    testPitchAndRelease();
    testFilterStability();
    testMonoLegatoGlide();
    testPolySteal();
    testStudioKeys();
    testPitchBend();
    testArp();
    testFx();
    std::printf("SVE OK\n");
    return 0;
}
