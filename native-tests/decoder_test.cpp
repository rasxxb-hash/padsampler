// Test WAV/AIFF dekodera na hostu:
//   g++ -std=c++17 -O1 -I../app/src/main/cpp decoder_test.cpp ../app/src/main/cpp/decoder.cpp -o decoder_test && ./decoder_test
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "decoder.h"

using namespace padsampler;
using Bytes = std::vector<uint8_t>;

static void le16(Bytes& b, uint32_t v) { b.push_back(v & 0xFF); b.push_back((v >> 8) & 0xFF); }
static void le32(Bytes& b, uint32_t v) { le16(b, v & 0xFFFF); le16(b, v >> 16); }
static void be16(Bytes& b, uint32_t v) { b.push_back((v >> 8) & 0xFF); b.push_back(v & 0xFF); }
static void be32(Bytes& b, uint32_t v) { be16(b, v >> 16); be16(b, v & 0xFFFF); }
static void str(Bytes& b, const char* s) { for (int i = 0; i < 4; ++i) b.push_back(s[i]); }

// kodira jedan sempl vrijednosti v u [-1,1) kao int sa 'bytes' bajtova
static void putInt(Bytes& b, double v, int bytes, bool big) {
    const int64_t scale = 1LL << (bytes * 8 - 1);
    int64_t x = static_cast<int64_t>(std::llround(v * scale));
    if (x >= scale) x = scale - 1;
    uint8_t tmp[4];
    for (int i = 0; i < bytes; ++i) tmp[i] = static_cast<uint8_t>((x >> (8 * i)) & 0xFF);  // LE
    if (big) for (int i = bytes - 1; i >= 0; --i) b.push_back(tmp[i]);
    else for (int i = 0; i < bytes; ++i) b.push_back(tmp[i]);
}

static Bytes makeWav(int channels, int rate, int bytes, bool isFloat, const std::vector<double>& s,
                     bool extensible = false) {
    Bytes data;
    for (double v : s) {
        if (isFloat) { float f = static_cast<float>(v); uint8_t t[4]; std::memcpy(t, &f, 4); for (int i = 0; i < 4; ++i) data.push_back(t[i]); }
        else if (bytes == 1) data.push_back(static_cast<uint8_t>(std::lround(v * 127) + 128));
        else putInt(data, v, bytes, false);
    }
    Bytes b;
    str(b, "RIFF"); le32(b, 0); str(b, "WAVE");
    str(b, "fmt ");
    le32(b, extensible ? 40 : 16);
    le16(b, extensible ? 0xFFFE : (isFloat ? 3 : 1));
    le16(b, channels); le32(b, rate); le32(b, rate * channels * (isFloat ? 4 : bytes));
    le16(b, channels * (isFloat ? 4 : bytes)); le16(b, isFloat ? 32 : bytes * 8);
    if (extensible) {
        le16(b, 22); le16(b, isFloat ? 32 : bytes * 8); le32(b, 3);
        le16(b, isFloat ? 3 : 1);  // subformat (prva 2 bajta GUID-a)
        for (int i = 0; i < 14; ++i) b.push_back(0);
    }
    str(b, "LIST"); le32(b, 3); b.push_back(1); b.push_back(2); b.push_back(3); b.push_back(0);  // nepoznat chunk + padding
    str(b, "data"); le32(b, static_cast<uint32_t>(data.size()));
    b.insert(b.end(), data.begin(), data.end());
    return b;
}

static void ext80(Bytes& b, double rate) {
    int exp = 0;
    double m = std::frexp(rate, &exp);  // rate = m * 2^exp, m u [0.5,1)
    uint64_t mant = static_cast<uint64_t>(std::ldexp(m, 64));
    int e = exp - 1 + 16383;
    be16(b, e);
    for (int i = 7; i >= 0; --i) b.push_back((mant >> (8 * i)) & 0xFF);
}

static Bytes makeAiff(int channels, int rate, int bytes, const std::vector<double>& s,
                      bool aifc = false, const char* comp = "NONE") {
    Bytes data;
    const bool little = aifc && std::memcmp(comp, "sowt", 4) == 0;
    for (double v : s) putInt(data, v, bytes, !little);
    Bytes b;
    str(b, "FORM"); be32(b, 0); str(b, aifc ? "AIFC" : "AIFF");
    str(b, "COMM"); be32(b, aifc ? 24 : 18);
    be16(b, channels); be32(b, static_cast<uint32_t>(s.size() / channels)); be16(b, bytes * 8);
    ext80(b, rate);
    if (aifc) { str(b, comp); b.push_back(0); b.push_back(0); }  // + prazan pascal string
    str(b, "SSND"); be32(b, static_cast<uint32_t>(data.size() + 8)); be32(b, 0); be32(b, 0);
    b.insert(b.end(), data.begin(), data.end());
    return b;
}

static void expectNear(const DecodedAudio& a, const std::vector<double>& s, int channels, double tol) {
    assert(a.frames == static_cast<int64_t>(s.size() / channels));
    for (int64_t f = 0; f < a.frames; ++f) {
        const double l = s[f * channels];
        const double r = channels > 1 ? s[f * channels + 1] : l;
        assert(std::fabs(a.data[2 * f] - l) < tol);
        assert(std::fabs(a.data[2 * f + 1] - r) < tol);
    }
}

int main() {
    const std::vector<double> stereo = {0.0, 0.0, 0.5, -0.5, -1.0, 0.25, 0.999, -0.125};
    const std::vector<double> mono = {0.0, 0.5, -0.5, 0.25};

    for (int bytes : {2, 3, 4}) {
        DecodedAudio a;
        assert(decodeAuto(makeWav(2, 44100, bytes, false, stereo).data(), makeWav(2, 44100, bytes, false, stereo).size(), a));
        assert(a.sampleRate == 44100);
        expectNear(a, stereo, 2, 1e-3);
    }
    { // wav 8 bit mono (unsigned)
        auto w = makeWav(1, 22050, 1, false, mono);
        DecodedAudio a; assert(decodeAuto(w.data(), w.size(), a));
        assert(a.sampleRate == 22050); expectNear(a, mono, 1, 2e-2);
    }
    { // wav float32 + extensible float
        for (bool ext : {false, true}) {
            auto w = makeWav(2, 48000, 4, true, stereo, ext);
            DecodedAudio a; assert(decodeAuto(w.data(), w.size(), a));
            assert(a.sampleRate == 48000); expectNear(a, stereo, 2, 1e-6);
        }
    }
    { // wav 24 bit extensible PCM
        auto w = makeWav(2, 96000, 3, false, stereo, true);
        DecodedAudio a; assert(decodeAuto(w.data(), w.size(), a));
        assert(a.sampleRate == 96000); expectNear(a, stereo, 2, 1e-4);
    }
    for (int bytes : {2, 3, 4}) { // aiff
        auto w = makeAiff(2, 44100, bytes, stereo);
        DecodedAudio a; assert(decodeAuto(w.data(), w.size(), a));
        assert(a.sampleRate == 44100); expectNear(a, stereo, 2, 1e-3);
    }
    { // aiff mono 48k
        auto w = makeAiff(1, 48000, 2, mono);
        DecodedAudio a; assert(decodeAuto(w.data(), w.size(), a));
        assert(a.sampleRate == 48000); expectNear(a, mono, 1, 1e-3);
    }
    { // aifc NONE i sowt (little-endian)
        for (const char* comp : {"NONE", "sowt"}) {
            auto w = makeAiff(2, 44100, 2, stereo, true, comp);
            DecodedAudio a; assert(decodeAuto(w.data(), w.size(), a));
            expectNear(a, stereo, 2, 1e-3);
        }
    }
    { // komprimirani aifc se odbija
        auto w = makeAiff(2, 44100, 2, stereo, true, "ima4");
        DecodedAudio a; assert(!decodeAuto(w.data(), w.size(), a));
    }
    { // smeće / mp3 zaglavlje / prekratko
        Bytes junk = {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        DecodedAudio a; assert(!decodeAuto(junk.data(), junk.size(), a));
        assert(!decodeAuto(junk.data(), 3, a));
    }
    { // odsječen wav ne smije srušiti dekoder
        auto w = makeWav(2, 44100, 2, false, stereo);
        for (size_t cut = 0; cut < w.size(); ++cut) {
            DecodedAudio a; decodeAuto(w.data(), cut, a);
        }
        auto x = makeAiff(2, 44100, 2, stereo);
        for (size_t cut = 0; cut < x.size(); ++cut) {
            DecodedAudio a; decodeAuto(x.data(), cut, a);
        }
    }
    std::printf("DEKODER: SVI TESTOVI PROSLI\n");
    return 0;
}
