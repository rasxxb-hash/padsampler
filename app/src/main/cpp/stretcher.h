// Offline time-stretch (tempo bez promjene visine tona) preko Rubber Band biblioteke.
// Čisti C++ (bez Androida), testira se na hostu. NIJE za audio nit: poziva se iz pozadinske niti.
#pragma once

#include <cstdint>
#include <vector>

namespace padsampler {

// Razvlači interleaved stereo float zapis na tačno outFrames frameova (isti sample rate).
// outFrames > inFrames = sporije, outFrames < inFrames = brže. Visina tona ostaje ista.
// Vraća false ako su parametri neispravni ili je Rubber Band prijavio grešku.
bool stretchStereo(const float* in, int64_t inFrames, int sampleRate, int64_t outFrames,
                   std::vector<float>& out);

}  // namespace padsampler
