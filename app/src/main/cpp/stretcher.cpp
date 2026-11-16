#include "stretcher.h"

#include <algorithm>
#include <cmath>
#include <exception>

#include "rubberband-lib/rubberband/RubberBandStretcher.h"

namespace padsampler {

bool stretchStereo(const float* in, int64_t inFrames, int sampleRate, int64_t outFrames,
                   std::vector<float>& out) {
    if (in == nullptr || inFrames <= 0 || outFrames <= 0 || sampleRate <= 0) return false;

    const double ratio = static_cast<double>(outFrames) / static_cast<double>(inFrames);
    if (ratio < 0.05 || ratio > 20.0) return false;

    try {
        using RB = RubberBand::RubberBandStretcher;
        // Offline, R2 "faster" motor (dovoljno dobar za loopove i znatno brži od R3), kanali zajedno
        // da stereo slika ostane stabilna. Transienti ostaju oštri (crisp), što je dobro za bubnjeve.
        RB rb(static_cast<size_t>(sampleRate), 2,
              RB::OptionProcessOffline | RB::OptionEngineFaster | RB::OptionTransientsCrisp |
                  RB::OptionThreadingNever | RB::OptionChannelsTogether,
              ratio, 1.0);
        rb.setExpectedInputDuration(static_cast<size_t>(inFrames));

        constexpr int64_t kBlock = 4096;
        std::vector<float> left(kBlock), right(kBlock);

        auto fill = [&](int64_t start, int64_t n) {
            for (int64_t i = 0; i < n; ++i) {
                left[static_cast<size_t>(i)] = in[2 * (start + i)];
                right[static_cast<size_t>(i)] = in[2 * (start + i) + 1];
            }
        };

        // Prolaz 1: analiza
        for (int64_t pos = 0; pos < inFrames; pos += kBlock) {
            const int64_t n = std::min(kBlock, inFrames - pos);
            fill(pos, n);
            const float* ptrs[2] = {left.data(), right.data()};
            rb.study(ptrs, static_cast<size_t>(n), pos + n >= inFrames);
        }

        // Prolaz 2: obrada, uz stalno preuzimanje izlaza
        std::vector<float> outL, outR;
        outL.reserve(static_cast<size_t>(outFrames) + 8192);
        outR.reserve(static_cast<size_t>(outFrames) + 8192);
        std::vector<float> rl(kBlock), rr(kBlock);

        auto drain = [&]() {
            for (;;) {
                const int avail = rb.available();
                if (avail <= 0) break;
                const size_t n = std::min<size_t>(static_cast<size_t>(avail), static_cast<size_t>(kBlock));
                float* optrs[2] = {rl.data(), rr.data()};
                const size_t got = rb.retrieve(optrs, n);
                if (got == 0) break;
                outL.insert(outL.end(), rl.begin(), rl.begin() + static_cast<long>(got));
                outR.insert(outR.end(), rr.begin(), rr.begin() + static_cast<long>(got));
            }
        };

        for (int64_t pos = 0; pos < inFrames; pos += kBlock) {
            const int64_t n = std::min(kBlock, inFrames - pos);
            fill(pos, n);
            const float* ptrs[2] = {left.data(), right.data()};
            rb.process(ptrs, static_cast<size_t>(n), pos + n >= inFrames);
            drain();
        }
        drain();

        // Dužina se tačno podesi na outFrames (razlika je tek nekoliko frameova): višak se odreže, manjak popuni tišinom.
        out.assign(static_cast<size_t>(outFrames) * 2, 0.0f);
        const size_t have = std::min(outL.size(), static_cast<size_t>(outFrames));
        for (size_t i = 0; i < have; ++i) {
            out[2 * i] = outL[i];
            out[2 * i + 1] = outR[i];
        }
        return true;
    } catch (const std::exception&) {
        return false;
    } catch (...) {
        return false;
    }
}

}  // namespace padsampler
