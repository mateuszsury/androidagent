package dev.mateuszsury.androidagent;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

/**
 * Shared motion: short, decelerating and quiet, like a terminal UI that feels alive. Every helper
 * respects the system "remove animations" setting (and test devices with animator scale 0): infinite
 * animations are never started there, and one-shot ones jump straight to their end state.
 */
final class Motion {
    private Motion() {
    }

    /** Material "emphasized decelerate"-like curve. */
    static final Interpolator EASE_OUT = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    static final Interpolator EASE_IN_OUT = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    static final Interpolator DECELERATE = new DecelerateInterpolator(1.6f);

    static final int SHORT = 160, MEDIUM = 240, LONG = 360;

    static boolean enabled() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    /** Fade in and rise by a few dp; for newly added rows, messages and cards. */
    static void enter(View v, int delayMs) {
        if (!enabled()) return;
        v.setAlpha(0f);
        v.setTranslationY(Ui.dp(v.getContext(), 10));
        v.animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(MEDIUM + 40)
                .setInterpolator(EASE_OUT).withLayer().start();
    }

    static void enter(View v) {
        enter(v, 0);
    }

    /** Fade only; for content that must not move (status lines, streaming text). */
    static void fadeIn(View v, int delayMs) {
        if (!enabled()) return;
        v.setAlpha(0f);
        v.animate().alpha(1f).setStartDelay(delayMs).setDuration(MEDIUM).setInterpolator(EASE_OUT).start();
    }

    /** Pop in from slightly smaller; for badges, dots and buttons that appear. */
    static void pop(View v, int delayMs) {
        if (!enabled()) return;
        v.setAlpha(0f);
        v.setScaleX(0.86f);
        v.setScaleY(0.86f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(delayMs).setDuration(MEDIUM)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
    }

    /** Stagger {@link #enter} over the children of a group, for screens that build lists at once. */
    static void stagger(ViewGroup g, int startDelay, int step) {
        if (!enabled()) return;
        int n = g.getChildCount();
        for (int i = 0; i < n; i++) enter(g.getChildAt(i), startDelay + Math.min(i, 10) * step);
    }

    /** A soft press: scales the view down while touched. Never consumes the touch. */
    @SuppressLint("ClickableViewAccessibility")
    static <T extends View> T press(T v) {
        v.setOnTouchListener((view, e) -> {
            if (!enabled()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.965f).scaleY(0.965f).setDuration(90).setInterpolator(EASE_OUT).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(SHORT).setInterpolator(EASE_OUT).start();
                    break;
                default:
                    break;
            }
            return false;
        });
        return v;
    }

    /** Gentle opacity breathing while something runs; stops itself when the view is detached. */
    static Animator pulse(View v) {
        return pulse(v, 0.35f, 900);
    }

    static Animator pulse(View v, float low, int periodMs) {
        if (!enabled()) return null;
        ObjectAnimator a = ObjectAnimator.ofFloat(v, View.ALPHA, 1f, low);
        a.setDuration(periodMs);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setInterpolator(EASE_IN_OUT);
        bindToWindow(v, a);
        a.start();
        return a;
    }

    /** Cancel an infinite animator when its view leaves the window (activity finished, row removed). */
    static void bindToWindow(View v, Animator a) {
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                if (!a.isStarted()) a.start();
            }

            @Override public void onViewDetachedFromWindow(View view) {
                a.cancel();
            }
        });
    }

    /** Animate a view from GONE to its natural height. */
    static void expand(View v) {
        if (v.getVisibility() == View.VISIBLE && v.getHeight() > 0) return;
        if (!enabled()) {
            v.setVisibility(View.VISIBLE);
            return;
        }
        View parent = (View) v.getParent();
        int width = parent == null ? 0 : parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        v.measure(width > 0 ? View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
                        : View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int target = v.getMeasuredHeight();
        final ViewGroup.LayoutParams lp = v.getLayoutParams();
        final int original = lp.height;
        lp.height = 1;
        v.setLayoutParams(lp);
        v.setVisibility(View.VISIBLE);
        v.setAlpha(0f);
        ValueAnimator a = ValueAnimator.ofInt(1, Math.max(1, target));
        a.setDuration(Math.min(LONG, MEDIUM + target / 8));
        a.setInterpolator(EASE_OUT);
        a.addUpdateListener(an -> {
            lp.height = (Integer) an.getAnimatedValue();
            v.setAlpha(an.getAnimatedFraction());
            v.setLayoutParams(lp);
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                lp.height = original;
                v.setAlpha(1f);
                v.setLayoutParams(lp);
            }
        });
        a.start();
    }

    /** Animate a view to height 0, then GONE. */
    static void collapse(View v) {
        if (v.getVisibility() == View.GONE) return;
        if (!enabled() || v.getHeight() == 0) {
            v.setVisibility(View.GONE);
            return;
        }
        final ViewGroup.LayoutParams lp = v.getLayoutParams();
        final int original = lp.height;
        int start = v.getHeight();
        ValueAnimator a = ValueAnimator.ofInt(start, 0);
        a.setDuration(Math.min(LONG, SHORT + start / 10));
        a.setInterpolator(EASE_IN_OUT);
        a.addUpdateListener(an -> {
            lp.height = (Integer) an.getAnimatedValue();
            v.setAlpha(1f - an.getAnimatedFraction());
            v.setLayoutParams(lp);
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                v.setVisibility(View.GONE);
                lp.height = original;
                v.setAlpha(1f);
                v.setLayoutParams(lp);
            }
        });
        a.start();
    }

    /** Rotate a chevron to show open/closed state. */
    static void rotate(View v, float degrees) {
        if (!enabled()) {
            v.setRotation(degrees);
            return;
        }
        v.animate().rotation(degrees).setDuration(MEDIUM).setInterpolator(EASE_OUT).start();
    }

    /** Swap a label with a quick cross-fade. */
    static void swapText(TextView t, CharSequence text) {
        if (text.toString().contentEquals(t.getText())) return;
        if (!enabled() || t.getWindowToken() == null) {
            t.setText(text);
            return;
        }
        t.animate().alpha(0f).setDuration(90).withEndAction(() -> {
            t.setText(text);
            t.animate().alpha(1f).setDuration(SHORT).start();
        }).start();
    }

    /** Animate layout changes (added/removed/resized children) in a container. */
    static void animateLayout(ViewGroup g) {
        if (!enabled()) return;
        LayoutTransition t = new LayoutTransition();
        t.enableTransitionType(LayoutTransition.CHANGING);
        t.setDuration(SHORT + 40);
        t.setInterpolator(LayoutTransition.CHANGING, EASE_OUT);
        t.setInterpolator(LayoutTransition.CHANGE_APPEARING, EASE_OUT);
        t.setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, EASE_OUT);
        t.setStartDelay(LayoutTransition.APPEARING, 0);
        t.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0);
        g.setLayoutTransition(t);
    }

    /** A brief horizontal shake for invalid input. */
    static void shake(View v) {
        if (!enabled()) return;
        float d = Ui.dp(v.getContext(), 6);
        ObjectAnimator a = ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0, d, -d, d * 0.6f, -d * 0.6f, 0);
        a.setDuration(320);
        a.start();
    }
}
