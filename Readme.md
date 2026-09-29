# Retro Mix 💽

**Retro Mix** is a dedicated offline, high-fidelity audio workstation and vintage turntable player engineered for purists who demand **studio-grade, bit-perfect local audio reproduction** without streaming compression, telemetry, or system bloat.

Built from the ground up in pure Java using low-level Digital Signal Processing (DSP) and custom 2D hardware-accelerated vector graphics, Retro Mix merges the warmth of analog crate digging with precision modern audio mixing.

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
