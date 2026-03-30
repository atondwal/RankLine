# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

Built from Termux on Android. Java 17, Gradle plugin 8.5.0, compileSdk/targetSdk 34, minSdk 24. No tests, no linter, no CI.

## Architecture

RankLine is an Android app for ranking images and videos by placing them on a continuous (-1, 1) number line. The core interaction is "zoom-scrub": finger Y controls exponential zoom (`1/(1-t)^4`), finger X pans, and the screen center determines placement position.

### Source Files (all in `app/src/main/java/com/example/rankline/`)

- **RankLineView.java** — Custom `View` that owns all drawing and touch handling. Contains the zoom-scrub math, coordinate system (`center`/`currentZoom`/`visibleWidth()`), item clustering, tick mark rendering, and a touch state machine managing idle browsing, inbox placement, and item repositioning. This is the largest and most complex file.

- **MainActivity.java** — Hosts `RankLineView`, implements its `Listener` interface. Manages the inbox queue (`ArrayDeque<RankedItem>`), browse mode navigation, video playback (ExoPlayer overlay), image loading (Glide), and JSON persistence to `rankings.json`.

- **RankedItem.java** — Data class with fields: `id` (UUID), `position` (double in -1..1), `imageUrl`, `thumbnail` (Bitmap), `label`, `isVideo`.

### Key Concepts

- **Inbox mode**: New items queue up; bottom card shows preview. Drag card up to enter zoom-scrub placement. Swipe left/right to skip through queue.
- **Browse mode**: View ranked items in order. Entered by tapping a thumbnail or menu. Auto-zooms to show item between its neighbors. Swipe down on card to remove item back to inbox.
- **Repositioning**: Long-press a thumbnail or drag up from browse card. Second finger tap cancels. Items snap back if barely moved.
- **Mode bar**: Bottom pills ("Inbox", "Browse") shown when no card is active.
- **Video**: ExoPlayer `PlayerView` overlays the card image area. Auto-muted, looping. Tap card to toggle mute.

### Persistence

`rankings.json` stores items array, view state (center/zoom), and last browse cursor. Saved on every place/reposition. Loaded in `onCreate`.

### Dependencies

Glide 4.16.0 (images/GIFs), Media3 ExoPlayer 1.2.1 (video), AppCompat 1.6.1, Material 1.11.0.
