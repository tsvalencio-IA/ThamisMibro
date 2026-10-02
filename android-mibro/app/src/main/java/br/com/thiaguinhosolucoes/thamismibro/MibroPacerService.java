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
    public static final String ACTION_QUERY = "br.com.thiaguinhosolucoes.thamismibro.QUERY";
    public static final String ACTION_TEST_ALERT = "br.com.thiaguinhosolucoes.thamismibro.TEST_ALERT";
    public static final String ACTION_TEST_SUITE = "br.com.thiaguinhosolucoes.thamismibro.TEST_SUITE";
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
    private long workoutStartedAt = 0L;
    private long totalPausedMs = 0L;
    private long blockStartedAt = 0L;
    private long pauseStartedAt = 0L;
    private long finishedElapsedSec = 0L;

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
        } else if (ACTION_QUERY.equals(action)) {
            sendCurrentTelemetry();
        } else if (ACTION_TEST_ALERT.equals(action)) {
            sendAlert("atletIA Mibro", "TESTE • comunicação confirmada com o GS Pro", true);
            speak("Teste do Mibro enviado.");
        } else if (ACTION_TEST_SUITE.equals(action)) {
            runTestSuite();
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
        alert.setDescription("Início, blocos, ACELERE, MANTENHA, REDUZA, pausa, retomada e finalização.");
        alert.enableVibration(true);
        alert.setVibrationPattern(new long[]{0, 220, 100, 220});

        notificationManager.createNotificationChannel(service);
        notificationManager.createNotificationChannel(alert);
    }

    private void initTts() {
        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(new Locale("pt", "BR"));
                if (result == TextToSpeech.LANG_MISSING_DATA
                        || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault());
                }
                tts.setSpeechRate(1.0f);
            }
        });
    }

    private Notification buildServiceNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_SERVICE)
                : new Notification.Builder(this);

        b.setSmallIcon(R.drawable.ic_stat_atletia)
                .setContentTitle("atletIA Mibro • Thamis")
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(running || paused)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE);

        if (running) {
            String action = paused ? ACTION_RESUME : ACTION_PAUSE;
            String label = paused ? "Continuar" : "Pausar";
            b.addAction(new Notification.Action.Builder(
                    0, label, servicePendingIntent(action, 10)).build());
            b.addAction(new Notification.Action.Builder(
                    0, "Próximo", servicePendingIntent(ACTION_NEXT, 12)).build());
            b.addAction(new Notification.Action.Builder(
                    0, "Encerrar", servicePendingIntent(ACTION_STOP, 11)).build());
        }
        return b.build();
    }

    private PendingIntent servicePendingIntent(String action, int requestCode) {
        Intent i = new Intent(this, MibroPacerService.class);
        i.setAction(action);
        return PendingIntent.getService(
                this, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void updateForeground(String text) {
        notificationManager.notify(FOREGROUND_ID, buildServiceNotification(text));
    }

    private void sendAlert(String title, String text, boolean vibrate) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ALERT)
                : new Notification.Builder(this);

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                this,
                (int)(alertCounter % 1000) + 100,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.MessagingStyle style = new Notification.MessagingStyle("atletIA")
                .setConversationTitle("atletIA Mibro • Thamis")
                .addMessage(text, System.currentTimeMillis(), "atletIA");

        b.setSmallIcon(R.drawable.ic_stat_atletia)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(style)
                .setContentIntent(openPi)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setCategory(Notification.CATEGORY_MESSAGE)
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
            if (!running) {
                updateForeground(configured
                        ? "Treino preparado: " + workoutTitle
                        : "Treino inválido");
            }
            sendTelemetry("READY", false, Double.NaN, Double.NaN);
        } catch (JSONException e) {
            configured = false;
            sendAlert("atletIA • ERRO",
                    "Não foi possível interpretar o treino recebido.", true);
        }
    }

    private double positiveOrZero(double v) {
        return Double.isFinite(v) && v > 0 ? v : 0;
    }

    private void startWorkout() {
        if (!configured || blocks.isEmpty()) {
            sendAlert("atletIA • TREINO NÃO PREPARADO",
                    "Abra o treino e toque em PREPARAR TREINO antes de iniciar.", true);
            return;
        }

        if (!hasLocationPermission()) {
            sendAlert("atletIA • GPS BLOQUEADO",
                    "Autorize a localização precisa para iniciar o pacer.", true);
            return;
        }

        if (running) {
            sendCurrentTelemetry();
            return;
        }

        running = true;
        paused = false;
        completed = false;
        blockIndex = 0;

        workoutStartedAt = elapsedNow();
        totalPausedMs = 0L;
        pauseStartedAt = 0L;
        finishedElapsedSec = 0L;

        distanceM = 0.0;
        blockStartDistanceM = 0.0;
        lastLocation = null;
        speedSamples.clear();
        paceSecPerKm = Double.NaN;
        accuracyM = Float.NaN;

        acquireWakeLock();
        startLocation();
        enterBlock(0, false);

        Block first = blocks.get(0);
        String message = workoutTitle + " • " + first.label + " • " + paceTargetText(first);
        sendAlert("atletIA • INICIADO", message, true);
        speak("Treino iniciado. " + first.label + ". " + targetSpeech(first));
        updateForeground("Correndo • " + first.label);
        sendTelemetry("RUNNING", false, remainingSec(first), remainingM(first));
    }

    private void pauseWorkout() {
        if (!running || paused) return;

        paused = true;
        pauseStartedAt = elapsedNow();
        stopLocation();
        releaseWakeLock();

        updateForeground("Pausado • " + currentBlockLabel());
        sendAlert("atletIA • PAUSADO",
                "Treino pausado • " + currentBlockLabel(), true);
        speak("Treino pausado.");
        sendCurrentTelemetry();
    }

    private void resumeWorkout() {
        if (!running || !paused) return;

        long now = elapsedNow();
        if (pauseStartedAt > 0) {
            long pausedFor = Math.max(0L, now - pauseStartedAt);
            totalPausedMs += pausedFor;
            blockStartedAt += pausedFor;
        }

        pauseStartedAt = 0L;
        paused = false;
        resetGuide();

        acquireWakeLock();
        startLocation();

        updateForeground("Correndo • " + currentBlockLabel());
        sendAlert("atletIA • RETOMADO",
                "Treino retomado • " + currentBlockLabel(), true);
        speak("Treino retomado.");
        sendCurrentTelemetry();
    }

    private void nextBlock(boolean manual) {
        if (!running) return;

        int next = blockIndex + 1;
        if (next >= blocks.size()) {
            finishWorkout(true);
            return;
        }

        enterBlock(next, true);
        if (manual) {
            sendAlert("atletIA • PRÓXIMO BLOCO",
                    blocks.get(next).label + " • " + paceTargetText(blocks.get(next)), true);
        }
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
            sendAlert("atletIA • " + b.label, paceTargetText(b), true);
            speak(b.label + ". " + targetSpeech(b));
        }

        sendTelemetry(paused ? "PAUSED" : "RUNNING",
                false, remainingSec(b), remainingM(b));
    }

    private void finishWorkout(boolean completedNormally) {
        if (!running && !paused) {
            sendTelemetry("ENDED", completed, Double.NaN, Double.NaN);
            stopSelf();
            return;
        }

        finishedElapsedSec = activeElapsedSec();
        running = false;
        paused = false;
        completed = completedNormally;

        stopLocation();
        releaseWakeLock();
        speedSamples.clear();

        String summary = formatDistance(distanceM)
                + " • " + formatElapsed(finishedElapsedSec)
                + " • pace médio " + formatAveragePace();

        if (completedNormally) {
            sendAlert("atletIA • TREINO CONCLUÍDO", summary, true);
            speak("Treino concluído.");
        } else {
            sendAlert("atletIA • TREINO ENCERRADO", summary, true);
            speak("Treino encerrado.");
        }

        sendTelemetry("ENDED", completedNormally, Double.NaN, Double.NaN);
        stopForeground(true);
        stopSelf();
    }

    private void tick() {
        if (!running || paused || blocks.isEmpty()) return;

        Block b = blocks.get(blockIndex);
        double remainingSec = remainingSec(b);
        double remainingM = remainingM(b);

        if (b.durationSec > 0 && remainingSec <= 0) {
            nextBlock(false);
            return;
        }

        if (b.durationSec <= 0 && b.distanceM > 0 && remainingM <= 0) {
            nextBlock(false);
            return;
        }

        evaluatePace(b);
        sendTelemetry("RUNNING", false, remainingSec, remainingM);
    }

    private double remainingSec(Block b) {
        if (b == null || b.durationSec <= 0) return Double.NaN;
        return Math.max(0.0,
                b.durationSec - ((elapsedNow() - blockStartedAt) / 1000.0));
    }

    private double remainingM(Block b) {
        if (b == null || b.durationSec > 0 || b.distanceM <= 0) return Double.NaN;
        return Math.max(0.0,
                b.distanceM - (distanceM - blockStartDistanceM));
    }

    private void evaluatePace(Block b) {
        if (!(b.paceMinSec > 0 && b.paceMaxSec > 0)) {
            guideState = "LIVRE";
            return;
        }

        if (!Double.isFinite(paceSecPerKm)
                || !Float.isFinite(accuracyM)
                || accuracyM > GPS_MAX_ACCURACY_M) {
            guideState = "WAIT";
            return;
        }

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

        long cooldown = "GOOD".equals(candidate)
                ? GOOD_COOLDOWN_MS : OUT_COOLDOWN_MS;

        if (now - lastGuideAlertAt < cooldown) return;
        lastGuideAlertAt = now;

        if ("FAST".equals(candidate)) {
            sendAlert("atletIA • REDUZA",
                    "Pace " + formatPace(paceSecPerKm)
                            + " • alvo " + paceTargetText(b), true);
            speak("Rápido demais. Reduza um pouco.");
        } else if ("SLOW".equals(candidate)) {
            sendAlert("atletIA • ACELERE",
                    "Pace " + formatPace(paceSecPerKm)
                            + " • alvo " + paceTargetText(b), true);
            speak("Acelere um pouco.");
        } else {
            sendAlert("atletIA • MANTENHA",
                    "Pace " + formatPace(paceSecPerKm)
                            + " • dentro do alvo.", false);
            speak("Ritmo certo. Mantenha.");
        }
    }

    private void resetGuide() {
        guideState = "UNKNOWN";
        guideSince = elapsedNow();
        lastGuideAlertAt = 0L;
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocation() {
        if (!hasLocationPermission()) return;

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000L, 0f, this, Looper.getMainLooper());
            } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        1000L, 0f, this, Looper.getMainLooper());
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
        accuracyM = acc;

        if (!Float.isFinite(acc) || acc > GPS_MAX_ACCURACY_M) {
            sendCurrentTelemetry();
            return;
        }

        long now = location.getElapsedRealtimeNanos() > 0
                ? location.getElapsedRealtimeNanos() / 1_000_000L
                : elapsedNow();

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

        sendCurrentTelemetry();
    }

    private void addSpeed(double speed, long now) {
        speedSamples.addLast(new SpeedSample(speed, now));

        while (!speedSamples.isEmpty()
                && speedSamples.peekFirst().time < now - SPEED_WINDOW_MS) {
            speedSamples.removeFirst();
        }

        if (speedSamples.size() < 2) return;

        double weighted = 0.0;
        double weights = 0.0;
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
        wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ThamisMibro:AtletIAPacer");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(4 * 60 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private void speak(String text) {
        if (tts == null || text == null || text.isEmpty()) return;
        tts.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "atletia-" + System.currentTimeMillis());
    }

    private String currentBlockLabel() {
        if (blockIndex >= 0 && blockIndex < blocks.size()) {
            return blocks.get(blockIndex).label;
        }
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
        return "Alvo de " + speechPace(b.paceMinSec)
                + " a " + speechPace(b.paceMaxSec) + " por quilômetro.";
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
        return (s / 60)
                + ":" + String.format(Locale.US, "%02d", s % 60)
                + "/km";
    }

    private String formatAveragePace() {
        if (distanceM < 10.0 || finishedElapsedSec <= 0) return "—";
        return formatPace(finishedElapsedSec / (distanceM / 1000.0));
    }

    private String formatDistance(double meters) {
        return String.format(Locale.US, "%.2f km", meters / 1000.0);
    }

    private String formatElapsed(long sec) {
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private long activeElapsedSec() {
        if (workoutStartedAt <= 0) return finishedElapsedSec;

        long end = paused && pauseStartedAt > 0
                ? pauseStartedAt : elapsedNow();

        long activeMs = Math.max(
                0L,
                end - workoutStartedAt - totalPausedMs);

        return activeMs / 1000L;
    }

    private long elapsedNow() {
        return android.os.SystemClock.elapsedRealtime();
    }

    private void sendCurrentTelemetry() {
        String state;
        if (running) state = paused ? "PAUSED" : "RUNNING";
        else if (finishedElapsedSec > 0 || completed) state = "ENDED";
        else state = configured ? "READY" : "IDLE";

        Block b = (blockIndex >= 0 && blockIndex < blocks.size())
                ? blocks.get(blockIndex) : null;

        sendTelemetry(
                state,
                completed,
                running && !paused ? remainingSec(b) : Double.NaN,
                running && !paused ? remainingM(b) : Double.NaN);
    }

    private void sendTelemetry(
            String state,
            boolean endedCompleted,
            double remainingSec,
            double remainingM) {
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("completed", endedCompleted);
            o.put("workoutTitle", workoutTitle);
            o.put("blockIndex", blockIndex);
            o.put("blocksTotal", blocks.size());
            o.put("blockLabel", currentBlockLabel());
            o.put("distanceM", distanceM);
            o.put("elapsedSec", activeElapsedSec());
            o.put("averagePaceSecPerKm",
                    distanceM >= 10.0 && activeElapsedSec() > 0
                            ? activeElapsedSec() / (distanceM / 1000.0)
                            : JSONObject.NULL);

            if (Double.isFinite(paceSecPerKm)) {
                o.put("paceSecPerKm", paceSecPerKm);
            }

            if (Float.isFinite(accuracyM)) {
                o.put("accuracyM", accuracyM);
            }

            if (Double.isFinite(remainingSec)) {
                o.put("remainingSec", remainingSec);
            }

            if (Double.isFinite(remainingM)) {
                o.put("remainingM", remainingM);
            }

            if ("FAST".equals(guideState)) o.put("guide", "REDUZA");
            else if ("SLOW".equals(guideState)) o.put("guide", "ACELERE");
            else if ("GOOD".equals(guideState)) o.put("guide", "MANTENHA");
            else if ("LIVRE".equals(guideState)) o.put("guide", "RITMO LIVRE");
            else if ("WAIT".equals(guideState)) o.put("guide", "AGUARDANDO PACE");

            Intent i = new Intent(ACTION_TELEMETRY);
            i.setPackage(getPackageName());
            i.putExtra(EXTRA_TELEMETRY, o.toString());
            sendBroadcast(i);
        } catch (JSONException ignored) { }
    }

    private void runTestSuite() {
        final String[] titles = new String[]{
                "atletIA • INICIADO",
                "atletIA • ACELERE",
                "atletIA • MANTENHA",
                "atletIA • REDUZA",
                "atletIA • PAUSADO",
                "atletIA • RETOMADO",
                "atletIA • PRÓXIMO BLOCO",
                "atletIA • TREINO CONCLUÍDO"
        };

        final String[] bodies = new String[]{
                "Teste completo • bloco Aquecimento • alvo 7:30–8:00/km",
                "Pace abaixo do alvo • acelere",
                "Pace dentro do alvo • mantenha",
                "Pace acima do alvo • reduza",
                "Treino pausado",
                "Treino retomado",
                "Desaquecimento • alvo 7:30–8:00/km",
                "Teste completo finalizado"
        };

        for (int i = 0; i < titles.length; i++) {
            final int index = i;
            handler.postDelayed(() -> {
                sendAlert(titles[index], bodies[index], true);
                if (index == 0) speak("Teste completo iniciado.");
                if (index == titles.length - 1) speak("Teste completo finalizado.");
            }, i * 2600L);
        }
    }

    @Override public void onProviderEnabled(String provider) { }

    @Override public void onProviderDisabled(String provider) { }

    @SuppressWarnings("deprecation")
    @Override public void onStatusChanged(
            String provider, int status, Bundle extras) { }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

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
