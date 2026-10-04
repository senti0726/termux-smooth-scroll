package com.termux.terminal;

/**
 * Tests that {@link RepaintScrollDetector} finds a scroll an app performs by redrawing (as Claude
 * Code does), only in the direction scrolled, keeps pinned and status rows out of it, and finds
 * nothing in ordinary redraws.
 */
public class RepaintScrollDetectorTest extends TerminalTestCase {

    private static final int ROWS = 12;

    private RepaintScrollDetector mDetector;

    /** Redraw every row with cursor positioning and text only, no scroll commands. */
    private void paint(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) sb.append("\033[").append(i + 1).append(";1H").append(lines[i]).append("\033[K");
        enterString(sb.toString());
    }

    /** A transcript of rows 0..8 showing lines first.. first+8, an input box and a status line. */
    private void paintTranscript(int first) {
        String[] lines = new String[ROWS];
        for (int i = 0; i < 9; i++) lines[i] = "line " + (first + i);
        lines[9] = "> input";
        lines[10] = "-- footer --";
        lines[11] = "[0] status 12:00";
        paint(lines);
    }

    private void setUpTranscript(int first) {
        withTerminalSized(20, ROWS);
        paintTranscript(first);
        mDetector = new RepaintScrollDetector();
        mDetector.snapshot(mTerminal.getScreen());
    }

    public void testScrollTowardsTheEndIsFound() {
        setUpTranscript(10);
        paintTranscript(13);
        assertEquals(3, mDetector.detect(mTerminal.getScreen(), 1));
        // The transcript rows only: the input box held still, so it bounds the rows brought in.
        assertEquals(0, mDetector.getTop());
        assertEquals(9, mDetector.getBottom());
    }

    public void testScrollTowardsTheStartIsFound() {
        setUpTranscript(10);
        paintTranscript(6);
        assertEquals(-4, mDetector.detect(mTerminal.getScreen(), -1));
        assertEquals(0, mDetector.getTop());
        assertEquals(9, mDetector.getBottom());
    }

    public void testOnlyTheScrolledDirectionIsTried() {
        setUpTranscript(10);
        paintTranscript(13);
        assertEquals(0, mDetector.detect(mTerminal.getScreen(), -1));
    }

    public void testLargeShiftLikeAWheelNotch() {
        withTerminalSized(20, 40);
        String[] before = new String[40], after = new String[40];
        for (int i = 0; i < 39; i++) {
            before[i] = "row " + i;
            after[i] = "row " + (i + 16);
        }
        before[39] = after[39] = "status";
        paint(before);
        mDetector = new RepaintScrollDetector();
        mDetector.snapshot(mTerminal.getScreen());
        paint(after);
        assertEquals(16, mDetector.detect(mTerminal.getScreen(), 1));
        // Never the last row, the likely status line.
        assertEquals(39, mDetector.getBottom());
    }

    public void testUnrelatedRedrawIsNotAScroll() {
        setUpTranscript(10);
        String[] lines = new String[ROWS];
        for (int i = 0; i < ROWS; i++) lines[i] = "other " + i;
        paint(lines);
        assertEquals(0, mDetector.detect(mTerminal.getScreen(), 1));
    }

    public void testUnchangedScreenIsNotAScroll() {
        setUpTranscript(10);
        paintTranscript(10);
        assertEquals(0, mDetector.detect(mTerminal.getScreen(), 1));
    }

    public void testAppendedOutputIsNotAScroll() {
        // A few rows written below the existing ones, nothing moved.
        setUpTranscript(10);
        enterString("\033[10;1Hnew a\033[K\033[11;1Hnew b\033[K");
        assertEquals(0, mDetector.detect(mTerminal.getScreen(), 1));
    }

    public void testBlankRowsAreNoEvidence() {
        withTerminalSized(20, ROWS);
        mDetector = new RepaintScrollDetector();
        mDetector.snapshot(mTerminal.getScreen());
        paint("x", "", "", "", "", "", "", "", "", "", "", "");
        assertEquals(0, mDetector.detect(mTerminal.getScreen(), 1));
    }

    public void testSideBySidePaneScrollIsNotAnimated() {
        // The left pane scrolls, the right one does not: no whole row moved.
        withTerminalSized(20, ROWS);
        String[] before = new String[ROWS], after = new String[ROWS];
        for (int i = 0; i < ROWS; i++) {
            before[i] = String.format("L%-8d|R%d", i, i);
            after[i] = String.format("L%-8d|R%d", i + 2, i);
        }
        paint(before);
        mDetector = new RepaintScrollDetector();
        mDetector.snapshot(mTerminal.getScreen());
        paint(after);
        assertEquals(0, mDetector.detect(mTerminal.getScreen(), 1));
    }

    public void testOtherBufferOrSizeFindsNothing() {
        setUpTranscript(10);
        // Switch to the alternate screen and draw the scrolled transcript there.
        enterString("\033[?1049h");
        paintTranscript(13);
        assertEquals(0, mDetector.detect(mTerminal.getScreen(), 1));

        setUpTranscript(10);
        resize(20, ROWS + 1);
        assertEquals(0, mDetector.detect(mTerminal.getScreen(), 1));
    }

    public void testNoSnapshotFindsNothing() {
        setUpTranscript(10);
        mDetector.clear();
        paintTranscript(13);
        assertEquals(0, mDetector.detect(mTerminal.getScreen(), 1));
    }

    public void testFoundScrollAnimatesWithGhostRowsFromTheSnapshot() {
        setUpTranscript(10);
        paintTranscript(13);
        int shift = mDetector.detect(mTerminal.getScreen(), 1);

        RegionScrollAnimation animation = new RegionScrollAnimation(120);
        animation.setRecording(true);
        animation.onRegionRepainted(mTerminal.getScreen(), mDetector.getSnapshotRows(), shift,
            mDetector.getTop(), mDetector.getBottom(), 0, mTerminal.mColumns);
        assertTrue(animation.isActive());
        assertEquals(3, animation.getOffsetRows(), 0f);
        assertEquals(0, animation.getTop());
        assertEquals(9, animation.getBottom());
        // The rows that left over the top are the old ones, not what the screen holds now.
        assertEquals(3, animation.getGhostRowsAboveCount());
        assertTrue(new String(animation.getGhostRowAbove(0).mText).startsWith("line 12"));
        assertTrue(new String(animation.getGhostRowAbove(2).mText).startsWith("line 10"));
    }

    public void testRepaintNotRecordedDoesNotAnimate() {
        setUpTranscript(10);
        paintTranscript(13);
        int shift = mDetector.detect(mTerminal.getScreen(), 1);
        RegionScrollAnimation animation = new RegionScrollAnimation(120);
        animation.onRegionRepainted(mTerminal.getScreen(), mDetector.getSnapshotRows(), shift,
            mDetector.getTop(), mDetector.getBottom(), 0, mTerminal.mColumns);
        assertFalse(animation.isActive());
    }

    public void testScrollEventsCanBeTurnedOffSeparately() {
        withTerminalSized(4, 6);
        RegionScrollAnimation animation = new RegionScrollAnimation(120);
        animation.setRecording(true);
        animation.setScrollEventsEnabled(false);
        mTerminal.setRegionScrollAnimation(animation);
        enterString("\033[2;5r\033[S");
        assertFalse(animation.isActive());
        // Still counted, so a repaint detector can tell an app sends scroll commands.
        assertEquals(1, animation.getScrollEventCount());
    }

}
