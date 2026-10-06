# Speaker API — Third-Party Notices

Speaker API is an offline, local TTS middleware for NeoForge 1.21.1. This file
declares the licenses of every bundled and optionally-downloadable component,
**per component** — we do not lump everything under one license.

> **Compliance bottom line:** We never bundle any unauthorized commercial voice,
> and we never ship a default voice-cloning model. Cloning (if ever added) must be
> a separate module, default-off, require a local authorization file, and show its
> own license dialog. Any non-Apache model is **optional download only**, never in
> the main jar, and shows its license before first use.

---

## Bundled by default

| Component            | Role                          | License      | Notes |
|----------------------|-------------------------------|--------------|-------|
| Speaker API (mod code) | The mod itself               | Apache-2.0   | See `LICENSE`. Can be re-licensed MIT if preferred. |
| sherpa-onnx (JVM + JNI) | TTS engine                  | Apache-2.0   | `com.github.k2fsa.sherpa-onnx`. Source: https://github.com/k2-fsa/sherpa-onnx |
| ONNX Runtime (native)   | Inference runtime (via sherpa native) | MIT | Official runtime; shipped only for the current platform's native. |
| Kokoro multi-lang model | Default voice weights       | Apache-2.0   | Verify the LICENSE on the actual download page before shipping. ~82M, 24kHz, CPU near-realtime. |
| espeak-ng data          | Phoneme front-end data used by Kokoro/Matcha | **Independent / copyleft (GPL family)** | We distribute **only the runtime data files required to run**, unmodified. **Do not modify the espeak-ng source.** If you intend to use this commercially, have legal review the espeak-ng license before distribution. |
| CBZip2InputStream (vendored → `net.speakerapi.internal.bzip2`) | bzip2 decompression for first-run model `.tar.bz2` download | Apache-2.0 | Vendored from Apache Ant's `org.apache.tools.bzip2` package (itself based on Keiron Liddle's bzip2 implementation). Pure Java, zero third-party dependencies — no commons-compress / commons-io bundling, so no JPMS split-package conflict and no missing-dependency (`NoClassDefFoundError`) at runtime. A tiny in-mod tar reader (`net.speakerapi.internal.tar`) completes the extraction. Source: https://github.com/apache/ant (package `org.apache.tools.bzip2`). |

---

## Optional downloads (NOT in the main release; license shown before first use)

| Component                | License | Source / Notes |
|--------------------------|---------|----------------|
| Matcha-Icefall (zh)      | Per release page (usually MIT/Apache per model) | Lighter, "broadcast-natural" 16kHz. Enable only after the in-game license-confirm toggle. |
| VITS AIShell3 (zh)       | Per release page (verify AIShell3 data license) | Multi-speaker, fast, standard-broadcast timbre. Low-end fallback. |
| Piper huayan             | Per voice (Piper/CC BY 4.0 per voice) | Only via subprocess mode; Java in-process embedding cost is high. |

Each of the above must:
1. Be downloaded on demand from a trusted URL (k2-fsa `tts-models` releases).
2. Have its own `LICENSE` file copied next to the weights.
3. Require an explicit "I accept this model's license" toggle (`require_model_license_confirm = true`) before it is loaded.

---

## Platform-native packaging

- Native libs (`sherpa-onnx-native-lib-*`) are pulled **per platform at build time**
  (win-x64, win-arm64, linux-x64, linux-aarch64, osx-x64, osx-aarch64).
- We do **not** ship a single "works-everywhere" universal jar. Release either as
  per-platform sub-packages or via an installer that fetches the correct native.
- Model weights and native libraries are **never** embedded in the mod's main jar.

---

## How to read this notice

- If you only use the default Kokoro package, your obligations are: Apache-2.0
  (mod + sherpa + Kokoro) and a legal review of the espeak-ng data if distributing
  commercially.
- If you enable any optional model, you additionally accept that model's license,
  shown in-game before first load.
