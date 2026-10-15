# LMC 8.4 Video Studio — Native Android build starter

This is a Kotlin/AndroidX Media3 native app project designed for building from a phone using GitHub Actions (no PC required).

## What is implemented in this source snapshot
- Native Android UI
- Local video selection and Media3 preview
- Four named preset actions
- All eight adjustment sliders retained in the UI
- LMC XML import for matching numeric saturation/contrast/sharpen fields
- Resolution choices (1×, 1.5×, 2×)
- Bitrate choices (14/25/40 Mbps)
- Media3 Transformer export path using H.264/AAC and Android's video frame processing pipeline
- Export progress/error status and share/save flow
- GitHub Actions workflow to compile a debug APK in the cloud

## Important current limitations — read before installing
This is a **starter build, not yet a feature-equivalent release**. Media3's hardware-accelerated export pipeline is wired up, but this snapshot does not yet reproduce the original custom 9-tap CAS/micro-texture/clarity/vignette GLSL shader. The `texture`, `sharpen`, `clarity`, `saturate`, and `vignette` controls are retained in the UI; only the currently wired native grading effects are actually applied to export. The current XML import updates stored slider values but does not rebuild the visible seek bars after import. Preset values are stored, but existing seek bars do not refresh after preset selection. These gaps must be completed and tested before claiming no feature loss.

The bitrate selector is stored for UI and status; Media3 Transformer bitrate configuration is not yet applied in this snapshot. Output size selection uses Media3's scale transformation and must be tested against real device output dimensions. Some source videos/codecs may fail on specific hardware. No native app can guarantee zero lag/crashes on every device; test with short clips first.

## Build an APK using only an Android phone
1. Download and extract this ZIP on your phone.
2. Open GitHub in Chrome and create a **new empty repository**.
3. Upload the extracted project files and folders to the repository root (include hidden `.github` folder; GitHub mobile upload may make hidden folders awkward, so use GitHub's web editor to create `.github/workflows/build-apk.yml` if needed).
4. Open the repository's **Actions** tab and allow workflows if prompted.
5. Run **Build LMC 8.4 APK** (or push to `main`) and wait for the workflow to finish.
6. Open the successful workflow run, download artifact `LMC84-native-debug-apk`, extract the artifact ZIP, and install `app-debug.apk`.
7. If Android blocks installation, allow “Install unknown apps” for the browser/file manager you used.

## Build details
- Minimum Android: 8.0 (API 26)
- Target SDK: 35
- Java 17
- AndroidX Media3 1.6.1
- Debug APK is unsigned by a release key and intended for personal testing.


## Feature mapping
- Preview shader + export shader: same `LmcGlEffect` pipeline.
- Original compare: toggles preview shader bypass; export retains selected settings.
- Presets: update all matching controls and the live preview.
- XML: recognized numeric fields for saturation, contrast and sharpness update controls and preview.
- Upscale: `Presentation` requests output dimensions at 1×, 1.5× or 2×, rounded to even dimensions for common encoders.
- Bitrate: `VideoEncoderSettings` requests the selected H.264 bitrate; a device codec may not honor every value.
- Audio: Media3 Transformer transcodes/muxes AAC audio with H.264 video.

The shader is a native approximation of the original web shader, not bit-for-bit pixel parity. There is no neural-network AI upscaler; this is spatial shader enhancement and resampling.
