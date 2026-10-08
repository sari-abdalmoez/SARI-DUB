# SARI DUB

Local-first Android app: import a video, translate its dialogue on-device and generate a dubbed audio track
that is synchronised to the original timing, played back in-app while later chunks are still processing.

> **Status: v0.1 foundation.** The native DSP engine is unit-tested on a host. The Android/Kotlin layer has
> **not been compiled by the author** (no Android SDK was available). The first GitHub Actions run is the real
> test; expect to fix a few compile errors. See "Limitations" for what is and is not implemented.

## Toolchain (pinned, mutually compatible)
| Component | Version |
|---|---|
| Gradle | 8.7 (installed by the workflow via `gradle/actions/setup-gradle`) |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 1.9.24 (Compose compiler extension 1.5.14) |
| JDK | 17 (Temurin) |
| compileSdk / targetSdk / minSdk | 34 / 34 / 26 |
| NDK | 26.1.10909125 |
| CMake | 3.22.1 |
| ABIs | arm64-v8a, armeabi-v7a |
| Compose BOM | 2024.06.00; Media3 1.3.1; Coil 2.6.0; ML Kit Translate 17.0.3 |

## Build
**GitHub Actions:** push the project root to GitHub → Actions → "Build SARI DUB". It runs the native DSP tests
(ASan+UBSan), builds `:app:assembleRelease`, checks there is exactly one APK, verifies its signature and that
`libsaridub.so` exists for both ABIs, and uploads one artifact named `SARI-DUB.apk`.
The release APK is signed with the Android debug key so it installs; use your own keystore for distribution.

**Local:** Android Studio (JDK 17, SDK 34, NDK 26.1.10909125, CMake 3.22.1) → open folder → Run.
No Gradle wrapper jar is included; run `gradle wrapper --gradle-version 8.7` once if you want `./gradlew`.

**Native tests (no Android needed):**
`g++ -std=c++17 -O2 -fsanitize=address,undefined native_tests/dsp_test.cpp app/src/main/cpp/dsp.cpp -o t && ./t`

## Architecture
- **Kotlin (UI/orchestration):** Compose UI, document picker, foreground service, project store (JSON files),
  thermal/memory/storage guards, scheduler, MediaCodec audio decode (streams through direct ByteBuffers into a file).
- **C++ (`app/src/main/cpp`):** `dsp.cpp` – streaming resampler, energy VAD, autocorrelation pitch/voice profile,
  WSOLA pitch-preserving time-stretch, chunk mixer with adaptive ducking + soft limiter, cooperative cancellation.
  `jni_bridge.cpp` – thin JNI (paths, numbers and tiny result arrays only).
- **Pipeline (per project, resumable):** extract audio → resample to 24 kHz mono PCM file → VAD snap → per-segment
  voice profile + pitch-based speaker clustering → translation (ML Kit, translation memory + glossary) →
  per chunk: TTS per segment → trim → measure → faster re-synthesis if too long → limited WSOLA (≥0.75×) →
  mix with ducked original → `chunks/chunk_N.wav`. State is saved after every chunk/stage.
- **Watch while processing:** `ChunkScheduler` processes the chunk nearest the playback position first; the player
  keeps two ExoPlayers (video = master clock, dub chunks follow, drift >250 ms is re-seeked) and shows *Dub Buffer*.
- **Memory:** no whole-movie buffers; native code streams in blocks; a chunk's dub segments (~tens of MB) are the
  largest allocation. Pipeline pauses when Android reports low memory.
- **Thermal:** `PowerManager` thermal status → WARM adds delay between segments, HOT adds more, CRITICAL pauses.
- **Privacy:** INTERNET is used only for legal-source search, URLs you enter, and model downloads you start.

## Models
- Translation: Settings → Models → Download (ML Kit, one-time, per language, ~30 MB). Runs offline afterwards.
- Speech: Android system TTS **offline voices** for the target language (install in system TTS settings).
- ASR: **none bundled** (see limitations). Provide the original-language `.srt` in Project → Setup.

## Limitations (honest list)
- **No ASR yet.** whisper.cpp is not vendored; text comes from an imported source `.srt`. VAD/voice analysis are real.
- **Translation is not truly context-aware.** ML Kit translates line by line; context is limited to translation
  memory + glossary. A local LLM/NLLB engine would plug into `SegTranslator`.
- **No source separation** (music/SFX/dialogue stems), **no non-verbal vocal classification**, **no emotion/prosody
  transfer**, **no voice conversion/cloning.** The original is *ducked* under the dub, not separated.
- Voices = system TTS voices, assigned per speaker with pitch/rate adjustment; not clones of the actors.
- Diarisation = pitch clustering (max 6 speakers); similar-pitch speakers will merge.
- Stereo is mixed down to mono 24 kHz in the dub; no NEON intrinsics (compiler auto-vectorises); single worker.
- No FFmpeg/ONNX Runtime. Export: SRT, dubbed WAV, project JSON. **Video muxing is not implemented.**
- Search: Internet Archive + user URLs only; modern commercial films will not appear (by design).
- Not implemented as separate screens: Dubbing Setup is a tab; there is no dedicated Movie Details screen.
