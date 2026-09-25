package {{PKG}};

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

public class MainActivity extends Activity {

    private static final String TAG = "Zen";
    private static final String URL = "https://appassets.androidplatform.net/assets/web/index.html";
    private static final String FILE_URL = "file:///android_asset/web/index.html";

    private WebView webView;
    private boolean fallbackTried = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setupWebView();
            hideSystemBars();
            requestNotifications();
        } catch (Throwable t) {
            Log.e(TAG, "WebView failed to start", t);
            showFatal("WebView failed to start", t);
        }
    }

    private void setupWebView() {
        webView = new WebView(this);
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/web/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (error != null) {
                    Log.e(TAG, "load error " + error.getErrorCode() + ": " + error.getDescription()
                            + " <- " + request.getUrl() + " main=" + request.isForMainFrame());
                }
                if (request != null && request.isForMainFrame()) {
                    fallbackIfNeeded(view);
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.e(TAG, "load error " + errorCode + ": " + description + " <- " + failingUrl);
                fallbackIfNeeded(view);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i(TAG, "page: " + m.message() + " [" + m.sourceId() + ":" + m.lineNumber() + "]");
                return true;
            }
        });
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.addJavascriptInterface(new ZenBridge(this, this, webView), "Zen");

        setContentView(webView);
        webView.loadUrl(URL);
    }

    private void fallbackIfNeeded(WebView view) {
        if (fallbackTried) return;
        fallbackTried = true;
        Log.w(TAG, "appassets URL failed -> falling back to " + FILE_URL);
        view.stopLoading();
        view.loadUrl(FILE_URL);
    }

    private void showFatal(String title, Throwable t) {
        String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
        if (msg == null || msg.trim().isEmpty()) msg = String.valueOf(t.getClass());
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("Exit", (d, w) -> finish())
                .show();
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        if (decor == null || !decor.isAttachedToWindow()) return;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }
}