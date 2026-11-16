// Dekoder za WAV i AIFF (PCM 8/16/24/32 bit i float). Čisti C++, testira se na hostu.
// MP3 i ostalo se dekodira na Kotlin strani (MediaCodec).
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace padsampler {

struct DecodedAudio {
    std::vector<float> data;  // interleaved stereo; mono se duplicira, >2 kanala = prva dva
    int64_t frames = 0;
    int sampleRate = 0;
};

bool decodeWav(const uint8_t* data, size_t size, DecodedAudio& out);
bool decodeAiff(const uint8_t* data, size_t size, DecodedAudio& out);

// Prepoznaje format po zaglavlju (RIFF/WAVE ili FORM/AIFF/AIFC).
bool decodeAuto(const uint8_t* data, size_t size, DecodedAudio& out);

}  // namespace padsampler
