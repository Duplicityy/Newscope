package local.kronos.mobile;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.util.Log;

/** Visible session lifetime, not a background bot or an idle-logout bypass. */
public final class GameSessionService extends Service {
    private static final String CHANNEL = "kronos-game-session";
    private static final int NOTIFICATION = 242;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private long inactiveSince = -1;
    private String displayedState = "";
    private final Runnable monitor = this::checkSession;

    static void ensure(Context context) {
        Intent intent = new Intent(context, GameSessionService.class);
        context.startForegroundService(intent);
    }
    static void end(Context context) { context.stopService(new Intent(context, GameSessionService.class)); }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Desktop test game session", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps your current game connection running while you briefly switch apps.");
        channel.setShowBadge(false); manager.createNotificationChannel(channel);
        PowerManager power = getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName()+":game-session");
        wakeLock.setReferenceCounted(false);
        Notification notice = notification("Game session running");
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION, notice);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(monitor); handler.post(monitor);
        return START_NOT_STICKY; // Never restart a dead JVM or log in behind the user's back.
    }
    private Notification notification(String state) {
        Notification.Builder notice = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_owner_test).setContentTitle("Newscape Desktop Test")
                .setContentText(state).setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setShowWhen(false);
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) {
            open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            notice.setContentIntent(PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        return notice.build();
    }
    private void checkSession() {
        try {
            long now = SystemClock.elapsedRealtime();
            String state = GameRuntime.status();
            boolean connected = "LOGGED_IN".equals(state) || "LOGGING_IN".equals(state);
            if (BackgroundSessionPolicy.needsWakeLock(state, now)) {
                if (!wakeLock.isHeld()) wakeLock.acquire(BackgroundSessionPolicy.MAX_BACKGROUND_WAKE_MILLIS);
            } else releaseWakeLock();
            String message = connected ? (BackgroundSessionPolicy.visible() ? "Connected to the game" : "Game connection active · tap to return") : "Game client running · tap to return";
            if (!displayedState.equals(message)) {
                displayedState = message;
                getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(message));
            }
            if (connected || GameRuntime.preparing() || BackgroundSessionPolicy.visible()) inactiveSince = -1;
            else if (inactiveSince < 0) inactiveSince = now;
            if (!GameRuntime.active() || GameRuntime.failure() != null || (inactiveSince >= 0 && now - inactiveSince >= 30000)) {
                stopSelf(); return;
            }
        } catch (RuntimeException failure) {
            Log.e("KronosSession", "Session monitor failed", failure);
            releaseWakeLock(); stopSelf(); return;
        }
        handler.postDelayed(monitor, 1000);
    }
    private void releaseWakeLock() { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
    @Override public void onTaskRemoved(Intent rootIntent) {
        // Swiping away Recents is an explicit exit, unlike onStop/app switching.
        try {
            handler.removeCallbacksAndMessages(null);
            releaseWakeLock();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        } finally {
            super.onTaskRemoved(rootIntent);
            GameRuntime.close(getApplicationContext());
        }
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null); releaseWakeLock(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
