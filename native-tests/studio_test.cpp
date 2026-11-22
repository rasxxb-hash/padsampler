// Test Studija (pattern, drum kit, sinhrono sa taktom miksera, offline render bez šava) na običnom računaru:
//   g++ -std=c++17 -O1 -g -fsanitize=address,undefined -I../app/src/main/cpp studio_test.cpp ../app/src/main/cpp/studio.cpp ../app/src/main/cpp/mixer.cpp -o studio_test && ./studio_test
// BPM 120 @ 48 kHz => takt = 96000 frameova, četvrtina = 24000.
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <vector>

#include "mixer.h"
#include "studio.h"

using namespace padsampler;

#define CHECK(cond)                                                          \
    do {                                                                     \
        if (!(cond)) {                                                       \
            std::printf("NEUSPJEH linija %d: %s\n", __LINE__, #cond);        \
            std::abort();                                                    \
        }                                                                    \
    } while (0)

static const int kRate = 48000;
static const int64_t kBar = 96000;

static std::shared_ptr<const Sample> dcSample(int64_t frames, float v) {
    auto s = std::make_shared<Sample>();
    s->data.assign(static_cast<size_t>(frames) * 2, v);
    s->frames = frames;
    s->sampleRate = kRate;
    return s;
}

static bool near(float a, float b) { return std::fabs(a - b) < 1e-4f; }

// Renderuje mikser u blokovima neparne veličine (granice bloka ne padaju na takt).
static std::vector<float> run(Mixer& m, int64_t frames, int block = 300) {
    std::vector<float> out(static_cast<size_t>(frames) * 2, 0.0f);
    for (int64_t f = 0; f < frames;) {
        const int n = static_cast<int>(std::min<int64_t>(block, frames - f));
        m.render(out.data() + 2 * f, n);
        f += n;
    }
    return out;
}

static std::vector<int64_t> onsets(const std::vector<float>& out) {  // frameovi gdje zvuk krene iz tišine
    std::vector<int64_t> r;
    const int64_t n = static_cast<int64_t>(out.size() / 2);
    for (int64_t f = 0; f < n; ++f) {
        const bool on = out[2 * f] != 0.0f;
        const bool prev = f > 0 && out[2 * (f - 1)] != 0.0f;
        if (on && !prev) r.push_back(f);
    }
    return r;
}

static void testPatternMake() {
    NoteEvent ev[] = {
        {300, 10, 2, 0.5f},
        {0, 2000, 1, 2.0f},   // dužina veća od patterna -> 384, jačina -> 1
        {-5, 10, 0, 1.0f},    // van patterna: odbacuje se
        {384, 10, 0, 1.0f},   // van patterna (1 takt = 384 ticka)
        {0, 96, 200, 1.0f},   // nota > 127
    };
    auto p = Pattern::make(1, ev, 5);
    CHECK(p->bars == 1);
    CHECK(p->notes.size() == 2);
    CHECK(p->notes[0].tick == 0 && p->notes[0].length == 384 && near(p->notes[0].velocity, 1.0f));
    CHECK(p->notes[1].tick == 300);
    CHECK(p->triggers.size() == 4);
    // nota 1: on 0, off (0+384)%384 = 0 -> off prije on
    CHECK(p->triggers[0].tick == 0 && !p->triggers[0].on);
    CHECK(p->triggers[1].tick == 0 && p->triggers[1].on);
    CHECK(p->triggers[2].tick == 300 && p->triggers[2].on);
    CHECK(p->triggers[3].tick == 310 && !p->triggers[3].on);
    CHECK(Pattern::make(99, nullptr, 0)->bars == kMaxPatternBars);
    std::printf("ok pattern\n");
}

static void testLiveOnBar() {
    Mixer m;
    Studio st;
    m.setAuxSource(&st);
    st.drums().setSample(0, dcSample(10, 0.5f));
    NoteEvent ev[] = {{0, 1, 0, 1.0f}, {96, 1, 0, 1.0f}, {192, 1, 0, 1.0f}, {288, 1, 0, 1.0f}};
    st.setPattern(kPartDrums, 1, ev, 4);

    run(m, 1000);           // takt 0 je počeo
    st.play();              // kreće na taktu 1 (frame 96000)
    auto out = run(m, 3 * kBar - 1000);
    CHECK(st.transport() == kStudioPlaying);
    auto on = onsets(out);
    CHECK(on.size() == 8);  // taktovi 1 i 2
    for (size_t i = 0; i < on.size(); ++i) {
        const int64_t expect = kBar + static_cast<int64_t>(i) * 24000 - 1000;
        CHECK(on[i] == expect);
        CHECK(near(out[2 * on[i]], 0.5f));
        CHECK(out[2 * (on[i] + 10)] == 0.0f);  // sempl traje 10 frameova
    }

    // Stop je odmah: nema više udaraca
    st.stop();
    auto after = run(m, kBar);
    CHECK(onsets(after).empty());
    CHECK(st.transport() == kStudioStopped);
    CHECK(st.positionTicks(kPartDrums) < 0.0f);

    // Nota uživo kreće odmah (u sljedećem bloku)
    st.noteOn(kPartDrums, 0, 0.5f);
    auto live = run(m, 1000);
    auto lo = onsets(live);
    CHECK(lo.size() == 1 && lo[0] == 0);
    CHECK(near(live[0], 0.25f));
    std::printf("ok live na taktu\n");
}

// Tempo kojem takt nije cijeli broj frameova: nijedan udarac ne smije ispasti ni ponoviti se.
static void testNoDoubles() {
    Mixer m;
    Studio st;
    m.setAuxSource(&st);
    m.setBpm(117.3);
    st.drums().setSample(3, dcSample(1, 1.0f));
    std::vector<NoteEvent> ev;
    for (int t = 0; t < 2 * kTicksPerBar; t += 24) ev.push_back({t, 1, 3, 1.0f});  // šesnaestine, 2 takta
    st.setPattern(kPartDrums, 2, ev.data(), static_cast<int>(ev.size()));
    st.play();
    const double fpb = kRate * 240.0 / 117.3;
    auto out = run(m, static_cast<int64_t>(9 * fpb), 257);
    int hits = 0;
    for (size_t i = 0; i < out.size(); i += 2) hits += out[i] != 0.0f;
    if (hits != 8 * 16) std::printf("hits=%d\n", hits);
    CHECK(hits == 8 * 16);  // taktovi 1..8
    std::printf("ok bez duplih/izgubljenih udaraca (117.3 BPM)\n");
}

static void testChoke() {
    Mixer m;
    Studio st;
    m.setAuxSource(&st);
    st.drums().setSample(0, dcSample(48000, 0.25f));  // tiho: zbir ispod praga limitera mastera
    st.drums().setSample(1, dcSample(48000, 0.125f));
    st.noteOn(kPartDrums, 0, 1.0f);
    st.noteOn(kPartDrums, 1, 1.0f);
    auto a = run(m, 1000);
    CHECK(near(a[2 * 500], 0.375f));
    st.noteOn(kPartDrums, 0, 1.0f);  // ista traka: stara se utiša (3 ms), nova svira
    auto b = run(m, 1000);
    CHECK(near(b[2 * 900], 0.375f));  // ne 0.625
    std::printf("ok utišavanje iste trake\n");
}

static void testOfflineSeamless() {
    Studio st;
    st.drums().setSample(0, dcSample(10, 0.5f));
    st.drums().setSample(1, dcSample(30000, 0.25f));
    st.drums().setLaneGain(1, 2.0f);
    NoteEvent ev[] = {{0, 1, 0, 1.0f}, {96, 1, 0, 1.0f}, {192, 1, 0, 1.0f}, {288, 1, 1, 1.0f}};
    st.setPattern(kPartDrums, 1, ev, 4);
    auto out = st.renderOffline(kPartDrums, 120.0, kRate);
    CHECK(out.size() == static_cast<size_t>(kBar) * 2);
    // Udarac trake 0 na 0, 24000, 48000
    CHECK(near(out[0], 0.5f + 0.5f));        // + rep trake 1 sa kraja (zamotan)
    CHECK(near(out[2 * 24000], 0.5f));
    CHECK(near(out[2 * 48000], 0.5f));
    // Traka 1 (0.25 x 2) od 72000; traje 30000 -> zadnjih 6000 frameova je na početku loopa
    CHECK(near(out[2 * 72000], 0.5f));
    CHECK(near(out[2 * 95999], 0.5f));
    CHECK(near(out[2 * 100], 0.5f));
    CHECK(near(out[2 * 5999], 0.5f));
    CHECK(out[2 * 6000] == 0.0f);
    // Drugi tempo / rate: tačna dužina
    auto o2 = st.renderOffline(kPartDrums, 117.3, 44100);
    CHECK(o2.size() == static_cast<size_t>(std::llround(44100 * 240.0 / 117.3)) * 2);
    CHECK(st.renderOffline(kPartKeys, 120.0, kRate).empty());  // keys još nema instrument
    std::printf("ok offline render bez šava\n");
}

// Zamjena sempla i patterna dok svira (ASan: stari se ne čita poslije oslobađanja).
static void testSwapWhilePlaying() {
    Published<Sample>::retireDelayMs().store(0);
    Published<Pattern>::retireDelayMs().store(0);
    Mixer m;
    Studio st;
    m.setAuxSource(&st);
    st.drums().setSample(0, dcSample(200000, 0.5f));
    NoteEvent ev[] = {{0, 1, 0, 1.0f}};
    st.setPattern(kPartDrums, 1, ev, 1);
    st.play();
    run(m, kBar + 5000);  // svira od takta 1
    for (int i = 0; i < 5; ++i) {
        st.drums().setSample(0, dcSample(200000, 0.1f * (i + 1)));
        st.drums().setSample(0, dcSample(200000, 0.3f));  // dvaput: prvi se oslobodi odmah
        st.setPattern(kPartDrums, 1 + (i % 2), ev, 1);
        st.setPattern(kPartDrums, 1, ev, 1);
        run(m, 7000);
    }
    run(m, 2 * kBar);
    Published<Sample>::retireDelayMs().store(2000);
    Published<Pattern>::retireDelayMs().store(2000);
    std::printf("ok zamjena dok svira\n");
}

// Drums i Keys imaju svaki svoj transport: play jednog ne pokreće drugi, stop jednog ne gasi drugi.
static void testSeparateTransport() {
    Mixer m;
    Studio st;
    m.setAuxSource(&st);
    st.drums().setSample(0, dcSample(10, 0.5f));
    NoteEvent ev[] = {{0, 1, 0, 1.0f}, {192, 1, 0, 1.0f}};
    st.setPattern(kPartDrums, 1, ev, 2);

    run(m, 1000);
    st.play(kPartKeys);                       // samo Keys
    auto a = run(m, 2 * kBar);
    CHECK(onsets(a).empty());                 // bubanj ćuti
    CHECK(st.transport(kPartKeys) == kStudioPlaying);
    CHECK(st.transport(kPartDrums) == kStudioStopped);
    CHECK(st.transport() == kStudioPlaying);  // "bilo koji"

    st.play(kPartDrums);
    auto b = run(m, 2 * kBar);
    CHECK(onsets(b).size() >= 2);
    CHECK(st.transport(kPartDrums) == kStudioPlaying);

    st.stop(kPartKeys);                       // bubanj svira dalje
    auto c = run(m, kBar);
    CHECK(onsets(c).size() == 2);
    CHECK(st.transport(kPartKeys) == kStudioStopped);
    CHECK(st.transport(kPartDrums) == kStudioPlaying);

    st.stop();                                // sve
    auto d = run(m, kBar);
    CHECK(onsets(d).empty());
    CHECK(st.transport() == kStudioStopped);
    std::printf("ok odvojen transport Drums / Keys\n");
}

// Metronom: tik na svaku četvrtinu (24000 frameova na 120 BPM), poslije gašenja tišina.
static void testClick() {
    Mixer m;
    Studio st;
    m.setAuxSource(&st);
    st.setClick(true);
    auto a = run(m, kBar);
    auto on = onsets(a);
    CHECK(on.size() == 4);
    for (size_t i = 0; i < on.size(); ++i) CHECK(on[i] - static_cast<int64_t>(i) * 24000 <= 2);
    st.setClick(false);
    auto b = run(m, kBar);
    for (size_t i = 2 * 2000; i < b.size(); ++i) CHECK(b[i] == 0.0f);   // samo rep zadnjeg tika (< 30 ms)
    std::printf("ok metronom\n");
}

static double energy(const std::vector<float>& v, size_t from, size_t to) {
    double e = 0.0;
    for (size_t i = from; i < to && i < v.size(); ++i) e += static_cast<double>(v[i]) * v[i];
    return e;
}

// Nova verzija patterna dok Keys svira ne smije prekinuti notu koju korisnik drži uživo (snimanje).
static void testLiveNoteSurvivesPatternSwap() {
    Mixer m;
    Studio st;
    m.setAuxSource(&st);
    st.setPattern(kPartKeys, 1, nullptr, 0);
    st.play(kPartKeys);
    run(m, kBar + 1000);
    CHECK(st.transport(kPartKeys) == kStudioPlaying);
    st.noteOn(kPartKeys, 60, 1.0f);
    run(m, 4000);
    st.setPattern(kPartKeys, 1, nullptr, 0);   // nova verzija (npr. snimljena nota)
    auto out = run(m, 30000);
    CHECK(energy(out, 2 * 25000, 2 * 30000) > 1e-3);   // nota i dalje zvuči (release bi je ugasio)
    st.noteOff(kPartKeys, 60);
    std::printf("ok nota uživo preživi zamjenu patterna\n");
}

int main() {
    setvbuf(stdout, nullptr, _IONBF, 0);
    testClick();
    testLiveNoteSurvivesPatternSwap();
    testSeparateTransport();
    testPatternMake();
    testLiveOnBar();
    testNoDoubles();
    testChoke();
    testOfflineSeamless();
    testSwapWhilePlaying();
    std::printf("SVE OK\n");
    return 0;
}
