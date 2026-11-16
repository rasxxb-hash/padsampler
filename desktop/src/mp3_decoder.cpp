// MP3 dekoder za računar (dr_mp3, public domain). Na Androidu mp3 dekodira MediaCodec.
#define DR_MP3_IMPLEMENTATION
#define DR_MP3_NO_STDIO
#include "dr_mp3.h"

#include <cstring>
#include "decoder.h"

namespace padsampler {

bool decodeMp3(const uint8_t* data, size_t size, DecodedAudio& out) {
    drmp3_config cfg;
    drmp3_uint64 frames = 0;
    float* pcm = drmp3_open_memory_and_read_pcm_frames_f32(data, size, &cfg, &frames, nullptr);
    if (pcm == nullptr || frames == 0 || cfg.channels == 0 || cfg.sampleRate == 0) {
        if (pcm) drmp3_free(pcm, nullptr);
        return false;
    }
    out.frames = static_cast<int64_t>(frames);
    out.sampleRate = static_cast<int>(cfg.sampleRate);
    out.data.resize(static_cast<size_t>(frames) * 2);
    const size_t ch = cfg.channels;
    for (size_t i = 0; i < frames; i++) {
        const float l = pcm[i * ch];
        out.data[i * 2] = l;
        out.data[i * 2 + 1] = ch > 1 ? pcm[i * ch + 1] : l;  // mono se duplicira
    }
    drmp3_free(pcm, nullptr);
    return true;
}

}  // namespace padsampler
