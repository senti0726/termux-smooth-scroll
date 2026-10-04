# Smooth scrolling: what to test on the phone

This fork adds pixel-smooth scrolling to the terminal view. There are two parts:

- **Phase 1, native scrollback.** This is the plain shell, with mouse tracking off and not on the
  alternate screen. A drag moves the scrollback by pixels, and a fling is a pixel fling.
- **Phase 2, apps that scroll themselves.** This covers tmux with `mouse on`, nvim, less and man.
  A swipe still sends one wheel event (or arrow key) per row of finger travel, as before. When the
  app answers by scrolling its region (LF at the bottom margin, `CSI S`/`CSI T`, IL/DL, reverse
  index), that region glides into place instead of jumping. The rows that just scrolled out fill
  the band it uncovers.

## Getting the build

The workflow runs on every push to this branch. Pick the arm64 artifact of the latest successful
**Build** run:

```sh
gh run list -R senti0726/termux-smooth-scroll -w Build -L 3
gh run download <run-id> -R senti0726/termux-smooth-scroll \
  -n 'termux-app_v0.118.0+<sha7>-apt-android-7-github-debug_arm64-v8a'
```

`<sha7>` is the first 7 characters of the commit the run built. The run page shows the exact
artifact name. The APK is signed with `testkey_untrusted.jks`, the same key as the GitHub releases
of Termux:API and Termux:Boot. `applicationId`, `sharedUserId` and `versionCode` (118) are
unchanged.

## Properties (`~/.termux/termux.properties`)

```properties
# Default true. false restores the stock row-by-row code paths exactly.
terminal-smooth-scroll = true

# Default 120 (ms), range 0-2000. How long a stepped scroll glides: an app's scroll (tmux, nvim,
# less), a mouse wheel notch, shift+PgUp/PgDn. 0 = no glide (jump), and app scrolls are not
# animated at all. Finger drags and flings are never delayed by it.
terminal-scroll-animation-duration = 120
```

Run `termux-reload-settings` after editing. No restart is needed. For an A/B comparison, flip
`terminal-smooth-scroll` and reload.

Values worth trying: `0`, `80`, `120`, `180`, `260` (the Ghostty presets).

## What to try

### 1. Plain shell scrollback, drag and fling (phase 1)

Use the stock shell, with no tmux. Run `seq 1 5000` first to fill the scrollback.

- **Slow drag up and down.** The text should follow the finger pixel for pixel, with no 1-row
  steps.
- **Fling up.** It should decelerate smoothly and stop at the top of the scrollback, showing the
  first line, with no overshoot or jump. Fling back down to the bottom: it should stop exactly
  aligned to the grid, with the prompt on the last row.
- **Touch during a fling.** It should stop where it is.
- **Stop mid-row after a drag.** The top row may stay partly cut off. That is expected.
- **Then type a key.** The output scrolls you to the bottom and snaps to the grid.
- **Open or close the soft keyboard while scrolled back.** The resize snaps to the grid, and the
  row count should stay what it was before this build. Watch for the mosh row drift.
- **Pinch-zoom while scrolled back.** It should snap with no garbled rows.
- **Large scrollback.** Set `terminal-transcript-rows = 50000`, run `seq 1 60000`, then fling
  repeatedly. Look for stutter or GC pauses (none are expected: no per-frame allocations).

**Failure looks like:** the text jumps in whole rows; a blank band or a duplicated row at the top
or bottom edge while dragging; a fling that stops off the grid at the very bottom; a fling that
keeps going after a key press; a row of the wrong content flashing at the bottom.

### 2. Tapping and selecting while offset (phase 1)

Drag so that the top row is about half cut off. Then:

- **Long-press a word.** The selection must start on the word under your finger, not one row
  above or below. Drag both handles. They must sit under the selected rows, and the selected text
  (Copy, then paste somewhere) must match what is highlighted.
- **Tap a URL** (with `terminal-onclick-url-open=true`). It must open the URL on the row you
  tapped.
- **The selection toolbar** (Copy/Paste/More) must sit next to the selection.

**Failure looks like:** the selection or the URL lands exactly one row off. Note whether it is
above or below, and how far the top row was cut off (about a quarter, a half, or almost all).

### 3. tmux with `mouse on` + nvim on a long file (phase 2)

Open `tmux`, then `nvim` on a long file (for example `:help` or a big source file). Keep
`mousescroll=ver:1` as configured.

- **Slow swipe.** Each row of finger travel is still one wheel event (1:1). The text should now
  slide into place instead of jumping row by row.
- **Fling.** The wheel events now arrive at a steady, slowing rate, not in a burst. The glide
  should look continuous. Your `scroll.lua` raises the step to 3 when events arrive under 25 ms
  apart, so a fast fling travels farther. That is your config, not this build.
- **The tmux status line and nvim's status line and command line must never move.** Only the text
  area slides.
- **tmux copy mode** (swipe in a shell pane, outside nvim): the pane slides and the status line
  stays.
- **Split panes.** In a horizontal split (one pane above the other), only the pane you scroll
  moves. A vertical split (side by side) usually is not animated, because tmux redraws instead of
  scrolling. A jump there is expected.
- **Typing in nvim, `:` commands, `cat bigfile` in a pane, compiler output.** None of this should
  animate. Only output within 300 ms of a swipe animates.

**Failure looks like:** the status line or command line slides; a band of blank rows at the edge
of the text area during the glide (expected only on very fast flicks of 24+ rows, which is the lag
cap); text that slides the wrong way, or slides and then jumps back; a tear where the left and
right parts of the screen are out of step; plain output (no finger on the screen) gliding.

### 4. less (phase 2, the arrow-key path)

Run `less /data/data/com.termux/files/usr/share/doc/*/README* 2>/dev/null` (or `man bash`). With
no mouse tracking, a swipe sends arrow keys.

- **Swipe and fling.** The text should glide. Flings now also work here (stock Termux did nothing
  on a fling in less).
- **The `:` prompt on the last row should stay still.**

### 5. yazi

yazi (ratatui) redraws the screen instead of sending scroll commands. It is **not expected to
animate** in this build, because the repaint detector (the stretch goal) is not implemented. What
to check is that nothing regressed: swipes still move the selection one entry per row, flings
send steady events, and there are no visual artifacts. The same applies to Claude Code.

### 6. Off switches

- `terminal-scroll-animation-duration = 0`: app scrolls jump instantly and the native drag stays
  pixel-smooth. There must be no freeze (the Ghostty fork once hung at 0).
- `terminal-smooth-scroll = false`: everything behaves exactly like stock Termux.

## Reporting back

For each problem, give: the section number above, the app (shell, tmux, nvim, less), the gesture
(slow drag, fast drag, fling, tap), the `terminal-scroll-animation-duration` value, and what you
saw. A screen recording helps a lot for glide problems: a 60 fps recording shows the frames
where something tears or jumps.
