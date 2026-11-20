package com.termux.terminal;

/**
 * Finds scrolls that an app performs by redrawing the screen instead of sending scroll commands,
 * like Claude Code's fullscreen view does (and tmux passing it on), so that they can be animated
 * by a {@link RegionScrollAnimation} like real scroll commands are.
 * <p>
 * It keeps a copy of the screen ({@link #snapshot}), and {@link #detect} compares the screen with
 * it, looking for rows whose content now sits a whole number of rows away in the direction the
 * user scrolled. Modelled on the smooth scroll Ghostty fork's repaint detector, simplified to whole
 * rows, with these rules:
 * <ul>
 *     <li>Only rows that changed and hold a visible glyph are evidence. A blank row, or one that
 *     did not change, matches any shift and so says nothing.</li>
 *     <li>Only the direction the user scrolled is tried, and the shift that explains the most rows
 *     wins, the smallest on a tie. It needs {@link #MIN_MATCHING_ROWS} rows, and more rows
 *     explained than left unexplained beyond the rows the shift brings in.</li>
 *     <li>The rectangle is the rows that moved, plus the rows the shift brings in, stopping at a
 *     row that stayed exactly where it was (a pinned label or status line). The last row of the
 *     screen is never included, as the likely status line (tmux's).</li>
 *     <li>Rows are compared whole, so a side by side split where only one pane moved matches
 *     nothing and is not animated, which is safe.</li>
 * </ul>
 * Its arrays are reused between calls, so detecting allocates nothing once the screen size is stable.
 */
public final class RepaintScrollDetector {

    /** The default for the largest shift looked for, in rows. Claude Code moves 16 rows a wheel notch. */
    public static final int DEFAULT_MAX_SHIFT_ROWS = 48;

    /** The fewest rows that must have moved by a shift for it to count. */
    public static final int MIN_MATCHING_ROWS = 3;

    private final int mMaxShiftRows;

    /** The buffer the snapshot was taken of. Detection against another buffer finds nothing. */
    private TerminalBuffer mScreen;
    private int mRows, mColumns;
    private boolean mHasSnapshot;

    private TerminalRow[] mPreviousRows = new TerminalRow[0];
    private long[] mPreviousHashes = new long[0];
    private boolean[] mPreviousBlank = new boolean[0];
    private long[] mHashes = new long[0];
    private boolean[] mBlank = new boolean[0];

    private int mTop, mBottom, mLeft, mRight;
    private int mFoundShift, mFoundScore;
    /** The screen's rows during {@link #detect}. */
    private TerminalRow[] mLines = new TerminalRow[0];
    private long[] mWholeHashes = new long[0], mPreviousWholeHashes = new long[0];
    private boolean[] mWholeBlank = new boolean[0], mPreviousWholeBlank = new boolean[0];

    /** The widest column at a band's left that may hold still while the rest moves (line numbers). */
    static final int MAX_GUTTER_COLUMNS = 16;
    /** The narrowest band of columns looked in. */
    static final int MIN_BAND_COLUMNS = 8;

    public RepaintScrollDetector() {
        this(DEFAULT_MAX_SHIFT_ROWS);
    }

    public RepaintScrollDetector(int maxShiftRows) {
        mMaxShiftRows = Math.max(1, maxShiftRows);
    }

    /** Forget the snapshot. */
    public void clear() {
        mHasSnapshot = false;
        mScreen = null;
    }

    public boolean hasSnapshot() {
        return mHasSnapshot;
    }

    /** Copy the screen as it is now, to compare later screens with. */
    public void snapshot(TerminalBuffer screen) {
        final int rows = screen.mScreenRows, columns = screen.mColumns;
        if (rows != mRows || columns != mColumns || mPreviousRows.length != rows) {
            mRows = rows;
            mColumns = columns;
            mPreviousRows = new TerminalRow[rows];
            for (int i = 0; i < rows; i++) mPreviousRows[i] = new TerminalRow(columns, TextStyle.NORMAL);
            mPreviousHashes = new long[rows];
            mPreviousBlank = new boolean[rows];
            mHashes = new long[rows];
            mBlank = new boolean[rows];
            mLines = new TerminalRow[rows];
            mWholeHashes = new long[rows];
            mWholeBlank = new boolean[rows];
            mPreviousWholeHashes = new long[rows];
            mPreviousWholeBlank = new boolean[rows];
        }
        for (int row = 0; row < rows; row++) {
            TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            mPreviousRows[row].copyFrom(line);
            mPreviousHashes[row] = mPreviousWholeHashes[row] = hash(line, columns);
            mPreviousBlank[row] = mPreviousWholeBlank[row] = isBlank(line);
        }
        mScreen = screen;
        mHasSnapshot = true;
    }

    /** The snapshot's rows, indexed by screen row, for the rows that scrolled out. */
    public TerminalRow[] getSnapshotRows() {
        return mPreviousRows;
    }

    /**
     * Look for the screen's content having moved, relative to the snapshot, by whole rows in one
     * direction. The snapshot is left as it was.
     *
     * @param direction Positive if the user scrolled towards the end (content should move up,
     *                  towards row 0), negative if towards the start.
     * @return The rows the content moved, positive up and negative down as in
     * {@link RegionScrollAnimation#onRegionScroll}, or 0 if no scroll was found. The rows it
     * happened in are {@link #getTop()} to {@link #getBottom()}.
     */
    public int detect(TerminalBuffer screen, int direction) {
        if (!mHasSnapshot || direction == 0 || screen != mScreen
            || screen.mScreenRows != mRows || screen.mColumns != mColumns) return 0;

        final int rows = mRows, columns = mColumns;
        final int sign = direction > 0 ? 1 : -1;
        for (int row = 0; row < rows; row++) mLines[row] = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));

        // Whole rows first: the common case, and the only one before column bands were looked for.
        mFoundScore = 0;
        if (detectBand(sign, 0, columns, false)) return finish(sign, 0, columns);

        // Then each tmux pane between vertical borders, with and without a column at its left
        // that held still while the text beside it moved (nvim's relative line numbers, its sign
        // column, a tmux pane's own border). The band that explains the most rows wins, the
        // widest on a tie.
        int bandsLeft = 0;
        int bestLeft = -1, bestRight = -1;
        int bestScore = 0, bestShift = 0, bestTop = 0, bestBottom = 0;
        for (int column = 0; column <= columns; column++) {
            if (column < columns && !isBorderColumn(column)) continue;
            final int bandLeft = bandsLeft, bandRight = column;
            bandsLeft = column + 1;
            if (bandRight - bandLeft < MIN_BAND_COLUMNS) continue;
            final int maxGutter = Math.min(MAX_GUTTER_COLUMNS, bandRight - bandLeft - MIN_BAND_COLUMNS);
            for (int gutter = 0; gutter <= maxGutter; gutter++) {
                if (bandLeft == 0 && bandRight == columns && gutter == 0) continue; // tried above
                mFoundScore = 0;
                if (detectBand(sign, bandLeft + gutter, bandRight, true) && mFoundScore > bestScore) {
                    bestScore = mFoundScore;
                    bestShift = mFoundShift;
                    bestTop = mTop;
                    bestBottom = mBottom;
                    bestLeft = bandLeft + gutter;
                    bestRight = bandRight;
                }
            }
        }
        if (bestScore == 0) return 0;
        mFoundShift = bestShift;
        mTop = bestTop;
        mBottom = bestBottom;
        return finish(sign, bestLeft, bestRight);
    }

    private int finish(int sign, int left, int right) {
        mLeft = left;
        mRight = right;
        return sign * mFoundShift;
    }

    /**
     * Look for a shift inside the columns [left, right). On success sets {@link #mFoundShift},
     * {@link #mFoundScore} (rows explained), {@link #mTop} and {@link #mBottom}.
     */
    private boolean detectBand(int sign, int left, int right, boolean band) {
        final int rows = mRows;
        final long[] hashes = mHashes, previousHashes = mPreviousHashes;
        final boolean[] blank = mBlank, previousBlank = mPreviousBlank;

        int changed = 0;
        for (int row = 0; row < rows; row++) {
            if (band) {
                hashes[row] = hash(mLines[row], left, right);
                blank[row] = isBlank(mLines[row], left, right);
                previousHashes[row] = hash(mPreviousRows[row], left, right);
                previousBlank[row] = isBlank(mPreviousRows[row], left, right);
            } else {
                hashes[row] = mWholeHashes[row] = hash(mLines[row], mColumns);
                blank[row] = mWholeBlank[row] = isBlank(mLines[row]);
                previousHashes[row] = mPreviousWholeHashes[row];
                previousBlank[row] = mPreviousWholeBlank[row];
            }
            if (isEvidence(row)) changed++;
        }
        if (changed < MIN_MATCHING_ROWS) return false;

        int bestShift = 0, bestMatches = 0;
        final int maxShift = Math.min(mMaxShiftRows, rows - 2);
        for (int shift = 1; shift <= maxShift; shift++) {
            int matches = 0;
            for (int row = 0; row < rows; row++) {
                // Content that moved up by shift came from the row shift below.
                final int source = row + sign * shift;
                if (source < 0 || source >= rows || !isEvidence(row)) continue;
                if (!previousBlank[source] && hashes[row] == previousHashes[source]) matches++;
            }
            if (matches > bestMatches) {
                bestMatches = matches;
                bestShift = shift;
            }
        }
        if (bestMatches < MIN_MATCHING_ROWS) return false;
        // The rows the shift brings in are new, so they are allowed to be unexplained.
        if (changed - bestMatches > bestMatches + bestShift) return false;

        int top = rows, bottom = 0;
        for (int row = 0; row < rows; row++) {
            final int source = row + sign * bestShift;
            if (source < 0 || source >= rows || !isEvidence(row)) continue;
            if (!previousBlank[source] && hashes[row] == previousHashes[source]) {
                top = Math.min(top, row);
                bottom = Math.max(bottom, row + 1);
            }
        }

        // Take in the rows the scroll brought in, up to a row that held still.
        for (int i = 0; i < bestShift; i++) {
            if (sign > 0) {
                if (bottom >= rows || isPinned(bottom)) break;
                bottom++;
            } else {
                if (top <= 0 || isPinned(top - 1)) break;
                top--;
            }
        }
        if (bottom == rows && rows > 2) bottom--;
        if (bottom - top <= bestShift) return false;

        mTop = top;
        mBottom = bottom;
        mFoundShift = bestShift;
        mFoundScore = bestMatches;
        return true;
    }

    /** A column holding a tmux pane border (a vertical line) on most rows, now and in the snapshot. */
    private boolean isBorderColumn(int column) {
        int count = 0;
        for (int row = 0; row < mRows; row++) {
            if (isVerticalLine(charAt(mLines[row], column)) && isVerticalLine(charAt(mPreviousRows[row], column))) count++;
        }
        return count * 4 >= mRows * 3;
    }

    private static boolean isVerticalLine(char c) {
        return c == '\u2502' || c == '\u2503' || c == '|' || c == '\u2551';
    }

    private static char charAt(TerminalRow line, int column) {
        final int index = line.findStartOfColumn(column);
        return index < line.getSpaceUsed() ? line.mText[index] : ' ';
    }

    /** The first column of the last scroll found. */
    public int getLeft() {
        return mLeft;
    }

    /** The column after the last column of the last scroll found. */
    public int getRight() {
        return mRight;
    }

    /** The first row of the last scroll found. */
    public int getTop() {
        return mTop;
    }

    /** The row after the last row of the last scroll found. */
    public int getBottom() {
        return mBottom;
    }

    /** A row with a visible glyph that differs from the snapshot's row at the same place. */
    private boolean isEvidence(int row) {
        return !mBlank[row] && mHashes[row] != mPreviousHashes[row];
    }

    /** A row with a visible glyph that is exactly what it was. */
    private boolean isPinned(int row) {
        return !mBlank[row] && mHashes[row] == mPreviousHashes[row];
    }

    static long hash(TerminalRow line, int columns) {
        long hash = 17;
        final char[] text = line.mText;
        final int used = line.getSpaceUsed();
        for (int i = 0; i < used; i++) hash = hash * 31 + text[i];
        final long[] style = line.mStyle;
        for (int i = 0; i < columns; i++) hash = hash * 1_000_003 + style[i];
        return hash;
    }

    static long hash(TerminalRow line, int left, int right) {
        long hash = 17;
        final char[] text = line.mText;
        final int start = line.findStartOfColumn(left);
        final int end = right >= line.mStyle.length ? line.getSpaceUsed() : line.findStartOfColumn(right);
        for (int i = start; i < end; i++) hash = hash * 31 + text[i];
        final long[] style = line.mStyle;
        for (int i = left; i < right; i++) hash = hash * 1_000_003 + style[i];
        return hash;
    }

    static boolean isBlank(TerminalRow line, int left, int right) {
        final char[] text = line.mText;
        final int start = line.findStartOfColumn(left);
        final int end = right >= line.mStyle.length ? line.getSpaceUsed() : line.findStartOfColumn(right);
        for (int i = start; i < end; i++) if (text[i] != ' ') return false;
        return true;
    }

    static boolean isBlank(TerminalRow line) {
        final char[] text = line.mText;
        final int used = line.getSpaceUsed();
        for (int i = 0; i < used; i++) if (text[i] != ' ') return false;
        return true;
    }

}
