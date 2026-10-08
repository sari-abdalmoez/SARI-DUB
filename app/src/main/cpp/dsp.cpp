#include "dsp.h"

namespace {
constexpr double kPi = 3.14159265358979323846;
}  // namespace

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>

namespace sari {

std::atomic<bool> g_cancel{false};

namespace {

struct FileCloser {
  void operator()(FILE* f) const {
    if (f) fclose(f);
  }
};
using File = std::unique_ptr<FILE, FileCloser>;
File openFile(const std::string& p, const char* m) { return File(fopen(p.c_str(), m)); }

inline int16_t clip16(float v) {
  if (v > 32767.f) v = 32767.f;
  if (v < -32768.f) v = -32768.f;
  return static_cast<int16_t>(lrintf(v));
}

struct WavInfo {
  int rate = 0, ch = 0, bits = 0;
  long dataOff = 0;
  long long dataBytes = 0;
};

bool readWavHeader(FILE* f, WavInfo& w) {
  unsigned char h[12];
  if (fread(h, 1, 12, f) != 12) return false;
  if (memcmp(h, "RIFF", 4) || memcmp(h + 8, "WAVE", 4)) return false;
  fseek(f, 0, SEEK_END);
  long total = ftell(f);
  fseek(f, 12, SEEK_SET);
  bool haveFmt = false;
  for (;;) {
    unsigned char c[8];
    if (fread(c, 1, 8, f) != 8) return false;
    uint32_t sz = c[4] | (c[5] << 8) | (c[6] << 16) | (static_cast<uint32_t>(c[7]) << 24);
    if (!memcmp(c, "fmt ", 4)) {
      unsigned char fm[16];
      if (sz < 16 || fread(fm, 1, 16, f) != 16) return false;
      int fmt = fm[0] | (fm[1] << 8);
      w.ch = fm[2] | (fm[3] << 8);
      w.rate = static_cast<int>(fm[4] | (fm[5] << 8) | (fm[6] << 16) | (static_cast<uint32_t>(fm[7]) << 24));
      w.bits = fm[14] | (fm[15] << 8);
      if (fmt != 1 && fmt != 0xFFFE) return false;
      haveFmt = true;
      long skip = static_cast<long>(sz) - 16 + (sz & 1);
      if (skip > 0) fseek(f, skip, SEEK_CUR);
    } else if (!memcmp(c, "data", 4)) {
      if (!haveFmt) return false;
      w.dataOff = ftell(f);
      long long rem = total - w.dataOff;
      w.dataBytes = (static_cast<long long>(sz) > rem) ? rem : sz;
      return true;
    } else {
      fseek(f, static_cast<long>(sz + (sz & 1)), SEEK_CUR);
    }
  }
}

void writeWavHeader(FILE* f, int rate, long long dataBytes) {
  auto u32 = [](unsigned char* p, uint32_t v) {
    p[0] = v & 255; p[1] = (v >> 8) & 255; p[2] = (v >> 16) & 255; p[3] = (v >> 24) & 255;
  };
  unsigned char h[44];
  memcpy(h, "RIFF", 4); u32(h + 4, static_cast<uint32_t>(36 + dataBytes));
  memcpy(h + 8, "WAVEfmt ", 8); u32(h + 16, 16);
  h[20] = 1; h[21] = 0; h[22] = 1; h[23] = 0;
  u32(h + 24, rate); u32(h + 28, rate * 2);
  h[32] = 2; h[33] = 0; h[34] = 16; h[35] = 0;
  memcpy(h + 36, "data", 4); u32(h + 40, static_cast<uint32_t>(dataBytes));
  fwrite(h, 1, 44, f);
}

bool readWavMono(const std::string& path, std::vector<float>& out, int& rate) {
  File f = openFile(path, "rb");
  if (!f) return false;
  WavInfo w;
  if (!readWavHeader(f.get(), w) || w.bits != 16 || w.ch < 1) return false;
  fseek(f.get(), w.dataOff, SEEK_SET);
  size_t frames = static_cast<size_t>(w.dataBytes / (2 * w.ch));
  std::vector<int16_t> raw(frames * w.ch);
  size_t got = fread(raw.data(), 2 * w.ch, frames, f.get());
  out.resize(got);
  for (size_t i = 0; i < got; i++) {
    float s = 0;
    for (int c = 0; c < w.ch; c++) s += raw[i * w.ch + c];
    out[i] = s / w.ch;
  }
  rate = w.rate;
  return true;
}

void resampleVec(const std::vector<float>& in, int r0, int r1, std::vector<float>& out) {
  if (r0 == r1 || in.size() < 2) { out = in; return; }
  size_t n = static_cast<size_t>(static_cast<double>(in.size()) * r1 / r0);
  out.resize(n);
  double step = static_cast<double>(r0) / r1;
  for (size_t i = 0; i < n; i++) {
    double p = i * step;
    size_t k = static_cast<size_t>(p);
    if (k + 1 >= in.size()) { out[i] = in.back(); continue; }
    float fr = static_cast<float>(p - k);
    out[i] = in[k] * (1.f - fr) + in[k + 1] * fr;
  }
}

void trimSilence(std::vector<float>& x, int rate) {
  float peak = 0;
  for (float v : x) peak = std::max(peak, std::fabs(v));
  if (peak < 1.f) { x.clear(); return; }
  float thr = std::max(60.f, 0.02f * peak);
  size_t a = 0, b = x.size();
  while (a < b && std::fabs(x[a]) < thr) a++;
  while (b > a && std::fabs(x[b - 1]) < thr) b--;
  size_t pad = static_cast<size_t>(rate * 0.03);
  a = a > pad ? a - pad : 0;
  b = std::min(x.size(), b + pad);
  x = std::vector<float>(x.begin() + a, x.begin() + b);
}

// WSOLA time-scale: output length = input length * r, pitch preserved.
void wsola(const std::vector<float>& in, std::vector<float>& out, double r, int rate) {
  const long N = (static_cast<long>(rate * 0.032)) & ~1L;
  const long Hs = N / 2;
  const long tol = static_cast<long>(rate * 0.012);
  const long inLen = static_cast<long>(in.size());
  const long outLen = static_cast<long>(inLen * r);
  if (inLen < 3 * N || outLen < 3 * N) {  // too short for WSOLA: plain interpolation
    out.resize(std::max(1L, outLen));
    for (long i = 0; i < static_cast<long>(out.size()); i++) {
      double p = i / r;
      long k = static_cast<long>(p);
      if (k + 1 >= inLen) { out[i] = in.empty() ? 0.f : in.back(); continue; }
      float fr = static_cast<float>(p - k);
      out[i] = in[k] * (1.f - fr) + in[k + 1] * fr;
    }
    return;
  }
  std::vector<float> win(N);
  for (long i = 0; i < N; i++) win[i] = 0.5f - 0.5f * std::cos(2.0 * kPi * i / N);
  std::vector<float> acc(outLen + 2 * N, 0.f), norm(outLen + 2 * N, 0.f);
  const double Ha = Hs / r;
  long prev = 0;
  for (long k = 0; k * Hs < outLen; k++) {
    long target = static_cast<long>(k * Ha);
    long pos;
    long maxPos = inLen - N;
    if (k == 0) {
      pos = 0;
    } else {
      long nat = prev + Hs;
      long lo = std::max(0L, target - tol), hi = std::min(maxPos, target + tol);
      if (nat > maxPos || hi < lo) {
        pos = std::min(std::max(target, 0L), maxPos);
      } else {
        double best = -1e300;
        pos = lo;
        for (long p = lo; p <= hi; p += 2) {
          double c = 0, e = 1e-3;
          for (long i = 0; i < N; i += 2) {
            float a = in[p + i], b = in[nat + i];
            c += a * b;
            e += static_cast<double>(a) * a;
          }
          double score = c / std::sqrt(e);
          if (score > best) { best = score; pos = p; }
        }
      }
    }
    long o = k * Hs;
    for (long i = 0; i < N; i++) {
      acc[o + i] += win[i] * in[pos + i];
      norm[o + i] += win[i];
    }
    prev = pos;
  }
  out.resize(outLen);
  for (long i = 0; i < outLen; i++) out[i] = norm[i] > 1e-3f ? acc[i] / norm[i] : 0.f;
}

inline float softLimit(float x) {  // x normalised to +-1
  const float knee = 0.85f;
  float a = std::fabs(x);
  if (a <= knee) return x;
  float y = knee + (1.f - knee) * std::tanh((a - knee) / (1.f - knee));
  return x < 0 ? -y : y;
}

// Autocorrelation pitch on a decimated frame. Returns Hz or 0.
float framePitch(const float* x, int n, int rate, float& conf) {
  int lagMin = rate / 400, lagMax = std::min(rate / 70, n / 2 - 1);
  conf = 0;
  if (lagMax <= lagMin + 2) return 0;
  int m = n - lagMax;
  double e0 = 1e-6;
  for (int i = 0; i < m; i++) e0 += static_cast<double>(x[i]) * x[i];
  auto corr = [&](int lag) {
    double c = 0, el = 1e-6;
    for (int i = 0; i < m; i++) { c += static_cast<double>(x[i]) * x[i + lag]; el += static_cast<double>(x[i + lag]) * x[i + lag]; }
    return c / std::sqrt(e0 * el);
  };
  std::vector<double> r(lagMax + 2, 0.0);
  double rb = -1;
  for (int lag = lagMin; lag <= lagMax; lag++) {
    r[lag] = corr(lag);
    rb = std::max(rb, r[lag]);
  }
  // Pick the SHORTEST strong local maximum (avoids locking onto period multiples / sub-octaves).
  int best = 0;
  for (int lag = lagMin + 1; lag < lagMax; lag++) {
    if (r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1] && r[lag] >= 0.9 * rb) { best = lag; break; }
  }
  if (best == 0) return 0;
  rb = r[best];
  conf = static_cast<float>(rb);
  return rb > 0 ? static_cast<float>(rate) / best : 0.f;
}

float percentile(std::vector<float> v, float q) {
  if (v.empty()) return 0;
  size_t k = static_cast<size_t>(q * (v.size() - 1));
  std::nth_element(v.begin(), v.begin() + k, v.end());
  return v[k];
}

}  // namespace

long long resampleFile(const std::string& in, int inRate, int inCh, const std::string& out, int outRate) {
  if (inRate <= 0 || outRate <= 0 || inCh <= 0) return kErrFormat;
  File fi = openFile(in, "rb"), fo = openFile(out, "wb");
  if (!fi || !fo) return kErrIo;
  const size_t BLK = 16384;
  std::vector<int16_t> raw(BLK * inCh);
  std::vector<float> cur;
  std::vector<int16_t> ob;
  cur.reserve(BLK + 1);
  const double step = static_cast<double>(inRate) / outRate;
  double pos = 0;
  long long base = 0, total = 0;
  float carry = 0;
  bool hasCarry = false;
  for (;;) {
    if (g_cancel.load(std::memory_order_relaxed)) return kCancelled;
    size_t n = fread(raw.data(), sizeof(int16_t) * inCh, BLK, fi.get());
    if (n == 0) break;
    cur.clear();
    if (hasCarry) cur.push_back(carry);
    for (size_t i = 0; i < n; i++) {
      float s = 0;
      for (int c = 0; c < inCh; c++) s += raw[i * inCh + c];
      cur.push_back(s / inCh);
    }
    ob.clear();
    while (pos + 1 < static_cast<double>(base) + cur.size()) {
      size_t i = static_cast<size_t>(pos - base);
      float fr = static_cast<float>(pos - base - i);
      ob.push_back(clip16(cur[i] * (1.f - fr) + cur[i + 1] * fr));
      pos += step;
    }
    carry = cur.back();
    hasCarry = true;
    base += static_cast<long long>(cur.size()) - 1;
    if (!ob.empty() && fwrite(ob.data(), 2, ob.size(), fo.get()) != ob.size()) return kErrIo;
    total += static_cast<long long>(ob.size());
  }
  return total;
}

int vad(const std::string& pcm, int rate, int minSpeechMs, int minSilenceMs, std::vector<double>& segs) {
  segs.clear();
  File f = openFile(pcm, "rb");
  if (!f) return kErrIo;
  const int fl = rate / 50;  // 20 ms
  std::vector<int16_t> buf(fl);
  std::vector<float> e;
  for (;;) {
    if (g_cancel.load(std::memory_order_relaxed)) return kCancelled;
    size_t n = fread(buf.data(), 2, fl, f.get());
    if (n < static_cast<size_t>(fl)) break;
    double s = 0;
    for (int i = 0; i < fl; i++) s += static_cast<double>(buf[i]) * buf[i];
    e.push_back(static_cast<float>(std::sqrt(s / fl)));
  }
  if (e.empty()) return kOk;
  float noise = percentile(e, 0.10f), p95 = percentile(e, 0.95f);
  float thr = std::max({noise * 3.f, 0.04f * p95, 200.f});
  const int gapFrames = std::max(1, minSilenceMs / 20), minFrames = std::max(1, minSpeechMs / 20);
  long start = -1, last = -1;
  auto flush = [&]() {
    if (start >= 0 && (last - start + 1) >= minFrames)
      segs.push_back(std::max(0.0, start * 0.02 - 0.06)), segs.push_back((last + 1) * 0.02 + 0.06);
    start = -1;
  };
  for (long i = 0; i < static_cast<long>(e.size()); i++) {
    if (e[i] > thr) {
      if (start < 0) start = i;
      else if (i - last > gapFrames) { flush(); start = i; }
      last = i;
    }
  }
  flush();
  return kOk;
}

int voiceProfile(const std::string& pcm, int rate, double startSec, double endSec, float* out) {
  for (int i = 0; i < kProfileSize; i++) out[i] = 0;
  File f = openFile(pcm, "rb");
  if (!f) return kErrIo;
  long long s0 = static_cast<long long>(std::max(0.0, startSec) * rate);
  long long s1 = static_cast<long long>(std::min(endSec, startSec + 60.0) * rate);
  if (s1 <= s0) return kOk;
  if (fseeko(f.get(), static_cast<off_t>(s0 * 2), SEEK_SET) != 0) return kErrIo;
  std::vector<int16_t> raw(static_cast<size_t>(s1 - s0));
  size_t got = fread(raw.data(), 2, raw.size(), f.get());
  raw.resize(got);
  if (got < static_cast<size_t>(rate / 10)) return kOk;
  const int dec = std::max(1, rate / 8000);
  const int r2 = rate / dec;
  std::vector<float> x(got / dec);
  for (size_t i = 0; i < x.size(); i++) {
    float a = 0;
    for (int k = 0; k < dec; k++) a += raw[i * dec + k];
    x[i] = a / dec;
  }
  double sq = 0;
  long zc = 0;
  for (size_t i = 0; i < raw.size(); i++) {
    sq += static_cast<double>(raw[i]) * raw[i];
    if (i && ((raw[i] >= 0) != (raw[i - 1] >= 0))) zc++;
  }
  float rms = static_cast<float>(std::sqrt(sq / raw.size()));
  const int fl = static_cast<int>(0.040 * r2), hop = static_cast<int>(0.020 * r2);
  std::vector<float> pitches;
  long frames = 0, active = 0;
  float actThr = std::max(150.f, 0.3f * rms);
  for (long p = 0; p + fl <= static_cast<long>(x.size()); p += hop) {
    if (g_cancel.load(std::memory_order_relaxed)) return kCancelled;
    frames++;
    double fe = 0;
    for (int i = 0; i < fl; i++) fe += static_cast<double>(x[p + i]) * x[p + i];
    if (std::sqrt(fe / fl) < actThr) continue;
    active++;
    float conf;
    float hz = framePitch(&x[p], fl, r2, conf);
    if (conf > 0.45f && hz >= 70.f && hz <= 400.f) pitches.push_back(hz);
  }
  out[0] = percentile(pitches, 0.5f);
  out[1] = percentile(pitches, 0.1f);
  out[2] = percentile(pitches, 0.9f);
  out[3] = rms / 32768.f;
  out[4] = static_cast<float>(zc) / raw.size();
  out[5] = frames ? static_cast<float>(pitches.size()) / frames : 0.f;
  out[6] = frames ? static_cast<float>(active) / frames : 0.f;
  out[7] = static_cast<float>(frames);
  return kOk;
}

int stretchWav(const std::string& in, const std::string& out, int rate, int targetMs, float minRatio, float maxRatio) {
  std::vector<float> x, y;
  int r0 = 0;
  if (!readWavMono(in, x, r0)) return kErrFormat;
  if (r0 != rate) { std::vector<float> t; resampleVec(x, r0, rate, t); x.swap(t); }
  trimSilence(x, rate);
  if (x.empty()) return kErrFormat;
  int curMs = static_cast<int>(x.size() * 1000LL / rate);
  if (targetMs > 0 && curMs > 0) {
    double r = std::min<double>(maxRatio, std::max<double>(minRatio, static_cast<double>(targetMs) / curMs));
    if (std::fabs(r - 1.0) > 0.02) wsola(x, y, r, rate); else y = x;
  } else {
    y = x;
  }
  File fo = openFile(out, "wb");
  if (!fo) return kErrIo;
  writeWavHeader(fo.get(), rate, static_cast<long long>(y.size()) * 2);
  std::vector<int16_t> o(y.size());
  for (size_t i = 0; i < y.size(); i++) o[i] = clip16(y[i]);
  if (fwrite(o.data(), 2, o.size(), fo.get()) != o.size()) return kErrIo;
  return static_cast<int>(y.size() * 1000LL / rate);
}

int mixChunk(const std::string& origPcm, int rate, double startSec, double endSec,
             const std::vector<double>& segStartSec, const std::vector<std::string>& segWavs,
             const std::string& outWav, float duckGain) {
  if (endSec <= startSec || segStartSec.size() != segWavs.size()) return kErrFormat;
  struct Seg { long long start; std::vector<int16_t> pcm; long long end() const { return start + static_cast<long long>(pcm.size()); } };
  std::vector<Seg> segs;
  for (size_t i = 0; i < segWavs.size(); i++) {
    std::vector<float> x;
    int r0;
    if (!readWavMono(segWavs[i], x, r0)) continue;  // a missing segment simply stays original
    if (r0 != rate) { std::vector<float> t; resampleVec(x, r0, rate, t); x.swap(t); }
    Seg s;
    s.start = static_cast<long long>(std::llround((segStartSec[i] - startSec) * rate));
    s.pcm.resize(x.size());
    for (size_t k = 0; k < x.size(); k++) s.pcm[k] = clip16(x[k]);
    segs.push_back(std::move(s));
  }
  std::sort(segs.begin(), segs.end(), [](const Seg& a, const Seg& b) { return a.start < b.start; });

  const long long total = static_cast<long long>(std::llround((endSec - startSec) * rate));
  File fi = openFile(origPcm, "rb"), fo = openFile(outWav, "wb");
  if (!fi || !fo) return kErrIo;
  long long off = static_cast<long long>(std::llround(startSec * rate));
  if (fseeko(fi.get(), static_cast<off_t>(off * 2), SEEK_SET) != 0) return kErrIo;
  writeWavHeader(fo.get(), rate, total * 2);

  const int BL = std::max(1, rate / 100);  // 10 ms control block
  const long long look = BL * 2, hold = BL * 12;
  const float atk = 1.f - std::exp(-0.01f / 0.03f), rel = 1.f - std::exp(-0.01f / 0.35f);
  std::vector<int16_t> bg(BL), ob(BL);
  float g = 1.f;
  size_t first = 0;
  for (long long b0 = 0; b0 < total; b0 += BL) {
    if (g_cancel.load(std::memory_order_relaxed)) return kCancelled;
    int n = static_cast<int>(std::min<long long>(BL, total - b0));
    size_t got = fread(bg.data(), 2, n, fi.get());
    for (size_t i = got; i < static_cast<size_t>(n); i++) bg[i] = 0;
    std::vector<float> dub(n, 0.f);
    bool active = false;
    while (first < segs.size() && segs[first].end() + hold < b0) first++;
    for (size_t j = first; j < segs.size() && segs[j].start - look < b0 + n; j++) {
      const Seg& s = segs[j];
      if (s.end() + hold >= b0 && s.start - look < b0 + n) active = true;
      for (int i = 0; i < n; i++) {
        long long k = b0 + i - s.start;
        if (k >= 0 && k < static_cast<long long>(s.pcm.size())) dub[i] += s.pcm[k];
      }
    }
    float target = active ? duckGain : 1.f, g1 = g + (target - g) * (target < g ? atk : rel);
    for (int i = 0; i < n; i++) {
      float gi = g + (g1 - g) * (i + 1) / n;
      float v = (bg[i] * gi + dub[i]) / 32768.f;
      ob[i] = clip16(softLimit(v) * 32767.f);
    }
    g = g1;
    if (fwrite(ob.data(), 2, n, fo.get()) != static_cast<size_t>(n)) return kErrIo;
  }
  return kOk;
}

int extractWav(const std::string& pcm, int rate, double startSec, double endSec, int outRate, const std::string& outWav) {
  File f = openFile(pcm, "rb");
  if (!f) return kErrIo;
  long long s0 = static_cast<long long>(std::max(0.0, startSec) * rate), s1 = static_cast<long long>(endSec * rate);
  if (s1 <= s0 || outRate <= 0) return kErrFormat;
  if (fseeko(f.get(), static_cast<off_t>(s0 * 2), SEEK_SET) != 0) return kErrIo;
  std::vector<int16_t> raw(static_cast<size_t>(s1 - s0));
  raw.resize(fread(raw.data(), 2, raw.size(), f.get()));
  if (raw.empty()) return kErrFormat;
  std::vector<float> x(raw.begin(), raw.end()), y;
  resampleVec(x, rate, outRate, y);
  File fo = openFile(outWav, "wb");
  if (!fo) return kErrIo;
  writeWavHeader(fo.get(), outRate, static_cast<long long>(y.size()) * 2);
  std::vector<int16_t> o(y.size());
  for (size_t i = 0; i < y.size(); i++) o[i] = clip16(y[i]);
  if (fwrite(o.data(), 2, o.size(), fo.get()) != o.size()) return kErrIo;
  return static_cast<int>(y.size() * 1000LL / outRate);
}

int bestLag(const uint8_t* speech, int n, const uint8_t* sub, int m, int from, int to, int minLag, int maxLag, float* out) {
  from = std::max(0, from); to = std::min(m, to);
  long subActive = 0;
  for (int i = from; i < to; i++) subActive += sub[i] ? 1 : 0;
  if (subActive == 0 || n <= 0) { out[0] = 0; out[1] = 0; out[2] = 0; return kErrFormat; }
  long bestOv = -1; int bestL = 0;
  for (int lag = minLag; lag <= maxLag; lag++) {
    if (g_cancel.load(std::memory_order_relaxed)) return kCancelled;
    long ov = 0;
    int a = std::max(from, -lag), b = std::min(to, n - lag);
    for (int i = a; i < b; i++) ov += (sub[i] & speech[i + lag]);
    if (ov > bestOv || (ov == bestOv && std::abs(lag) < std::abs(bestL))) { bestOv = ov; bestL = lag; }
  }
  long sp = 0, cnt = 0;
  for (int i = std::max(0, from + bestL); i < std::min(n, to + bestL); i++) { sp += speech[i] ? 1 : 0; cnt++; }
  out[0] = static_cast<float>(bestL);
  out[1] = static_cast<float>(bestOv) / subActive;
  out[2] = cnt ? static_cast<float>(sp) / cnt : 0.f;
  return kOk;
}

int wavDurationMs(const std::string& path) {
  File f = openFile(path, "rb");
  if (!f) return kErrIo;
  WavInfo w;
  if (!readWavHeader(f.get(), w) || w.ch < 1 || w.bits != 16) return kErrFormat;
  return static_cast<int>(w.dataBytes / (2 * w.ch) * 1000LL / w.rate);
}

}  // namespace sari
