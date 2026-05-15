# TaukiTauki

Basque flick-input keyboard for Android.

Each key supports eight flick directions, allowing fast syllable entry without
leaving the home position. Tap a key to type its base syllable; flick in any
direction for the variants. A swap button cycles through related forms
(e.g. `ka → ga → ca`).

## Features

- Flick-input Basque layout with morphological rotation groups
- Word suggestions, bigram predictions, and emoji lookup (fully offline)
- QWERTY fallback with accent flicks
- Basque and English UI

## Build

Requires Python 3 (generates the dictionary database at build time).

```bash
./gradlew assembleRelease   # signed release APK (needs keystore.properties)
./gradlew assembleDebug     # debug APK
```

See `keystore.properties.example` for signing setup.

## Install

Open the **TaukiTauki** app after installing, and follow the two-step guide to
enable and select the keyboard.

## License

MIT — see [LICENSE](LICENSE).
