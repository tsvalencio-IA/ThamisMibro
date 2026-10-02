package br.com.thiaguinhosolucoes.thamismibro;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MibroPacerService extends Service implements LocationListener {
    public static final String ACTION_CONFIGURE = "br.com.thiaguinhosolucoes.thamismibro.CONFIGURE";
    public static final String ACTION_START = "br.com.thiaguinhosolucoes.thamismibro.START";
    public static final String ACTION_PAUSE = "br.com.thiaguinhosolucoes.thamismibro.PAUSE";
    public static final String ACTION_RESUME = "br.com.thiaguinhosolucoes.thamismibro.RESUME";
    public static final String ACTION_NEXT = "br.com.thiaguinhosolucoes.thamismibro.NEXT";
    public static final String ACTION_STOP = "br.com.thiaguinhosolucoes.thamismibro.STOP";
    public static final String ACTION_TEST_ALERT = "br.com.thiaguinhosolucoes.thamismibro.TEST_ALERT";
    public static final String ACTION_TELEMETRY = "br.com.thiaguinhosolucoes.thamismibro.TELEMETRY";
    public static final String EXTRA_CONFIG = "config";
    public static final String EXTRA_TELEMETRY = "telemetry";

    private static final String CHANNEL_SERVICE = "atletia_mibro_service";
    private static final String CHANNEL_ALERT = "atletia_mibro_alert";
    private static final int FOREGROUND_ID = 4100;
    private static final int GPS_MAX_ACCURACY_M = 35;
    private static final long SPEED_WINDOW_MS = 20000L;
    private static final long HOLD_MS = 12000L;
    private static final long OUT_COOLDOWN_MS = 22000L;
    private static final long GOOD_COOLDOWN_MS = 60000L;
    private static final double HYSTERESIS_SEC = 3.0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<SpeedSample> speedSamples = new ArrayDeque<>();
    private final List<Block> blocks = new ArrayList<>();

    private NotificationManager notificationManager;
    private LocationManager locationManager;
    private TextToSpeech tts;
    private PowerManager.WakeLock wakeLock;

    private String workoutTitle = "Treino atletIA";
    private String athleteName = "Thamis";
    private boolean configured = false;
    private boolean running = false;
    private boolean paused = false;
    private boolean completed = false;
    private int blockIndex = 0;
    private long blockStartedAt = 0L;
    private long pauseStartedAt = 0L;
    private double distanceM = 0.0;
    private double blockStartDistanceM = 0.0;
    private Location lastLocation = null;
    private double paceSecPerKm = Double.NaN;
    private float accuracyM = Float.NaN;
    private String guideState = "UNKNOWN";
    private long guideSince = 0L;
    private long lastGuideAlertAt = 0L;
    private long alertCounter = 5000L;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            try {
                tick();
            } finally {
                handler.postDelayed(this, 500L);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        createChannels();
        startForeground(FOREGROUND_ID, buildServiceNotification("Pronto para o treino"));
        initTts();
        handler.post(ticker);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_STICKY;
        String action = intent.getAction();

        if (ACTION_CONFIGURE.equals(action)) {
            String json = intent.getStringExtra(EXTRA_CONFIG);
            if (json != null) configure(json);
        } else if (ACTION_START.equals(action)) {
            startWorkout();
        } else if (ACTION_PAUSE.equals(action)) {
            pauseWorkout();
        } else if (ACTION_RESUME.equals(action)) {
            resumeWorkout();
        } else if (ACTION_NEXT.equals(action)) {
            nextBlock(true);
        } else if (ACTION_STOP.equals(action)) {
            finishWorkout(false);
        } else if (ACTION_TEST_ALERT.equals(action)) {
            sendAlert("atletIA Mibro • TESTE", "Se este aviso apareceu no GS Pro, a ponte Mibro está funcionando.", true);
            speak("Teste do Mibro enviado.");
        }
        return START_STICKY;
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel service = new NotificationChannel(
                CHANNEL_SERVICE, "atletIA durante a corrida", NotificationManager.IMPORTANCE_LOW);
        service.setDescription("Mantém o pacer funcionando com a tela apagada.");
        service.setSound(null, null);

        NotificationChannel alert = new NotificationChannel(
                CHANNEL_ALERT, "Alertas do pace para o Mibro", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("ACELERE, REDUZA, MANTENHA e mudanças de bloco.");
        alert.enableVibration(true);
        alert.setVibrationPattern(new long[]{0, 220, 100, 220});
        notificationManager.createNotificationChannel(service);
        notificationManager.createNotificationChannel(alert);
    }

    private void initTts() {
        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(new Locale("pt", "BR"));
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault());
                }
                tts.setSpeechRate(1.0f);
            }
        });
    }

    private Notification buildServiceNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_SERVICE)
                : new Notification.Builder(this);

        b.setSmallIcon(R.drawable.ic_stat_atletia)
                .setContentTitle("atletIA Mibro • Thamis")
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE);

        if (running) {
            String action = paused ? ACTION_RESUME : ACTION_PAUSE;
            String label = paused ? "Continuar" : "Pausar";
            b.addAction(new Notification.Action.Builder(
                    0, label, servicePendingIntent(action, 10)).build());
            b.addAction(new Notification.Action.Builder(
                    0, "Encerrar", servicePendingIntent(ACTION_STOP, 11)).build());
        }
        return b.build();
    }

    private PendingIntent servicePendingIntent(String action, int requestCode) {
        Intent i = new Intent(this, MibroPacerService.class);
        i.setAction(action);
        return PendingIntent.getService(this, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void updateForeground(String text) {
        notificationManager.notify(FOREGROUND_ID, buildServiceNotification(text));
    }

    private void sendAlert(String title, String text, boolean vibrate) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ALERT)
                : new Notification.Builder(this);

        b.setSmallIcon(R.drawable.ic_stat_atletia)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_WORKOUT)
                .setVisibility(Notification.VISIBILITY_PUBLIC);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_HIGH);
            if (vibrate) b.setVibrate(new long[]{0, 220, 100, 220});
        }
        notificationManager.notify((int)(++alertCounter), b.build());
    }

    private void configure(String json) {
        try {
            JSONObject root = new JSONObject(json);
            workoutTitle = root.optString("title", "Treino atletIA");
            athleteName = root.optString("athleteName", "Thamis");
            JSONArray arr = root.optJSONArray("blocks");
            blocks.clear();

            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Block b = new Block();
                    b.label = o.optString("label", "Bloco " + (i + 1));
                    b.durationSec = positiveOrZero(o.optDouble("durationSec", 0));
                    b.distanceM = positiveOrZero(o.optDouble("distanceM", 0));
                    b.paceMinSec = positiveOrZero(o.optDouble("targetPaceMinSec", 0));
                    b.paceMaxSec = positiveOrZero(o.optDouble("targetPaceMaxSec", 0));
                    if (b.durationSec > 0 || b.distanceM > 0) blocks.add(b);
                }
            }
            configured = !blocks.isEmpty();
            if (!running) updateForeground(configured ? "Treino preparado: " + workoutTitle : "Treino inválido");
            sendTelemetry("READY", false);
        } catch (JSONException e) {
            configured = false;
            sendAlert("atletIA • ERRO", "Não foi possível interpretar o treino recebido.", true);
        }
    }

    private double positiveOrZero(double v) {
        return Double.isFinite(v) && v > 0 ? v : 0;
    }

    private void startWorkout() {
        if (!configured || blocks.isEmpty()) {
            sendAlert("atletIA • TREINO NÃO PREPARADO", "Abra o treino no aplicativo e toque em PREPARAR TREINO antes de iniciar.", true);
            return;
        }
        if (!hasLocationPermission()) {
            sendAlert("atletIA • GPS BLOQUEADO", "Autorize a localização precisa para iniciar o pacer.", true);
            return;
        }

        running = true;
        paused = false;
        completed = false;
        blockIndex = 0;
        distanceM = 0;
        blockStartDistanceM = 0;
        lastLocation = null;
        speedSamples.clear();
        paceSecPerKm = Double.NaN;
        accuracyM = Float.NaN;
        blockStartedAt = elapsedNow();
        resetGuide();

        acquireWakeLock();
        startLocation();
        enterBlock(0, true);
        updateForeground("Correndo • " + blocks.get(0).label);
        sendAlert("atletIA • INICIADO", workoutTitle + " • " + blocks.get(0).label, true);
        sendTelemetry("RUNNING", false);
    }

    private void pauseWorkout() {
        if (!running || paused) return;
        paused = true;
        pauseStartedAt = elapsedNow();
        stopLocation();
        releaseWakeLock();
        updateForeground("Pausado • " + currentBlockLabel());
        sendAlert("atletIA • PAUSADO", "Treino pausado.", true);
        speak("Treino pausado.");
        sendTelemetry("PAUSED", false);
    }

    private void resumeWorkout() {
        if (!running || !paused) return;
        long now = elapsedNow();
        if (pauseStartedAt > 0) blockStartedAt += now - pauseStartedAt;
        pauseStartedAt = 0;
        paused = false;
        resetGuide();
        acquireWakeLock();
        startLocation();
        updateForeground("Correndo • " + currentBlockLabel());
        sendAlert("atletIA • RETOMADO", "Treino retomado.", true);
        speak("Treino retomado.");
        sendTelemetry("RUNNING", false);
    }

    private void nextBlock(boolean manual) {
        if (!running) return;
        int next = blockIndex + 1;
        if (next >= blocks.size()) {
            finishWorkout(true);
            return;
        }
        enterBlock(next, true);
        if (manual) sendAlert("atletIA • PRÓXIMO BLOCO", blocks.get(next).label, true);
    }

    private void enterBlock(int index, boolean announce) {
        if (index < 0 || index >= blocks.size()) return;
        blockIndex = index;
        blockStartedAt = elapsedNow();
        blockStartDistanceM = distanceM;
        resetGuide();
        Block b = blocks.get(index);

        updateForeground("Correndo • " + b.label);
        if (announce) {
            String target = paceTargetText(b);
            sendAlert("atletIA • " + b.label, target, true);
            speak(b.label + ". " + targetSpeech(b));
        }
        sendTelemetry(paused ? "PAUSED" : "RUNNING", false);
    }

    private void finishWorkout(boolean completedNormally) {
        if (!running && !paused) {
            stopSelf();
            return;
        }
        running = false;
        paused = false;
        completed = completedNormally;
        stopLocation();
        releaseWakeLock();
        speedSamples.clear();

        if (completedNormally) {
            sendAlert("atletIA • TREINO CONCLUÍDO", workoutTitle + " concluído.", true);
            speak("Treino concluído.");
        } else {
            sendAlert("atletIA • TREINO ENCERRADO", "Treino encerrado.", true);
            speak("Treino encerrado.");
        }
        sendTelemetry("ENDED", completedNormally);
        stopForeground(true);
        stopSelf();
    }

    private void tick() {
        if (!running || paused || blocks.isEmpty()) return;
        Block b = blocks.get(blockIndex);
        double remainingSec = Double.NaN;
        double remainingM = Double.NaN;

        if (b.durationSec > 0) {
            remainingSec = Math.max(0, b.durationSec - ((elapsedNow() - blockStartedAt) / 1000.0));
            if (remainingSec <= 0) {
                nextBlock(false);
                return;
            }
        } else if (b.distanceM > 0) {
            remainingM = Math.max(0, b.distanceM - (distanceM - blockStartDistanceM));
            if (remainingM <= 0) {
                nextBlock(false);
                return;
            }
        }

        evaluatePace(b);
        sendTelemetry(paused ? "PAUSED" : "RUNNING", false, remainingSec, remainingM);
    }

    private void evaluatePace(Block b) {
        if (!(b.paceMinSec > 0 && b.paceMaxSec > 0)) {
            maybeSetGuide("LIVRE", "RITMO LIVRE", "", Long.MAX_VALUE);
            return;
        }
        if (!Double.isFinite(paceSecPerKm) || !Float.isFinite(accuracyM) || accuracyM > GPS_MAX_ACCURACY_M) return;

        String candidate = "GOOD";
        if (paceSecPerKm < b.paceMinSec - HYSTERESIS_SEC) candidate = "FAST";
        else if (paceSecPerKm > b.paceMaxSec + HYSTERESIS_SEC) candidate = "SLOW";

        long now = elapsedNow();
        if (!candidate.equals(guideState)) {
            guideState = candidate;
            guideSince = now;
            return;
        }
        if (now - guideSince < HOLD_MS) return;

        long cooldown = "GOOD".equals(candidate) ? GOOD_COOLDOWN_MS : OUT_COOLDOWN_MS;
        if (now - lastGuideAlertAt < cooldown) return;
        lastGuideAlertAt = now;

        if ("FAST".equals(candidate)) {
            sendAlert("atletIA • REDUZA", "Pace " + formatPace(paceSecPerKm) + " • alvo " + paceTargetText(b), true);
            speak("Rápido demais. Reduza um pouco.");
        } else if ("SLOW".equals(candidate)) {
            sendAlert("atletIA • ACELERE", "Pace " + formatPace(paceSecPerKm) + " • alvo " + paceTargetText(b), true);
            speak("Acelere um pouco.");
        } else {
            sendAlert("atletIA • MANTENHA", "Pace " + formatPace(paceSecPerKm) + " • dentro do alvo.", false);
            speak("Ritmo certo. Mantenha.");
        }
    }

    private void maybeSetGuide(String state, String title, String body, long cooldown) {
        long now = elapsedNow();
        if (!state.equals(guideState)) {
            guideState = state;
            guideSince = now;
        }
        if (cooldown != Long.MAX_VALUE && now - lastGuideAlertAt >= cooldown) {
            lastGuideAlertAt = now;
            sendAlert("atletIA • " + title, body, false);
        }
    }

    private void resetGuide() {
        guideState = "UNKNOWN";
        guideSince = elapsedNow();
        lastGuideAlertAt = 0L;
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocation() {
        if (!hasLocationPermission()) return;
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
            } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
            }
        } catch (SecurityException ignored) { }
    }

    private void stopLocation() {
        try {
            if (locationManager != null) locationManager.removeUpdates(this);
        } catch (SecurityException ignored) { }
    }

    @Override public void onLocationChanged(Location location) {
        if (!running || paused || location == null) return;
        float acc = location.hasAccuracy() ? location.getAccuracy() : Float.NaN;
        if (!Float.isFinite(acc) || acc > GPS_MAX_ACCURACY_M) {
            accuracyM = acc;
            sendTelemetry("RUNNING", false);
            return;
        }

        long now = location.getElapsedRealtimeNanos() > 0
                ? location.getElapsedRealtimeNanos() / 1_000_000L
                : elapsedNow();
        accuracyM = acc;

        double derivedSpeed = Double.NaN;
        if (lastLocation != null) {
            float d = lastLocation.distanceTo(location);
            long previous = lastLocation.getElapsedRealtimeNanos() > 0
                    ? lastLocation.getElapsedRealtimeNanos() / 1_000_000L
                    : now - 1000L;
            double dt = Math.max(0.001, (now - previous) / 1000.0);
            if (d >= 0.8f && d <= Math.max(45.0, dt * 12.0)) {
                distanceM += d;
                derivedSpeed = d / dt;
            }
        }
        lastLocation = new Location(location);

        double speed = location.hasSpeed() ? location.getSpeed() : derivedSpeed;
        if (Double.isFinite(speed) && speed >= 0.6 && speed <= 8.5) {
            addSpeed(speed, now);
        }
        sendTelemetry("RUNNING", false);
    }

    private void addSpeed(double speed, long now) {
        speedSamples.addLast(new SpeedSample(speed, now));
        while (!speedSamples.isEmpty() && speedSamples.peekFirst().time < now - SPEED_WINDOW_MS) {
            speedSamples.removeFirst();
        }
        if (speedSamples.size() < 2) return;

        double weighted = 0;
        double weights = 0;
        int i = 1;
        for (SpeedSample s : speedSamples) {
            weighted += s.speed * i;
            weights += i;
            i++;
        }
        double avg = weighted / weights;
        if (avg > 0) paceSecPerKm = 1000.0 / avg;
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ThamisMibro:AtletIAPacer");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(4 * 60 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private void speak(String text) {
        if (tts == null || text == null || text.isEmpty()) return;
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "atletia-" + System.currentTimeMillis());
    }

    private String currentBlockLabel() {
        if (blockIndex >= 0 && blockIndex < blocks.size()) return blocks.get(blockIndex).label;
        return workoutTitle;
    }

    private String paceTargetText(Block b) {
        if (b.paceMinSec > 0 && b.paceMaxSec > 0) {
            return formatPace(b.paceMinSec) + " – " + formatPace(b.paceMaxSec);
        }
        return "Ritmo livre";
    }

    private String targetSpeech(Block b) {
        if (!(b.paceMinSec > 0 && b.paceMaxSec > 0)) return "Ritmo livre.";
        return "Alvo de " + speechPace(b.paceMinSec) + " a " + speechPace(b.paceMaxSec) + " por quilômetro.";
    }

    private String speechPace(double seconds) {
        int s = (int)Math.round(seconds);
        int min = s / 60;
        int sec = s % 60;
        return min + " minutos e " + sec + " segundos";
    }

    private String formatPace(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0) return "—";
        int s = (int)Math.round(seconds);
        return (s / 60) + ":" + String.format(Locale.US, "%02d", s % 60) + "/km";
    }

    private long elapsedNow() {
        return android.os.SystemClock.elapsedRealtime();
    }

    private void sendTelemetry(String state, boolean endedCompleted) {
        sendTelemetry(state, endedCompleted, Double.NaN, Double.NaN);
    }

    private void sendTelemetry(String state, boolean endedCompleted, double remainingSec, double remainingM) {
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("completed", endedCompleted);
            o.put("blockIndex", blockIndex);
            o.put("distanceM", distanceM);
            if (Double.isFinite(paceSecPerKm)) o.put("paceSecPerKm", paceSecPerKm);
            if (Float.isFinite(accuracyM)) o.put("accuracyM", accuracyM);
            if (Double.isFinite(remainingSec)) o.put("remainingSec", remainingSec);
            if (Double.isFinite(remainingM)) o.put("remainingM", remainingM);
            if ("FAST".equals(guideState)) o.put("guide", "REDUZA");
            else if ("SLOW".equals(guideState)) o.put("guide", "ACELERE");
            else if ("GOOD".equals(guideState)) o.put("guide", "MANTENHA");
            else if ("LIVRE".equals(guideState)) o.put("guide", "RITMO LIVRE");

            Intent i = new Intent(ACTION_TELEMETRY);
            i.setPackage(getPackageName());
            i.putExtra(EXTRA_TELEMETRY, o.toString());
            sendBroadcast(i);
        } catch (JSONException ignored) { }
    }

    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
    @SuppressWarnings("deprecation")
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        handler.removeCallbacks(ticker);
        stopLocation();
        releaseWakeLock();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }

    private static final class Block {
        String label;
        double durationSec;
        double distanceM;
        double paceMinSec;
        double paceMaxSec;
    }

    private static final class SpeedSample {
        final double speed;
        final long time;
        SpeedSample(double speed, long time) {
            this.speed = speed;
            this.time = time;
        }
    }
}
