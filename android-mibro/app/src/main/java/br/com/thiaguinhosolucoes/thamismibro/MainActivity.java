package br.com.thiaguinhosolucoes.thamismibro;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String LOCAL_URL = "file:///android_asset/pacer.html?native=1&mibro=1";
    private static final String MIBRO_FIT_PACKAGE = "com.xiaoxun.xunoversea.mibrofit";
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
        settings.setUserAgentString(settings.getUserAgentString() + " atletIA-Mibro-Thamis/1.1.0");

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

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.postDelayed(() -> webView.evaluateJavascript(
                    "window.refreshMibroBridgeStatus && window.refreshMibroBridgeStatus();", null), 350L);
        }
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
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isMibroFitInstalled() {
        try {
            getPackageManager().getPackageInfo(MIBRO_FIT_PACKAGE, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean secureSettingContains(String key, String packageName) {
        try {
            String value = Settings.Secure.getString(getContentResolver(), key);
            return value != null && value.toLowerCase().contains(packageName.toLowerCase());
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean mibroNotificationListenerEnabled() {
        return secureSettingContains("enabled_notification_listeners", MIBRO_FIT_PACKAGE);
    }

    private boolean mibroAccessibilityEnabled() {
        return secureSettingContains(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, MIBRO_FIT_PACKAGE);
    }

    private String bridgeStatusJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("appVersion", "1.1.0");
            o.put("mibroFitInstalled", isMibroFitInstalled());
            o.put("notificationPermission", hasNotificationPermission());
            o.put("locationPermission", hasFineLocationPermission());
            o.put("notificationListener", mibroNotificationListenerEnabled());
            o.put("accessibility", mibroAccessibilityEnabled());
            o.put("bridgeAccess", mibroNotificationListenerEnabled() || mibroAccessibilityEnabled());
            return o.toString();
        } catch (Exception e) {
            return "{\"appVersion\":\"1.1.0\"}";
        }
    }

    private void requestRuntimePermissions(boolean startAfterGrant) {
        java.util.ArrayList<String> permissions = new java.util.ArrayList<>();
        pendingStart = pendingStart || startAfterGrant;

        if (!hasFineLocationPermission()) permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!permissions.isEmpty()) requestPermissions(permissions.toArray(new String[0]), PERMISSION_REQUEST);
        else if (pendingStart) {
            pendingStart = false;
            startMibroService();
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
    }

    private void startMibroService() {
        if (!pendingConfig.isEmpty()) sendToService(MibroPacerService.ACTION_CONFIGURE, pendingConfig);
        sendToService(MibroPacerService.ACTION_START, null);
    }

    private void openMibroFit() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(MIBRO_FIT_PACKAGE);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                return;
            }
        } catch (Exception ignored) { }

        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + MIBRO_FIT_PACKAGE)));
        } catch (Exception ignored) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + MIBRO_FIT_PACKAGE)));
        }
    }

    private void openThisAppNotificationSettings() {
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        startActivity(i);
    }

    private void openNotificationListenerSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception ignored) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception ignored) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void openMibroAppDetails() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + MIBRO_FIT_PACKAGE));
            startActivity(i);
        } catch (Exception ignored) {
            openMibroFit();
        }
    }

    private void fixMibroBridge() {
        if (!isMibroFitInstalled()) {
            openMibroFit();
            return;
        }
        if (!hasNotificationPermission()) {
            openThisAppNotificationSettings();
            return;
        }

        boolean listener = mibroNotificationListenerEnabled();
        boolean accessibility = mibroAccessibilityEnabled();

        if (!listener && !accessibility) {
            int attempts = getSharedPreferences("mibro_bridge", MODE_PRIVATE)
                    .getInt("fix_attempts", 0);
            getSharedPreferences("mibro_bridge", MODE_PRIVATE)
                    .edit().putInt("fix_attempts", attempts + 1).apply();

            // Primeiro caminho: orientação oficial da Mibro para falha de alertas.
            // Se o aparelho não expuser o Mibro Fit em Acessibilidade, o próximo
            // toque leva automaticamente ao Acesso a notificações do Android.
            if (attempts % 2 == 0) openAccessibilitySettings();
            else openNotificationListenerSettings();
            return;
        }

        getSharedPreferences("mibro_bridge", MODE_PRIVATE)
                .edit().putInt("fix_attempts", 0).apply();
        openMibroFit();
    }

    public final class NativeBridge {
        @JavascriptInterface public void configure(String configJson) {
            runOnUiThread(() -> pendingConfig = configJson == null ? "" : configJson);
        }

        @JavascriptInterface public void start() {
            runOnUiThread(() -> {
                if (!hasFineLocationPermission() || !hasNotificationPermission()) requestRuntimePermissions(true);
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
            runOnUiThread(() -> {
                if (!hasNotificationPermission()) requestRuntimePermissions(false);
                sendToService(MibroPacerService.ACTION_TEST_ALERT, null);
            });
        }

        @JavascriptInterface public String mode() { return "mibro-gs-pro"; }
        @JavascriptInterface public String version() { return "1.1.0"; }

        @JavascriptInterface public String notificationPermission() {
            return hasNotificationPermission() ? "granted" : "denied";
        }

        @JavascriptInterface public String mibroBridgeStatus() {
            return bridgeStatusJson();
        }

        @JavascriptInterface public void fixMibroBridge() {
            runOnUiThread(MainActivity.this::fixMibroBridge);
        }

        @JavascriptInterface public void openMibroFit() {
            runOnUiThread(MainActivity.this::openMibroFit);
        }

        @JavascriptInterface public void openMibroNotificationAccess() {
            runOnUiThread(MainActivity.this::openNotificationListenerSettings);
        }

        @JavascriptInterface public void openMibroAccessibility() {
            runOnUiThread(MainActivity.this::openAccessibilitySettings);
        }

        @JavascriptInterface public void openMibroAppDetails() {
            runOnUiThread(MainActivity.this::openMibroAppDetails);
        }

        @JavascriptInterface public void openThisAppNotificationSettings() {
            runOnUiThread(MainActivity.this::openThisAppNotificationSettings);
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
