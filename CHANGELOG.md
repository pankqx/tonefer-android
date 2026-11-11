# Changelog

## 1.6.0 — November 2026

- One-tap updates: the app checks GitHub when opened, shows a banner when a new version is out, downloads it and opens Android's installer. Settings and pairing are kept
- Settings → Updates: turn the automatic check off, or check by hand

## 1.5.1 — November 2026

- Long transfers no longer stop when the screen turns off: the phone stays awake and the transfer runs as a foreground task with progress in the notification
- The screen stays on while a transfer is visible
- A dropped connection is retried for about five minutes instead of one
- When a transfer fails, the app now says why
- Sending a very large file to an old laptop version shows a message instead of running out of memory

## 1.5.0 — November 2026

- Cancel a transfer: tap ✕ on its progress row
- "Ask where to save" setting: Save opens the system dialog so you choose the folder and name
- Works with laptop guest sessions (Tonefer 1.6 on the laptop)

## 1.4.0 — November 2026

- Send a whole folder as a zip: Attach → Folder as zip. Sub-folders are kept
- Send several files as one zip (Attach → Files as one zip), or turn on "Share several files as one zip" in Settings for the share sheet
- No more 400 MB limit: files of any size move in small encrypted chunks, in both directions, without filling the phone's memory
- A chunk lost to a hotspot hiccup is sent again instead of failing the whole transfer
- Progress and speed for big downloads from the laptop
- Folders shared while the laptop is away are queued and sent as a zip when it's back

## 1.3.0 — November 2026

- New start-up animation built around speed: packets race from phone to laptop over a warp field that keeps accelerating, a live percentage meter, and a hyperjump into the app after five seconds
- "Developed & owned by K S Pankaj" on the start-up screen and in About
- Quieter background connection: no more "looking for your laptop" notification popping up. The notification only changes when the laptop connects, leaves or something is waiting to be sent
- Searching now happens around real use: opening Tonefer or sharing to it starts it, and it winds down about a minute after the laptop leaves (ten minutes when something is still waiting to be sent)
- Android no longer restarts the background connection on its own after closing it

## 1.2.0 — October 2026

- **Connect again** and **Scan pairing code again** in the menu and on the offline banner
- **Turn on hotspot** in the menu
- Looks the same as the phone web app: "?" help button in the top bar, plain background, the same empty state and dividers

## 1.1.1 — October 2026

- Sharp, square corners everywhere. Only the top bar keeps its rounded status pill and logo.

## 1.1.0 — October 2026

- New calmer look: soft rounded windows, filled message bubbles, teal and coral accents
- Five-second start-up screen
- Share while the laptop is away: items are queued, Tonefer opens the hotspot switch, and delivers automatically once the laptop joins
- "Turn on hotspot" button and queued-item count on the home screen, with notifications for queued and delivered items
- Answers speed tests started from the laptop, and shows live MB/s while sending
- "Soft" (default) and "Classic" window styles

## 1.0.0 — October 2026

First release.

- Pair with Tonefer on the laptop by scanning a QR code, or by pasting the pairing link
- Automatic discovery of the laptop over hotspot, USB tethering or Wi‑Fi
- Send notes, photos, videos and files; *Share → Tonefer* from any app
- Receive in the background, with notifications and a *Copy* action on notes
- Auto-save photos and files, auto-copy notes, and choose photo quality when sending
- Customisation: theme, text size, typeface, background, window style
- End-to-end encryption (XSalsa20‑Poly1305), compatible with the laptop app
