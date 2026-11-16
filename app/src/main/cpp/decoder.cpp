#include "decoder.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace padsampler {

namespace {

// Ograničenje da jedan sample ne potroši svu memoriju (~5 min stereo @ 48 kHz = 115 MB float).
constexpr int64_t kMaxFrames = 48000LL * 60 * 5;

uint16_t rd16le(const uint8_t* p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }
uint32_t rd32le(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}
uint16_t rd16be(const uint8_t* p) { return static_cast<uint16_t>((p[0] << 8) | p[1]); }
uint32_t rd32be(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
}

bool tag(const uint8_t* p, const char* t) { return std::memcmp(p, t, 4) == 0; }

float readSample(const uint8_t* p, int bytes, bool bigEndian, bool isFloat, bool unsigned8) {
    if (isFloat) {
        if (bytes == 4) {
            const uint32_t u = bigEndian ? rd32be(p) : rd32le(p);
            float f;
            std::memcpy(&f, &u, 4);
            return f;
        }
        if (bytes == 8) {
            uint64_t u = 0;
            if (bigEndian) {
                for (int i = 0; i < 8; ++i) u = (u << 8) | p[i];
            } else {
                for (int i = 7; i >= 0; --i) u = (u << 8) | p[i];
            }
            double d;
            std::memcpy(&d, &u, 8);
            return static_cast<float>(d);
        }
        return 0.0f;
    }
    switch (bytes) {
        case 1:
            return unsigned8 ? (static_cast<int>(p[0]) - 128) / 128.0f
                             : static_cast<int8_t>(p[0]) / 128.0f;
        case 2: {
            const int16_t v = static_cast<int16_t>(bigEndian ? rd16be(p) : rd16le(p));
            return v / 32768.0f;
        }
        case 3: {
            int32_t v = bigEndian ? ((p[0] << 16) | (p[1] << 8) | p[2])
                                  : ((p[2] << 16) | (p[1] << 8) | p[0]);
            if (v >= 0x800000) v -= 0x1000000;
            return v / 8388608.0f;
        }
        case 4: {
            const int32_t v = static_cast<int32_t>(bigEndian ? rd32be(p) : rd32le(p));
            return static_cast<float>(v / 2147483648.0);
        }
        default:
            return 0.0f;
    }
}

bool convert(const uint8_t* data, size_t dataBytes, int channels, int bytesPerSample,
             bool bigEndian, bool isFloat, bool unsigned8, int rate, DecodedAudio& out) {
    if (channels < 1 || rate <= 0 || bytesPerSample < 1) return false;
    if (isFloat ? (bytesPerSample != 4 && bytesPerSample != 8) : bytesPerSample > 4) return false;

    const size_t frameSize = static_cast<size_t>(bytesPerSample) * channels;
    const int64_t frames = static_cast<int64_t>(dataBytes / frameSize);
    if (frames <= 0 || frames > kMaxFrames) return false;

    out.data.assign(static_cast<size_t>(frames) * 2, 0.0f);
    for (int64_t f = 0; f < frames; ++f) {
        const uint8_t* fp = data + static_cast<size_t>(f) * frameSize;
        const float l = readSample(fp, bytesPerSample, bigEndian, isFloat, unsigned8);
        const float r = channels > 1
                            ? readSample(fp + bytesPerSample, bytesPerSample, bigEndian, isFloat,
                                         unsigned8)
                            : l;
        out.data[2 * f] = l;
        out.data[2 * f + 1] = r;
    }
    out.frames = frames;
    out.sampleRate = rate;
    return true;
}

double readExtended80(const uint8_t* p) {
    const int exponent = ((p[0] & 0x7F) << 8) | p[1];
    uint64_t mantissa = 0;
    for (int i = 0; i < 8; ++i) mantissa = (mantissa << 8) | p[2 + i];
    if (exponent == 0 && mantissa == 0) return 0.0;
    const double v = std::ldexp(static_cast<double>(mantissa), exponent - 16383 - 63);
    return (p[0] & 0x80) ? -v : v;
}

}  // namespace

bool decodeWav(const uint8_t* d, size_t n, DecodedAudio& out) {
    if (n < 12 || !tag(d, "RIFF") || !tag(d + 8, "WAVE")) return false;

    size_t pos = 12;
    bool haveFmt = false;
    uint16_t fmtTag = 0, channels = 0, bits = 0;
    uint32_t rate = 0;

    while (pos + 8 <= n) {
        const uint8_t* c = d + pos;
        const uint32_t sz = rd32le(c + 4);
        const size_t body = pos + 8;
        const size_t avail = n - body;
        const size_t csz = sz > avail ? avail : sz;

        if (tag(c, "fmt ")) {
            if (csz < 16) return false;
            fmtTag = rd16le(d + body);
            channels = rd16le(d + body + 2);
            rate = rd32le(d + body + 4);
            bits = rd16le(d + body + 14);
            if (fmtTag == 0xFFFE) {  // WAVE_FORMAT_EXTENSIBLE: stvarni format je u subformatu
                if (csz < 26) return false;
                fmtTag = rd16le(d + body + 24);
            }
            haveFmt = true;
        } else if (tag(c, "data")) {
            if (!haveFmt) return false;
            if (fmtTag != 1 && fmtTag != 3) return false;  // samo PCM i float
            const int bytes = (bits + 7) / 8;
            return convert(d + body, csz, channels, bytes, false, fmtTag == 3, bits == 8,
                           static_cast<int>(rate), out);
        }
        pos = body + csz + (csz & 1);  // chunkovi su poravnati na paran broj bajtova
    }
    return false;
}

bool decodeAiff(const uint8_t* d, size_t n, DecodedAudio& out) {
    if (n < 12 || !tag(d, "FORM")) return false;
    const bool aifc = tag(d + 8, "AIFC");
    if (!aifc && !tag(d + 8, "AIFF")) return false;

    size_t pos = 12;
    bool haveComm = false;
    int channels = 0, bits = 0, rate = 0;
    int64_t numFrames = 0;
    bool littleEndian = false, isFloat = false;

    while (pos + 8 <= n) {
        const uint8_t* c = d + pos;
        const uint32_t sz = rd32be(c + 4);
        const size_t body = pos + 8;
        const size_t avail = n - body;
        const size_t csz = sz > avail ? avail : sz;

        if (tag(c, "COMM")) {
            if (csz < 18) return false;
            channels = rd16be(d + body);
            numFrames = rd32be(d + body + 2);
            bits = rd16be(d + body + 6);
            rate = static_cast<int>(std::lround(readExtended80(d + body + 8)));
            if (aifc) {
                if (csz < 22) return false;
                const uint8_t* ct = d + body + 18;
                if (tag(ct, "NONE") || tag(ct, "twos")) {
                    // big-endian PCM
                } else if (tag(ct, "sowt")) {
                    littleEndian = true;
                } else if (tag(ct, "fl32") || tag(ct, "FL32")) {
                    isFloat = true;
                    bits = 32;
                } else if (tag(ct, "fl64") || tag(ct, "FL64")) {
                    isFloat = true;
                    bits = 64;
                } else {
                    return false;  // komprimirani AIFC (ima4, ulaw...) nije podržan
                }
            }
            haveComm = true;
        } else if (tag(c, "SSND")) {
            if (!haveComm || csz < 8) return false;
            const uint32_t offset = rd32be(d + body);
            const size_t start = body + 8 + offset;
            if (start > body + csz) return false;
            size_t bytes = body + csz - start;

            const int bytesPerSample = (bits + 7) / 8;
            if (bytesPerSample < 1 || channels < 1) return false;
            const size_t frameSize = static_cast<size_t>(bytesPerSample) * channels;
            const size_t declared = static_cast<size_t>(numFrames) * frameSize;
            if (numFrames > 0 && bytes > declared) bytes = declared;

            return convert(d + start, bytes, channels, bytesPerSample, !littleEndian, isFloat,
                           false, rate, out);
        }
        pos = body + csz + (csz & 1);
    }
    return false;
}

#ifdef PADSAMPLER_MP3
// Samo na računaru (desktop/src/mp3_decoder.cpp); Android mp3 dekodira MediaCodec.
bool decodeMp3(const uint8_t* data, size_t size, DecodedAudio& out);
#endif

bool decodeAuto(const uint8_t* d, size_t n, DecodedAudio& out) {
    if (n < 12) return false;
    if (tag(d, "RIFF")) return decodeWav(d, n, out);
    if (tag(d, "FORM")) return decodeAiff(d, n, out);
#ifdef PADSAMPLER_MP3
    return decodeMp3(d, n, out);
#else
    return false;
#endif
}

}  // namespace padsampler
