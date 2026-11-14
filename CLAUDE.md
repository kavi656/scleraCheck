# CLAUDE.md — ScleraCheck (working name)

A GP-prescribed Android app for at-home jaundice screening. The patient holds the phone close to one eye and the app guides them through a short capture. It measures how yellow the white of the eye (sclera) is, compared against the patient's own baseline that the GP recorded at the start, and returns **No concern / Refer to GP / Retake**.

Built for the National MedTech Hackathon (Manchester, 14 Nov 2026). The goal is a credible, working demo by the end of the day.

---

## 0. Decisions (defaults chosen; change here first if the team disagrees)

| Decision | Default | Why |
|---|---|---|
| Camera | **Front camera + screen as the light** (default, self check). Rear camera + torch kept as "helper mode" | Changed 14 Nov: holding the rear camera to your own eye blind made capture unreliable. The demo Oppo's front camera has autofocus (same 15 cm closest focus as the rear) and manual controls. The screen (black for ambient, full-brightness white for lit) replaces the LED. It's dimmer, so very bright rooms will need a retake more often. Baselines are per mode because the numbers aren't comparable. |
| Capture distance | **Just beyond the lens's closest focus distance** (+2 cm, 50 mm band), at 2× zoom | Read at runtime from the camera. On the demo Oppo that's 17–22 cm. 2× is a sensor crop, so the eye gets ~4× the pixels without losing detail. Never "against the eye". |
| User guidance | **Audio + haptics** | With the rear camera the patient can't see the screen, so the phone tells them "closer", "hold still", "look left" and beeps when it's done. |
| Gaze positions | **Look left, then look right** | Shows the most sclera on either side of the iris. |
| Output | **3 states: No concern / Refer / Retake** | Retake is a real outcome. Never force a verdict from a bad capture. |
| Decision basis | **Change from personal baseline** + an absolute safety threshold | We don't have bilirubin ground truth, so tracking change from baseline is honest. |
| Target users | **Adults being monitored for liver/biliary disease** | Neonatal use is a pitch slide only, not part of the build. |

---

## 1. Non-negotiable constraints

1. **Positioned as screening/triage, never diagnosis.** The UI says "signs of yellowing — contact your GP", never "you have jaundice". Show a "not a diagnostic device" notice on first run and on every result.
2. **Bias the safety threshold toward over-referring.** A missed jaundice case is worse than an unnecessary GP call. Document the threshold and explain how it was chosen.
3. **Eye comfort:** turn the torch on only during the capture burst (2 seconds max), at the lowest brightness that still gives enough signal. No strobing. Tell the patient beforehand that a light will turn on.
4. **Privacy:** store only the cropped sclera patches and the colour metrics, on the device. Never store full-face images. No network permission in the hackathon build. The "send to GP" export is mocked as a PDF or share sheet.
5. Kotlin, Jetpack Compose, CameraX with Camera2Interop for manual controls, min SDK 29.

---

## 2. User flows

**A. GP onboarding (in clinic, once)**
1. The GP opens the app in clinician mode and scans a **colour reference card** under the clinic's light. This creates a per-device colour correction matrix.
2. The patient does one guided capture with the GP present. If the GP confirms the patient is not currently jaundiced, this becomes the **baseline**. If they are, it's flagged as a monitoring baseline.
3. The GP sets the check frequency (e.g. daily or every 3 days) and the referral rule.

**B. Patient home check (~20 s)**
1. Notification: "Time for your eye check."
2. The app checks ambient light. If the room is too bright or too dark, it gives advice ("move away from the window").
3. The patient turns the rear camera toward their face. Audio guidance gets them to the right distance with the eye centred.
4. "Look left". The phone locks focus, white balance and exposure, then captures a burst with the torch off and a burst with the torch on.
5. "Look right". Repeat.
6. Quality gate, then measurement, then the result screen with one of the 3 states and simple next steps.

**C. GP view (mocked)**
A trend chart of the yellowness index over time against the baseline, with flagged checks. Exported as a PDF.

---

## 3. Imaging pipeline

```
CameraX preview → MediaPipe Face Landmarker (eye + iris landmarks)
   │  distance estimate from iris diameter in pixels (~11.7 mm real)
   ▼
Guidance controller (audio/haptic: closer / further / centre / hold still)
   │  when in range + sharp + stable for 0.5 s:
   ▼
Lock AF / AE / AWB (Camera2 AE_LOCK, AWB_LOCK, fixed ISO + exposure if supported)
   ▼
Burst: N frames torch OFF, N frames torch ON
   ▼
Frame selection — sharpest frames (variance of Laplacian), no blink
   ▼
Flash/no-flash differencing: I_torch − I_ambient → image lit only by the torch (known light colour)
   ▼
Device colour correction (matrix from GP card calibration)
   ▼
Sclera segmentation — the region between the iris and the eyelid corners,
   then exclude: specular glare, blood vessels (high redness), eyelashes, eyelid margin
   ▼
Convert to CIELAB → yellowness = median b* over the clean sclera pixels (also log a*, L*)
   ▼
Decision engine → No concern / Refer / Retake
```

Keep `colour/`, `segmentation/`, `quality/` and `decision/` as **pure Kotlin with no Android imports** so they can be unit-tested on the JVM with saved images.

## 4. Quality gate (any failure → Retake, with a specific reason)
- Fewer than X clean sclera pixels on either side (eyes not open enough, or not looking far enough to the side)
- Blur below the sharpness threshold
- Torch contribution too weak compared with ambient light (e.g. direct sunlight)
- Glare covers more than Y% of the sclera region
- Left and right gaze measurements disagree by more than Z (something went wrong)
- Too much redness in the sclera (red eye or bleeding) → retake, plus a suggestion to see a GP if it persists

## 5. Decision rule (v1, deliberately simple and explainable)
- `delta = b*_today − b*_baseline`, averaged over left and right gaze.
- **Refer** if `delta > T_change` on 2 consecutive checks, OR if any single check is above `T_absolute`.
- **No concern** otherwise.
- Tune the thresholds using the lighting-invariance experiment (section 6). `T_change` must sit well above the normal check-to-check noise for one person; a good target is mean + 3 SD of that noise.
- Write every threshold to a config file with a comment explaining where the number came from.

## 6. Validation plan (what we can honestly show today)
1. **Lighting invariance (the key demo):** 4–5 teammates, each measured under 4–5 lighting setups (warm lamp, overhead fluorescent, window daylight, dim room). Plot b* with and without the correction pipeline. The corrected values should stay flat.
2. **Repeatability:** 5 captures per person back to back. Report the standard deviation. This sets the noise floor for `T_change`.
3. **Sensitivity sanity check:** a printed sclera-colour target in increasing yellow tints shows the index increases steadily. This is clearly labelled as a phantom, not a patient.
4. **Not claimed:** accuracy against serum bilirubin. That needs a clinical study, and it's what we say comes next in the pitch.

## 7. Build order
1. CameraX rear preview + face/eye landmarks + iris-based distance readout
2. Audio/haptic guidance to the target distance
3. AE/AWB/AF lock + torch on/off burst capture, saved locally for debugging
4. Sclera segmentation, shown as an overlay on screen
5. Differencing + colour correction + b* value, displayed live
6. Quality gate with Retake reasons
7. Baseline storage (Room) + decision engine + result screen
8. (Stretch) GP mode with colour-card calibration, trend chart, PDF export

Don't start step 7 until step 5 gives stable numbers across lighting conditions.

## 8. Known confounders (handle them or state them)
- Pinguecula or other yellowish growths on the eye; natural yellowing of the sclera with age, which is why the personal baseline matters
- Red eyes and conjunctivitis (redness gets caught by the quality gate)
- Tinted contact lenses, eye makeup on the lid edge
- High beta-carotene intake (carotenaemia) yellows the skin but **not** the sclera. Mention this in the pitch: it's an advantage of using the sclera.

## 9. Conventions for Claude Code
- Explain every imaging/colour choice in a comment with a reference.
- Unit-test the colour maths (RGB→Lab, matrix correction, differencing) with known values.
- Keep debug saving of captured patches behind a developer toggle. Turn it off in the demo build.
- Don't add libraries beyond CameraX/Camera2Interop, MediaPipe Tasks Vision, Room and Compose without asking.
- When unsure how Camera2 manual controls behave on a given device, check whether it supports them at runtime and fall back gracefully. Never assume.
