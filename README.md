# EclipseMusic

EclipseMusic is a modern, high-end Android music player built with a stunning "Premium Dark-Neon Glassmorphism" design language.

## Features

- **Premium UI/UX:** Stunning bento-grid layouts, neon purple and blue accents, and glassmorphic translucent UI cards.
- **YouTube Audio Resolver:** Automatically fetch and play high-quality audio streams seamlessly from YouTube without downloading heavy MP4s.
- **Discord Rich Presence (RPC):** Native Discord RPC integration! Share exactly what you are listening to in real-time, including accurate progress bars, album art, and smart pause/resume detection.
- **Advanced Audio Equalizer:** Built-in equalizer support for fine-tuning bass, treble, and vocal clarity.
- **Voice Recognition:** Full-screen voice search powered by Shazam (RapidAPI) with an aesthetic neon-pulse animation. Identify songs playing around you instantly.
- **Last.fm Scrobbling:** Automatically scrobble your listening history to Last.fm.
- **Local & Remote Playback:** Unified playback engine for both local device audio files and remote streams.

## Installation

You can download the latest APK build directly from the `releases` folder in this repository:

[Download EclipseMusic APK](releases/app-debug.apk)

## Building from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/kagetechnology/eclipsemusic.git
   ```
2. Open the project in **Android Studio**.
3. Sync Gradle and build the project.
4. Run on a connected Android device or emulator.

## Tech Stack

- **Platform:** Android (Java/Native)
- **UI Architecture:** Custom Layouts, Views, and XML Glassmorphism.
- **Dependencies:** 
  - `OkHttp3` for networking
  - `NewPipeExtractor` for YouTube stream resolution
  - `Discord Gateway WebSocket` for Rich Presence
  - `Shazam API` for audio fingerprinting

## Design Philosophy

The Eclipse application embraces a dark, immersive aesthetic (`#252540` and `#ADC7FF`) designed to feel like a premium, specialized music hub rather than a generic app. 

---
*Created by KageTechnology*
