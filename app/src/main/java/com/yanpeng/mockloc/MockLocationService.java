package com.yanpeng.mockloc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.Random;

public class MockLocationService extends Service {
    public static final String ACTION_START = "com.yanpeng.mockloc.START";
    public static final String ACTION_STOP = "com.yanpeng.mockloc.STOP";
    public static final String EXTRA_LAT = "lat_wgs";
    public static final String EXTRA_LNG = "lng_wgs";
    public static final String EXTRA_GCJ_LAT = "lat_gcj";
    public static final String EXTRA_GCJ_LNG = "lng_gcj";

    private static final String TAG = "MockLocService";
    private static final String CHANNEL_ID = "mock_loc";
    private static final int NOTIF_ID = 1001;
    /** Fast enough that fused / map SDKs keep seeing fresh mock fixes. */
    private static final long INTERVAL_MS = 250;
    private static final int STARTUP_BURST = 12;

    private LocationManager locationManager;
    private Handler handler;
    private double latWgs;
    private double lngWgs;
    private double latGcj;
    private double lngGcj;
    private boolean running;
    private boolean providersReady;
    private int burstLeft;
    private final Random rng = new Random();
    private final String[] providers = new String[]{
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            "fused",
            "passive"
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            pushLocation();
            long delay = burstLeft > 0 ? 80L : INTERVAL_MS;
            if (burstLeft > 0) burstLeft--;
            handler.postDelayed(this, delay);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopMocking();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        latWgs = intent.getDoubleExtra(EXTRA_LAT, 0);
        lngWgs = intent.getDoubleExtra(EXTRA_LNG, 0);
        latGcj = intent.getDoubleExtra(EXTRA_GCJ_LAT, latWgs);
        lngGcj = intent.getDoubleExtra(EXTRA_GCJ_LNG, lngWgs);

        startForeground(NOTIF_ID, buildNotification());
        try {
            if (!providersReady) {
                setupProviders();
                providersReady = true;
            } else {
                for (String provider : providers) {
                    try {
                        locationManager.setTestProviderEnabled(provider, true);
                    } catch (Exception ignored) {
                    }
                }
            }
            running = true;
            burstLeft = STARTUP_BURST;
            handler.removeCallbacks(tick);
            handler.post(tick);
            Log.i(TAG, "Mock started WGS84=" + latWgs + "," + lngWgs);
        } catch (SecurityException se) {
            Log.e(TAG, "Not mock location app", se);
            running = false;
            stopForeground(true);
            stopSelf();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start mock", e);
            running = false;
            stopForeground(true);
            stopSelf();
        }
        return START_STICKY;
    }

    private void setupProviders() {
        for (String provider : providers) {
            try {
                try {
                    locationManager.removeTestProvider(provider);
                } catch (Exception ignored) {
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    locationManager.addTestProvider(
                            provider,
                            /*requiresNetwork*/ false,
                            /*requiresSatellite*/ false,
                            /*requiresCell*/ false,
                            /*hasMonetaryCost*/ false,
                            /*supportsAltitude*/ true,
                            /*supportsSpeed*/ true,
                            /*supportsBearing*/ true,
                            ProviderProperties.POWER_USAGE_LOW,
                            ProviderProperties.ACCURACY_FINE
                    );
                } else {
                    locationManager.addTestProvider(
                            provider,
                            false, false, false, false,
                            true, true, true,
                            Criteria.POWER_LOW,
                            Criteria.ACCURACY_FINE
                    );
                }
                locationManager.setTestProviderEnabled(provider, true);
            } catch (Exception e) {
                Log.w(TAG, "addTestProvider " + provider + ": " + e.getMessage());
            }
        }
    }

    private void pushLocation() {
        long now = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtimeNanos();
        // ~1–3 m GPS-like jitter so SDKs don't treat the fix as a frozen spoof
        double dLat = (rng.nextDouble() - 0.5) * 0.00003;
        double dLng = (rng.nextDouble() - 0.5) * 0.00003;
        float acc = 2.5f + rng.nextFloat() * 2.5f;
        for (String provider : providers) {
            try {
                Location loc = new Location(provider);
                loc.setLatitude(latWgs + dLat);
                loc.setLongitude(lngWgs + dLng);
                loc.setAltitude(28.0 + rng.nextDouble() * 4.0);
                loc.setAccuracy(acc);
                loc.setBearing(rng.nextFloat() * 360f);
                loc.setSpeed(0.05f + rng.nextFloat() * 0.2f);
                loc.setTime(now);
                loc.setElapsedRealtimeNanos(elapsed);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    loc.setVerticalAccuracyMeters(3.0f);
                    loc.setBearingAccuracyDegrees(5f);
                    loc.setSpeedAccuracyMetersPerSecond(0.3f);
                }
                // Do NOT put mock=true in extras — that helps apps reject the fix.
                locationManager.setTestProviderLocation(provider, loc);
            } catch (Exception e) {
                Log.w(TAG, "setTestProviderLocation " + provider + ": " + e.getMessage());
            }
        }
    }

    private void stopMocking() {
        running = false;
        providersReady = false;
        handler.removeCallbacks(tick);
        for (String provider : providers) {
            try {
                locationManager.setTestProviderEnabled(provider, false);
            } catch (Exception ignored) {
            }
            try {
                locationManager.removeTestProvider(provider);
            } catch (Exception ignored) {
            }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Intent stop = new Intent(this, MockLocationService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        String body = String.format("WGS84 %.5f, %.5f (GCJ %.5f, %.5f)",
                latWgs, lngWgs, latGcj, lngGcj);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(body)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .addAction(0, getString(R.string.stop_mock), stopPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override
    public void onDestroy() {
        stopMocking();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static boolean isMockAppSelected(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.app.AppOpsManager ops =
                        (android.app.AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
                if (ops == null) return false;
                int mode = ops.checkOpNoThrow(
                        "android:mock_location",
                        android.os.Process.myUid(),
                        ctx.getPackageName()
                );
                return mode == android.app.AppOpsManager.MODE_ALLOWED;
            }
        } catch (Exception ignored) {
        }
        return true;
    }
}
