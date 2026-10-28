# Aim Track (draft)

Assistive aim tool for Minecraft Java Edition on PojavLauncher, for players who cannot make
fast or precise touch drags (tremor, limited dexterity, cerebral palsy). A separate Android app:
it reads the screen with MediaProjection, finds a highlighted entity near the crosshair, and
nudges the camera with a swipe sent through an AccessibilityService.

## Read this first
- **Single-player or private worlds only.** Do not use it on multiplayer servers. Most servers
  prohibit aim assistance, and using it there can get you banned.
- It does not modify the game, PojavLauncher or Minecraft, injects nothing, uses no network, and
  has no anti-cheat evasion or timing randomization. Behavior is deterministic.
- It only acts while the configured package (default `net.kdt.pojavlaunch`) is in the foreground.
- Screen capture is processed in memory; nothing is stored.

## Build
Open the folder in Android Studio (Ladybug or newer). minSdk 30, compile/target 35, Kotlin 2.0,
AGP 8.7. These versions are a best guess; accept Studio's upgrade suggestions if it offers them.
Studio generates the Gradle wrapper files on first sync. This draft has not been compiled or run.

## Use
1. Enable "Aim Track" under Settings > Accessibility.
2. Grant "display over other apps" (for the bubble and debug overlay).
3. Pick a highlight color (team-color presets provided) and tap Start. Allow screen capture.
4. Switch to the game. Bubble: green = active, amber = paused, gray = game not focused.
   Tap the bubble, or use the notification, to pause/resume.
5. In the game, give nearby mobs the Glowing effect and a team color (private world, commands).
   Vanilla Minecraft draws no outline on its own.

## Settings in plain words
Pixel values are in reference pixels (1080 px short side) and scale with your screen.

| Setting | Meaning |
|---|---|
| Assist strength | Share of the measured offset corrected per swipe (0..1). |
| Gain | Swipe pixels per pixel of offset. Calibrate with test mode. |
| Smoothing | Low = steadier, slower to react. High = snappier, noisier. |
| Deadzone | Offsets smaller than this are ignored (keep it above your crosshair jitter). |
| Max step | Longest single swipe. Caps overshoot. |
| Cooldown / delay / hold / speed | Pause after a swipe, wait before a swipe, minimum contact time, swipe speed. |
| Hue tolerance, min saturation, min brightness | What counts as "the highlight color". |
| Max blob size, stable frames | Reject huge areas (sky, terrain); require the target to persist. |
| Lock | Keep following one entity until it vanishes or jumps away. |

"Strictness" in this app = the color settings (how picky the match is) plus stable frames and
deadzone (how sure it must be before moving).

## Tuning: false positives vs false negatives
- **False positive** = aiming when no entity is there (camera drifts toward grass, lava, a block
  of similar color). Disorienting; the player loses control.
- **False negative** = missing a real entity (no assist that moment). The player simply aims as usual.
- Tightening color tolerance, raising min saturation/brightness, lowering max blob size and
  raising stable frames all reduce false positives and raise false negatives. Loosening does the reverse.
- Because a false positive is worse than a miss, prefer to err slightly tight.

Procedure:
1. Turn on the debug overlay. It shows the detection region, the target marker, and the average
   color of the best blob. Look at a highlighted mob in bright light, shade and at night.
2. Set hue tolerance to about 1.5x the hue spread you see; set min saturation/brightness to
   about 70% of the lowest values you see.
3. Walk around with no highlighted mob in view for a minute. Any "blobs 1+" there is a false
   positive: tighten until it stays at 0.
4. Assist strength: start at 0.4-0.6. Raise it until the offset readout starts flipping sign
   after a swipe (overshoot), then back off about 20%. Lower strength is slower but stable.
5. Test mode (swipes right, left, down, up at a fixed interval) shows which way and how far one
   swipe turns the camera. Use that to set invert X/Y and gain. Test mode sends raw vectors with
   no inversion, only runs while the game is focused, and switches off when the service stops.

## Known limits
- No highlight, no detection. Plain color detection cannot find un-highlighted mobs.
- Overlays (bubble, debug) are captured by MediaProjection. Debug drawings use a color opposite
  your target's hue, but white log text can interfere with a white target. Turn debug off for play.
- Landscape is assumed. Rotation is not handled.
- Injected swipes can collide with the player's own touches. There is no back-off yet.
- Focus gating relies on accessibility window events and can be wrong around some system dialogs.
