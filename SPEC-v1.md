# QR Cards — v1 Spec

## Purpose

QR Cards is a tiny, free-forever Android app for people who show QR codes to other humans. It generates, organizes, and presents QR codes — contact cards for the different parts of your life (work, social, personal), guest Wi-Fi codes, saved places — so the right code is one tap away when you need it.

No ads. No accounts. No cloud. No paid tiers. Ever — structural, not a setting.

## Who it's for

Anyone who regularly shares contact info, Wi-Fi access, or a location by having someone scan their phone screen.

## v1: what it does

1. **Create cards** — URL, contact (vCard), Wi-Fi network, geographic location, plain text, email, phone number, SMS, calendar event. Each card has a name, an optional label/color, and an optional "sensitive" flag.
2. **Library** — cards listed and searchable; tap a card to view it.
3. **Present mode** — full-screen QR at maximum brightness, for reliable scanning in sunlight or bad lighting.
4. **Quick access** — per-card app shortcuts (long-press the launcher icon to jump straight to a card in present mode). Home-screen widgets are deferred: most users only have room for a few widgets, so we'll wait for real user demand.
5. **Share / export** — send a card as a PNG image through any app, or download high-resolution PNG and SVG files (for marketing materials, signage, print shops). Vector SVG export is paywalled in competing apps; here it's free.
7. **Backup** — local export file, restore from file. Plain JSON by default (always restorable, on any install or device); optional password encryption (AES-256-GCM, portable). No cloud involved.
8. **Correctness** — every generated code scans with the stock Android and iOS cameras. Wi-Fi payloads escaped properly, vCard 3.0, sensible error-correction defaults. A code that "scans but doesn't work" is a bug, not a platform quirk.

## Non-goals (v1)

- **No scanning.** Phone cameras already do this well; it's a different user scenario.
- **No sharing/sync between devices.** Deferred to v2 (see below).
- **No cloud backup, accounts, or analytics.**
- **No "dynamic" / editable QR codes.** They require a server and expire when you stop paying — a trust-killer pattern in this category.
- **No ads, in-app purchases, or subscriptions.** (Repeated because it matters.)

## v2 candidates (not v1)

- **Card sharing between devices** — e.g. put together a set of codes and get them onto a family member's phone easily, without accounts or a server.
- **Home-screen widgets** — only if users ask for them; shortcuts cover the fast-access need in v1.

## Quality bars

- **Fully offline.** Works in airplane mode. Stretch goal: the app requests no INTERNET permission at all.
- **Minimal permissions.** No contacts access (you type exactly what you want to share — that's the point), no location access.
- **Small.** Single-digit megabytes installed.
- **Verified cross-platform.** Every code type is scanned with stock Android and iOS cameras before release.
- **Honest privacy.** Sensitive cards require confirm-before-display. Creating a Wi-Fi card shows a plaintext reminder that the password is baked into the code — and nudges toward a guest network, never the main LAN.

## Technical notes

- Kotlin. ZXing core for generation (mature, Apache 2.0; the encoder is stable despite the project being in maintenance mode).
- Contacts: vCard 3.0 default (widest real-world support); MECARD only when code size demands it. Keep contact cards lean — name, phone, email, org, URL; no photos.
- Wi-Fi: `T:WPA` (phone and router negotiate the actual mode); backslash-escape `\ ; , : "` in SSID/password; never trim leading/trailing spaces; `H:true` only for hidden SSIDs.
- Error correction: M for on-screen display, H for print/export.
- Storage: local only. Backup file plain JSON by default (always restorable); optional password encryption.

## Open questions

(none — all resolved 2026-10-03)
