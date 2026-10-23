# SATB

An Android app for writing four-part (SATB) harmony and checking it against common-practice voice-leading rules.

## Features

- Write four voices (S, A, T, B) by tapping notes onto the staff.
- Edit pitch, length, accidentals and ties, with undo/redo.
- Harmony check (✓) marks voice-leading mistakes such as parallel fifths, bad spacing or unresolved tendency tones. Each rule can be turned off.
- Playback (▶) with any SoundFont (`.sf2`) you pick.
- Choose key, time signature, tempo and staff layout.
- Export to MusicXML (MuseScore, Sibelius, Dorico and others).
- Light and dark theme.

## Install

Download the APK from the [Releases](../../releases) page, or build it yourself (see below). Requires Android 8.0 (API 26) or newer.

For playback, pick a SoundFont (`.sf2`) file in Settings. None is bundled.

## Build

Requirements: Android Studio with Android SDK 37, NDK `27.0.12077973`, CMake `3.22.1`.

```sh
./gradlew assembleDebug            # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest        # unit tests
```

## License

Copyright © 2026 Nefelion

SATB is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License v3.0](LICENSE) as published by the Free Software Foundation.
It comes with no warranty.

## Credits

Music font: [Bravura](https://github.com/steinbergmedia/bravura) by Steinberg, under the SIL Open Font License (`app/src/main/assets/Bravura-OFL.txt`).

SoundFont synthesis: [TinySoundFont](https://github.com/schellingb/TinySoundFont) by Bernhard Schelling, under the MIT License (`app/src/main/cpp/tsf.h`).
