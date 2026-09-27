# Camera Pro (Camera Professional)

Package: `com.example.cameraprofessional`

## What's actually in this build

| Screen | Status |
|---|---|
| **MainActivity** — fast photo + video capture, mode strip, zoom bar (0.6x/1x/2x/3x/5x) | ✅ Fully working |
| **OptimizeActivity** — scan summary + ⚡ Optimize Camera | ✅ Fully working |
| **ProCameraActivity** — manual ISO/shutter (where HAL supports it), exposure comp, white balance | ✅ Fully working; manual sensor controls auto-disable with a clear message on devices that don't expose them |
| **PortraitActivity** — capture-then-blur using on-device ML Kit segmentation | ✅ Working, capture-then-process (see note below) |
| **QrScannerActivity** — live QR/barcode detection | ✅ Fully working |
| **DocumentScannerActivity** — capture → tap 4 corners → perspective warp → B/W or color → multi-page → PDF export | ✅ Fully working |
| **SettingsActivity → Diagnostics** — capture latency + active optimized profile | ✅ Fully working |
| **CapabilitiesActivity** — full spec sheet (sensor, focal length, aperture, FOV, OIS/EIS, AF, flash, FPS, resolutions, HDR, RAW, HEIF, zoom/exposure/ISO/WB ranges, slow-motion) | ✅ Fully working |
| **Panorama** | 🚧 Stub only — real multi-shot stitching is a large separate feature (see Roadmap) |

## Honest notes on scope

1. **Portrait mode** here is capture → segment → blur, not a live 30fps
   blurred viewfinder. True live portrait preview needs GPU frame
   compositing (OpenGL/RenderEffect) — doable, but a much bigger build than
   fits in one pass. What's here already gives real background blur with an
   adjustable strength slider.
2. **Manual ISO/shutter** only work if the specific phone's camera HAL
   reports `MANUAL_SENSOR` capability. Many mid-range Samsung Galaxy phones
   (including budget F-series units) do **not** expose this — the app
   detects this per-device and shows Auto instead of pretending to control
   something the hardware won't accept.
3. **Zoom-based lens switching** uses `CameraControl.setZoomRatio()`. On
   phones with a logical multi-camera (most modern Samsung/Android phones),
   the OS itself switches between ultra-wide/main/telephoto physical
   sensors as the ratio crosses their boundaries — this is the standard,
   smooth approach; manually selecting physical camera IDs is unnecessary
   (and riskier) on these devices.
4. **HDR/Night mode via Camera2 Extensions** (`CameraExtensionCharacteristics`)
   are a runtime, bind-time API rather than static characteristics — wiring
   them in is straightforward but not yet included; flagged in code comments
   as the next step.
5. **Video bitrate control / audio level meter** are not yet built — CameraX's
   `Recorder` doesn't expose bitrate directly; needs a Camera2-level
   MediaRecorder path. Marked as roadmap.
6. The uploaded LMC preset files (`Sam_F23.xml`, etc.) are **not** used
   anywhere in this codebase — their exact tuning values are another
   developer's proprietary work. The Enhancer/Document filters here are
   original, generic implementations you can freely modify.

## Architecture (why it stays smooth)

- **MainActivity never runs anything heavy.** Capture, save, and capability
  scanning are all on background executors; the shutter button's job is
  just to fire `takePicture()`/`startRecording()`.
- **Optimizer runs once, caches everything.** `CameraOptimizer.kt` reads the
  capability scan, decides resolution/FPS/stabilization/HDR, and stores the
  result in SharedPreferences. Every other screen reads that cache instead
  of re-touching the CameraManager.
- **Heavy features are their own Activities**, not modes bolted onto
  MainActivity: Pro controls, Portrait blur, QR scanning, Document scanning.
  Each rebinds its own lightweight camera session, so nothing they do can
  slow down the main capture screen.
- **QR scanning uses `STRATEGY_KEEP_ONLY_LATEST`** so frames never queue up
  — if ML Kit is still processing one frame, older frames are dropped
  instead of piling up and causing lag.

## Build

1. Open `CameraProfessional/` in Android Studio, let Gradle sync (this pulls
   ML Kit's barcode-scanning and segmentation-selfie models).
2. Run on a real device (Samsung Galaxy F23 or any Android 7.0+ phone).
3. First launch → open Settings → Re-run Optimizer (or the Optimize screen
   directly) once, before heavy use, so the cached profile is populated.

## Roadmap (clearly not done yet)

- Live GPU-composited portrait preview
- Camera2 Extensions wiring for true HDR/Night modes
- Panorama capture + stitching
- Manual focus distance slider (needs `MANUAL_SENSOR` + lens position control)
- Video bitrate slider + live audio level meter
- Draggable (not tap-to-place) document corner handles
