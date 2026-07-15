# OpenMoto — Android Auto & screen mirroring for CFMoto dashboards

Run **Android Auto** (or mirror any app) on a CFMoto MotoPlay dashboard straight from your phone. This is a community fork of
[dcoletto/open-cfmoto](https://github.com/dcoletto/open-cfmoto) that adds full **CFMoto 800NK
(US-market)** support, a **screen-mirror** mode, **bike saving**, and an
**in-app touch control surface**

> ⚠️ **This is a proof-of-concept, not a product.** It talks to your dash over an
> undocumented, reverse-engineered protocol. It can misbehave or disconnect. **Do not fiddle with the
> phone or interact with the dash while riding** — set everything up while stopped, then ride. Use at
> your own risk.

---

## Supported bikes

Bike support is selected automatically from the pairing QR + the dash's handshake (see
[`BikeProfile.kt`](app/src/main/java/dev/coletz/opencfmoto/BikeProfile.kt)). Any unrecognised dash
falls back to the legacy (675) profile.

| Bike | Dash family | QR modelId | Panel | Status |
|------|-------------|-----------|-------|--------|
| **CFMoto 675 SR-R** | CFDL16 (sdk 0.9.29.1) | `37416` | Landscape ~800×480 | ✅ Original upstream target — behaviour preserved *(see note)* |
| **CFMoto 675 SS (US)** | CFDL16 (assumed) | *(no QR)* | Landscape (675-class, assumed) | 🧪 **Manual Wi-Fi entry** — dash shows no pairing QR; type the hotspot SSID/password by hand. Unverified |
| **CFMoto 800NK (US)** | CRCP (sdk 0.9.23.4) | `66660703` | Landscape 800×400, Wi-Fi Direct | ✅ **Fully working**, confirmed on-bike |
| **CFMoto 1000 MT-X** | CFDL26 / MotoPlay (sdk 1.1.4) | `37426` | Portrait ~800×951 | 🧪 **Experimental** — handshake incomplete, never confirmed end-to-end |

**Notes**
- The **800NK** is the only bike this fork's author owns and has verified end-to-end.
- The **675 SR-R** path is preserved byte-for-byte from upstream and is the guaranteed fallback, but it
  has not been *re-run* on this branch — if you have a 675 SR-R, a confirmation report is very welcome.
- The **675 SS** (US-market) reportedly broadcasts a Wi-Fi hotspot but shows **no pairing QR**. For it,
  tap **"No QR? Enter Wi-Fi"** on the pairing screen and type the hotspot's SSID/password by hand — it
  maps to the legacy 675 profile and the handshake re-scores from there. This path is **unverified**; a
  report from a 675 SS owner would confirm it. (OpenMoto ships no credentials — you supply your own.)
- The **1000 MT-X** profile connects and handshakes but the dash never opens its media ports (it likely
  needs a real sock-server auth exchange that isn't implemented yet). Contributions with a 1000 MT-X to
  test against are the fastest way to finish it.

---

## Features

- **Android Auto on the dash** — the phone runs Android Auto in loopback ("self") mode and streams it
  to the dashboard over CFMoto's EasyConn protocol.
- **Screen Mirror** — mirror the whole phone screen instead, to put *any* app on the dash (e.g. a nav
  app AA doesn't support). Run the app in **landscape** for a full-screen picture; the phone screen
  must stay on while mirroring.
- **Remember the bike** — scan the pairing QR once (or, for a bike with no QR, enter its Wi-Fi by
  hand); after that a single tap reconnects.
- **In-app control surface** — a compact live view of Android Auto that you can **touch to control**
  (set nav, pick music, change the view) before pocketing the phone, plus a fullscreen mode.

### What it deliberately does **not** do

- **No vehicle telemetry.** Fuel, speed, gear, trip data etc. are **not available** over this link —
  it's a display/mirroring channel only. (Confirmed: the dash exposes no telemetry over Wi-Fi, and the
  bike's BLE service is auth-only. Riding data goes to CFMoto's cloud over cellular.)
- **Handlebar buttons don't control Android Auto** — the dash consumes them natively and forwards
  nothing. Use the in-app touch surface, a Bluetooth handlebar remote paired to the *phone*, or voice.
- **No hardware key / d-pad input to AA.** It was tried; advertising keycodes puts Android Auto into
  focus mode, which destabilises the video stream. Touch-only is the stable path.

---

## How it works (short version)

The **phone acts as the head unit**. It:
1. Reads the dash's pairing QR (SSID / password / model id) and joins the dash's Wi-Fi (a Wi-Fi Direct
   group on the 800NK, a plain AP on the 675).
2. Runs Google Android Auto in loopback self-mode and decodes its H.264 video.
3. Re-encodes/letterboxes that video to the dash's native size and serves it over the PXC protocol
   (the phone is the TCP *server*; the dash connects back and pulls frames).
4. For the control surface, injects touch back into Android Auto over the AAP input channel.

Per-dash quirks live in `BikeProfile` implementations, so the shared handshake/video code stays generic.

---

## Requirements

- An **Android phone**, Android 10+ (minSdk 29), with **Google Android Auto** installed and set up.
- A **CFMoto bike with MotoPlay Capability** (the dashboard QR-pairing screen).
- For sideloading: a computer with the Android SDK platform tools (`adb`).

## Build & install

Toolchain: **JDK 17**, Android **SDK platform 36** + **build-tools 36**. Gradle 9.4.1 / AGP 9.2.1 come
via the wrapper.

```bash
# from the repo root
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Installed app id: `io.github.negligentnarwhal.openmoto`. (The internal Kotlin namespace is kept as
`dev.coletz.opencfmoto` so upstream changes still merge cleanly.)

## Usage

1. Navigate to the bike's **QR connect screen**.
2. Open the app, accept permissions when prompted, tap **PAIR BIKE** and scan the dash QR — or, if your
   bike shows no QR, tap **NO QR? ENTER WI-FI** and type the hotspot's name and password.
3. Tap **ANDROID AUTO** (or **MIRROR**). Accept the Wi-Fi prompt if asked. The stream should appear on the dash after some time.
4. Use the **compact control view** to set up nav/music; tap **⛶** for a fullscreen touch view.

---

## Contributing a new bike

Add a `BikeProfile` in
[`BikeProfile.kt`](app/src/main/java/dev/coletz/opencfmoto/BikeProfile.kt) and register it in
`BikeProfiles.all`:

- `matchesModelId(...)` — coarse match from the QR `modelId` (picks the Android Auto resolution before
  connecting).
- `score(info)` — claim strength from the dash's CLIENT_INFO (highest positive score wins).
- `aaVideo` — the AA resolution/orientation/dpi to request for this panel.
- Capability flags — `requiresPhoneHeartbeat`, `requiresSockServerAuth`, `supportsScreenTouch`,
  `advertisedSupportFunction`, and optionally `encoder` (per-dash H.264 params).
- `handleUnknownControl(...)` — reply to any extra control frames this dash needs acked.

A capture from the Python prototyping harness makes reversing a new dash much easier.

---

## Credits

This project stands on a chain of others' work:

- **[headunit-revived](https://github.com/andreknieriem/headunit-revived)** by **André Rinas**
  ([@andreknieriem](https://github.com/andreknieriem)) — the Android Auto self-mode (AAP) implementation
  this app's `aa/` package is ported from. Huge thanks; dcoletto's work and this fork wouldn't exist without it.
- **[open-cfmoto](https://github.com/dcoletto/open-cfmoto)** by **dcoletto** — the original CFMoto 675
  SR-R proof-of-concept and the `BikeProfile`/PXC foundation this forks. Another huge thanks. This fork wouldn't exist without dcoletto's work. 
- **[richardbizik/open-cfmoto](https://github.com/richardbizik/open-cfmoto)** — Wi-Fi Direct + video
  support for the 1000 MT-X.
- **OpenMoto** (this fork) by **[NegligentNarwhal](https://github.com/NegligentNarwhal)** — CFMoto
  800NK (US) support, screen-mirror mode, remembered-bike/auto-connect, and the in-app Android Auto
  touch control surface.

The app icon uses the "motorbike" glyph from
**[Material Design Icons](https://pictogrammers.com/library/mdi/)** (Pictogrammers), Apache-2.0.
The UI font is **[Space Mono](https://fonts.google.com/specimen/Space+Mono)** (SIL Open Font License,
see `docs/SpaceMono-OFL.txt`).

Background & discussion:
[r/cfmoto thread](https://www.reddit.com/r/cfmoto/comments/1uuu63m/working_on_android_auto_on_cf_moto_at_least_675/).

## Interoperability & legal

OpenMoto is a **non-commercial, open-source interoperability project**. It lets a device you own (your
phone) talk to another device you own (your bike's dashboard) over the dashboard's own wireless link,
using the credentials the bike hands to its owner.

- It ships **no CFMoto code and no CFMoto credentials**, and **circumvents no access control** — the
  wire protocol was reverse-engineered by observing traffic, for the sole purpose of interoperability.
  For bikes with no pairing QR, *you* supply your own bike's Wi-Fi credentials; the app embeds none.
- It talks only to **hardware you own**, over a local link. It does not touch CFMoto's servers or
  account, and reads no vehicle telemetry.
- It is **free** and not monetised.

Please keep it that way in any fork: non-commercial and interoperability-focused, shipping no
manufacturer code or credentials. Use it with your own bike, at your own risk. **None of this is legal
advice.**

## License

Because this app incorporates code from **headunit-revived**, which is licensed under the
**GNU Affero General Public License v3.0 (AGPL-3.0)**, this project is **AGPL-3.0** as well. If you
distribute it — including running a modified version as a network service — you must make your source
available under the same license.

CFMoto and MotoPlay are trademarks of their respective owners. This project is not affiliated with,
endorsed by, or supported by CFMoto or Google.
