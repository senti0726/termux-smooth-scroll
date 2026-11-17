package com.termux.view;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Temporary diagnostics for the "screen stops updating until you leave and come back" bug.
 *
 * Every second, while the {@link TerminalView} is attached, it logs (tag {@code TermuxFreeze})
 * how many screen updates came in, how many invalidate() calls and onDraw() calls the view saw,
 * how many frames the window's RenderThread finished, and the view's window state. A second,
 * background-thread watchdog logs when the main thread stops answering, so a hung UI thread
 * is told apart from a UI thread that runs but does not draw.
 *
 * Read the log with: grep TermuxFreeze $LOGDIR/sv/termux-logcat/current
 */
public final class FreezeProbe {

    static final String TAG = "TermuxFreeze";
    private static final long PERIOD_MS = 1000;
    private static final long WATCHDOG_STALL_MS = 2000;

    /** Updates the activity client dropped before they reached the view, see {@link #noteDropped}. */
    private static final AtomicInteger sDroppedNotVisible = new AtomicInteger();
    private static final AtomicInteger sDroppedOtherSession = new AtomicInteger();

    /** Called from the app's session client when it ignores onTextChanged(). */
    public static void noteDropped(boolean notVisible) {
        (notVisible ? sDroppedNotVisible : sDroppedOtherSession).incrementAndGet();
    }

    /** Log an activity lifecycle step, with which activity instance it was. */
    public static void lifecycle(Object activity, String what) {
        Log.i(TAG, "LIFECYCLE " + what + " activity=" + Integer.toHexString(System.identityHashCode(activity)));
    }

    private final TerminalView mView;
    private final Handler mMainHandler;

    private int mUpdates, mInvalidates, mDraws, mTreeDraws;
    private final AtomicInteger mFrames = new AtomicInteger();
    private int mQuietTicks;
    private String mLastState = "";
    /** Seconds in a row with updates but no onDraw(). */
    private int mStallSeconds;

    private volatile long mLastMainTick = SystemClock.uptimeMillis();
    private HandlerThread mWatchdogThread;
    private Handler mWatchdogHandler;
    private boolean mWatchdogReported;

    private Window mWindow;
    private Object mFrameMetricsListener;

    private final ViewTreeObserver.OnDrawListener mTreeDrawListener = () -> mTreeDraws++;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            mLastMainTick = SystemClock.uptimeMillis();
            report();
            mMainHandler.postDelayed(this, PERIOD_MS);
        }
    };

    private final Runnable mWatchdog = new Runnable() {
        @Override
        public void run() {
            final long sinceTick = SystemClock.uptimeMillis() - mLastMainTick;
            if (sinceTick > WATCHDOG_STALL_MS) {
                if (!mWatchdogReported) {
                    Log.w(TAG, "WATCHDOG main thread silent for " + sinceTick + " ms, main thread stack:\n"
                        + stackOf(mMainHandler.getLooper().getThread()));
                    mWatchdogReported = true;
                }
            } else if (mWatchdogReported) {
                Log.w(TAG, "WATCHDOG main thread back after stall");
                mWatchdogReported = false;
            }
            if (mWatchdogHandler != null) mWatchdogHandler.postDelayed(this, PERIOD_MS);
        }
    };

    FreezeProbe(TerminalView view) {
        mView = view;
        mMainHandler = new Handler(android.os.Looper.getMainLooper());
    }

    void onScreenUpdated() { mUpdates++; }
    void onInvalidate() { mInvalidates++; }
    void onDraw() { mDraws++; }

    void onAttached() {
        mView.getViewTreeObserver().addOnDrawListener(mTreeDrawListener);
        attachFrameMetrics();
        mLastMainTick = SystemClock.uptimeMillis();
        mMainHandler.removeCallbacks(mTick);
        mMainHandler.postDelayed(mTick, PERIOD_MS);
        if (mWatchdogThread == null) {
            mWatchdogThread = new HandlerThread("TermuxFreezeWatchdog");
            mWatchdogThread.start();
            mWatchdogHandler = new Handler(mWatchdogThread.getLooper());
            mWatchdogHandler.postDelayed(mWatchdog, PERIOD_MS);
        }
        event("attached");
    }

    void onDetached() {
        event("detached");
        mView.getViewTreeObserver().removeOnDrawListener(mTreeDrawListener);
        detachFrameMetrics();
        mMainHandler.removeCallbacks(mTick);
        if (mWatchdogThread != null) {
            mWatchdogHandler.removeCallbacksAndMessages(null);
            mWatchdogThread.quitSafely();
            mWatchdogThread = null;
            mWatchdogHandler = null;
        }
    }

    /** Log a lifecycle event of the view with its full state. */
    void event(String what) {
        Log.i(TAG, "EVENT " + what + " " + state());
    }

    private void report() {
        final int frames = mFrames.getAndSet(0);
        final int notVisible = sDroppedNotVisible.getAndSet(0);
        final int otherSession = sDroppedOtherSession.getAndSet(0);
        final String state = state();
        final boolean active = mUpdates > 0 || mInvalidates > 0 || mDraws > 0 || frames > 0 || notVisible > 0;

        if (mUpdates > 0 && mDraws == 0) mStallSeconds++;
        else mStallSeconds = 0;

        // Log every second with activity, on any state change, and once a minute otherwise.
        if (active || !state.equals(mLastState) || ++mQuietTicks >= 60) {
            Log.i(TAG, (mStallSeconds > 0 ? "STALL" + mStallSeconds + "s " : "TICK ")
                + "updates=" + mUpdates + " invalidates=" + mInvalidates + " onDraw=" + mDraws
                + " treeDraws=" + mTreeDraws + " frames=" + frames
                + " droppedNotVisible=" + notVisible + " droppedOtherSession=" + otherSession
                + " " + state);
            mQuietTicks = 0;
        }
        mLastState = state;
        mUpdates = mInvalidates = mDraws = mTreeDraws = 0;
    }

    private String state() {
        final View root = mView.getRootView();
        final Activity activity = activityOf(mView.getContext());
        return "winVis=" + visibilityName(mView.getWindowVisibility())
            + " shown=" + mView.isShown()
            + " attached=" + mView.isAttachedToWindow()
            + " winFocus=" + mView.hasWindowFocus()
            + " focus=" + mView.hasFocus()
            + " size=" + mView.getWidth() + "x" + mView.getHeight()
            + " root=" + root.getWidth() + "x" + root.getHeight() + "/" + visibilityName(root.getVisibility())
            + " layoutReq=" + mView.isLayoutRequested()
            + " hwAccel=" + mView.isHardwareAccelerated()
            + " session=" + (mView.mTermSession == null ? "null" : Integer.toHexString(System.identityHashCode(mView.mTermSession)))
            + " emulator=" + (mView.mEmulator == null ? "null" : Integer.toHexString(System.identityHashCode(mView.mEmulator)))
            + " sessionEmu=" + (mView.mTermSession == null || mView.mTermSession.getEmulator() == null ? "null"
                : Integer.toHexString(System.identityHashCode(mView.mTermSession.getEmulator())))
            + " activity=" + (activity == null ? "null" : Integer.toHexString(System.identityHashCode(activity))
                + (activity.isFinishing() ? "/finishing" : "")
                + (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed() ? "/destroyed" : ""));
    }

    private void attachFrameMetrics() {
        if (Build.VERSION.SDK_INT < 24) return;
        final Activity activity = activityOf(mView.getContext());
        if (activity == null) return;
        detachFrameMetrics();
        mWindow = activity.getWindow();
        final Window.OnFrameMetricsAvailableListener listener =
            (window, frameMetrics, dropCountSinceLastInvocation) -> mFrames.addAndGet(1 + dropCountSinceLastInvocation);
        try {
            mWindow.addOnFrameMetricsAvailableListener(listener, mWatchdogHandlerOrMain());
            mFrameMetricsListener = listener;
        } catch (RuntimeException e) {
            Log.w(TAG, "frame metrics unavailable: " + e);
            mWindow = null;
        }
    }

    private void detachFrameMetrics() {
        if (Build.VERSION.SDK_INT < 24 || mWindow == null || mFrameMetricsListener == null) return;
        try {
            mWindow.removeOnFrameMetricsAvailableListener((Window.OnFrameMetricsAvailableListener) mFrameMetricsListener);
        } catch (RuntimeException ignored) {
        }
        mWindow = null;
        mFrameMetricsListener = null;
    }

    /** Frame metrics need a handler that is not the main thread's, so a hung main thread still counts. */
    private Handler mWatchdogHandlerOrMain() {
        if (mWatchdogThread == null) {
            mWatchdogThread = new HandlerThread("TermuxFreezeWatchdog");
            mWatchdogThread.start();
            mWatchdogHandler = new Handler(mWatchdogThread.getLooper());
            mWatchdogHandler.postDelayed(mWatchdog, PERIOD_MS);
        }
        return mWatchdogHandler;
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static String visibilityName(int visibility) {
        switch (visibility) {
            case View.VISIBLE: return "VISIBLE";
            case View.INVISIBLE: return "INVISIBLE";
            case View.GONE: return "GONE";
            default: return String.valueOf(visibility);
        }
    }

    private static String stackOf(Thread thread) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement e : thread.getStackTrace()) sb.append("    at ").append(e).append('\n');
        return sb.toString();
    }
}
