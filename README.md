<div align="center">

# Tonefer for Android

**Private, offline transfer between your phone and your laptop.**

The Android companion to [Tonefer](https://github.com/pankqx/tonefer). Share notes, photos and files to your laptop in one tap, end-to-end encrypted, over your own hotspot, USB cable or Wi‑Fi.

</div>

---

## Features

| | |
|---|---|
| **Share → Tonefer** | Send from Gallery, Files, Chrome or any app in one tap, without opening Tonefer. |
| **Finds your laptop** | Pair once by QR code. Tonefer then finds the laptop by itself on your hotspot, USB tethering or Wi‑Fi, even when its address changes. |
| **Background delivery** | Notes and photos from the laptop arrive while the app is closed, with *Copy* on note notifications. |
| **Auto-save & auto-copy** | Photos go to `Pictures/Tonefer` and notes to the clipboard, each optional. |
| **End-to-end encrypted** | XSalsa20‑Poly1305 (NaCl `secretbox`) with a 256‑bit key exchanged only through the QR code. No servers or accounts. |
| **Customisable** | Theme, text size, typeface, background, window style, photo quality, notifications and background connection. |
| **Old-school by design** | Paper-and-ink windows with pinstriped title bars and IBM Plex Mono, matching the laptop app. |

## Install

1. Download **[tonefer.apk](https://github.com/pankqx/tonefer-android/releases/latest/download/tonefer.apk)** (latest release).
2. Open it on your phone and allow *Install unknown apps* for your browser or file manager when asked.
3. Open **Tonefer → Scan pairing code**, and scan the code shown by Tonefer on your laptop.

Requires Android 10 or newer, and Tonefer running on the laptop ([setup guide](https://github.com/pankqx/tonefer/blob/main/GUIDE.md)).

## Customisation (Menu ⋮ → Settings)

| Section | Options |
|---|---|
| Appearance | Theme (System / Light / Dark) · Text size · Typeface (Mono / Sans) · Background (Dotted / Plain) · Windows (Classic / Flat) · Reset |
| Transfers | Save received photos & videos · Save other files · Auto-copy notes · Photo quality when sending (Original / High / Balanced) |
| Notifications | Notify on arrival · Vibrate |
| Connection | Stay connected in background · Paired laptop · Find laptop · Unpair |
| Storage | Clear cache |

## Architecture

```
app/src/main/java/com/pankqx/tonefer/
├── crypto/SecretBox.java      XSalsa20-Poly1305, dependency-free, verified against PyNaCl
├── data/                      Settings (DataStore), pairing link parsing, history items
├── net/Discovery.kt           Finds the laptop by probing local subnets with the pairing token
├── net/ToneferClient.kt       WebSocket session, encrypted upload/download, reconnect loop
├── service/                   Foreground service for background delivery, notifications
├── share/ShareActivity.kt     Invisible "Share → Tonefer" target
└── ui/                        Jetpack Compose: pairing, timeline, settings, instructions
```

The wire protocol is documented in [tonefer/docs/PROTOCOL.md](https://github.com/pankqx/tonefer/blob/main/docs/PROTOCOL.md).

## Build from source

```bash
./gradlew testDebugUnitTest assembleRelease
```

JDK 17 and the Android SDK (API 35) are required. Every push is built and tested by GitHub Actions. Builds of `main` publish the release named in `VERSION` with the APK attached. Bump `VERSION` to start a new release.

### Release signing

Without signing secrets, CI signs with a temporary debug key. That's fine for trying the app, but Android then can't update it in place. To sign every build with your own key, add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `TONEFER_KEYSTORE_BASE64` | `base64 -w0 tonefer-release.jks` |
| `TONEFER_KEYSTORE_PASSWORD` | Keystore password |
| `TONEFER_KEY_ALIAS` | Key alias (e.g. `tonefer`) |
| `TONEFER_KEY_PASSWORD` | Key password |

Keep the keystore safe and backed up. Every future update must be signed with it.

## Privacy

Tonefer has no servers, accounts, analytics, ads or crash reporting. See [PRIVACY.md](PRIVACY.md).

---

<sub>© 2026 Pank. All rights reserved. Third-party components are listed in the app under About → Credits.</sub>
