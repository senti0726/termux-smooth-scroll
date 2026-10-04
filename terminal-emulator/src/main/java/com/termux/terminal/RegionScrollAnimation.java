package com.termux.terminal;

/**
 * Animates the scrolls an application performs inside its own scroll region, like tmux, nvim,
 * yazi and less do in response to wheel events or arrow keys, so that the text glides instead of
 * jumping a row per event.
 * <p>
 * The {@link TerminalEmulator} reports every scroll of a rectangle of the screen through
 * {@link #onRegionScroll} just before it moves the cells. The content of that rectangle is then
 * drawn displaced by {@link #getOffsetRows()} rows, back where it was before the scroll, and the
 * offset springs back to 0. The band the displacement uncovers is filled with "ghost rows": the
 * rows that just scrolled out of the rectangle, kept in a small ring buffer, see
 * {@link #getGhostRowAbove(int)} and {@link #getGhostRowBelow(int)}.
 * <p>
 * Rules, from the smooth scroll Ghostty fork this is modelled on:
 * <ul>
 *     <li>Only scrolls that happen while {@link #setRecording(boolean) recording} animate. The
 *     view records for a short while after the user scrolls, so plain program output never
 *     animates. A scroll outside recording ends any running animation.</li>
 *     <li>Scrolls during one animation add up, and the rectangle becomes the union of all of
 *     them, so no row or column that contributed to the offset snaps back while it is standing.
 *     A rectangle disjoint from the current one starts over. Rows outside every scroll region
 *     the application declared, like a status line, never move. When the whole screen scrolls,
 *     which means no region was declared, its last row is held still as the likely prompt.</li>
 *     <li>The offset follows a critically damped spring, which keeps its speed across rows
 *     instead of restarting an ease-out on every row.</li>
 *     <li>The offset never lags more than {@link #getMaxLagRows()} rows, the rest lands at once.
 *     A scroll of the whole rectangle or more (a clear) is not animated.</li>
 * </ul>
 * Everything runs on the main thread, where the emulator is fed and the view draws.
 */
public final class RegionScrollAnimation {

    /** The default for {@link #getMaxLagRows()}. */
    public static final int DEFAULT_MAX_LAG_ROWS = 24;

    /**
     * The spring's angular frequency times its duration. A critically damped spring released from
     * rest is within 1% of its target after (1 + u) * e^-u = 0.01, at u = 6.64.
     */
    private static final double SPRING_SETTLE = 6.64;

    /** Offsets and speeds below these, in rows and rows per ms, are at rest. */
    private static final float REST_OFFSET_ROWS = 0.01f;
    private static final float REST_VELOCITY_ROWS_PER_MS = 0.0005f;

    /** The longest step taken in one frame, so a stalled frame does not make the spring jump. */
    private static final long MAX_STEP_MS = 100;

    private int mDurationMs;
    /** Whether scroll commands animate. Repaints found by a detector animate either way. */
    private boolean mScrollEventsEnabled = true;
    /** How many scroll commands have been reported, animated or not. See {@link #getScrollEventCount()}. */
    private long mScrollEventCount;
    private final int mMaxLagRows;
    private boolean mRecording;

    private boolean mActive;
    /** The buffer the animated rows belong to. An animation is meaningless on another buffer. */
    private TerminalBuffer mScreen;
    /** The rectangle being displaced, rows [top, bottom) and columns [left, right). */
    private int mTop, mBottom, mLeft, mRight;
    /**
     * How far the rectangle's content is drawn from its true position, in rows. Positive when the
     * content scrolled up (it is drawn lower, and rises into place), negative when it scrolled down.
     */
    private float mOffsetRows;
    /** The rate of change of {@link #mOffsetRows}, in rows per ms. */
    private float mVelocity;
    /** The time of the last {@link #step(long)}, or -1 if the next step starts the clock. */
    private long mLastStepTime = -1;

    /** Rows that scrolled out of the top of the rectangle; index 0 of {@link #getGhostRowAbove(int)} is the nearest. */
    private final GhostRows mAbove;
    /** Rows that scrolled out of the bottom of the rectangle. */
    private final GhostRows mBelow;

    public RegionScrollAnimation(int durationMs) {
        this(durationMs, DEFAULT_MAX_LAG_ROWS);
    }

    public RegionScrollAnimation(int durationMs, int maxLagRows) {
        mMaxLagRows = Math.max(1, maxLagRows);
        // One more than the lag, for the row that is partly visible at the edge of the band.
        mAbove = new GhostRows(mMaxLagRows + 1);
        mBelow = new GhostRows(mMaxLagRows + 1);
        setDuration(durationMs);
    }

    /** Set how long the offset takes to settle, in ms. 0 turns the animation off. */
    public void setDuration(int durationMs) {
        mDurationMs = Math.max(0, durationMs);
        if (mDurationMs == 0) reset();
    }

    public int getDuration() {
        return mDurationMs;
    }

    /** Set whether scroll commands (scroll regions, IL/DL, index at a margin) animate. */
    public void setScrollEventsEnabled(boolean enabled) {
        mScrollEventsEnabled = enabled;
        if (!enabled) reset();
    }

    public boolean isScrollEventsEnabled() {
        return mScrollEventsEnabled;
    }

    /**
     * How many scroll commands the emulator has reported, whether they animated or not. A repaint
     * detector uses it to stay out of the way of apps that send scroll commands, so that one scroll
     * is never counted twice.
     */
    public long getScrollEventCount() {
        return mScrollEventCount;
    }

    public int getMaxLagRows() {
        return mMaxLagRows;
    }

    /** Set whether scrolls should animate, i.e. whether the user scrolled recently. */
    public void setRecording(boolean recording) {
        mRecording = recording;
    }

    public boolean isRecording() {
        return mRecording;
    }

    /** End any animation; the screen is drawn where it really is. */
    public void reset() {
        mActive = false;
        mScreen = null;
        mOffsetRows = 0;
        mVelocity = 0;
        mLastStepTime = -1;
        mAbove.clear();
        mBelow.clear();
    }

    /**
     * Called by the emulator just before the content of a rectangle of the screen moves.
     *
     * @param screen The buffer being scrolled, still holding the rows about to scroll out.
     * @param rows How many rows the content moves up (towards row 0); negative moves it down.
     * @param top The first row of the rectangle.
     * @param bottom The row after the last row of the rectangle.
     * @param left The first column of the rectangle.
     * @param right The column after the last column of the rectangle.
     */
    public void onRegionScroll(TerminalBuffer screen, int rows, int top, int bottom, int left, int right) {
        mScrollEventCount++;
        if (!mRecording || mDurationMs == 0 || !mScrollEventsEnabled) {
            // Output nobody scrolled for: whatever is standing would now be drawn over the wrong text.
            if (mActive) reset();
            return;
        }

        // An app that scrolls the whole screen has declared no region, and its last row is most
        // often a prompt or status it redraws in place (less, man). Keep that row still.
        if (top == 0 && bottom == screen.mScreenRows && bottom > 2) bottom--;

        push(screen, null, rows, top, bottom, left, right);
    }

    /**
     * Called when an app moved the content of a rectangle by redrawing it rather than with a scroll
     * command, as found by a {@link RepaintScrollDetector}. The screen already shows the moved
     * content, so the rows that left the rectangle come from a copy of the screen made before.
     *
     * @param previousRows The screen's rows as they were before the redraw, indexed by row.
     * @see #onRegionScroll for the other parameters.
     */
    public void onRegionRepainted(TerminalBuffer screen, TerminalRow[] previousRows, int rows, int top, int bottom, int left, int right) {
        if (!mRecording || mDurationMs == 0) {
            if (mActive) reset();
            return;
        }
        push(screen, previousRows, rows, top, bottom, left, right);
    }

    /** Add a scroll to the offset. Leaving rows are copied from previousRows if not null, else from the screen. */
    private void push(TerminalBuffer screen, TerminalRow[] previousRows, int rows, int top, int bottom, int left, int right) {
        final int height = bottom - top;
        if (rows == 0 || height <= 0 || right <= left) return;
        if (Math.abs(rows) >= height) {
            // The whole rectangle is replaced, which is a clear, not a scroll.
            reset();
            return;
        }

        if (mActive) {
            boolean overlaps = top < mBottom && bottom > mTop && left < mRight && right > mLeft;
            if (screen != mScreen || !overlaps) reset();
        }

        final int columns = screen.mColumns;
        if (rows > 0) {
            // Rows [top, top + rows) leave over the top, the last of them ending up nearest.
            for (int i = 0; i < rows; i++) mAbove.push(sourceRow(screen, previousRows, top + i), columns);
            // Rows that left over the bottom earlier come back in.
            mBelow.pop(rows);
        } else {
            for (int i = 0; i < -rows; i++) mBelow.push(sourceRow(screen, previousRows, bottom - 1 - i), columns);
            mAbove.pop(-rows);
        }

        if (mActive) {
            mTop = Math.min(mTop, top);
            mBottom = Math.max(mBottom, bottom);
            mLeft = Math.min(mLeft, left);
            mRight = Math.max(mRight, right);
        } else {
            mActive = true;
            mScreen = screen;
            mTop = top;
            mBottom = bottom;
            mLeft = left;
            mRight = right;
            mLastStepTime = -1;
        }

        mOffsetRows += rows;
        if (mOffsetRows > mMaxLagRows) mOffsetRows = mMaxLagRows;
        else if (mOffsetRows < -mMaxLagRows) mOffsetRows = -mMaxLagRows;
    }

    private static TerminalRow sourceRow(TerminalBuffer screen, TerminalRow[] previousRows, int row) {
        return previousRows != null ? previousRows[row] : screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
    }

    /**
     * Advance the spring to a point in time.
     *
     * @param now The current time in ms, on any clock that only moves forward.
     * @return Whether the animation is still running.
     */
    public boolean step(long now) {
        if (!mActive) return false;
        if (mDurationMs == 0) {
            reset();
            return false;
        }
        if (mLastStepTime < 0) {
            mLastStepTime = now;
            return true;
        }

        final long dt = Math.max(0, Math.min(now - mLastStepTime, MAX_STEP_MS));
        mLastStepTime = now;
        if (dt == 0) return true;

        // Exact solution of a critically damped spring, so any frame rate gives the same motion:
        // x(t) = (x0 + (v0 + w x0) t) e^-wt, v(t) = (v0 - w (v0 + w x0) t) e^-wt
        final double w = SPRING_SETTLE / mDurationMs;
        final double x0 = mOffsetRows, v0 = mVelocity;
        final double c = v0 + w * x0;
        final double decay = Math.exp(-w * dt);
        final double x = (x0 + c * dt) * decay;
        final double v = (v0 - w * c * dt) * decay;

        // Crossing zero would draw the band on the other side, which has no ghost rows to show.
        if ((x0 > 0 && x <= 0) || (x0 < 0 && x >= 0)
            || (Math.abs(x) < REST_OFFSET_ROWS && Math.abs(v) < REST_VELOCITY_ROWS_PER_MS)) {
            reset();
            return false;
        }

        mOffsetRows = (float) x;
        mVelocity = (float) v;
        return true;
    }

    /** Whether an offset is standing and should be drawn. */
    public boolean isActive() {
        return mActive;
    }

    /** The buffer the animation belongs to. */
    public TerminalBuffer getScreen() {
        return mScreen;
    }

    /** See {@link #mOffsetRows}. */
    public float getOffsetRows() {
        return mOffsetRows;
    }

    public int getTop() {
        return mTop;
    }

    public int getBottom() {
        return mBottom;
    }

    public int getLeft() {
        return mLeft;
    }

    public int getRight() {
        return mRight;
    }

    /** How many rows that scrolled out over the top are kept. */
    public int getGhostRowsAboveCount() {
        return mAbove.mCount;
    }

    /** How many rows that scrolled out over the bottom are kept. */
    public int getGhostRowsBelowCount() {
        return mBelow.mCount;
    }

    /** A row that scrolled out over the top: 0 belongs just above the rectangle, 1 above that. */
    public TerminalRow getGhostRowAbove(int index) {
        return mAbove.get(index);
    }

    /** A row that scrolled out over the bottom: 0 belongs just below the rectangle, 1 below that. */
    public TerminalRow getGhostRowBelow(int index) {
        return mBelow.get(index);
    }

    /** A ring buffer of copied rows, newest (nearest the rectangle) first. Rows are reused, not reallocated. */
    private static final class GhostRows {
        final TerminalRow[] mRows;
        int mStart, mCount;

        GhostRows(int capacity) {
            mRows = new TerminalRow[capacity];
        }

        void clear() {
            mStart = 0;
            mCount = 0;
        }

        /** Copy a row of the screen in as the nearest; the farthest falls off when full. */
        void push(TerminalRow source, int columns) {
            final int capacity = mRows.length;
            mStart = (mStart - 1 + capacity) % capacity;
            TerminalRow copy = mRows[mStart];
            if (copy == null || copy.getColumns() != columns) {
                copy = new TerminalRow(columns, TextStyle.NORMAL);
                mRows[mStart] = copy;
            }
            copy.copyFrom(source);
            if (mCount < capacity) mCount++;
        }

        /** Drop the nearest rows, which came back into the rectangle. */
        void pop(int rows) {
            rows = Math.min(rows, mCount);
            mStart = (mStart + rows) % mRows.length;
            mCount -= rows;
        }

        TerminalRow get(int index) {
            if (index < 0 || index >= mCount) return null;
            return mRows[(mStart + index) % mRows.length];
        }
    }

}
