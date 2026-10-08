// Thin JNI bridge: strings/ids in, small arrays/status out. No media buffers cross JNI.
#include <jni.h>

#include <string>
#include <vector>

#include "dsp.h"

namespace {
std::string str(JNIEnv* env, jstring s) {
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string r(c ? c : "");
  if (c) env->ReleaseStringUTFChars(s, c);
  return r;
}
}  // namespace

extern "C" {

JNIEXPORT void JNICALL Java_com_saridub_app_Native_setCancel(JNIEnv*, jobject, jboolean c) {
  sari::g_cancel.store(c == JNI_TRUE);
}

JNIEXPORT jlong JNICALL Java_com_saridub_app_Native_resampleFile(JNIEnv* env, jobject, jstring in, jint inRate,
                                                                  jint inCh, jstring out, jint outRate) {
  return sari::resampleFile(str(env, in), inRate, inCh, str(env, out), outRate);
}

JNIEXPORT jdoubleArray JNICALL Java_com_saridub_app_Native_vad(JNIEnv* env, jobject, jstring pcm, jint rate,
                                                                jint minSpeechMs, jint minSilenceMs) {
  std::vector<double> segs;
  int rc = sari::vad(str(env, pcm), rate, minSpeechMs, minSilenceMs, segs);
  if (rc != sari::kOk) return nullptr;
  jdoubleArray arr = env->NewDoubleArray(static_cast<jsize>(segs.size()));
  if (!segs.empty()) env->SetDoubleArrayRegion(arr, 0, static_cast<jsize>(segs.size()), segs.data());
  return arr;
}

JNIEXPORT jfloatArray JNICALL Java_com_saridub_app_Native_voiceProfile(JNIEnv* env, jobject, jstring pcm, jint rate,
                                                                        jdouble s, jdouble e) {
  float out[sari::kProfileSize];
  if (sari::voiceProfile(str(env, pcm), rate, s, e, out) != sari::kOk) return nullptr;
  jfloatArray arr = env->NewFloatArray(sari::kProfileSize);
  env->SetFloatArrayRegion(arr, 0, sari::kProfileSize, out);
  return arr;
}

JNIEXPORT jint JNICALL Java_com_saridub_app_Native_stretchWav(JNIEnv* env, jobject, jstring in, jstring out, jint rate,
                                                               jint targetMs, jfloat minR, jfloat maxR) {
  return sari::stretchWav(str(env, in), str(env, out), rate, targetMs, minR, maxR);
}

JNIEXPORT jint JNICALL Java_com_saridub_app_Native_mixChunk(JNIEnv* env, jobject, jstring orig, jint rate, jdouble s,
                                                             jdouble e, jdoubleArray starts, jobjectArray wavs,
                                                             jstring out, jfloat duck) {
  jsize n = env->GetArrayLength(starts);
  std::vector<double> st(n);
  if (n) env->GetDoubleArrayRegion(starts, 0, n, st.data());
  std::vector<std::string> w;
  for (jsize i = 0; i < n; i++) {
    jstring js = static_cast<jstring>(env->GetObjectArrayElement(wavs, i));
    w.push_back(str(env, js));
    env->DeleteLocalRef(js);
  }
  return sari::mixChunk(str(env, orig), rate, s, e, st, w, str(env, out), duck);
}

JNIEXPORT jint JNICALL Java_com_saridub_app_Native_wavDurationMs(JNIEnv* env, jobject, jstring p) {
  return sari::wavDurationMs(str(env, p));
}

JNIEXPORT jint JNICALL Java_com_saridub_app_Native_extractWav(JNIEnv* env, jobject, jstring pcm, jint rate, jdouble s,
                                                               jdouble e, jint outRate, jstring out) {
  return sari::extractWav(str(env, pcm), rate, s, e, outRate, str(env, out));
}

JNIEXPORT jfloatArray JNICALL Java_com_saridub_app_Native_bestLag(JNIEnv* env, jobject, jbyteArray speech, jbyteArray sub,
                                                                   jint from, jint to, jint minLag, jint maxLag) {
  jsize n = env->GetArrayLength(speech), m = env->GetArrayLength(sub);
  std::vector<jbyte> sp(n), sb(m);
  if (n) env->GetByteArrayRegion(speech, 0, n, sp.data());
  if (m) env->GetByteArrayRegion(sub, 0, m, sb.data());
  float out[3];
  int rc = sari::bestLag(reinterpret_cast<const uint8_t*>(sp.data()), n, reinterpret_cast<const uint8_t*>(sb.data()), m,
                         from, to, minLag, maxLag, out);
  if (rc != sari::kOk) return nullptr;
  jfloatArray arr = env->NewFloatArray(3);
  env->SetFloatArrayRegion(arr, 0, 3, out);
  return arr;
}

}  // extern "C"
