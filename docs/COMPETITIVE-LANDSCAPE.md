# Competitive Landscape — Android QR Generator Apps

**Research date:** 2026-10-03
**Purpose:** Feature matrix to decide QR Cards' next moves (v0.0.3+).
**Scope:** Android apps that *generate* QR codes (not pure scanners). Public listing data only — Play Store listing text, appbrain.com, androidrank.org, apk mirrors. Play Store text fetches return description text only (no rating/install metadata rows), so install/rating stats come from third-party crawls (Aug–Oct 2026) and are estimates. Anything not verifiable is marked **unverified**. Play data changes; re-verify before citing numbers publicly.

**QR Cards baseline (v0.0.2, for comparison):** genuinely free — no ads, no IAP, no subscriptions, no accounts, fully offline (no `INTERNET` permission). Generates 9 typed payloads (URL, contact/vCard, Wi-Fi, geographic location, plain text, email, phone, SMS, calendar event). Searchable library with color labels, full-screen present mode at max brightness with confirm-before-display for sensitive cards, per-card launcher shortcuts, PNG share sheet + high-res PNG/SVG export, plain-JSON backup (default) with optional AES-256-GCM password encryption. No scanning. MIT licensed.

---

## Per-app findings

### 1. QR & Barcode Scanner — Gamma Play (`com.gamma.scan`)
- **Developer:** Gamma Play Limited (Hong Kong)
- **Scale:** Play bucket **500,000,000+**; third-party estimates ~840M–1B total; 4.8 rating (~4.5–4.8M ratings); #3 in Tools; v2.2.224 updated 2026-07-30
- **Monetization:** free, **contains ads** (tracking flagged by third parties). No IAP, no subscription. Separate paid app **QR & Barcode Scanner PRO** (`com.gamma.scan2`), **$9.99 one-time**, 1M+ installs, 4.66–4.69 rating — listing describes it only as the ad-free version; no stated feature difference.
- **Generation payloads:** free-text/arbitrary data only ("simply enter the data you wish"), contact sharing, generate from clipboard. No typed Wi-Fi/vCard/calendar/email/SMS/location forms.
- **Extras:** scan history with favorites, **CSV/TXT export, CSV import**, batch scan mode, scan from image/gallery, app theme colors. No PNG/SVG code export stated. No widgets, shortcuts, backup/sync, offline claim.

### 2. QR & Barcode Scanner — TeaCapps (`com.teacapps.barcodescanner`)
- **Developer:** TeaCapps GmbH (Germany)
- **Scale:** Play bucket **100,000,000+**; estimates up to ~500M total; 4.6 rating (~3.75M ratings)
- **Monetization:** free, **ad-supported** (Facebook Ads/AdMob). No IAP. Separate paid app **QR & Barcode Scanner Pro** (`com.teacapps.barcodescanner.pro`), **$6.99 one-time**, 100k+ installs, 4.66 rating — ad-free variant, no stated feature difference.
- **Generation payloads:** free-text/arbitrary only ("Share arbitrary data such as website links"). Typed payloads (URL, vCard, calendar, Wi-Fi, geo, phone, email/SMS) are *scan*-side only.
- **Extras:** unlimited annotated scan history, **CSV export**, minimal-permissions pitch, Chrome Custom Tabs + Safe Browsing. No code customization, no PNG/SVG export, no widgets/shortcuts/backup/offline claim.

### 3. QRbot — TeaCapps (`net.qrbot`)
- **Developer:** TeaCapps GmbH
- **Scale:** Play bucket **5,000,000+** (~6M est.); 4.70 rating (~36k ratings); v3.3.6 updated 2026-03-26
- **Monetization:** free, **contains ads** (Facebook Ads/AdMob). No IAP, no subscription. 1-star reviews complain about **aggressive tracking-consent prompts and deceptive-looking ads** — a reputational opening.
- **Generation payloads:** free-text/arbitrary only ("CREATE AND SHARE" section). No typed forms.
- **Extras:** unlimited history, CSV export, flashlight/zoom, scan from images, Safe Browsing. No code customization, no PNG/SVG export, no widgets/shortcuts/backup/offline claim.

### 4. QR Droid — GELLINER LIMITED / Zapper (`la.droid.qr`)
- **Developer:** footer now shows GELLINER LIMITED (Zapper successor; historic dev DroidLa)
- **Scale:** ~**50,000,000+** installs (androidrank), 4.14 rating (~346k ratings)
- **Monetization:** **unverified** — listing shows no ads/IAP badges. Integrates **Zapper commerce/checkout hooks** (register/login/purchase with participating sites). Old copy claimed ad-free; not a current claim.
- **Generation payloads:** contact/bookmark, maps, installed apps, Wi-Fi, PayPal payments (older copy), "XQR codes" for large text/contact.
- **Extras:** history sortable/groupable, **auto-sync to Google Drive**, **widgets** (separate "QR Droid Widgets" download), color organization. No code customization or SVG export mentioned.

### 5. QRito: QR Code Generator — Panagiotis Moschos (`ai.datanous.qrito`)
- **Developer:** Panagiotis Moschos (Greece)
- **Scale:** **unverified** — no indexed stats; small/niche
- **Monetization:** **unverified** — no ads/IAP mentioned anywhere in listing
- **Generation payloads:** **URL only** — single payload type
- **Extras:** **local searchable library** (save/update/delete), share via Email/WhatsApp/Messenger, copy image/export for print. Closest in spirit to QR Cards' library concept, but URL-only. No customization, widgets/shortcuts/backup/offline claim.

### 6. Instant QR Code Generator — JBMSOFT (`com.JBMSOFT.qrcode`)
- **Developer:** JBMSOFT (South Korea)
- **Scale:** **unverified** — no indexed stats
- **Monetization:** **contains ads, "minimized"** ("No personal data is ever collected or transmitted. Ads are minimized."). No IAP/paid unlock mentioned — **unverified** whether any exist.
- **Generation payloads:** URL, plain text, contact info, Wi-Fi credentials, notes (5 types)
- **Extras:** **512×512+ high-res output**, auto-save to gallery, one-tap share sheet, **fully offline**, privacy-first positioning. Roadmap (not shipped): bulk generation, custom logos, styling. No history/library, widgets/shortcuts/backup.

### 7. QR Code Generator — YenKu / YKART (`com.ykart.tool.qrcodegen`)
- **Developer:** YenKu (appbrain; "YKART" variant on apkfab)
- **Scale:** Play bucket **1,000,000+** (~4.9M est.); **3.78 rating** (~9.6k ratings); v1.9.22 updated 2026-07-07
- **Monetization:** **contains ads** (reviews cite heavy ad load — "79 ad brokers" — and Play Protect "risky app" warnings). **Pro Pass** paywalls the customization layer: 30+ premium shape templates and **lossless SVG export** ("Available with Pro Pass"). **Pro Pass price: unverified** — no public source lists it; one-time vs subscription unknown. Needs live-browser check of the Play listing's in-app-purchases section.
- **Generation payloads:** broadest coverage — URL, text, email, SMS, phone, location, vCard contact, Wi-Fi, **vEvent calendar**, **Venmo/Cash App + Pix/UPI/PromptPay/PayNow/SEPA GiroCode payments**, Instagram/WhatsApp/social, photo backgrounds.
- **Extras:** custom colors/gradients, 30+ pro shapes/eyes (Pro), center logos, export **SVG (Pro)**/PNG/JPG/WEBP, smart history, dark mode, 60 FPS scanner with quick actions. No widgets, shortcuts, backup/sync, bulk generation.

### 8. QR Code Generator Offline — MCMLV1, LLC (`org.mcmlv1.qrcodegenerator`)
- **Developer:** MCMLV1, LLC (Trinity, FL)
- **Scale:** **unverified** — no indexed stats
- **Monetization:** **no ads, no tracking, no subscription** (explicit). **PRO = one-time purchase** — **price unverified**. Free tier: first code free (any of 18 types), gallery save, history shows 3 most recent. PRO unlocks: unlimited codes of every type, **WiFi QR**, **vCard**, **SVG export**, custom filenames, full history (50 codes).
- **Generation payloads:** 18 types — URL, text, email, phone, SMS, WhatsApp, location, Google Review, vCard, logo, event, app link, social (IG/TikTok/YT/FB/LinkedIn/X), PayPal, UPI, crypto, WiFi, eSIM, lost-and-found.
- **Extras:** **Backup Zip** (all codes + history into one file, copy to Drive/email/USB, restore on new phone), auto-save to gallery, share to any app, **fully offline, requests no internet permission**, light/dark themes. Generator-only, no scanner. No widgets/shortcuts.

### 9. QRBot: QR Code Generator, Scan — ROBUST RESEARCH AND DEVELOPMENT LTD. (`ltd.rrad.apps.qr.robust_qr`)
*Note: different developer from TeaCapps' QRbot.*
- **Developer:** Robust Research and Development Ltd. (Dhaka, Bangladesh)
- **Scale:** **unverified** — no indexed stats
- **Monetization:** **subscription-based** — Play listing carries full subscription boilerplate. **Price unverified**; ads unverified. Generation + Customization Studio (colors, logo, eyes, patterns) sit behind the paywall; exact free-vs-premium boundary **unverified**.
- **Generation payloads:** URLs, text, app links, Wi-Fi, vCard/contact, social profiles, events, locations.
- **Extras:** Customization Studio (custom colors/backgrounds, center logo, QR eyes/patterns), export **JPEG/PNG/PDF** (no SVG mentioned), scanner with decorated scan results. No history/favorites, library, widgets, backup, offline claim.

### 10. QR Scanner: Scan & Create / Fast QR Scanner: Read & Create (`qr.code.barcode.scanner.generator.reader`)
*Title differs between Play cache and AppBrain; package identical.*
- **Developer:** UAE Apps by Alex
- **Scale:** Play bucket **1,000,000+** (~1.5M est.); 3.63 rating (~2.5k ratings)
- **Monetization:** **contains ads**. No IAP or subscription found — **unverified**.
- **Generation payloads:** URLs, text, email, phone, SMS, contacts, calendar events, apps, WiFi (9+ types).
- **Extras:** batch continuous scanning, scan from gallery, **scan history auto-saved, filterable by type, with favourites**, generated codes shareable/savable to gallery. Claims "Works Offline". No code customization, no stated export formats, no backup/sync, no widgets.

### 11. QR Maker: QR code Generator — Meteor Rain (`com.qrcreator.meteorrain`)
- **Developer:** Meteor Rain
- **Scale:** Play bucket **500,000+** (~630k est.); 4.35 rating (~6.2k ratings)
- **Monetization:** **contains ads**. Listing claims "Unlimited free QR code generation". No IAP/subscription found — **unverified**.
- **Generation payloads:** URL, text/SMS, contact/vCard/business card, Wi-Fi, email, calendar events, locations, phone, Bitcoin wallets, WhatsApp, PDFs/documents, QR from images, audio links, branding templates.
- **Extras:** strongest free customization — solid colors + gradients (code & background), module/corner shapes, center logo, captions, pre-built templates; in-app library/history; export **PNG/JPEG/PDF** (no SVG); **print layout tool** (rows/columns/spacing for labels & business cards); scanner with history; **works offline**; phone+tablet adaptive UI. No backup/sync, no widgets/shortcuts.

---

## Comparison table

| App | Dev | Installs (Play bucket / est.) | Rating | Ads | Paid model | Typed payloads | Code customization | SVG export | PNG export | History/organize | Present mode | Shortcuts/widgets | Backup | Offline |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| QR Cards (ours) | Derick Campbell | — | — | **No** | Free, MIT | **9 types** | Color labels (library), no code styling | **Free** | **Free, hi-res** | **Searchable library + color labels** | **Full-screen max-brightness + sensitive confirm** | **Per-card shortcuts** | **JSON plain default + AES option** | **Yes, no net perm** |
| QR & Barcode Scanner — Gamma Play | Gamma Play | 500M+ / ~840M–1B | 4.8 | Yes | $9.99 one-time PRO (ad-free only) | Free-text only | App theme only | No | No (CSV/TXT history) | Favorites + CSV | No | No | No (CSV export) | Unverified |
| QR & Barcode Scanner — TeaCapps | TeaCapps | 100M+ / ~500M | 4.6 | Yes | $6.99 one-time Pro (ad-free only) | Free-text only | No | No | No | Unlimited history, CSV | No | No | No (CSV export) | Unverified |
| QRbot — TeaCapps | TeaCapps | 5M+ / ~6M | 4.70 | Yes | Free, ads only | Free-text only | No | No | No | Unlimited history, CSV | No | No | No | Unverified |
| QR Droid | GELLINER/Zapper | ~50M+ | 4.14 | Unverified | Unverified (Zapper commerce hooks) | Contact, bookmark, maps, apps, Wi-Fi, PayPal | No | No | Unverified | History sortable/grouped | No | **Widgets** | Google Drive sync | Unverified |
| QRito | P. Moschos | Unverified | Unverified | Unverified | Unverified | **URL only** | No | No | Yes (copy image) | Local searchable library | No | No | No | Unverified |
| Instant QR Code Generator | JBMSOFT | Unverified | Unverified | Minimized | None visible (unverified) | URL, text, contact, Wi-Fi, notes | No | No | Yes (512px+) | Gallery save only | No | No | No | **Yes** |
| QR Code Generator | YenKu | 1M+ / ~4.9M | 3.78 | Yes (heavy) | **Pro Pass** (price unverified): 30+ shapes + SVG | URL, text, email, SMS, phone, location, vCard, Wi-Fi, **vEvent**, **payments**, social | Colors, gradients, logos, shapes (Pro) | **Pro only** | Yes | Smart history | No | No | No | Unverified |
| QR Code Generator Offline | MCMLV1 | Unverified | Unverified | **No** | One-time PRO (price unverified): **WiFi, vCard, SVG**, full history | 18 types | Logo codes | **Pro only** | Yes | History (3 free / 50 pro) | No | No | **Backup Zip** | **Yes, no net perm** |
| QRBot — Rrad | Rrad Ltd. | Unverified | Unverified | Unverified | **Subscription** (price unverified) | URL, text, app links, Wi-Fi, vCard, social, events, locations | Colors, logos, eyes, patterns (paywalled) | No (PDF instead) | Yes | None | No | No | No | Unverified |
| QR Scanner: Scan & Create | UAE Apps by Alex | 1M+ / ~1.5M | 3.63 | Yes | None found (unverified) | URL, text, email, phone, SMS, contacts, calendar, apps, WiFi | No | No | Unverified | History + favourites, filterable | No | No | No | Claims yes |
| QR Maker — Meteor Rain | Meteor Rain | 500k+ / ~630k | 4.35 | Yes | None found (unverified) | URL, text, SMS, vCard, Wi-Fi, email, calendar, location, phone, Bitcoin, WhatsApp, PDF | Colors, gradients, shapes, logos, captions, templates | No | Yes | Library/history | No | No | No | **Yes** |

---

## Where QR Cards wins / gaps to consider

### Where QR Cards already wins (v0.0.2)
1. **Free + ad-free, fully offline, no tracking.** The entire top tier is ad-supported (Gamma Play, TeaCapps, YenKu, Meteor Rain) or paywalled. The only ad-free competitors found are MCMLV1 (one-time PRO, paywalls WiFi/vCard/SVG) and the unverified-small QRito/JBMSOFT. This is the headline differentiator — lead with it.
2. **Typed payload library, not free-text.** The three biggest apps (Gamma Play, TeaCapps scanner, QRbot) generate *free-text codes only*; typed payloads are scan-side features. QR Cards' 9 typed card types + named, color-labeled, searchable library has no direct equivalent — the closest are QRito (URL-only library) and QR Maker (library, no labels/shortcuts).
3. **SVG export free.** SVG is the single most commonly paywalled generator feature (YenKu Pro Pass, MCMLV1 PRO; QR Maker omits it entirely). QR Cards ships it free.
4. **Present mode.** Full-screen, max-brightness display with sensitive-card confirmation appears nowhere in the surveyed apps. Unique.
5. **Per-card launcher shortcuts.** Not advertised by any competitor. (QR Droid has widgets, a heavier alternative Derick already rejected.)
6. **Backup that can't strand you.** MCMLV1's Backup Zip is the only comparable feature, but it's app-local proprietary. QR Cards' plain-JSON-default (+ optional AES) backup is more portable.

### Gaps / opportunities to consider
1. **Code styling (colors, logos, shapes, gradients).** QR Maker offers the richest *free* styling; YenKu paywalls it. This is the most visible feature gap vs. "pretty QR" apps. *Opportunity:* optional per-card code styling (foreground color at minimum) — but keep codes scannable-first; QR Cards' identity is utility, not marketing design. Low priority unless users ask.
2. **Payment QR types (Venmo/Cash App, UPI, Pix, GiroCode, crypto).** YenKu, MCMLV1, and QR Maker all offer payment/currency payloads; QR Cards has none. *Opportunity:* a generic "payment link/address" text-adjacent card type could cover much of this without per-provider work.
3. **Print layouts.** QR Maker's print layout tool (rows/columns/spacing for labels and business cards) targets the signage/marketing use case QR Cards already serves via PNG/SVG export. *Opportunity:* a multi-card sheet export would strengthen the print story without building a full layout editor.
4. **Bulk/batch generation.** JBMSOFT lists bulk generation on its roadmap; nobody surveyed ships it. Underserved; plausible v2-adjacent feature for event/retail use.
5. **CSV history export** (Gamma Play, TeaCapps) — trivially matchable if users care; the JSON backup already covers the data portability case better.
6. **Scan-side features.** Deliberately out of scope for v1 (Derick's decision, 2026-10-03). The giants are scan-first; QR Cards' differentiation comes from being generate/organize-first. Hold the line.

### What NOT to copy
- **Ads or any paid tier.** The market's uniform monetization (ads + one-time ad-free PRO) is exactly what QR Cards positions against; 1-star reviews on QRbot/YenKu complain about aggressive ads and tracking consent.
- **Subscription models** (Rrad QRBot). Antithetical to the project.
- **Zapper-style commerce hooks** (QR Droid). Scope creep + trust risk.
- **Widgets** — already deferred by Derick until users ask; shortcuts cover the quick-access case.

### Open verification items
- YenKu "Pro Pass" price and billing model (one-time vs subscription) — needs a live Play listing check of the In-app purchases section.
- MCMLV1 PRO one-time price — not published; needs in-app or live listing check.
- Rrad QRBot subscription price and free/premium boundary — needs live listing check.
- Install/rating stats for QR Droid, QRito, JBMSOFT, MCMLV1, Rrad QRBot, QR Maker — Play buckets unverified via text fetch; androidrank/appbrain pages were not indexed for several packages.
