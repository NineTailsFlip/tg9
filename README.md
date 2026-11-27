# TG9 — Telegram on 9 keys

An unofficial Telegram client for Android phones with a keypad and no touchscreen.

[![Build](https://github.com/NineTailsFlip/tg9/actions/workflows/build.yml/badge.svg)](https://github.com/NineTailsFlip/tg9/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/NineTailsFlip/tg9)](https://github.com/NineTailsFlip/tg9/releases/latest)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](LICENSE)

TG9 uses the interface and key handling of the [Nine suite](https://git.sr.ht/~timofurrer/nine)
(L9, C9, P9, M9): one column of plain text on black, a numbered `*` menu, a hint bar, and the
phone's own T9 input for typing. Under the hood it runs [TDLib](https://github.com/tdlib/td),
Telegram's official client library. Part of [NineTailsFlip](https://github.com/NineTailsFlip).

> TG9 is not made by Telegram and is not affiliated with it. It talks only to Telegram's servers.

## Features

- **Sign in** by phone number: the code, the two-step password, email verification, and
  registering a new account.
- **Chat list** in Telegram's order, with unread counts, mute state and a live T9 filter by name;
  `#` shows unread chats only.
- **Chats:** reading (oldest at the top, opening on the newest message), replying, replies to a
  message, senders' names in groups, sent/read ticks, day jumps, copying and deleting.
- **Media:** photos, videos, round video messages, GIFs and stickers full screen, with
  Telegram's tiny preview while downloading. Photos zoom and pan; videos pause and seek. Files
  over 15 MB wait for OK. Saved to `Pictures/TG9` or `Movies/TG9`.
- **Sending photos and videos** from TG9's own gallery: by day, thumbnails, up to 10 as an album;
  rotated by EXIF and scaled to at most 2560 px.
- **Voice messages and music:** play in place; record and send voice messages.
- **Files:** save to `Download/TG9` (or Music/Movies by type); pictures and videos sent as files
  open in the viewer.
- **Links, phone numbers, contacts and locations:** open, call, show on a map.
- **Polls:** options and results in the message text.
- **Notifications** through TDLib, following each chat's mute settings, with "Mark as read".
- **No Google push needed:** a background connection (foreground service), which can be turned
  off.
- **Updates** from GitHub Releases.

## Installation

Download the APK from [Releases](https://github.com/NineTailsFlip/tg9/releases/latest)
(`arm64-v8a` for most keypad phones, `armeabi-v7a` for older 32-bit ones) and install it.
After that TG9 checks for a new release once a day and offers it; Android asks you to confirm
each update. Settings → Updates turns the check off or runs it now.

## Usage

Up/Down move, OK opens or acts, Back goes back, `*` opens the numbered menu (send a photo or
video, record a voice message, save a file, open a link…), `#` does the screen's second thing
(unread only, play/pause). While signing in, `*` belongs to the keyboard (Shift, and its command
palette when held), so the phone's Menu key opens the menu there. The full key map is in the app:
Settings → About → Key mapping.

## Building

Requirements: JDK 17+, Android SDK (platform 34, build-tools 34), NDK `27.2.12479018`, CMake,
Ninja and gperf. On macOS:

```bash
brew install openjdk@21 cmake ninja gperf
brew install --cask android-commandlinetools
sdkmanager --sdk_root="$HOME/Library/Android/sdk" "platforms;android-34" "build-tools;34.0.0" "platform-tools" "ndk;27.2.12479018"
```

1. Clone with both submodules (TDLib and nine-core):
   ```bash
   git clone --recursive https://github.com/NineTailsFlip/tg9.git
   ```
   In an existing clone: `git submodule update --init`.
2. Build TDLib once (about 10 minutes; again after bumping `third_party/td`):
   ```bash
   scripts/build-tdlib.sh
   ```
3. Get your own API credentials at <https://my.telegram.org> → API development tools and put
   them in `local.properties` (git-ignored):
   ```properties
   sdk.dir=/path/to/Android/sdk
   tg9.apiId=1234567
   tg9.apiHash=0123456789abcdef0123456789abcdef
   ```
   Without them the app builds, and its login screen says what's missing.
4. Build:
   ```bash
   ./gradlew :app:assembleDebug
   ```
   You get one APK per ABI in `app/build/outputs/apk/debug/`.

Tests: `./gradlew testDebugUnitTest`. Releases are built by GitHub Actions from a
`v<versionName>` tag — see
[RELEASING.md](https://github.com/NineTailsFlip/.github/blob/main/RELEASING.md).

## Project structure

| Path | What's there |
|---|---|
| `app/` | TG9 itself (`dev.tg9`): `tg/` (the TDLib client and cache), `auth/`, `chats/`, `chat/`, `media/`, `notify/`, `settings/` |
| `nine-core/` | Git submodule: [nine-core](https://github.com/NineTailsFlip/nine-core) — the Nine suite's `core/` (unchanged from `~timofurrer/nine` at `ab73f97`), the gallery `kit/` shared with [G9](https://github.com/NineTailsFlip/g9), and `update/` |
| `third_party/td` | TDLib, a pinned git submodule |
| `tdlib/` | TDLib's Java API and `libtdjni.so`, generated by `scripts/build-tdlib.sh`, not committed |

## Requirements and limitations

- Android 14+ (minSdk 34), as the Nine suite.
- Not done yet: secret chats; captions on sent photos and videos; sending files, stickers and
  GIFs; voting in polls; animated (TGS) stickers (a still preview is shown); streaming a video
  before it has downloaded; calls; editing messages; folders and the archive; contacts and
  searching for new chats.

## Contributing

Issues and pull requests are welcome; see
[CONTRIBUTING](https://github.com/NineTailsFlip/.github/blob/main/CONTRIBUTING.md).

## License

TG9 is GPL-3.0-or-later — see [LICENSE](LICENSE) — because it includes the Nine suite's
`core/`, which is GPL-3.0. TDLib is under the Boost Software License 1.0; OpenSSL, linked into
`libtdjni.so`, is under Apache-2.0.

Unofficial clients must not be called "Telegram" or use its logo — see
<https://core.telegram.org/api/obtaining_api_id>.
