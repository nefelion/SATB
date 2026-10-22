# SATB

An Android app for writing four-part (SATB) harmony and checking it against common-practice voice-leading rules.

Tap notes onto the staff, hear them, and let the app point out parallel fifths, bad spacing, unresolved tendency tones and more.

## Features

- **Fast note entry**: pick a voice (S, A, T, B) and tap the staff. Input moves to the voice that is furthest behind, so chords fill left to right.
- **Voice lock**: hold a voice button to write one whole line (for example a melody or a bass line) before the others.
- **Movement mode**: tap the active voice button again to scroll, zoom and select without adding notes.
- **Editing**: drag a note to change its pitch, change lengths and accidentals, tie and untie notes, delete single tied heads or whole bars, undo/redo.
- **Engraving**: real music notation (SMuFL / Bravura font): ties across barlines and beats, beams, ledger lines, chord symbols and Roman numerals.
- **Harmony check** (✓): highlights problems in the score. Each rule can be turned off:
  parallel and direct fifths/octaves, unequal fifths, voice crossing and overlap, spacing, range, melodic intervals, leap handling,
  cross relations, all voices in one direction, tendency tones, leading tone, seventh resolution, six-four chords, harmonic rhythm,
  doubling, missing third, non-chord tones, unrecognized chords, missing voices.
- **Voice order help**: finds block chords whose voices were entered in the wrong order and offers to swap them.
- **Playback** (▶): low-latency native synth (AAudio) with any SoundFont (`.sf2`) you pick.
- **Score settings**: key, time signature, tempo, clefs and staff layout (SATB on 1–4 staves, including keyboard style).
- **Export**: MusicXML, for MuseScore, Sibelius, Dorico and other notation programs.
- Light and dark theme, welcome tutorial.

## Install

Download the APK from the [Releases](../../releases) page, or build it yourself (see below). Requires Android 8.0 (API 26) or newer.

For playback, pick a SoundFont (`.sf2`) file in Settings. None is bundled.

## Build

Requirements: Android Studio with Android SDK 37, NDK `27.0.12077973`, CMake `3.22.1`.

```sh
./gradlew assembleDebug            # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest        # unit tests
```

## Project layout

```
app/src/main/java/com/nefelion/fourpartwriting/
  model/     Score, events, pure edit functions
  engrave/   Note splitting (Notation) and layout of the score
  theory/    Chord analysis and voice-leading rules
  audio/     Kotlin side of the native synth
  export/    MusicXML export
  ui/        Jetpack Compose screens and the score canvas
app/src/main/cpp/   Native SoundFont synth (AAudio)
```

## License

Copyright © 2026 Nefelion

SATB is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License v3.0](LICENSE) as published by the Free Software Foundation.
It comes with no warranty.

## Credits

Music font: [Bravura](https://github.com/steinbergmedia/bravura) by Steinberg, under the SIL Open Font License (`app/src/main/assets/Bravura-OFL.txt`).
