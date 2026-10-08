#include <cmath>
static constexpr double kPi = 3.14159265358979323846;
#include <cstdio>
#include <cstdlib>
#include <vector>
#include "../app/src/main/cpp/dsp.h"
using namespace sari;
static int fails = 0;
#define CHECK(c, ...) do { if (!(c)) { printf("FAIL: "); printf(__VA_ARGS__); printf("\n"); fails++; } else { printf("ok:   "); printf(__VA_ARGS__); printf("\n"); } } while (0)

static void voiced(std::vector<int16_t>& x, int rate, double t0, double t1, double f0, float amp) {
  for (long i = (long)(t0 * rate); i < (long)(t1 * rate) && i < (long)x.size(); i++) {
    double t = (double)i / rate, s = 0;
    for (int h = 1; h <= 8; h++) s += std::sin(2 * kPi * f0 * h * t) / h;
    double env = 0.5 + 0.5 * std::sin(2 * kPi * 3 * t);  // syllable-like modulation
    x[i] = (int16_t)(amp * (0.3 + 0.7 * env) * s);
  }
}
static void wpcm(const char* p, const std::vector<int16_t>& x) { FILE* f = fopen(p, "wb"); fwrite(x.data(), 2, x.size(), f); fclose(f); }
static void wwav(const char* p, const std::vector<int16_t>& x, int rate) {
  FILE* f = fopen(p, "wb"); unsigned char h[44] = {'R','I','F','F',0,0,0,0,'W','A','V','E','f','m','t',' ',16,0,0,0,1,0,1,0};
  auto u32 = [&](int o, uint32_t v) { for (int i = 0; i < 4; i++) h[o + i] = (v >> (8 * i)) & 255; };
  u32(4, 36 + x.size() * 2); u32(24, rate); u32(28, rate * 2); h[32] = 2; h[34] = 16; h[36]='d';h[37]='a';h[38]='t';h[39]='a'; u32(40, x.size() * 2);
  fwrite(h, 1, 44, f); fwrite(x.data(), 2, x.size(), f); fclose(f);
}

int main() {
  const int R = 24000;
  srand(1);
  std::vector<int16_t> x(R * 10);
  for (auto& s : x) s = (int16_t)((rand() % 60) - 30);  // noise floor
  voiced(x, R, 2.0, 5.0, 150, 6000);
  voiced(x, R, 6.0, 9.0, 220, 6000);
  wpcm("/tmp/t.pcm", x);

  std::vector<double> seg;
  CHECK(vad("/tmp/t.pcm", R, 200, 300, seg) == kOk, "vad runs");
  CHECK(seg.size() == 4, "vad finds 2 speech regions (got %zu values)", seg.size());
  if (seg.size() == 4) CHECK(std::fabs(seg[0] - 2.0) < 0.25 && std::fabs(seg[1] - 5.0) < 0.25 && std::fabs(seg[2] - 6.0) < 0.25, "vad boundaries %.2f-%.2f, %.2f-%.2f", seg[0], seg[1], seg[2], seg[3]);

  float p[kProfileSize];
  voiceProfile("/tmp/t.pcm", R, 2.2, 4.8, p);
  CHECK(std::fabs(p[0] - 150) < 8, "pitch speaker A = %.1f Hz (expect 150)", p[0]);
  voiceProfile("/tmp/t.pcm", R, 6.2, 8.8, p);
  CHECK(std::fabs(p[0] - 220) < 12, "pitch speaker B = %.1f Hz (expect 220)", p[0]);
  voiceProfile("/tmp/t.pcm", R, 0.0, 1.8, p);
  CHECK(p[5] < 0.1, "noise has ~no voiced frames (%.2f)", p[5]);

  // resample: stereo 48k 2 s -> mono 24k
  std::vector<int16_t> st(48000 * 2 * 2);
  for (size_t i = 0; i < st.size() / 2; i++) { int16_t v = (int16_t)(8000 * std::sin(2 * kPi * 440 * i / 48000.0)); st[2*i] = v; st[2*i+1] = v; }
  wpcm("/tmp/st.pcm", st);
  long long n = resampleFile("/tmp/st.pcm", 48000, 2, "/tmp/rs.pcm", 24000);
  CHECK(std::llabs(n - 48000) <= 2, "resample 48k stereo->24k mono: %lld samples (expect 48000)", n);

  // stretch: 2 s of speech-like audio with silence padding, compress to 1.5 s
  std::vector<int16_t> w((int)(R * 3.0), 0);
  voiced(w, R, 0.5, 2.5, 180, 7000);
  wwav("/tmp/in.wav", w, R);
  int nat = stretchWav("/tmp/in.wav", "/tmp/nat.wav", R, -1, 1, 1);
  CHECK(nat > 1950 && nat < 2200, "trim-only keeps speech: %d ms (expect ~2000+pad)", nat);
  int ms = stretchWav("/tmp/in.wav", "/tmp/st.wav", R, 1500, 0.75f, 1.f);
  CHECK(std::abs(ms - 1500) < 80, "stretch to 1500 ms -> %d ms", ms);
  CHECK(wavDurationMs("/tmp/st.wav") == ms, "wav header matches duration");
  voiceProfile("/tmp/st.wav", R, 0, 1.4, p);
  CHECK(std::fabs(p[0] - 180) < 12, "pitch preserved after stretch: %.1f Hz (expect 180)", p[0]);
  int clamp = stretchWav("/tmp/in.wav", "/tmp/cl.wav", R, 500, 0.75f, 1.f);
  CHECK(clamp > nat * 0.7 && clamp < nat * 0.8, "extreme target clamped to 0.75x: %d ms of %d", clamp, nat);

  // mix: background = constant tone 10 s; dub segment 3.0..4.5 s inside chunk starting 0
  std::vector<int16_t> bgm(R * 10);
  for (size_t i = 0; i < bgm.size(); i++) bgm[i] = (int16_t)(8000 * std::sin(2 * kPi * 100 * i / (double)R));
  wpcm("/tmp/bg.pcm", bgm);
  CHECK(mixChunk("/tmp/bg.pcm", R, 0, 8, {3.0}, {"/tmp/st.wav"}, "/tmp/mix.wav", 0.2f) == kOk, "mix runs");
  CHECK(wavDurationMs("/tmp/mix.wav") == 8000, "mix length exactly 8000 ms (%d)", wavDurationMs("/tmp/mix.wav"));
  // measure bg level during dub (3.3..3.5 s, find rms of mix minus nothing) vs outside (1..2 s)
  FILE* f = fopen("/tmp/mix.wav", "rb"); fseek(f, 44, SEEK_SET); std::vector<int16_t> mx(R * 8); if (fread(mx.data(), 2, mx.size(), f) == 0) return 2; fclose(f);
  auto rms = [&](double a, double b) { double s = 0; long c = 0; for (long i = (long)(a * R); i < (long)(b * R); i++) { s += (double)mx[i] * mx[i]; c++; } return std::sqrt(s / c); };
  double before = rms(1, 2), after = rms(6, 7), during = rms(3.6, 4.0);
  CHECK(std::fabs(before - after) / before < 0.02, "background restored after dub (%.0f vs %.0f)", before, after);
  CHECK(during > 0, "dub audible during segment (%.0f)", during);
  int peak = 0; for (auto v : mx) peak = std::max(peak, std::abs((int)v));
  CHECK(peak < 32768, "no hard clipping (peak %d)", peak);
  // extractWav: 24k -> 16k, 2 s window
  CHECK(extractWav("/tmp/t.pcm", R, 2.0, 4.0, 16000, "/tmp/e16.wav") == 2000, "extractWav 2 s @16k");
  CHECK(wavDurationMs("/tmp/e16.wav") == 2000, "extractWav header duration");
  // bestLag: speech bins vs subtitle bins shifted by +37 bins with 5% flipped
  {
    std::vector<uint8_t> sp(20000, 0), sb(20000, 0);
    int i = 0; srand(7);
    while (i < 19000) { int on = 5 + rand() % 40, off = 5 + rand() % 60; for (int k = 0; k < on && i < 19000; k++) sp[i++] = 1; i += off; }
    for (int k = 0; k < 20000; k++) { int s = k + 37; sb[k] = (s < 20000 ? sp[s] : 0); if (rand() % 20 == 0) sb[k] ^= 1; }
    // sub[k] matches speech[k+37]  =>  lag +37
    float o[3];
    CHECK(bestLag(sp.data(), 20000, sb.data(), 20000, 0, 20000, -600, 600, o) == kOk, "bestLag runs");
    CHECK((int)o[0] == 37, "bestLag recovers lag %d (expect 37), precision %.2f vs density %.2f", (int)o[0], o[1], o[2]);
    CHECK(o[1] > 0.85f, "aligned precision high (%.2f)", o[1]);
    std::vector<uint8_t> rnd(20000); for (auto& v : rnd) v = rand() % 2;
    bestLag(sp.data(), 20000, rnd.data(), 20000, 0, 20000, -600, 600, o);
    CHECK(o[1] < 0.8f, "unrelated subtitle gets low precision (%.2f)", o[1]);
  }
  // cancellation
  g_cancel = true;
  CHECK(resampleFile("/tmp/st.pcm", 48000, 2, "/tmp/x.pcm", 24000) == kCancelled, "cancellation honoured");
  g_cancel = false;
  printf("\n%s (%d failures)\n", fails ? "FAILED" : "ALL PASSED", fails);
  return fails ? 1 : 0;
}
