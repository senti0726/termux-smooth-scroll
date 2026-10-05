package com.termux.terminal;

/**
 * Tests that the emulator reports scrolls of rectangles of the screen to a
 * {@link RegionScrollAnimation} with the right rows and rectangle, keeps the rows that scroll out,
 * and that the animation unions, caps, gates and settles as intended.
 */
public class RegionScrollAnimationTest extends TerminalTestCase {

    private static final int DURATION = 120;

    private RegionScrollAnimation mAnimation;

    /** A 4 column, 6 row terminal showing "r0".."r5", with a recording animation attached. */
    private void setUpScreen() {
        withTerminalSized(4, 6).enterString("r0\r\nr1\r\nr2\r\nr3\r\nr4\r\nr5");
        assertLinesAre("r0  ", "r1  ", "r2  ", "r3  ", "r4  ", "r5  ");
        mAnimation = new RegionScrollAnimation(DURATION);
        mAnimation.setRecording(true);
        mTerminal.setRegionScrollAnimation(mAnimation);
    }

    private static String text(TerminalRow row) {
        return new String(row.mText, 0, row.getSpaceUsed());
    }

    private void assertRegion(float offsetRows, int top, int bottom, int left, int right) {
        assertTrue(mAnimation.isActive());
        assertEquals(offsetRows, mAnimation.getOffsetRows(), 0.0001f);
        assertEquals(top, mAnimation.getTop());
        assertEquals(bottom, mAnimation.getBottom());
        assertEquals(left, mAnimation.getLeft());
        assertEquals(right, mAnimation.getRight());
    }

    public void testLinefeedAtBottomMarginInsideScrollRegion() {
        setUpScreen();
        // Rows 2-4 (1-based) are the region, i.e. [1, 4); put the cursor on its last row.
        enterString("\033[2;4r\033[4;1H\n");
        assertLinesAre("r0  ", "r2  ", "r3  ", "    ", "r4  ", "r5  ");
        assertRegion(1, 1, 4, 0, 4);
        assertEquals(1, mAnimation.getGhostRowsAboveCount());
        assertEquals("r1  ", text(mAnimation.getGhostRowAbove(0)));
        assertEquals(0, mAnimation.getGhostRowsBelowCount());
    }

    public void testFullScreenScrollKeepsTheLastRowStill() {
        setUpScreen();
        // No region declared, as less does: the last row is left out as the likely prompt.
        enterString("\n");
        assertRegion(1, 0, 5, 0, 4);
        assertEquals("r0  ", text(mAnimation.getGhostRowAbove(0)));
        mAnimation.reset();

        // Scrolling back, the row that leaves the animated rows is the one above the prompt.
        assertLinesAre("r1  ", "r2  ", "r3  ", "r4  ", "r5  ", "    ");
        enterString("\033[H\033M");
        assertRegion(-1, 0, 5, 0, 4);
        assertEquals("r5  ", text(mAnimation.getGhostRowBelow(0)));
    }

    public void testScrollUp() {
        setUpScreen();
        enterString("\033[2;5r\033[2S");
        assertLinesAre("r0  ", "r3  ", "r4  ", "    ", "    ", "r5  ");
        assertRegion(2, 1, 5, 0, 4);
        // The last row to leave is the nearest to the region.
        assertEquals(2, mAnimation.getGhostRowsAboveCount());
        assertEquals("r2  ", text(mAnimation.getGhostRowAbove(0)));
        assertEquals("r1  ", text(mAnimation.getGhostRowAbove(1)));
    }

    public void testScrollDown() {
        setUpScreen();
        enterString("\033[2;5r\033[2T");
        assertLinesAre("r0  ", "    ", "    ", "r1  ", "r2  ", "r5  ");
        assertRegion(-2, 1, 5, 0, 4);
        assertEquals(2, mAnimation.getGhostRowsBelowCount());
        assertEquals("r3  ", text(mAnimation.getGhostRowBelow(0)));
        assertEquals("r4  ", text(mAnimation.getGhostRowBelow(1)));
    }

    public void testReverseIndexAtTopMargin() {
        setUpScreen();
        enterString("\033[2;5r\033[2;1H\033M");
        assertLinesAre("r0  ", "    ", "r1  ", "r2  ", "r3  ", "r5  ");
        assertRegion(-1, 1, 5, 0, 4);
        assertEquals("r4  ", text(mAnimation.getGhostRowBelow(0)));
    }

    public void testReverseIndexAwayFromTopMarginIsNotAScroll() {
        setUpScreen();
        enterString("\033[2;5r\033[3;1H\033M");
        assertFalse(mAnimation.isActive());
    }

    public void testInsertLinesInsideMargins() {
        setUpScreen();
        enterString("\033[2;5r\033[3;1H\033[L");
        assertLinesAre("r0  ", "r1  ", "    ", "r2  ", "r3  ", "r5  ");
        assertRegion(-1, 2, 5, 0, 4);
        assertEquals("r4  ", text(mAnimation.getGhostRowBelow(0)));
    }

    public void testDeleteLinesInsideMargins() {
        setUpScreen();
        enterString("\033[2;5r\033[3;1H\033[2M");
        assertLinesAre("r0  ", "r1  ", "r4  ", "    ", "    ", "r5  ");
        assertRegion(2, 2, 5, 0, 4);
        assertEquals("r3  ", text(mAnimation.getGhostRowAbove(0)));
        assertEquals("r2  ", text(mAnimation.getGhostRowAbove(1)));
    }

    public void testLeftAndRightMargins() {
        setUpScreen();
        // DECLRMM on, columns 2-3 (1-based), rows 2-5; scroll up inside it.
        enterString("\033[?69h\033[2;3s\033[2;5r\033[S");
        assertRegion(1, 1, 5, 1, 3);
    }

    public void testNotRecordingDoesNotAnimate() {
        setUpScreen();
        mAnimation.setRecording(false);
        enterString("\033[2;5r\033[S");
        assertFalse(mAnimation.isActive());
    }

    public void testOutputWhileNotRecordingEndsTheAnimation() {
        setUpScreen();
        enterString("\033[2;5r\033[S");
        assertTrue(mAnimation.isActive());
        mAnimation.setRecording(false);
        enterString("\033[S");
        assertFalse(mAnimation.isActive());
        assertEquals(0, mAnimation.getOffsetRows(), 0f);
    }

    public void testZeroDurationNeverAnimates() {
        setUpScreen();
        mAnimation.setDuration(0);
        enterString("\033[2;5r\033[S");
        assertFalse(mAnimation.isActive());
        // And stepping, which divides by the duration, is safe.
        assertFalse(mAnimation.step(0));
        assertFalse(mAnimation.step(1000));
    }

    public void testScrollsAddUpAndUnionTheRegion() {
        setUpScreen();
        enterString("\033[2;5r\033[S");
        enterString("\033[1;4r\033[S");
        assertRegion(2, 0, 5, 0, 4);
    }

    public void testDisjointRegionStartsOver() {
        setUpScreen();
        enterString("\033[1;2r\033[S");
        enterString("\033[4;6r\033[S");
        assertRegion(1, 3, 6, 0, 4);
        assertEquals(1, mAnimation.getGhostRowsAboveCount());
        assertEquals("r3  ", text(mAnimation.getGhostRowAbove(0)));
    }

    public void testWholeRegionScrollIsAClear() {
        setUpScreen();
        enterString("\033[2;4r\033[3S");
        assertFalse(mAnimation.isActive());
    }

    public void testGhostRowsComeBackWhenTheScrollReverses() {
        setUpScreen();
        enterString("\033[2;5r\033[2S");
        assertEquals(2, mAnimation.getGhostRowsAboveCount());
        enterString("\033[T");
        assertRegion(1, 1, 5, 0, 4);
        // The nearest row above came back in, and one row left over the bottom.
        assertEquals(1, mAnimation.getGhostRowsAboveCount());
        assertEquals("r1  ", text(mAnimation.getGhostRowAbove(0)));
        assertEquals(1, mAnimation.getGhostRowsBelowCount());
    }

    public void testLagIsCapped() {
        withTerminalSized(4, 40);
        mAnimation = new RegionScrollAnimation(DURATION, 24);
        mAnimation.setRecording(true);
        mTerminal.setRegionScrollAnimation(mAnimation);
        enterString("\033[40;1H");
        for (int i = 0; i < 30; i++) enterString("\n");
        assertRegion(24, 0, 39, 0, 4);
        // Only one more ghost row than the lag can ever be drawn.
        assertEquals(25, mAnimation.getGhostRowsAboveCount());
    }

    public void testSpringSettlesToZeroWithoutOvershoot() {
        setUpScreen();
        enterString("\033[2;5r\033[2S");
        long now = 1000;
        assertTrue(mAnimation.step(now)); // starts the clock
        float last = mAnimation.getOffsetRows();
        int frames = 0;
        while (mAnimation.step(now += 16)) {
            float offset = mAnimation.getOffsetRows();
            assertTrue("offset must fall every frame", offset < last);
            assertTrue("offset must not cross zero", offset > 0);
            last = offset;
            frames++;
        }
        assertFalse(mAnimation.isActive());
        assertEquals(0, mAnimation.getOffsetRows(), 0f);
        assertEquals(0, mAnimation.getGhostRowsAboveCount());
        // Settles in about the duration, not instantly and not much later.
        assertTrue("frames: " + frames, frames >= 4 && frames * 16 <= DURATION * 2);
    }

    public void testSpringKeepsItsSpeedWhenARowArrives() {
        setUpScreen();
        enterString("\033[2;5r\033[S");
        long now = 1000;
        mAnimation.step(now);
        mAnimation.step(now += 30);
        float before = mAnimation.getOffsetRows();
        mAnimation.step(now += 16);
        float fallPerFrameMoving = before - mAnimation.getOffsetRows();

        // A second row arrives mid-glide: the spring keeps moving at its speed instead of
        // restarting from rest, so the next frame moves at least as much as the last.
        enterString("\033[S");
        before = mAnimation.getOffsetRows();
        mAnimation.step(now += 16);
        float fallAfterNewRow = before - mAnimation.getOffsetRows();
        assertTrue(fallAfterNewRow + " < " + fallPerFrameMoving, fallAfterNewRow >= fallPerFrameMoving);

        // And a row that starts the animation is already moving on the first frame, instead of
        // easing in from a standstill: an exponential decay drops 1 - e^-(6.64 * 16 / 120) = 59%.
        RegionScrollAnimation fromRest = new RegionScrollAnimation(DURATION);
        fromRest.setRecording(true);
        mTerminal.setRegionScrollAnimation(fromRest);
        enterString("\033[S");
        fromRest.step(0);
        fromRest.step(16);
        assertEquals(Math.exp(-6.64 * 16 / DURATION), fromRest.getOffsetRows(), 0.01);
    }

    public void testResetEndsEverything() {
        setUpScreen();
        enterString("\033[2;5r\033[2S\033[T");
        mAnimation.reset();
        assertFalse(mAnimation.isActive());
        assertEquals(0, mAnimation.getGhostRowsAboveCount());
        assertEquals(0, mAnimation.getGhostRowsBelowCount());
        assertNull(mAnimation.getGhostRowAbove(0));
    }

    public void testDetachedAnimationIsNotTold() {
        setUpScreen();
        mTerminal.setRegionScrollAnimation(null);
        enterString("\033[2;5r\033[S");
        assertFalse(mAnimation.isActive());
    }

}
