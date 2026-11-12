# Privacy Policy — Tonefer for Android

_Last updated: October 2026_

Tonefer moves notes, photos and files between your phone and your own laptop. It is designed so that **nobody else, including the developer, ever receives your data.**

## What Tonefer collects

**Nothing.** Tonefer has no servers, accounts, analytics, advertising or crash reporting.

## How your data moves

- Data travels only between your phone and your laptop, over a network you control: the phone's hotspot, USB tethering or a shared Wi‑Fi network.
- Every note and file is encrypted end to end with XSalsa20‑Poly1305 using a 256‑bit key. The key is created on your laptop and shared only through the QR code you scan.
- Received files are kept in the app's private storage, and in your gallery or Downloads folder only if you turn on auto-save or tap Save.

## Permissions

| Permission | Why |
|---|---|
| Camera | Scanning the pairing QR code. Images are processed on the device and not stored. |
| Network access | Talking to your laptop on the local network. |
| Notifications | Telling you when something arrives (optional). |
| Foreground service (connected device) | Staying connected to your laptop in the background (optional, Settings → Connection). |

## Your control

- **Unpair** (Settings → Connection) deletes the pairing key from the phone.
- **Clear cache** (Settings → Storage) deletes received files from the app's storage.
- The pairing key is excluded from Android cloud backups and device transfers.

## Gallery

The Gallery reads the list of photos and videos on your phone to show them to you. This needs Android's photos and videos permission. Nothing is uploaded or sent anywhere unless you press Send to laptop.

## Update check

When you open the app, Tonefer asks GitHub which version is newest, and downloads the new version from GitHub if you tap Update. This is the only time the app uses the internet. No personal data, identifiers or transfer contents are sent; GitHub sees your IP address, as any website does. You can turn the automatic check off in Settings → Updates.

## Contact

Tonefer is developed and owned by K S Pankaj. Questions: open an issue on the project's GitHub repository.
