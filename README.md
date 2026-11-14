# ScleraCheck (hackathon build)

At-home jaundice screening aid. See [CLAUDE.md](CLAUDE.md) for the design. **Not a diagnostic device.**

## Install on an Android phone (Android 10+)

### Option A: USB with adb (recommended, also gives logs)
1. On the phone: **Settings → About phone → tap "Build number" 7 times** to unlock Developer options.
2. **Settings → System → Developer options → turn on "USB debugging".**
3. Plug the phone into the Mac and tap **Allow** on the "Allow USB debugging?" prompt.
4. On the Mac:
   ```bash
   cd "/Users/Karanvir/Documents/Karanvir's Files/medtechapp"
   ~/Library/Android/sdk/platform-tools/adb devices          # your phone should be listed as "device"
   ~/Library/Android/sdk/platform-tools/adb install -r ScleraCheck-debug.apk
   ```
5. Open **ScleraCheck** on the phone.

### Option B: no cable
Copy `ScleraCheck-debug.apk` to the phone (AirDrop doesn't work with Android; use Google Drive, email or Quick Share),
open it on the phone, and allow "Install unknown apps" for whichever app opened it.

## Rebuild after changing code
```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.9/libexec/openjdk.jdk/Contents/Home   # JDK 21; JDK 25 is too new for this Gradle
./gradlew :app:installDebug          # builds and installs onto the connected phone
./gradlew :app:testDebugUnitTest     # JVM tests for colour maths, segmentation, quality gate, decision rule
```
Or open the folder in Android Studio and press Run.

## Testing tips
- **Developer mode** (toggle at the bottom of the home screen, debug build only) shows the eye contour (cyan),
  segmentation region (green), excluded iris (magenta), live distance, sharpness, gaze and uncorrected b*.
  It also saves masked sclera patches and a CSV of every measurement:
  ```bash
  adb pull /sdcard/Android/data/com.scleracheck/files/debug/ ./captures/
  ```
  Type a label such as `alice_warm_lamp` before each run: it goes into `log.csv` for the lighting-invariance plot
  (compare `bStar`, corrected, with `naiveBStar`, uncorrected).
- Start with the phone at arm's length so the face tracker locks on, then follow "closer" down to about 10–15 cm.
- Clinician mode PIN: `0000`. Record a baseline there first, otherwise only the absolute threshold is used.
- Logs: `adb logcat -s ScleraCamera ScleraCheck` (camera capabilities are logged at start).

## What's in / out
In: guided rear-camera capture, AE/AWB/AF lock (falls back at runtime), torch-off/torch-on bursts (<2 s of light),
flash/no-flash differencing, sclera segmentation with glare, vessel and lash exclusion, Lab b*, quality gate with
retake reasons, baseline and decision engine, Room history, trend chart, PDF share.

Not yet: the colour-card calibration screen (identity matrix for now; `ColourCorrection.fit` is ready),
check-due notifications (shown on the home screen instead). Every threshold in `decision/Thresholds.kt`
is a placeholder until the repeatability experiment has been run.
