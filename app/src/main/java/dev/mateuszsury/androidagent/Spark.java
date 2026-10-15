package dev.mateuszsury.androidagent;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Claude Code's thinking indicator, drawn natively: a coral starburst whose rays breathe and turn
 * ({@link Spark}), and {@link Line}: the spark, a shimmering verb ("Kombinuję…") and elapsed time.
 * The glyphs (✻ ✶ ✳ ✢) are not in the bundled fonts, so the star is painted on a Canvas.
 */
final class Spark extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color = Ui.ACCENT;
    private int rays = 10;
    private boolean animated = true;
    private boolean ticking;
    private final long born = SystemClock.uptimeMillis();
    private float staticPhase = 0.35f;

    Spark(Context c) {
        super(c);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    Spark color(int c) {
        color = c;
        invalidate();
        return this;
    }

    Spark rays(int n) {
        rays = Math.max(4, Math.min(16, n));
        invalidate();
        return this;
    }

    /** A still star (logo use) or a breathing one (activity). */
    Spark animated(boolean on) {
        animated = on;
        if (on) tick();
        else invalidate();
        return this;
    }

    private void tick() {
        if (ticking || !animated || !Motion.enabled() || getWindowToken() == null) return;
        ticking = true;
        postInvalidateOnAnimation();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        tick();
    }

    @Override protected void onDetachedFromWindow() {
        ticking = false;
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (visibility == VISIBLE) tick();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f;
        boolean moving = animated && Motion.enabled();
        float t = moving ? (SystemClock.uptimeMillis() - born) / 1000f : staticPhase;
        float spin = moving ? t * 38f : 12f;
        paint.setColor(color);
        paint.setStrokeWidth(Math.max(1.6f, r * 0.2f));
        float inner = r * 0.12f;
        for (int i = 0; i < rays; i++) {
            double base = Math.toRadians(spin + i * 360.0 / rays);
            // Irregular, breathing rays: each has its own phase, like the Claude spark.
            float wave = (float) (0.5 + 0.5 * Math.sin(t * 3.1 + i * 1.7));
            float length = r * (0.5f + 0.45f * (moving ? wave : (i % 3 == 0 ? 1f : 0.62f)));
            float x1 = cx + (float) Math.cos(base) * inner, y1 = cy + (float) Math.sin(base) * inner;
            float x2 = cx + (float) Math.cos(base) * length, y2 = cy + (float) Math.sin(base) * length;
            canvas.drawLine(x1, y1, x2, y2, paint);
        }
        if (moving && ticking && isShown()) postInvalidateOnAnimation();
        else ticking = false;
    }

    // ------------------------------------------------------------------ status line

    /** Playful verbs, in the spirit of Claude Code's spinner. */
    static final String[] VERBS = {
            "Myślę", "Kombinuję", "Analizuję", "Rozważam", "Główkuję", "Dumam", "Planuję", "Pichcę",
            "Warzę", "Szlifuję", "Łączę kropki", "Przetwarzam", "Kminię", "Składam", "Wymyślam", "Przeliczam",
            "Rozkminiam", "Klaruję", "Destyluję", "Tkam", "Doprawiam", "Mieszam", "Krystalizuję", "Ogarniam",
    };

    /**
     * "✻ Kombinuję… (12s · Stop)" — a spark, a verb with a moving highlight and the elapsed time. When
     * the agent reports a concrete status (a tool step), it is shown instead of the verb.
     */
    static final class Line extends LinearLayout {
        private final Spark spark;
        private final TextView verb;
        private final TextView meta;
        private final ShimmerAnimator shimmer;
        private long started = SystemClock.elapsedRealtime();
        private String status = "";
        private int verbIndex = (int) (Math.random() * VERBS.length);
        private long verbChanged = SystemClock.elapsedRealtime();
        private final Runnable clock = new Runnable() {
            @Override public void run() {
                update();
                if (getWindowToken() != null && getVisibility() == VISIBLE) postDelayed(this, 1000);
            }
        };

        Line(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            spark = new Spark(c);
            addView(spark, new LayoutParams(Ui.dp(c, 16), Ui.dp(c, 16)));
            verb = Ui.single(Ui.text(c, "", 14, Ui.ACCENT_TEXT));
            verb.setTypeface(Ui.mono(c, 500));
            verb.setPadding(Ui.dp(c, 10), 0, 0, 0);
            verb.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LayoutParams vp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 0);
            addView(verb, vp);
            meta = Ui.single(Ui.monoText(c, "", 12, Ui.FAINT));
            meta.setPadding(Ui.dp(c, 8), 0, 0, 0);
            meta.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(meta, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            shimmer = new ShimmerAnimator(verb);
            setContentDescription("Agent pracuje");
            update();
        }

        TextView verbView() {
            return verb;
        }

        /** Restart the elapsed time, e.g. when a new run starts. */
        void start(long elapsedRealtimeStart) {
            started = elapsedRealtimeStart;
            update();
        }

        /** A concrete status from the agent; empty for a rotating verb. */
        void setStatus(String s) {
            String next = s == null ? "" : s.trim();
            if (next.equals(status)) return;
            status = next;
            update();
        }

        private void update() {
            long now = SystemClock.elapsedRealtime();
            if (now - verbChanged > 4200) {
                verbIndex = (verbIndex + 1 + (int) (Math.random() * 3)) % VERBS.length;
                verbChanged = now;
            }
            String label = status.isEmpty() || isGeneric(status) ? VERBS[verbIndex] + "…" : status;
            if (!label.contentEquals(verb.getText())) {
                verb.setText(label);
                shimmer.restart();
            }
            long s = Math.max(0, (now - started) / 1000);
            String time = s < 60 ? s + "s" : (s / 60) + "m " + (s % 60) + "s";
            String m = "(" + time + ")";
            if (!m.contentEquals(meta.getText())) meta.setText(m);
        }

        private static boolean isGeneric(String s) {
            return s.equals("Thinking…") || s.equals("Working…") || s.equals("Pracuję…") || s.equals("Myślę…");
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            removeCallbacks(clock);
            post(clock);
            shimmer.start();
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(clock);
            shimmer.stop();
            super.onDetachedFromWindow();
        }

        @Override protected void onVisibilityChanged(View changed, int visibility) {
            super.onVisibilityChanged(changed, visibility);
            if (getWindowToken() == null) return;
            removeCallbacks(clock);
            if (visibility == VISIBLE) {
                post(clock);
                shimmer.start();
            } else {
                shimmer.stop();
            }
        }
    }

    /** A highlight band that sweeps across a TextView's glyphs. Paint-only: no text or layout changes. */
    static final class ShimmerAnimator {
        private final TextView view;
        private ValueAnimator animator;
        private LinearGradient gradient;
        private final Matrix matrix = new Matrix();
        private int width;

        ShimmerAnimator(TextView view) {
            this.view = view;
        }

        void start() {
            if (!Motion.enabled() || (animator != null && animator.isStarted())) return;
            animator = ValueAnimator.ofFloat(-1f, 2f);
            animator.setDuration(1800);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(Motion.EASE_IN_OUT);
            animator.addUpdateListener(a -> {
                int w = (int) view.getPaint().measureText(view.getText().toString());
                if (w <= 0) return;
                if (gradient == null || w != width) {
                    width = w;
                    int base = view.getCurrentTextColor();
                    int glow = Theme.lighten(base, Ui.DARK ? 0.55f : 0.0f);
                    if (!Ui.DARK) glow = Theme.darken(base, 0.55f);
                    gradient = new LinearGradient(0, 0, w * 0.5f, 0,
                            new int[]{base, glow, base}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
                    view.getPaint().setShader(gradient);
                }
                matrix.setTranslate(w * (Float) a.getAnimatedValue() - w * 0.25f, 0);
                gradient.setLocalMatrix(matrix);
                view.invalidate();
            });
            animator.start();
        }

        void restart() {
            gradient = null;
            width = 0;
            view.getPaint().setShader(null);
        }

        void stop() {
            if (animator != null) animator.cancel();
            animator = null;
            view.getPaint().setShader(null);
            gradient = null;
            view.invalidate();
        }
    }
}
