# Smooth scrolling: what to test on the phone

This fork adds pixel-smooth scrolling to the terminal view. There are two parts:

- **Phase 1, native scrollback.** This is the plain shell, with mouse tracking off and not on the
  alternate screen. A drag moves the scrollback by pixels, and a fling is a pixel fling.
- **Phase 2, apps that scroll themselves.** This covers tmux with `mouse on`, nvim, less, man and
  Claude Code. A swipe still sends one wheel event (or arrow key) per row of finger travel, as
  before. When the app answers by scrolling its region (LF at the bottom margin, `CSI S`/`CSI T`,
  IL/DL, reverse index), that region glides into place instead of jumping. When it answers by
  redrawing (Claude Code), the shift is found by comparing the screen before and after, and
  glides the same way. The rows that just scrolled out fill the band the glide uncovers.

## One tmux line to add

tmux's copy mode draws a position marker in the pane's top-right corner (`09:14 [190/255]`). It
sits on the pane's top row, so every copy of it that scrolls out is kept as one of the rows that
fill the band during a glide, and they stack up into a smear. Hide it in your tmux config
(tmux 3.5 or newer, check with `tmux -V`):

```tmux
set -g copy-mode-position-format ''
```

Then run `tmux source-file ~/.config/tmux/tmux.conf` (or wherever yours lives). The cost is
losing the position readout in copy mode.

## Reading in the plain shell

For reading only, `nvcat` (a file with nvim's own highlighting), `ccview` (a Claude Code
transcript including thinking, tool calls and output) and `rd` (picks the right one) print into
the plain shell, which has the perfect phase 1 scrolling. See `tools/reading/README.md` to
install them and for their options.

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

Each feature has its own switch, so you can turn off whichever one misbehaves. All default to
`true`. Run `termux-reload-settings` after editing; no restart is needed.

```properties
# Plain shell scrollback dragged and flung by pixels (phase 1). false = stock row-by-row scrolling.
# Since this build it no longer switches the app features below off.
terminal-smooth-scroll = true

# Apps that scroll themselves (tmux, nvim, less, Claude Code):
# a fling sends its wheel events (or arrow keys) at the fling's slowing rate, not in a burst.
terminal-smooth-scroll-app-fling = true
# wheel events (or arrow keys) wait until the app has answered the ones before, so a fast swipe
# or fling arrives in batches it can keep up with instead of a backlog. No rows are dropped.
terminal-smooth-scroll-app-coalesce = true
# scroll commands the app sends while you scroll it glide (nvim, tmux copy mode, less).
terminal-smooth-scroll-app-regions = true
# scrolls the app performs by redrawing while you scroll it are detected and glide (Claude Code).
terminal-smooth-scroll-app-repaints = true

# Default 600 (ms, your Ghostty value; it was 120), range 0-2000. How long the glides take: app scrolls, a mouse wheel notch,
# shift+PgUp/PgDn. 0 = no glides at all (both app switches above have no effect). Finger drags and
# flings of the scrollback never wait on it.
terminal-scroll-animation-duration = 120
```

Values worth trying for the duration: `260`, `400`, `600`, `800`. Lower feels snappier, higher
smoother but further behind the finger.

To tell which feature causes a problem, turn them off one at a time: `app-repaints` first, then
`app-regions`, then `app-coalesce`, then `app-fling`.

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

### 3. nvim inside tmux (phase 2, scroll commands)

This needs `terminal-smooth-scroll-app-regions = true`. Open `tmux`, then `nvim` on a long file
(for example `:e $PREFIX/share/nvim/runtime/doc/options.txt`, about 9000 lines). Keep
`mousescroll=ver:1` as configured. Do each step with `:set nowrap` and again with `:set wrap`.

1. **Slow swipe, both directions.** Each row of finger travel is still one wheel event (1:1), and
   the text slides into place instead of jumping row by row.
2. **Fling, both directions.** The wheel events arrive at a steady, slowing rate, not in a burst,
   and the glide is continuous. Your `scroll.lua` triples the step when events arrive under 25 ms
   apart, so a fast fling travels farther. That comes from your config, not this build.
3. **Pinned rows.** The tmux status line, nvim's status line and command line, and the tabline
   (if shown) must never move. Only the text area slides.
4. **Split nvim windows.** In `:split` (one window above the other), only the window you swipe
   slides; the other and both status lines stay. In `:vsplit` (side by side), nvim redraws
   instead of scrolling. If that glides at all, only the swiped window may move. A plain jump is
   fine.
5. **The cursor and line numbers.** `:set number relativenumber`. The numbers slide with the text
   and then show the right values. The cursor moves with its line.
6. **Short region.** In a `:split` window only 5 rows high, swipe fast. Rows that scroll out keep
   showing in the band until the glide ends, with no blank flashes.
7. **tmux copy mode.** Swipe in a shell pane (not nvim). Copy mode's text slides and the status
   line stays. With the tmux line above, there is no position marker to smear.
8. **Nothing animates without a finger.** Typing in nvim, `:` commands, `G`/`gg`, `cat bigfile`
   in a pane, and compiler output must all jump as before. Only output within 300 ms of a swipe
   glides.

**Failure looks like:** a status line or command line that slides; a blank band at the edge of the
text area mid-glide (expected only past 48 rows of lag, on very fast flicks); text that slides the
wrong way, or slides and then jumps back; a seam where part of the window is a row out of step
with the rest; output gliding with no finger on the screen.

**Switch to try:** `terminal-smooth-scroll-app-regions = false` stops the glides and keeps the
steady fling.

9. **Multi-row scrolls (the lag fix).** Swipe fast, and fling hard, in a long file. The text
   should keep up with the finger and stop soon after the finger stops, with no long run-on
   after it while nvim catches up. Compare with `terminal-smooth-scroll-app-coalesce = false`
   (the previous build's behaviour). If it is still laggy, say whether the text is **behind**
   the finger (and keeps moving after you stop) or **choppy** (moves, but in visible steps).

### 4. Claude Code inside tmux (phase 2, repaint detection)

This needs `terminal-smooth-scroll-app-repaints = true`. Claude Code redraws its screen instead of
sending scroll commands, so this build compares the screen before and after each redraw while
you are scrolling. When the transcript moved by whole rows in the direction you swiped, it slides
it. Open a session with a long transcript (resume an old one), in tmux.

1. **Slow swipe up and down in the transcript.** The transcript slides instead of jumping. The
   input box, the footer under it, and the tmux status line stay still.
2. **Fast swipe and fling, both directions (the lag fix).** Wheel events now wait for Claude
   Code to finish redrawing before the next batch goes out, so it does not fall behind. While you
   scroll, drawing waits up to 12 ms (32 ms at most if output keeps streaming) for each redraw to
   arrive whole. As with nvim, compare with `terminal-smooth-scroll-app-coalesce = false`, and
   say whether any remaining lag is "behind" or "choppy".
3. **Scroll while Claude is answering.** It is streaming output, so the transcript changes while
   it moves. Expect it to glide less often, or to jump when the change is too big to recognise.
   It must never slide a wrong-looking screen.
4. **"Jump to bottom" label, bullets.** The label, and the bullets in the first column, may slide
   with the transcript row they sit on. (Ghostty pins them; this build compares whole rows, so it
   cannot.) Report it if it looks bad, not just because it moves.
5. **Typing, a long answer arriving, `/` menus, the session picker.** No finger on the screen
   means no glide, ever.
6. **Side-by-side tmux panes.** Claude Code in one, a shell in the other. Scrolling Claude Code
   does not glide there, because no whole screen row moved. That is deliberate (no tearing), not
   a bug.

**Failure looks like:** the input box or status line slides; a frame where the transcript
flashes to its new position and then slides from the old one (a half-drawn redraw being shown);
a slide in the wrong direction; a slide by the wrong distance that then jumps (rows that look
alike, such as long runs of `───`, can fool it); a glide with no finger on the screen.

**Switch to try:** `terminal-smooth-scroll-app-repaints = false` turns off only this. Everything
else keeps working.

### 5. less (phase 2, the arrow-key path)

Run `less /data/data/com.termux/files/usr/share/doc/*/README* 2>/dev/null` (or `man bash`). With
no mouse tracking, a swipe sends arrow keys.

- **Swipe and fling.** The text should glide. Flings now also work here (stock Termux did nothing
  on a fling in less).
- **The `:` prompt on the last row should stay still.**

### 6. Off switches

- `terminal-scroll-animation-duration = 0`: no glides anywhere, the native drag stays
  pixel-smooth, and the steady app fling still works. There must be no freeze (the Ghostty fork
  once hung at 0).
- Each of the four `terminal-smooth-scroll*` switches turns off only its own feature.
- All four `false`: everything behaves exactly like stock Termux.

yazi isn't covered, as agreed; it should behave as before.

### 7. Volume down as the keyboard key

Volume down is still the virtual Ctrl key when held. A quick tap now shows or hides the keyboard,
like the KEYBOARD extra key.

1. **Tap volume down** (under 0.4 s). The keyboard appears; tap again and it hides.
2. **Hold volume down and press `c`** on the keyboard. That sends Ctrl-C, and the keyboard does
   not toggle when you let go.
3. **Hold volume down for a second and let go,** with nothing typed. Nothing happens.
4. `volume-down-tap-action = none` in `termux.properties` (then `termux-reload-settings`) turns
   the tap off. `volume-keys = volume` still turns both volume keys back into volume keys.

## Reporting back

For each problem, give: the section and step number above, the app (shell, tmux, nvim, less, Claude Code), the switches you had on, the gesture
(slow drag, fast drag, fling, tap), the `terminal-scroll-animation-duration` value, and what you
saw. A screen recording helps a lot for glide problems: a 60 fps recording shows the frames
where something tears or jumps.
