package com.inhouse.paellas;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.inhouse.services.updater.NativeUpdates;
import com.inhouse.services.updater.UpdateActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;

/**
 * paellas.net for Android: a full-screen WebView around https://paellas.net, so
 * every web release reaches the app without a new APK. Read android/README.md.
 * Same shell as Inhouse Learn (inhouselearn/android/.../MainActivity.java).
 *
 * The page talks to this shell only through {@code window.PaellasApp}, an
 * origin-restricted WebMessageListener (never addJavascriptInterface): Google
 * sign-in in a Chrome Custom Tab, the system "save as" dialog for the PDFs the
 * page generates, Android's back button asking the page first, and opening the
 * update settings. The camera is granted only to paellas.net (ticket reader).
 *
 * APK updates come from the shared Inhouse services updater (UPDATER.md):
 * NativeUpdates runs its hidden checker with the bundled assets and opens
 * UpdateActivity for the popup. The hosted page never gets an install bridge.
 * The updater code is synced from its canonical repository; never edit it here.
 */
public class MainActivity extends ComponentActivity {
    static final String APP_HOST = "paellas.net";
    static final String APP_ORIGIN = "https://" + APP_HOST;
    static final String START_URL = APP_ORIGIN + "/?app=android";
    static final String APP_SCHEME = "paellas";
    static final String GOOGLE_AUTH = "https://accounts.google.com/o/oauth2/";
    static final long SPLASH_MAX_MS = 2500;

    private WebView webView;
    private FrameLayout root;
    private boolean pageReady;
    private boolean offlineRetry;
    private long createdAt;
    private String pendingAuthQuery;
    private ValueCallback<Uri[]> fileCallback;
    private byte[] pendingSave;
    private String pendingSaveMime = "application/octet-stream";
    private NativeUpdates updates;
    private PermissionRequest pendingCamera;
    private boolean cameraPromptOpen;

    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                cameraPromptOpen = false;
                PermissionRequest request = pendingCamera;
                pendingCamera = null;
                if (request == null) return;
                if (granted && trustedCameraRequest(request)) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                } else {
                    request.deny();
                }
            });

    private final ActivityResultLauncher<Intent> filePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), this::deliverPickedFiles);

    private final ActivityResultLauncher<String> saveDocument = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/octet-stream") {
                @NonNull
                @Override
                public Intent createIntent(@NonNull Context context, @NonNull String input) {
                    return super.createIntent(context, input).setType(pendingSaveMime);
                }
            }, this::writePendingSave);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // The paella stays up until the page has painted (at most SPLASH_MAX_MS).
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        createdAt = SystemClock.uptimeMillis();
        splash.setKeepOnScreenCondition(() -> !pageReady && SystemClock.uptimeMillis() - createdAt < SPLASH_MAX_MS);

        int background = ContextCompat.getColor(this, R.color.app_background);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(true);
        bars.setAppearanceLightNavigationBars(true);

        root = new FrameLayout(this);
        // The status bar sits on the page's yellow band; the rest is the cream page.
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.app_band));
        webView = new WebView(this);
        webView.setBackgroundColor(background);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // Edge to edge: the page is laid out between the system bars and above the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets system = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(system.left, system.top, system.right, Math.max(system.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });

        configureWebView();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // The page closes its own panels, dialogs and views first.
                webView.evaluateJavascript(
                        "(typeof window.paellasBack === 'function' && window.paellasBack()) === true",
                        handled -> {
                            if ("true".equals(handled)) return;
                            if (webView.canGoBack()) {
                                webView.goBack();
                                return;
                            }
                            setEnabled(false);
                            getOnBackPressedDispatcher().onBackPressed();
                            setEnabled(true);
                        });
            }
        });

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(START_URL);
        }
        handleAppIntent(getIntent());
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        // Identify the shell without hiding that this is a WebView.
        settings.setUserAgentString(settings.getUserAgentString() + " PaellasApp/" + BuildConfig.VERSION_NAME);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // The web has its own light design; never let Android invert it.
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // Only the app's own origin, main frame only, gets window.PaellasApp.
            WebViewCompat.addWebMessageListener(webView, "PaellasApp", Collections.singleton(APP_ORIGIN),
                    (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                        if (isMainFrame && message.getData() != null) onWebMessage(message.getData());
                    });
        }
        webView.setWebViewClient(new ShellClient());
        webView.setWebChromeClient(new ShellChrome());
        webView.setDownloadListener((url, userAgent, disposition, mime, length) -> openExternal(Uri.parse(url)));
    }

    private void onWebMessage(String data) {
        try {
            JSONObject message = new JSONObject(data);
            switch (message.optString("type")) {
                case "signIn": {
                    String url = message.optString("url");
                    if (url.startsWith(GOOGLE_AUTH)) openCustomTab(Uri.parse(url));
                    break;
                }
                case "save": {
                    pendingSave = Base64.decode(message.optString("data"), Base64.DEFAULT);
                    String mime = message.optString("mime");
                    pendingSaveMime = mime.isEmpty() ? "application/octet-stream" : mime;
                    saveDocument.launch(message.optString("name", "paellas"));
                    break;
                }
                case "updates":
                    // Channel A/B and "Check for updates" (the profile menu in the app).
                    startActivity(new Intent(this, UpdateActivity.class).putExtra("settings", true));
                    break;
                case "open":
                    openExternal(Uri.parse(message.optString("url")));
                    break;
                default:
                    break;
            }
        } catch (JSONException | IllegalArgumentException error) {
            // Malformed messages are ignored; only the app's own page can send them.
        }
    }

    // --- Google sign-in -------------------------------------------------------
    // Google does not allow its sign-in page inside a WebView, so the page asks
    // for a Chrome Custom Tab. Google redirects to https://paellas.net (the
    // client's registered URI), whose early script forwards to app-auth.html,
    // which hands the result back as paellas://auth?...; the page checks its state.

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAppIntent(intent);
    }

    private void handleAppIntent(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data != null && APP_SCHEME.equals(data.getScheme())) handleAppUri(data);
    }

    private void handleAppUri(Uri uri) {
        if ("retry".equals(uri.getHost())) {
            offlineRetry = false;
            webView.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
            webView.loadUrl(START_URL);
            return;
        }
        if (!"auth".equals(uri.getHost()) || uri.getEncodedQuery() == null) return;
        pendingAuthQuery = uri.getEncodedQuery();
        deliverPendingAuth();
    }

    private void deliverPendingAuth() {
        if (!pageReady || pendingAuthQuery == null) return;
        String query = pendingAuthQuery;
        pendingAuthQuery = null;
        webView.evaluateJavascript("window.paellasAuth && window.paellasAuth("
                + JSONObject.quote(query) + ")", null);
    }

    // --- Camera (ticket reader) ------------------------------------------------

    private boolean trustedCameraRequest(PermissionRequest request) {
        Uri origin = request.getOrigin();
        return !isFinishing() && !isDestroyed() && webView != null
                && "https".equals(origin.getScheme())
                && APP_HOST.equals(origin.getHost())
                && (origin.getPort() == -1 || origin.getPort() == 443)
                && webView.getUrl() != null && isAppUrl(Uri.parse(webView.getUrl()))
                && request.getResources().length == 1
                && PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(request.getResources()[0]);
    }

    private void cancelPendingCamera() {
        if (pendingCamera != null) {
            pendingCamera.deny();
            pendingCamera = null;
        }
    }

    // --- Links, files and downloads ------------------------------------------

    private static boolean isAppUrl(Uri uri) {
        return "https".equals(uri.getScheme()) && (APP_HOST.equals(uri.getHost()) || ("www." + APP_HOST).equals(uri.getHost()));
    }

    private void openCustomTab(Uri uri) {
        int toolbar = ContextCompat.getColor(this, R.color.app_band);
        CustomTabsIntent tab = new CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(new CustomTabColorSchemeParams.Builder().setToolbarColor(toolbar).build())
                .setShowTitle(true)
                .build();
        try {
            tab.launchUrl(this, uri);
        } catch (ActivityNotFoundException error) {
            openWithSystem(uri);
        }
    }

    private void openExternal(Uri uri) {
        String scheme = uri.getScheme();
        if ("https".equals(scheme) || "http".equals(scheme)) openCustomTab(uri);
        else openWithSystem(uri);
    }

    private void openWithSystem(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show();
        }
    }

    private void deliverPickedFiles(ActivityResult result) {
        if (fileCallback == null) return;
        Uri[] picked = null;
        Intent data = result.getData();
        if (result.getResultCode() == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                picked = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) picked[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                picked = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(picked);
        fileCallback = null;
    }

    private void writePendingSave(Uri target) {
        byte[] bytes = pendingSave;
        pendingSave = null;
        if (target == null || bytes == null) return;
        try (OutputStream out = getContentResolver().openOutputStream(target)) {
            if (out == null) throw new IOException("No output stream");
            out.write(bytes);
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        } catch (IOException | SecurityException error) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void showOffline(WebView view) {
        String html = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<body style='margin:0;min-height:100vh;display:grid;place-items:center;background:#FFF9E8;"
                + "font-family:sans-serif;color:#26170A;text-align:center;padding:24px'><div>"
                + "<p style='font-size:18px;font-weight:600'>Sin conexión</p>"
                + "<p style='color:#5C4730'>paellas.net necesita internet para cargar.</p>"
                + "<a href='paellas://retry' style='display:inline-block;margin-top:12px;padding:12px 22px;"
                + "border-radius:999px;background:#26170A;color:#FFD43B;text-decoration:none'>Reintentar</a>"
                + "</div></body>";
        view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private final class ShellClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            cancelPendingCamera();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (APP_SCHEME.equals(uri.getScheme())) {
                handleAppUri(uri);
                return true;
            }
            if (isAppUrl(uri)) return false;
            // Everything outside the app opens in a Custom Tab or its own app.
            openExternal(uri);
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            pageReady = true;
            deliverPendingAuth();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            if (!offlineRetry) {
                // Offline: try the copy the WebView already has before giving up.
                offlineRetry = true;
                view.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
                view.loadUrl(START_URL);
                return;
            }
            pageReady = true;
            showOffline(view);
        }
    }

    private final class ShellChrome extends WebChromeClient {
        @Override
        public void onPermissionRequest(PermissionRequest request) {
            runOnUiThread(() -> {
                if (!trustedCameraRequest(request) || cameraPromptOpen) {
                    request.deny();
                    return;
                }
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                } else {
                    pendingCamera = request;
                    cameraPromptOpen = true;
                    cameraPermission.launch(Manifest.permission.CAMERA);
                }
            });
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            runOnUiThread(() -> { if (pendingCamera == request) pendingCamera = null; });
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            Intent intent = params.createIntent();
            if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            try {
                filePicker.launch(intent);
            } catch (ActivityNotFoundException error) {
                fileCallback = null;
                return false;
            }
            return true;
        }
    }

    // --- Lifecycle -------------------------------------------------------------

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        // Optional update prompts: B by default, Later snoozes them for 14 days.
        if (updates == null) updates = new NativeUpdates(getApplicationContext());
        updates.onResumed(this);
    }

    @Override
    protected void onPause() {
        if (updates != null) updates.onPaused(this);
        webView.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelPendingCamera();
        if (updates != null) updates.onDestroyed(this);
        webView.destroy();
        super.onDestroy();
    }
}
