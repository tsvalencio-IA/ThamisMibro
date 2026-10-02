package br.com.thiaguinhosolucoes.thamismibro;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Atualizador próprio do atletIA Mibro.
 *
 * Fluxo:
 * 1) Consulta a release "latest" do GitHub.
 * 2) Compara versionCode.
 * 3) Faz download do APK + SHA-256.
 * 4) Valida SHA-256, applicationId e certificado de assinatura.
 * 5) Usa PackageInstaller para atualizar o próprio app.
 *
 * A instalação silenciosa só é tentada quando o Android permite. Caso o sistema
 * exija ação do usuário, UpdateInstallReceiver abre a confirmação oficial.
 */
public final class AutoUpdateManager {
    private static final String REPO_API =
            "https://api.github.com/repos/tsvalencio-IA/ThamisMibro/releases/latest";
    private static final String PREFS = "atletia_update";
    private static final String KEY_STATUS = "status";
    private static final String KEY_PENDING_URL = "pending_url";
    private static final String KEY_PENDING_SHA_URL = "pending_sha_url";
    private static final String KEY_PENDING_VERSION = "pending_version";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final long MIN_CHECK_INTERVAL_MS = 60_000L;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public interface Listener {
        void onUpdateStatusChanged(String status);
    }

    private AutoUpdateManager() { }

    public static void checkAndUpdate(Activity activity, Listener listener, boolean force) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = prefs.getLong(KEY_LAST_CHECK, 0L);

        if (!force && now - last < MIN_CHECK_INTERVAL_MS) {
            notifyListener(activity, listener, prefs.getString(KEY_STATUS, "Atualizado"));
            return;
        }

        prefs.edit().putLong(KEY_LAST_CHECK, now).apply();
        setStatus(activity, listener, "Verificando atualização…");

        EXECUTOR.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = open(REPO_API, "application/vnd.github+json");
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("GitHub HTTP " + code);
                }

                String json = readText(conn.getInputStream());
                JSONObject release = new JSONObject(json);
                JSONArray assets = release.optJSONArray("assets");
                if (assets == null) throw new IllegalStateException("Release sem assets");

                String apkUrl = null;
                String shaUrl = null;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String name = a.optString("name", "");
                    String url = a.optString("browser_download_url", "");
                    if (name.endsWith(".apk") && name.contains("atletIA-Mibro-Thamis")) apkUrl = url;
                    if (name.endsWith(".sha256") && name.contains("atletIA-Mibro-Thamis")) shaUrl = url;
                }
                if (apkUrl == null) throw new IllegalStateException("APK não encontrado na release");

                long remoteVersion = parseRemoteVersionCode(release);
                long localVersion = localVersionCode(activity);

                if (remoteVersion <= localVersion) {
                    clearPending(activity);
                    setStatus(activity, listener, "Atualizado • v" + localVersionName(activity));
                    return;
                }

                SharedPreferences.Editor e = prefs.edit()
                        .putString(KEY_PENDING_URL, apkUrl)
                        .putLong(KEY_PENDING_VERSION, remoteVersion);
                if (shaUrl != null) e.putString(KEY_PENDING_SHA_URL, shaUrl);
                else e.remove(KEY_PENDING_SHA_URL);
                e.apply();

                setStatus(activity, listener,
                        "Nova versão " + remoteVersion + " encontrada • preparando…");

                activity.runOnUiThread(() -> continuePendingUpdate(activity, listener));
            } catch (Exception ex) {
                setStatus(activity, listener, "Atualização: sem conexão • usando versão instalada");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    public static void continuePendingUpdate(Activity activity, Listener listener) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        String url = prefs.getString(KEY_PENDING_URL, null);
        if (url == null || url.isEmpty()) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            setStatus(activity, listener, "Autorize “Instalar apps desconhecidos” uma única vez");
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(i);
            } catch (Exception e) {
                activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            }
            return;
        }

        downloadVerifyAndInstall(activity, listener);
    }

    public static String getStatus(Activity activity) {
        return activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .getString(KEY_STATUS, "Verificando…");
    }

    private static void downloadVerifyAndInstall(Activity activity, Listener listener) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        String apkUrl = prefs.getString(KEY_PENDING_URL, null);
        String shaUrl = prefs.getString(KEY_PENDING_SHA_URL, null);
        if (apkUrl == null) return;

        setStatus(activity, listener, "Baixando atualização…");

        EXECUTOR.execute(() -> {
            File apk = new File(activity.getCacheDir(), "atletia-mibro-update.apk");
            try {
                downloadFile(apkUrl, apk, activity, listener);

                if (shaUrl != null && !shaUrl.isEmpty()) {
                    String shaLine = downloadText(shaUrl).trim();
                    String expected = shaLine.split("\\s+")[0].trim().toLowerCase(Locale.ROOT);
                    String actual = sha256(apk);
                    if (!expected.equals(actual)) {
                        throw new SecurityException("SHA-256 do APK não confere");
                    }
                }

                verifyPackageAndSignature(activity, apk);

                setStatus(activity, listener, "Atualização validada • instalando…");
                installPackage(activity, apk);
            } catch (SecurityException sec) {
                setStatus(activity, listener,
                        "ATUALIZAÇÃO BLOQUEADA • assinatura/arquivo não conferem");
                apk.delete();
            } catch (Exception ex) {
                setStatus(activity, listener,
                        "Falha ao atualizar • versão atual continua funcionando");
                apk.delete();
            }
        });
    }

    private static void downloadFile(
            String url, File out, Activity activity, Listener listener) throws Exception {
        HttpURLConnection conn = open(url, "application/vnd.android.package-archive");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);

            long total = conn.getContentLengthLong();
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[32 * 1024];
                long read = 0;
                int n;
                int lastPct = -1;
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    read += n;
                    if (total > 0) {
                        int pct = (int) Math.min(100, (read * 100L) / total);
                        if (pct >= lastPct + 10 || pct == 100) {
                            lastPct = pct;
                            setStatus(activity, listener, "Baixando atualização… " + pct + "%");
                        }
                    }
                }
                fos.flush();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String downloadText(String url) throws Exception {
        HttpURLConnection conn = open(url, "text/plain");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            return readText(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String accept) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(12_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("Accept", accept);
        conn.setRequestProperty("User-Agent", "atletIA-Mibro-Thamis-Android");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        return conn;
    }

    private static String readText(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static long parseRemoteVersionCode(JSONObject release) {
        String body = release.optString("body", "");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("VERSION_CODE\\s*=\\s*(\\d+)")
                .matcher(body);
        if (m.find()) return Long.parseLong(m.group(1));

        String tag = release.optString("tag_name", "");
        java.util.regex.Matcher build = java.util.regex.Pattern
                .compile("build-(\\d+)")
                .matcher(tag);
        if (build.find()) return 200_000L + Long.parseLong(build.group(1));

        return 0L;
    }

    private static long localVersionCode(Activity activity) throws Exception {
        PackageInfo p = activity.getPackageManager()
                .getPackageInfo(activity.getPackageName(), 0);
        if (Build.VERSION.SDK_INT >= 28) return p.getLongVersionCode();
        return p.versionCode;
    }

    private static String localVersionName(Activity activity) {
        try {
            PackageInfo p = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            return p.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        return toHex(md.digest());
    }

    private static void verifyPackageAndSignature(Activity activity, File apk) throws Exception {
        PackageManager pm = activity.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;

        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        PackageInfo current = pm.getPackageInfo(activity.getPackageName(), flags);

        if (archive == null || archive.packageName == null
                || !activity.getPackageName().equals(archive.packageName)) {
            throw new SecurityException("applicationId inesperado");
        }

        Signature[] incoming = signaturesOf(archive);
        Signature[] installed = signaturesOf(current);
        if (incoming.length == 0 || installed.length == 0) {
            throw new SecurityException("certificado ausente");
        }

        String inDigest = digestSignature(incoming[0]);
        String curDigest = digestSignature(installed[0]);
        if (!inDigest.equals(curDigest)) {
            throw new SecurityException("certificado de assinatura diferente");
        }
    }

    private static Signature[] signaturesOf(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            if (info.signingInfo.hasMultipleSigners()) {
                return info.signingInfo.getApkContentsSigners();
            }
            return info.signingInfo.getSigningCertificateHistory();
        }
        return info.signatures == null ? new Signature[0] : info.signatures;
    }

    private static String digestSignature(Signature signature) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return toHex(md.digest(signature.toByteArray()));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    private static void installPackage(Activity activity, File apk) throws Exception {
        PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(activity.getPackageName());

        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }

        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);

        try (OutputStream out = session.openWrite("base.apk", 0, apk.length());
             InputStream in = new FileInputStream(apk)) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            session.fsync(out);
        }

        Intent statusIntent = new Intent(activity, UpdateInstallReceiver.class);
        statusIntent.setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS);
        PendingIntent pi = PendingIntent.getBroadcast(
                activity,
                sessionId,
                statusIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
        );
        IntentSender sender = pi.getIntentSender();
        session.commit(sender);
        session.close();
    }

    public static void onInstallSuccess(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
        prefs.edit()
                .remove(KEY_PENDING_URL)
                .remove(KEY_PENDING_SHA_URL)
                .remove(KEY_PENDING_VERSION)
                .putString(KEY_STATUS, "Atualização instalada")
                .apply();
    }

    private static void clearPending(Activity activity) {
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .edit()
                .remove(KEY_PENDING_URL)
                .remove(KEY_PENDING_SHA_URL)
                .remove(KEY_PENDING_VERSION)
                .apply();
    }

    private static void setStatus(Activity activity, Listener listener, String status) {
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .edit().putString(KEY_STATUS, status).apply();
        notifyListener(activity, listener, status);
    }

    private static void notifyListener(Activity activity, Listener listener, String status) {
        if (listener == null) return;
        activity.runOnUiThread(() -> listener.onUpdateStatusChanged(status));
    }
}
