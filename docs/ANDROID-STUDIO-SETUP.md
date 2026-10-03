# Setting up Android Studio (first-timer's guide)

This is the from-zero path to running QR Cards on your own machine. No
assumed Android knowledge.

## 1. Install Android Studio

Download from <https://developer.android.com/studio> (~1 GB) and install with
the defaults. On first launch a setup wizard installs the Android SDK —
accept the defaults.

## 2. Open the QR Cards project

In Android Studio: **File → Open** → select the `qr-cards` folder → **Trust Project**
when asked. A Gradle sync starts automatically. The first sync downloads
dependencies and takes several minutes — watch the progress bar at the bottom
and don't panic.

> Command-line note: the Gradle *wrapper jar* is not committed to the repo.
> Android Studio doesn't need it (it manages Gradle itself). If you ever want
> `./gradlew` on the command line, generate it once with `gradle wrapper`
> after installing Gradle separately.

## 3. Check the SDK pieces

QR Cards targets **API 34** (`compileSdk`/`targetSdk`) with `minSdk 26`.
Open the **SDK Manager** (toolbar icon, or Settings → Languages & Frameworks →
Android SDK) and confirm **Android 14.0 ("UpsideDownCake", API 34) → SDK Platform**
is checked. Build-tools come along automatically.

## 4. Run it — two options

### A. Emulator (easiest first run)

**Tools → Device Manager → Create Device** → pick a Pixel 7 or 8 → choose an
API 34 system image (x86_64; it downloads on demand) → Finish. Select the new
device in the run dropdown at the top, then hit **Run ▶**.

### B. Your own phone (best for this app)

1. On the phone: **Settings → About phone → tap "Build number" 7 times** to
   unlock Developer options.
2. In **Developer options**, enable **USB debugging**.
3. Plug the phone in via USB, accept the RSA fingerprint prompt, select the
   phone in Android Studio's run dropdown, hit **Run ▶**.

For QR Cards specifically, a real phone is worth it: the spec's quality bar is
*every code type scanned by stock Android and iOS cameras*, and an emulator
can't do that convincingly.

## 5. The buttons that matter

| Button | What it does |
|---|---|
| ▶ **Run** | Builds, installs, launches (debug build) |
| 🐞 **Debug** | Same, with the debugger attached |
| 🐘 **Sync Project with Gradle Files** | Re-resolves dependencies — hit this when builds complain after editing `build.gradle.kts` |
| **Logcat** (bottom panel) | The app's live logs. Filter by package `ca.derickcampbell.qrcards`. This is your best friend when something breaks. |

## 6. Key concepts (60-second version)

- **Gradle** is the build system. The `build.gradle.kts` files declare the SDK
  versions and libraries (ours: ZXing for QR generation, androidx.security for
  encrypted backup).
- **Debug vs release.** The Run button makes a *debug* build: fast and
  debuggable. A *release* build needs app signing (see §8).
- **The manifest** (`app/src/main/AndroidManifest.xml`) declares permissions
  and entry points. Ours deliberately requests **no `INTERNET` permission** —
  the app is fully offline by design. If you ever add a library that needs the
  network, that decision deserves a conversation first.

## 7. Troubleshooting

- **"SDK location not found"** — point Android Studio at your SDK in the SDK
  Manager, or set `sdk.dir` in `local.properties`. That file is gitignored;
  never commit it.
- **Gradle sync fails** — first suspect: no internet (the first sync downloads
  a lot). Then **File → Invalidate Caches → Restart**.
- **Emulator won't start (Windows)** — enable *Windows Hypervisor Platform* in
  Windows Features; or skip the emulator and use a physical phone.
- **"App not installed" on your phone** — uninstall the existing copy first.
  Debug builds signed on different machines conflict.

## 8. Later: release builds (not yet)

When v1 is ready for the Play Store: **Build → Generate Signed Bundle / APK**.
You'll create a **keystore** exactly once — back it up somewhere safe. Lose it
and you can never publish updates to the same Play Store listing. We'll do this
step together when the time comes.
