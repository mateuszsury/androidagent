package dev.mateuszsury.androidagent;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * Accent arcs orbiting the bubble's chip while an agent works: a faint track, a main arc whose
 * head and tail chase each other on an ease-in-out curve (like a native progress ring) and a thin
 * counter-rotating arc in a lighter tone. Paint-only; with animations off it draws one still frame.
 */
final class RingView extends View {
    private static final float CYCLE_S = 1.5f, MIN_SWEEP = 16f, MAX_SWEEP = 250f;

    private final Paint main = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint second = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float dp;
    private boolean spinning, fading;
    private long start;

    RingView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        for (Paint p : new Paint[]{main, second, track}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setSpinning(boolean on) {
        if (spinning == on) return;
        spinning = on;
        animate().cancel();
        if (on) {
            fading = false;
            start = SystemClock.uptimeMillis();
            setVisibility(VISIBLE);
            if (Motion.enabled()) {
                setAlpha(0f);
                setScaleX(0.86f);
                setScaleY(0.86f);
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.MEDIUM)
                        .setInterpolator(Motion.EASE_OUT).start();
            } else {
                setAlpha(1f);
                setScaleX(1f);
                setScaleY(1f);
            }
            invalidate();
        } else if (Motion.enabled() && isShown()) {
            // let the arcs keep turning while they fade out
            fading = true;
            animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(Motion.SHORT)
                    .setInterpolator(Motion.EASE_IN_OUT).withEndAction(() -> {
                        fading = false;
                        if (!spinning) setVisibility(GONE);
                    }).start();
        } else {
            fading = false;
            setVisibility(GONE);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!spinning && !fading) return;
        boolean moving = Motion.enabled();
        float t = moving ? (SystemClock.uptimeMillis() - start) / 1000f : 0.55f;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r1 = Math.min(cx, cy) - 2.2f * dp;
        float r2 = r1 - 3.6f * dp;

        // faint full track so the ring reads as one shape even when the arc is short
        track.setStrokeWidth(2.2f * dp);
        track.setColor(Theme.withAlpha(Ui.ACCENT, 0x30));
        c.drawCircle(cx, cy, r1, track);

        // main arc: head runs ahead, then the tail catches up, while the whole thing turns
        box.set(cx - r1, cy - r1, cx + r1, cy + r1);
        main.setStrokeWidth(3f * dp);
        main.setColor(Ui.ACCENT);
        float cycles = t / CYCLE_S;
        int k = (int) Math.floor(cycles);
        float p = cycles - k;
        float grow = p < 0.5f ? Motion.EASE_IN_OUT.getInterpolation(p * 2f) : 1f;
        float shrink = p < 0.5f ? 0f : Motion.EASE_IN_OUT.getInterpolation((p - 0.5f) * 2f);
        float span = MAX_SWEEP - MIN_SWEEP;
        float startAngle = (t * 160f + (k + shrink) * span) % 360f - 90f;
        float sweep = moving ? MIN_SWEEP + span * (grow - shrink) : 220f;
        c.drawArc(box, startAngle, sweep, false, main);

        // a thin, lighter arc turning the other way
        if (moving) {
            box.set(cx - r2, cy - r2, cx + r2, cy + r2);
            second.setStrokeWidth(1.6f * dp);
            second.setColor(Theme.withAlpha(Theme.lighten(Ui.ACCENT, 0.35f), 0xB4));
            float wave = 0.5f - 0.5f * (float) Math.cos(t * 2.2f);
            c.drawArc(box, (-t * 120f) % 360f, 36f + 46f * wave, false, second);
            postInvalidateOnAnimation();
        }
    }
}
