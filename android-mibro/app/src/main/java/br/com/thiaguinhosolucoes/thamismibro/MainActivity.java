package br.com.thiaguinhosolucoes.thamismibro;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String LOCAL_URL = "file:///android_asset/pacer.html?native=1&mibro=1";
    private static final int PERMISSION_REQUEST = 150;
    private WebView webView;
    private String pendingConfig = "";
    private boolean pendingStart = false;

    private final BroadcastReceiver telemetryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String payload = intent.getStringExtra(MibroPacerService.EXTRA_TELEMETRY);
            if (payload == null || payload.isEmpty() || webView == null) return;
            String quoted = JSONObject.quote(payload);
            webView.post(() -> webView.evaluateJavascript(
                    "window.AtletIAMibroNativeTelemetry && window.AtletIAMibroNativeTelemetry(JSON.parse(" + quoted + "));", null));
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestRuntimePermissions(false);

        webView = new WebView(this);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " atletIA-Mibro-Thamis/1.0.0");

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, hasFineLocationPermission(), false);
            }
        });
        webView.addJavascriptInterface(new NativeBridge(), "AtletIANative");
        webView.loadUrl(LOCAL_URL);
        registerTelemetryReceiver();
    }

    private void registerTelemetryReceiver() {
        IntentFilter filter = new IntentFilter(MibroPacerService.ACTION_TELEMETRY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(telemetryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(telemetryReceiver, filter);
    }

    private boolean hasFineLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRuntimePermissions(boolean startAfterGrant) {
        java.util.ArrayList<String> permissions = new java.util.ArrayList<>();
        pendingStart = pendingStart || startAfterGrant;
        if (!hasFineLocationPermission()) permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!permissions.isEmpty()) requestPermissions(permissions.toArray(new String[0]), PERMISSION_REQUEST);
        else if (pendingStart) { pendingStart = false; startMibroService(); }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST && pendingStart && hasFineLocationPermission()) {
            pendingStart = false;
            startMibroService();
        }
    }

    private void sendToService(String action, String configJson) {
        Intent intent = new Intent(this, MibroPacerService.class);
        intent.setAction(action);
        if (configJson != null) intent.putExtra(MibroPacerService.EXTRA_CONFIG, configJson);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent); else startService(intent);
    }

    private void startMibroService() {
        if (!pendingConfig.isEmpty()) sendToService(MibroPacerService.ACTION_CONFIGURE, pendingConfig);
        sendToService(MibroPacerService.ACTION_START, null);
    }

    public final class NativeBridge {
        @JavascriptInterface public void configure(String configJson) {
            runOnUiThread(() -> pendingConfig = configJson == null ? "" : configJson);
        }
        @JavascriptInterface public void start() {
            runOnUiThread(() -> {
                if (!hasFineLocationPermission()) requestRuntimePermissions(true);
                else startMibroService();
            });
        }
        @JavascriptInterface public void pause() {
            runOnUiThread(() -> sendToService(MibroPacerService.ACTION_PAUSE, null));
        }
        @JavascriptInterface public void resume() {
            runOnUiThread(() -> sendToService(MibroPacerService.ACTION_RESUME, null));
        }
        @JavascriptInterface public void next() {
            runOnUiThread(() -> sendToService(MibroPacerService.ACTION_NEXT, null));
        }
        @JavascriptInterface public void stop() {
            runOnUiThread(() -> sendToService(MibroPacerService.ACTION_STOP, null));
        }
        @JavascriptInterface public void testAlert() {
            runOnUiThread(() -> sendToService(MibroPacerService.ACTION_TEST_ALERT, null));
        }
        @JavascriptInterface public String mode() { return "mibro-gs-pro"; }
        @JavascriptInterface public String version() { return "1.0.0"; }
        @JavascriptInterface public String notificationPermission() {
            return hasNotificationPermission() ? "granted" : "denied";
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(telemetryReceiver); } catch (Exception ignored) { }
        if (webView != null) {
            webView.removeJavascriptInterface("AtletIANative");
            webView.destroy();
        }
        super.onDestroy();
    }
}
