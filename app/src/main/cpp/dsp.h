// SARI DUB native DSP core: pure C++17, no JNI, no Android dependencies (host-testable).
#pragma once
#include <atomic>
#include <string>
#include <vector>

namespace sari {

extern std::atomic<bool> g_cancel;  // cooperative cancellation flag

constexpr int kOk = 0;
constexpr int kErrIo = -1;
constexpr int kCancelled = -2;
constexpr int kErrFormat = -3;

// Streaming s16le interleaved file -> mono s16le file at outRate. Returns samples written or negative error.
long long resampleFile(const std::string& in, int inRate, int inCh, const std::string& out, int outRate);

// Energy VAD over a raw mono s16le file. segs = [start0,end0,start1,end1,...] in seconds.
int vad(const std::string& pcm, int rate, int minSpeechMs, int minSilenceMs, std::vector<double>& segs);

// Voice analysis of [startSec,endSec) of a raw mono s16le file.
// out[0]=median pitch Hz (0 if unvoiced) [1]=p10 pitch [2]=p90 pitch [3]=rms (0..1)
// [4]=zero-crossing rate (brightness proxy) [5]=voiced ratio [6]=activity ratio [7]=frames analysed
constexpr int kProfileSize = 8;
int voiceProfile(const std::string& pcm, int rate, double startSec, double endSec, float* out);

// Reads a 16-bit WAV, resamples to `rate`, trims leading/trailing silence, and if targetMs>0
// time-scales (pitch preserving, WSOLA) so duration approaches targetMs, with the ratio clamped to
// [minRatio,maxRatio]. Writes mono WAV. Returns resulting duration in ms, or negative error.
int stretchWav(const std::string& in, const std::string& out, int rate, int targetMs, float minRatio, float maxRatio);

// Mixes dubbed segments over the original background for [startSec,endSec) with adaptive ducking and
// a soft limiter. Writes a mono WAV of exactly the chunk length. Returns 0 or negative error.
int mixChunk(const std::string& origPcm, int rate, double startSec, double endSec,
             const std::vector<double>& segStartSec, const std::vector<std::string>& segWavs,
             const std::string& outWav, float duckGain);

int wavDurationMs(const std::string& path);

// Cuts [startSec,endSec) from a raw mono s16le file, resamples to outRate, writes a mono WAV (ASR upload unit).
int extractWav(const std::string& pcm, int rate, double startSec, double endSec, int outRate, const std::string& outWav);

// Subtitle/speech alignment on 0/1 activity bins. Finds lag in [minLag,maxLag] maximising overlap of
// sub[i] with speech[i+lag] for i in [from,to). out = {lag, precision (overlap/subActive), speechDensity}.
int bestLag(const uint8_t* speech, int n, const uint8_t* sub, int m, int from, int to, int minLag, int maxLag, float* out);

}  // namespace sari
