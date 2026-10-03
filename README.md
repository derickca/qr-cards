# QR Cards

A tiny, free-forever Android app for people who show QR codes to others.

Generate, organize, and present QR codes — contact cards for the different parts
of your life (work, social, personal), guest Wi-Fi codes, saved places — so the
right code is one tap away when you need it.

**No ads. No accounts. No cloud. No paid tiers. Ever.** That's structural, not a
setting — see [SPEC-v1.md](SPEC-v1.md).

## Status

Scaffold (2026-10-03). The v1 spec is written; implementation hasn't started.

**New to Android development?** Start with
[docs/ANDROID-STUDIO-SETUP.md](docs/ANDROID-STUDIO-SETUP.md) — from-zero
install through running the app on an emulator or your phone.

## The idea

The Play Store's QR category is dominated by ad-crammed giants (the market leader
has 850M+ installs and charges $9.99 just to remove ads), while the free
alternatives are scan-only, tiny, or paywall exactly the features a QR manager
needs — Wi-Fi and vCard generation, styling, high-res export. Nobody does the
"QR wallet" concept: named identity profiles with per-context quick access, in
one fully offline library. That's the gap QR Cards fills.

## Building

Requires the Android SDK (or just open the project in Android Studio):

```bash
# generate the gradle wrapper jar first (not committed):
gradle wrapper
./gradlew assembleDebug
```

- Language: Kotlin
- QR generation: ZXing core (`com.google.zxing:core`, Apache 2.0)
- `minSdk 26`, `compileSdk 34`, single-digit-MB install target

## Project principles

1. **Fully offline.** Works in airplane mode. Stretch goal: no `INTERNET` permission at all.
2. **Minimal permissions.** No contacts access (you type exactly what you want to share), no location.
3. **Correctness is the feature.** Every code type verified against stock Android and iOS cameras. A code that "scans but doesn't connect" is a bug.
4. **Honest privacy.** QR payloads are plaintext readable by any camera. Sensitive cards confirm before display; Wi-Fi cards nudge toward a guest network, never the main LAN.

## Roadmap

- **v1** — generate + organize + present: nine card types (URL, contact/vCard, Wi-Fi, location, text, email, phone, SMS, calendar event), named library, present mode (full-screen, max brightness), per-card app shortcuts, share/export as PNG and SVG, encrypted local backup.
- **v2 candidates** — card sharing between devices (no accounts, no server); home-screen widgets if users ask.

See [SPEC-v1.md](SPEC-v1.md) for the full spec.

## License

MIT — borrow and steal. (Swappable; this was the scaffold default.)

## Author
Written by Meep from Muse, with a little guidance from DerickC.
