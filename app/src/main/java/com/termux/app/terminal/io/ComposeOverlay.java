package com.termux.app.terminal.io;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import com.termux.app.TermuxActivity;
import com.termux.shared.view.KeyboardUtils;
import com.termux.terminal.TerminalSession;

/**
 * Multi-line compose box (fork, docs/phone SPEC Stage 1 step 1).
 *
 * Opened by the two-rows-tall ✎ key. An overlay at the top of the screen, black at ~80 % so the
 * terminal shows faintly through. A plain Android EditText, so Gboard works fully there,
 * autocorrect included; Enter is a new line. ✎ again sends the text as ONE bracketed paste, so
 * bash, nvim and Claude Code take it as one input. There is no automatic Enter: text that ends
 * in a line break is pasted without it and then run (Enter); other text only lands on the command
 * line. ESC on the bar (or Back) closes it and keeps the draft. (User's notes, docs/phone 11-07.)
 */
public class ComposeOverlay {

    /** Grows to this many lines, then scrolls. */
    private static final int MAX_LINES = 6;
    /** Tokyo Night foreground on 80 % black (the screen is OLED). */
    private static final int BG = 0xCC000000;
    private static final int FG = 0xFFC0CAF5;
    private static final int HINT = 0xFF565F89;

    private final TermuxActivity mActivity;
    private final LinearLayout mRoot;
    private final EditText mEdit;

    public ComposeOverlay(TermuxActivity activity, RelativeLayout parent) {
        mActivity = activity;

        mRoot = new LinearLayout(activity);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setBackgroundColor(BG);
        int pad = dp(8);
        mRoot.setPadding(pad, pad, pad, pad);
        mRoot.setVisibility(View.GONE);
        mRoot.setClickable(true);   // touches on the overlay never reach the terminal under it

        mEdit = new EditText(activity);
        mEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);   // no auto-capitals: "Echo" broke commands
        mEdit.setMinLines(1);
        mEdit.setMaxLines(MAX_LINES);
        mEdit.setVerticalScrollBarEnabled(true);
        mEdit.setGravity(Gravity.TOP | Gravity.START);
        mEdit.setTypeface(Typeface.SERIF);
        mEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        mEdit.setTextColor(FG);
        mEdit.setHintTextColor(HINT);
        mEdit.setHint("✎ sends · ESC keeps the draft · end with a new line to run it");
        mEdit.setBackgroundColor(Color.TRANSPARENT);
        mRoot.addView(mEdit, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));


        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        parent.addView(mRoot, lp);
    }

    private int dp(int v) {
        return Math.round(v * mActivity.getResources().getDisplayMetrics().density);
    }

    public boolean isOpen() {
        return mRoot.getVisibility() == View.VISIBLE;
    }

    /** The ✎ key: open the box, or send it if it is open. */
    public void composeKey() {
        if (isOpen()) send(); else open();
    }

    public void open() {
        mRoot.setVisibility(View.VISIBLE);
        mRoot.bringToFront();
        mEdit.requestFocus();
        mEdit.setSelection(mEdit.getText().length());
        KeyboardUtils.showSoftKeyboard(mActivity, mEdit);
    }

    public void close() {
        mRoot.setVisibility(View.GONE);
        if (mActivity.getTerminalView() != null) mActivity.getTerminalView().requestFocus();
    }

    /** Send the draft (see {@link #output}) and close. An empty box just closes. */
    private void send() {
        TerminalSession session = mActivity.getCurrentSession();
        String out = output(mEdit.getText().toString());
        if (session == null || !session.isRunning()) { close(); return; }
        if (!out.isEmpty()) session.write(out);
        mEdit.setText("");
        close();
    }

    /**
     * What ✎ writes for a draft: one bracketed paste, and Enter only when the text ends in a line
     * break (that last break is not pasted, it becomes the Enter). Empty or only "\n" = "" / "\r".
     */
    static String output(String text) {
        String t = text.replace("\r\n", "\n");
        boolean run = t.endsWith("\n");
        if (run) t = t.substring(0, t.length() - 1);
        return (t.isEmpty() ? "" : bracketedPaste(t)) + (run ? "\r" : "");
    }

    /**
     * Always bracketed, even if the app in front never asked for it: over mosh the phone can't
     * see that mode reliably, and tmux strips the markers for a pane that did not ask for them.
     * Line breaks go as CR, as a terminal paste does.
     */
    static String bracketedPaste(String text) {
        String body = text.replace("\r\n", "\n").replace("\033", "").replace('\n', '\r');
        return "\033[200~" + body + "\033[201~";
    }
}
