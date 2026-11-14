# ScleraCheck for iPhone

Same app as the Android build (`../app`), using only Apple frameworks: AVFoundation (camera), Vision (eye
landmarks), SwiftUI + Swift Charts (UI). **Not a diagnostic device.**

```
ios/
  project.yml          XcodeGen spec → generates ScleraCheck.xcodeproj
  ScleraCore/          Swift package: colour maths, segmentation, quality gate, decision rule.
                       Line-for-line port of the Kotlin core; same tests, same numbers.
  ScleraCheck/         The app: camera, Vision eye tracking, check flow, screens
```

## Run it on your iPhone (iOS 16+)
1. Install **Xcode** from the Mac App Store (large download), open it once and let it install components.
2. Generate the project (already generated, re-run after adding files):
   ```bash
   cd "/Users/Karanvir/Documents/Karanvir's Files/medtechapp/ios"
   xcodegen generate
   open ScleraCheck.xcodeproj
   ```
3. In Xcode: click the **ScleraCheck** project → target **ScleraCheck** → **Signing & Capabilities** →
   tick *Automatically manage signing* → **Team**: add your Apple ID (a free one works).
   If it complains the bundle ID is taken, change `com.scleracheck.app` to something unique.
4. Plug in the iPhone, tap **Trust**. On the iPhone: **Settings → Privacy & Security → Developer Mode → On** (restarts).
5. Pick your iPhone at the top of Xcode and press **▶ Run**.
6. First launch only: on the iPhone, **Settings → General → VPN & Device Management → your Apple ID → Trust**.

With a free Apple ID the app stops opening after 7 days; just press Run again.

## Testing
- Developer mode (debug builds): overlay, live numbers, and the measured sclera patches on the result screen.
- CSV + masked patches are saved to the app's Documents/debug folder: Finder → your iPhone → **Files** →
  ScleraCheck, or the Files app on the phone.
- Core tests without Xcode:
  ```bash
  cd ScleraCore
  FW=/Library/Developer/CommandLineTools/Library/Developer/Frameworks
  swift test -Xswiftc -F -Xswiftc $FW -Xlinker -F -Xlinker $FW -Xlinker -rpath -Xlinker $FW \
    -Xlinker -rpath -Xlinker /Library/Developer/CommandLineTools/Library/Developer/usr/lib
  ```
  (With Xcode installed, plain `swift test` works.)

## Differences from Android
| | Android | iPhone |
|---|---|---|
| Eye landmarks | MediaPipe (iris ring → iris size) | Apple Vision (eye contour + pupil; iris size estimated from eye width, 11.7/29 mm) |
| Distance | iris diameter 11.7 mm | eye width 29 mm (less precise; ±10%) |
| White balance | Camera2 DAYLIGHT preset | locked to 5500 K gains |
| Room-light check | light sensor (lux) | estimated from auto-exposure (no public light sensor on iOS) |
| Torch brightness | Android 15+ only | `setTorchModeOn(level: 0.6)` on every iPhone |
| Lens | logical camera, zoom kept < tele switch | physical wide-angle camera only, never switches lens |

Older iPhones (before iPhone 14) have **fixed-focus front cameras**; the app detects this and guides to
25–32 cm instead, relying on 2× zoom. Check the developer overlay's `AF=` line.
