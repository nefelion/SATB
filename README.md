<p align="center"><img src="docs/images/logo.svg" width="128" alt="SATB logo: a treble staff with four colored notes rising"></p>

# SATB

An Android app for writing four-part (SATB) harmony and checking it against common-practice voice-leading rules.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/hero-dark.png">
  <img alt="A seven-bar chorale in G major with chord symbols above and Roman numerals below" src="docs/images/hero-light.png">
</picture>

## Features

- Write four voices by tapping notes onto the staff, or play them on a USB or Bluetooth MIDI keyboard.
- Harmony check against 25 voice-leading rules (parallel fifths and octaves, leading tone, doubling, spacing …), each explained with an example.
- Figured bass and given soprano exercises: write the other voices around a given bass or melody, check them, or ask for a hint or a full solution.
- Chord symbols, Roman numerals and named non-chord tones.
- Playback with any SoundFont (`.sf2`) and export to MusicXML.

<img alt="Harmony check: parallel octaves highlighted in the score, with the list of errors and warnings below" src="docs/images/check.png">

Tap a mistake to see the notes involved. The ⓘ button explains the rule with an example.

<img alt="Phone screens: harmony check, a rule explanation, and the list of rules" src="docs/images/phone.png">

## Install

Download the APK from the [Releases](../../releases) page, or build it yourself (see below). Requires Android 8.0 (API 26) or newer.

For playback, pick a SoundFont (`.sf2`) file in Settings. None is bundled.

## Build

Requirements: JDK 17+, Android SDK 37, NDK `27.0.12077973`, CMake `3.22.1`. Android Studio is optional; the command line is enough.

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
