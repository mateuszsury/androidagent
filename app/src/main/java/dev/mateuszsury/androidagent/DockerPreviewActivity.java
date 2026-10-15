package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;
import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.*;
import android.widget.*;
import java.util.Locale;

/** Interactive localhost preview of one published container port. Pages get no Android Java bridge. */
public class DockerPreviewActivity extends Screen {
    static final String EXTRA_PORT = "port", EXTRA_TITLE = "title";
    private static final int STEPS = 1000;
    WebView web;
    private int port;
    private String home, lastUrl;
    private ProgressBar loading;
    private Animator creep;
    private View failure;
    private TextView failureDetail;
    private boolean failed, rendererGone;

    /** Preview of a task's published app; {@code port} is the host_port from docker_run/docker_status. */
    static Intent intent(Context c, int port, String title) {
        Intent i = new Intent(c, DockerPreviewActivity.class).putExtra(EXTRA_PORT, port);
        if (title != null && !title.trim().isEmpty()) i.putExtra(EXTRA_TITLE, title.trim());
        return i;
    }

    /** Only VM preview forwards, never the guest control API or another local service. */
    static int previewPort(int requested) {
        boolean runtime = requested >= DockerVm.HOST_PREVIEW_FIRST && requested <= DockerVm.HOST_PREVIEW_LAST;
        return runtime || requested == DockerVm.PREVIEW_PORT ? requested : DockerVm.PREVIEW_PORT;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        port = previewPort(getIntent().getIntExtra(EXTRA_PORT, DockerVm.PREVIEW_PORT));
        String origin = "http://127.0.0.1:" + port;
        home = origin + "/";
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        boolean named = title != null && !title.trim().isEmpty();
        if (named) setTitle(title.trim());

        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        LinearLayout bar = topBar(this, "Podgląd aplikacji", named ? title.trim() + " · " + origin : origin, refreshAction());
        // Keep the port visible when a long task name shares the line.
        View subtitle = ((ViewGroup) topBarTitle(bar).getParent()).getChildAt(1);
        if (subtitle instanceof TextView) ((TextView) subtitle).setEllipsize(TextUtils.TruncateAt.MIDDLE);
        root.addView(bar, full());

        FrameLayout stage = new FrameLayout(this);
        web = new WebView(this);
        web.setContentDescription("Podgląd aplikacji WWW");
        // Revealed on the first committed frame: no white flash over the dark screen.
        web.setVisibility(View.INVISIBLE);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        web.setWebViewClient(new PreviewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                if (!failed && progress < 100) advance(progress * STEPS / 100);
            }
        });
        stage.addView(web, new FrameLayout.LayoutParams(-1, -1));
        failure = failureView();
        failure.setVisibility(View.GONE);
        stage.addView(failure, new FrameLayout.LayoutParams(-1, -1));
        loading = loadBar();
        stage.addView(loading, new FrameLayout.LayoutParams(-1, dp(this, 2), Gravity.TOP));
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        web.loadUrl(home);
    }

    @Override protected void onDestroy() {
        if (creep != null) creep.cancel();
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }

    private void refresh() {
        if (rendererGone) { recreate(); return; }
        web.loadUrl(lastUrl != null ? lastUrl : home);
    }

    private boolean local(Uri uri) {
        return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == port;
    }

    private static boolean http(String url) { return url != null && (url.startsWith("http://") || url.startsWith("https://")); }

    private final class PreviewClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (local(uri)) return false;
            if ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                catch (android.content.ActivityNotFoundException e) { Toast.makeText(DockerPreviewActivity.this, "Brak przeglądarki do otwarcia odnośnika", Toast.LENGTH_SHORT).show(); }
            }
            return true;
        }

        @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (!http(url)) return; // our own placeholder document after an error
            failed = false;
            lastUrl = url;
            startLoading();
        }

        @Override public void onPageCommitVisible(WebView view, String url) {
            if (http(url) && !failed) reveal();
        }

        @Override public void onPageFinished(WebView view, String url) {
            if (!http(url)) return;
            finishLoading();
            if (!failed) reveal();
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            failed = true;
            showFailure(request.getUrl().toString(), String.valueOf(error.getDescription()));
            // Keeps a readable page in the DOM too (instrumentation and WebView tooling read it).
            view.loadDataWithBaseURL(null, placeholder(), "text/html", "UTF-8", null);
        }

        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // A container page crashed WebView's renderer: keep androidagent alive.
            rendererGone = true;
            failed = true;
            showFailure(lastUrl != null ? lastUrl : home, "WebView: proces strony zakończył pracę");
            return true;
        }
    }

    // ------------------------------------------------------------------ chrome

    private View refreshAction() {
        LinearLayout action = row(this);
        action.setContentDescription("Odśwież");
        action.setPadding(dp(this, 10), dp(this, 7), dp(this, 13), dp(this, 7));
        action.setMinimumHeight(dp(this, 36));
        action.setBackground(new RippleDrawable(ColorStateList.valueOf(Theme.withAlpha(TEXT, 0x1F)),
                outlined(this, 0x00000000, 20, OUTLINE, 1), rounded(this, 0xFFFFFFFF, 20)));
        action.addView(icon(this, R.drawable.ms_refresh, ACCENT_TEXT, 16));
        TextView label = single(monoText(this, "Odśwież", 13, TEXT));
        label.setTypeface(mono(this, 600));
        label.setPadding(dp(this, 6), 0, 0, 0);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        action.addView(label);
        action.setClickable(true);
        action.setFocusable(true);
        action.setOnClickListener(v -> refresh());
        Motion.press(action);
        LinearLayout.LayoutParams p = wrap();
        p.rightMargin = dp(this, 4);
        action.setLayoutParams(p);
        return action;
    }

    /** Thin accent bar over the top edge of the page; eases toward real progress, then fades. */
    private ProgressBar loadBar() {
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(false);
        bar.setMax(STEPS);
        bar.setMinHeight(0);
        bar.setMaxHeight(dp(this, 2));
        ClipDrawable fill = new ClipDrawable(new ColorDrawable(ACCENT), Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{new ColorDrawable(Theme.withAlpha(ACCENT, 0x24)), fill});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        bar.setProgressDrawable(layers);
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        bar.setVisibility(View.INVISIBLE);
        return bar;
    }

    private void startLoading() {
        loading.animate().cancel();
        loading.setAlpha(1f);
        loading.setVisibility(View.VISIBLE);
        if (creep != null) creep.cancel();
        loading.setProgress(0);
        advance(STEPS / 12);
    }

    /** Quick catch-up to {@code target}, then a slow decelerating creep so a slow VM server still looks alive. */
    private void advance(int target) {
        if (loading.getVisibility() != View.VISIBLE || target <= loading.getProgress()) return;
        if (creep != null) creep.cancel();
        if (!Motion.enabled()) { loading.setProgress(target); return; }
        ObjectAnimator catchUp = ObjectAnimator.ofInt(loading, "progress", loading.getProgress(), target);
        catchUp.setDuration(Motion.SHORT);
        catchUp.setInterpolator(Motion.EASE_OUT);
        ObjectAnimator slow = ObjectAnimator.ofInt(loading, "progress", target, target + (STEPS * 9 / 10 - target) * 2 / 3);
        slow.setDuration(6000);
        slow.setInterpolator(Motion.DECELERATE);
        AnimatorSet set = new AnimatorSet();
        set.playSequentially(catchUp, slow);
        creep = set;
        set.start();
    }

    private void finishLoading() {
        if (loading.getVisibility() != View.VISIBLE) return;
        if (creep != null) creep.cancel();
        if (!Motion.enabled()) { loading.setVisibility(View.INVISIBLE); loading.setProgress(0); return; }
        ObjectAnimator done = ObjectAnimator.ofInt(loading, "progress", loading.getProgress(), STEPS);
        done.setDuration(Motion.SHORT);
        done.setInterpolator(Motion.EASE_OUT);
        creep = done;
        done.start();
        loading.animate().alpha(0f).setStartDelay(Motion.SHORT).setDuration(Motion.MEDIUM)
                .withEndAction(() -> { loading.setVisibility(View.INVISIBLE); loading.setAlpha(1f); loading.setProgress(0); }).start();
    }

    private void reveal() {
        if (rendererGone) return;
        if (web.getVisibility() != View.VISIBLE) {
            web.setVisibility(View.VISIBLE);
            Motion.fadeIn(web, 0);
        }
        web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if (failure.getVisibility() == View.VISIBLE) {
            if (!Motion.enabled()) { failure.setVisibility(View.GONE); return; }
            failure.animate().alpha(0f).setDuration(Motion.SHORT)
                    .withEndAction(() -> { if (!failed) failure.setVisibility(View.GONE); failure.setAlpha(1f); }).start();
        }
    }

    // ------------------------------------------------------------------ error state

    private View failureView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout box = column(this);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(this, 20), dp(this, 16), dp(this, 20), dp(this, 48));
        box.addView(emptyState(this, "Aplikacja jeszcze nie odpowiada",
                "Uruchom kontener z opublikowanym portem i naciśnij Odśwież. Serwer w maszynie wirtualnej może startować kilkanaście sekund."), full());
        failureDetail = monoText(this, "", 12, CODE_TEXT);
        failureDetail.setLineSpacing(dp(this, 3), 1f);
        failureDetail.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        failureDetail.setBackground(outlined(this, CODE, 12, CODE_LINE, 1));
        failureDetail.setTextIsSelectable(true);
        box.addView(failureDetail, full());
        TextView hint = text(this, "Agent publikuje aplikację przez docker_run z parametrem port; adres podaje docker_status.", 12, FAINT);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(this, 8), dp(this, 12), dp(this, 8), 0);
        box.addView(hint, full());
        Button retry = button(this, "Spróbuj ponownie", true);
        retry.setOnClickListener(v -> refresh());
        LinearLayout.LayoutParams p = wrap();
        p.topMargin = dp(this, 18);
        box.addView(retry, p);
        scroll.addView(box, new ScrollView.LayoutParams(-1, -1));
        return scroll;
    }

    private void showFailure(String url, String reason) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        append(s, "$ ", ACCENT);
        append(s, "GET " + url + "\n", CODE_TEXT);
        append(s, "└ ", CODE_MUTED);
        append(s, reason == null || reason.isEmpty() ? "brak odpowiedzi" : reason, ERR);
        failureDetail.setText(s);
        finishLoading();
        web.setVisibility(View.INVISIBLE);
        web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        failure.animate().cancel();
        failure.setAlpha(1f);
        if (failure.getVisibility() != View.VISIBLE) {
            failure.setVisibility(View.VISIBLE);
            Motion.enter(failure, 0);
        }
    }

    private static void append(SpannableStringBuilder s, String text, int color) {
        int start = s.length();
        s.append(text);
        s.setSpan(new ForegroundColorSpan(color), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static String placeholder() {
        return "<!doctype html><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Podgląd niedostępny</title><style>html,body{margin:0;background:" + css(BG) + ";color:" + css(MUTED)
                + ";font:15px sans-serif}p{padding:24px}</style>"
                + "<p>Aplikacja jeszcze nie odpowiada. Uruchom kontener z podglądem i naciśnij Odśwież.</p>";
    }

    private static String css(int color) { return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF); }
}
