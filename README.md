<p align="center">
  <img src="res/drawable-nodpi/jetbird_logo.png" alt="JetBird icon" width="128">
</p>

<h1 align="center">JetBird</h1>
<p align="center"><strong>Fast multi-part downloads. Direct to your PS4. Over USB or Wi‑Fi.</strong><br>by nueex</p>

> **Project summary:** JetBird is an open-source Android download manager and PS4 companion app by nueex built for [PS MTP Explorer](https://github.com/nueex/ps-mtp-explorer). Download files in up to 8 parallel parts, stream transfers directly from your phone to a jailbroken PlayStation 4 over USB or certificate-pinned Wi‑Fi, install `.pkg` files while downloading with Parallel Install, or hand off HTTPS links for console-side Direct PS4 Download — all with a built-in ad-blocking browser.

<p align="center">
  <a href="https://github.com/nueex/jetbird/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/nueex/jetbird?style=for-the-badge&color=8b5cf6"></a>
  <img alt="Platform: Android 10+" src="https://img.shields.io/badge/Android-10%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white">
  <img alt="Companion: PS4 Homebrew" src="https://img.shields.io/badge/PS4-Companion-006FCD?style=for-the-badge">
  <img alt="Supports Parallel PKG Install" src="https://img.shields.io/badge/Supports-Parallel_Install-06b6d4?style=for-the-badge">
  <a href="LICENSE"><img alt="License: GPL-3.0" src="https://img.shields.io/badge/License-GPL--3.0-blue?style=for-the-badge"></a>
  <a href="https://ko-fi.com/S6S01X1LYP"><img alt="Support on Ko-fi" src="https://img.shields.io/badge/Support-Ko--fi-ff5e5b?style=for-the-badge&logo=kofi&logoColor=white"></a>
</p>

<p align="center">
  <a href="https://github.com/nueex/jetbird/releases/latest"><strong>Download APK</strong></a> ·
  <a href="#getting-started">Getting started</a> ·
  <a href="BUILD.md">Build from source</a> ·
  <a href="#transfer-modes">Transfer modes</a> ·
  <a href="#in-app-browser">Browser</a> ·
  <a href="https://github.com/nueex/jetbird/issues">Report an issue</a>
</p>

---

## Everything in one place

| Capability | What you can do |
| :--- | :--- |
| **Multi-part download manager** | Download files in **1, 2, 4 or 8 parallel parts** with automatic single-stream fallback, queue management, pause and verified resume. |
| **USB Transfer (Direct Cable)** | Stream downloads directly from Android to your PS4 over a USB data cable using Android Open Accessory — no local network required. |
| **Wireless Transfer (QR & 6-Digit Pairing)** | Pair with PS MTP Explorer over local Wi‑Fi by scanning a QR code or entering a 6-digit SPAKE2 code, protected by certificate-pinned TLS. |
| **Direct PS4 Download** | Hand off an HTTPS download link (with required browser headers) to the console over USB or Wi‑Fi so the PS4 downloads directly — even after disconnecting your phone. |
| **Parallel PKG Install** | Stream compatible `.pkg` files straight into the PS4 installer as bytes arrive, without waiting for the full file to finish first. |
| **Optional phone copy** | Keep downloads on the PS4 only (streamed through memory), or enable **Save File on Phone** to keep a verified copy on Android too. |
| **Built-in ad-blocking browser** | Browse and capture downloads directly inside JetBird with multi-tab support, bookmarks, history, Desktop Site mode, search engine choice and EasyList/uBlock-powered ad blocking. |
| **Organized downloads** | Automatically categorizes phone downloads under `Download/JetBird` (Compressed, Packages, Videos, Audio, Images, Documents, Other) or saves to any custom SAF folder. |
| **Background execution** | Runs reliably in the background and with the screen locked via Android foreground services, live speed/progress notifications and battery optimization guidance. |
| **7 languages & RTL support** | Switch between English, French, Arabic (full RTL layout), Spanish, Russian, Simplified Chinese and Hindi with bundled typography and Light/Dark/System themes. |

## Getting started

### What you need

- An **Android 10 (API 29) or later** device.
- For PS4 transfers: a jailbroken **PS4 with GoldHEN active** running **[PS MTP Explorer 3.0](https://github.com/nueex/ps-mtp-explorer/releases/latest)** or newer.
- A **USB data cable** (for USB Transfer) or a **shared local Wi‑Fi network** (for Wireless Transfer).

### Install and open

Download **`JetBird-v1.0.apk`** from [Published releases](https://github.com/nueex/jetbird/releases/latest) (or compile it yourself using [BUILD.md](BUILD.md)) and install it on your Android device.

1. **Choose a transfer mode** in **Settings → Transfer Mode** (**None** for phone-only downloads, **USB Transfer**, or **Wireless Transfer**).
2. **Pair or connect to your PS4** when using USB or Wireless: open **Settings → Direct Download** inside [PS MTP Explorer](https://github.com/nueex/ps-mtp-explorer) on your console and select the matching mode.
3. **Start a download** by tapping **+**, pasting or sharing a link, or opening the built-in **Browser** and tapping any file download.

## Transfer modes

| Mode | How it works | Storage outcome |
| :--- | :--- | :--- |
| **None (Default)** | Standard high-speed multi-part Android download manager. | Saves to `Download/JetBird/<Category>` or your chosen custom folder. |
| **USB Transfer** | Connects directly to PS MTP Explorer over a USB data cable (Android Open Accessory). | Streams directly to `/psmtp/Downloads/` on PS4; optionally keeps a phone copy. |
| **Wireless Transfer** | Pairs via QR code or 6-digit SPAKE2 code over local Wi‑Fi with certificate-pinned TLS. | Streams directly to `/psmtp/Downloads/` on PS4; optionally keeps a phone copy. |
| **Direct PS4 Download** | Sends the resolved HTTPS link and browser headers to PS4 over USB or Wireless. | Console downloads directly over HTTPS; your phone can disconnect once handed off. |
| **Parallel Install** | Available for compatible `.pkg` files over USB, Wireless or Direct PS4 Download. | Installs on PS4 while bytes arrive, cleaning up temporary package data automatically. |

> **Tip for background reliability:** Disable Android battery optimization for JetBird when prompted on the Home screen so long downloads and PS4 transfers continue smoothly with the screen locked.

## In-app browser

Open the browser from the header menu to find and hand off downloads directly inside JetBird:

- **Ad & pop-up blocker:** Filters network requests, pop-ups and cosmetic elements using bundled EasyList, EasyPrivacy and uBlock Origin lists.
- **Full browsing tools:** Multiple tabs with restore across restarts, bookmarks, persistent history, pull-to-refresh, **Desktop Site** toggle, and selectable search engine (**Google**, **Bing**, or **DuckDuckGo**).
- **Smart download handoff:** Automatically detects attachment filenames (`Content-Disposition`) and file sizes before starting or queueing a download.
- **Privacy controls:** Clear cache, cookies, WebView storage and browsing history from **Browser Settings → Clear browsing data** while preserving bookmarks.

## Build from source

See [BUILD.md](BUILD.md) for the required Android SDK, NDK, JDK and standalone Kotlin compiler setup, standalone build commands, and release signing instructions. All essential Kotlin, native JNI/SPAKE2 pairing sources, resources, fonts and filter lists are included in this repository.

## Common questions

<details>
<summary><strong>Do I need a PS4 to use JetBird?</strong></summary>

No. When **Transfer Mode** is set to **None** (the default), JetBird works as a standalone multi-part Android download manager with a built-in ad-blocking browser.
</details>

<details>
<summary><strong>Which PS MTP Explorer version is required for PS4 transfers?</strong></summary>

USB Transfer, Wireless Transfer, Direct PS4 Download and Parallel Install require **[PS MTP Explorer 3.0](https://github.com/nueex/ps-mtp-explorer/releases/latest)** or newer on your PS4.
</details>

<details>
<summary><strong>Does my phone need to stay connected during Direct PS4 Download?</strong></summary>

No. Once JetBird displays **Sent to PS4**, the console downloads the file directly over its own HTTPS connection and you can disconnect your phone.
</details>

<details>
<summary><strong>Does JetBird store a copy on my phone during PS4 transfers?</strong></summary>

Not by default. **Save File on Phone** is turned off so downloads stream through memory straight to the PS4. Enable it if you want a verified copy saved on Android as well.
</details>

## Feedback

[Open an issue](https://github.com/nueex/jetbird/issues) with your Android device and OS version, transfer mode (None, USB, Wireless or Direct PS4 Download), PS MTP Explorer version if applicable, and steps to reproduce the issue.

## Support / Donate

If JetBird and PS MTP Explorer make managing your PS4 easier, you can support development on Ko-fi. Thank you for helping the project continue.

[![Support me on Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/S6S01X1LYP)

## Credits & third-party notices

**Original Author: nueex — Copyright (C) 2026 nueex**. Built with Kotlin, Android SDK/NDK, [BearSSL](https://www.bearssl.org/), [ZXing Core](https://github.com/zxing/zxing), Inter, Readex Pro, Noto Sans and open-source filter lists (EasyList, EasyPrivacy, uBlock Origin).

See [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) for complete component attributions and upstream license terms. JetBird is an independent open-source project and is not affiliated with Sony Interactive Entertainment or Google.

## License

JetBird is free software licensed under the **GNU General Public License v3.0 (`GPL-3.0-or-later`)**. You may redistribute and/or modify it under the terms of the GNU GPL version 3 or (at your option) any later version. It is distributed **WITHOUT ANY WARRANTY**, including MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.

See [LICENSE](LICENSE) for the full license text and [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) for third-party component notices.

---

<p align="center"><strong>Android download manager | PS4 Direct Download | USB &amp; Wireless PKG transfer | Companion for PS MTP Explorer</strong><br>JetBird by nueex</p>
