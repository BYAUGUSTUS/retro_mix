# Retro Mix 💽

A minimalist retro desktop music player and vinyl workstation built with pure Java OOP and Swing[cite: 1].

---

## Features

- **Animated Turntable**: 60 FPS spinning vinyl record with realistic lighting, grooves, and an animated mechanical tonearm.
- **Embedded Album Art**: Extracts and displays album covers directly on the rotating center label from FLAC files.
- **Interactive Scratching**: Click and drag the record with your mouse to scrub and scratch with authentic needle audio physics.
- **Auto-Crossfade**: Seamless equal-power transitions between tracks as they end.
- **Crate Queue**: Slide-out playlist overlay that lets you browse and queue songs without disturbing the vinyl deck.
- **Folder Sync**: Select a directory to automatically load all `.flac` and `.wav` tracks.

---

## Quick Start

### Prerequisites
- JDK 17 or newer

### Compile & Run
```powershell
# Compile
javac -cp "lib/jflac-codec-1.5.2.jar" -d bin src/engine/*.java src/gui/*.java src/Main.java

# Run
java -cp "bin;lib/jflac-codec-1.5.2.jar" Main
```