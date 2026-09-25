package {{PKG}};

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class ZenBridge {

    public static final String TAG = "Zen";
    private static final String CHANNEL_ID = "zen";
    private static final String BG_PREFS = "zen_bg";
    private static final int ALARM_CODE = 9801;

    private final Context context;
    private final Activity activity;
    private final WebView webView;
    private final Handler main = new Handler(Looper.getMainLooper());

    private LocationManager locationManager;
    private LocationListener listener;
    private boolean listening = false;
    private TextView overlayView;
    private boolean mockErrorReported = false;

    private SensorManager sensorManager;
    private final Map<String, SensorEventListener> sensorListeners = new HashMap<>();
    private PowerManager.WakeLock wakeLock;
    private PendingIntent alarmPi;

    public ZenBridge(Context context, Activity activity, WebView webView) {
        this.context = context;
        this.activity = activity;
        this.webView = webView;
    }

    // ------------------------------------------------------------------
    // Basic / misc
    // ------------------------------------------------------------------

    @JavascriptInterface
    public String version() {
        return "1.2";
    }

    @JavascriptInterface
    public long now() {
        return System.currentTimeMillis();
    }

    @JavascriptInterface
    public boolean checkPermission(String perm) {
        if (activity == null || Build.VERSION.SDK_INT < 23) return true;
        return activity.checkSelfPermission(String.valueOf(perm)) == PackageManager.PERMISSION_GRANTED;
    }

    @JavascriptInterface
    public String requestPermission(final String perm) {
        final String p = String.valueOf(perm);
        if (activity == null || Build.VERSION.SDK_INT < 23) return "granted";
        if (activity.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) return "granted";
        main.post(() -> {
            try {
                activity.requestPermissions(new String[]{p}, 300);
            } catch (Exception e) {
            }
        });
        return "requested";
    }

    @JavascriptInterface
    public void toast(String msg) {
        doToast(String.valueOf(msg), Toast.LENGTH_SHORT);
    }

    @JavascriptInterface
    public void toastLong(String msg) {
        doToast(String.valueOf(msg), Toast.LENGTH_LONG);
    }

    private void doToast(final String msg, final int len) {
        main.post(() -> {
            try {
                Toast.makeText(context, msg, len).show();
            } catch (Exception e) {
            }
        });
    }

    @JavascriptInterface
    public void log(String msg) {
        Log.i(TAG, String.valueOf(msg));
    }

    // ------------------------------------------------------------------
    // Location
    // ------------------------------------------------------------------

    @JavascriptInterface
    public String getLocation() {
        try {
            LocationManager lm = getLocationManager();
            Location loc = null;
            try {
                loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            } catch (Exception e) {
            }
            if (loc == null) {
                try {
                    loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                } catch (Exception e) {
                }
            }
            if (loc == null) {
                try {
                    loc = lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER);
                } catch (Exception e) {
                }
            }
            return loc == null ? "null" : toJson(loc).toString();
        } catch (Exception e) {
            return "null";
        }
    }

    @JavascriptInterface
    public void setLocationCallback(int intervalMs) {
        if (listening) return;
        LocationManager lm = getLocationManager();
        listener = this::dispatchLocation;
        for (String p : new String[]{
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.FUSED_PROVIDER}) {
            try {
                lm.requestLocationUpdates(p, Math.max(100L, intervalMs), 0f, listener);
            } catch (Exception e) {
            }
        }
        listening = true;
    }

    @JavascriptInterface
    public void clearLocationCallback() {
        try {
            if (listener != null) getLocationManager().removeUpdates(listener);
        } catch (Exception e) {
        }
        listening = false;
    }

    private void dispatchLocation(Location loc) {
        try {
            final String json = toJson(loc).toString();
            main.post(() -> {
                try {
                    if (webView != null) {
                        webView.evaluateJavascript(
                                "window.__zenOnLocation && window.__zenOnLocation(" + json + ")", null);
                    }
                } catch (Exception e) {
                }
            });
        } catch (Exception e) {
        }
    }

    // ------------------------------------------------------------------
    // Sensors (streaming) -- window.__zenOnSensor(name, {x,y,z,v,t})
    // ------------------------------------------------------------------

    @JavascriptInterface
    public String subscribeSensor(String sensor, int intervalMs) {
        if (sensorManager == null) {
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        }
        Integer type = sensorType(sensor);
        if (type == null) return "ERROR: unknown sensor";
        Sensor s = sensorManager.getDefaultSensor(type);
        if (s == null) return "ERROR: no " + sensor + " on this device";
        if (sensorListeners.containsKey(sensor)) return "already subscribed";
        SensorEventListener l = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                try {
                    final String json = sensorJson(sensor, e).toString();
                    main.post(() -> {
                        try {
                            if (webView != null) {
                                webView.evaluateJavascript(
                                        "window.__zenOnSensor && window.__zenOnSensor(\""
                                        + sensor + "\"," + json + ")", null);
                            }
                        } catch (Exception ex) {
                        }
                    });
                } catch (Exception ex) {
                }
            }

            @Override
            public void onAccuracyChanged(Sensor s, int a) {
            }
        };
        try {
            sensorManager.registerListener(l, s, (int) Math.max(20L, Math.min(60_000_000L, intervalMs * 1000L)));
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
        sensorListeners.put(sensor, l);
        return "ok";
    }

    @JavascriptInterface
    public String unsubscribeSensor(String sensor) {
        SensorEventListener l = sensorListeners.remove(sensor);
        if (l == null) return "not subscribed";
        if (sensorManager != null) sensorManager.unregisterListener(l);
        return "ok";
    }

    private Integer sensorType(String name) {
        switch (String.valueOf(name).toLowerCase()) {
            case "accelerometer": return Sensor.TYPE_ACCELEROMETER;
            case "gyroscope": return Sensor.TYPE_GYROSCOPE;
            case "gyro": return Sensor.TYPE_GYROSCOPE;
            case "magnetometer": return Sensor.TYPE_MAGNETIC_FIELD;
            case "light": return Sensor.TYPE_LIGHT;
            case "proximity": return Sensor.TYPE_PROXIMITY;
            case "pressure": return Sensor.TYPE_PRESSURE;
            case "barometer": return Sensor.TYPE_PRESSURE;
            default: return null;
        }
    }

    private JSONObject sensorJson(String sensor, SensorEvent e) throws Exception {
        JSONObject o = new JSONObject();
        o.put("t", e.timestamp / 1000000L);
        float[] v = e.values;
        switch (sensor.toLowerCase()) {
            case "accelerometer":
            case "gyroscope":
            case "gyro":
            case "magnetometer":
                o.put("x", v[0]);
                o.put("y", v[1]);
                o.put("z", v[2]);
                break;
            default:
                o.put("v", v[0]);
        }
        return o;
    }

    // ------------------------------------------------------------------
    // Notifications + alarms
    // ------------------------------------------------------------------

    @JavascriptInterface
    public void notify(final String title, final String body) {
        main.post(() -> {
            try {
                NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Zen", NotificationManager.IMPORTANCE_LOW));
                }
                PendingIntent pi = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification n = new Notification.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_zen)
                        .setContentTitle(String.valueOf(title))
                        .setContentText(String.valueOf(body))
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build();
                nm.notify((int) (System.currentTimeMillis() % 100000), n);
            } catch (Exception e) {
                Log.w(TAG, "notify: " + e.getMessage());
            }
        });
    }

    /** Schedule a real system alarm (persisted across app restarts). */
    @JavascriptInterface
    public String setAlarm(final long delayMs, final String title, final String body) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(context, AlarmReceiver.class)
                    .putExtra("t", String.valueOf(title))
                    .putExtra("b", String.valueOf(body));
            alarmPi = PendingIntent.getBroadcast(context, ALARM_CODE, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + Math.max(0L, delayMs), alarmPi);
            return "ok";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @JavascriptInterface
    public String cancelAlarm() {
        try {
            if (alarmPi != null) {
                AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                am.cancel(alarmPi);
                alarmPi = null;
                return "ok";
            }
            return "no alarm set";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // Mock location
    // ------------------------------------------------------------------

    @JavascriptInterface
    public String setMockLocation(double lat, double lng) {
        try {
            if (!isMockLocationApp()) {
                return "ERROR: Zen is not selected as the Mock location app (Developer options > Mock location app).";
            }
            ensureTestProviders();
            Location g = new Location(LocationManager.GPS_PROVIDER);
            g.setLatitude(lat);
            g.setLongitude(lng);
            g.setAccuracy(5f);
            g.setTime(System.currentTimeMillis());
            try {
                getLocationManager().setTestProviderLocation(LocationManager.GPS_PROVIDER, g);
            } catch (Exception e) {
            }
            return "ok";
        } catch (SecurityException e) {
            mockErrorReported = true;
            return "ERROR: not allowed to use mock providers";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // Root / shell
    // ------------------------------------------------------------------

    @JavascriptInterface
    public boolean isRoot() {
        try {
            java.lang.Process p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("uid=0")) return true;
            }
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public String runRoot(String cmd) {
        try {
            java.lang.Process p = new ProcessBuilder("su", "-c", String.valueOf(cmd)).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
            int exit = p.waitFor();
            JSONObject o = new JSONObject();
            o.put("exit", exit);
            o.put("out", out.toString().trim());
            return o.toString();
        } catch (Exception e) {
            JSONObject o = new JSONObject();
            try {
                o.put("exit", -1);
                o.put("out", "error: " + e.getMessage());
            } catch (Exception ignored) {
            }
            return o.toString();
        }
    }

    // ------------------------------------------------------------------
    // Overlay
    // ------------------------------------------------------------------

    @JavascriptInterface
    public void showOverlay(final String text) {
        main.post(() -> {
            try {
                if (activity != null && Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
                    activity.requestPermissions(new String[]{Manifest.permission.SYSTEM_ALERT_WINDOW}, 200);
                    return;
                }
                if (overlayView != null) {
                    overlayView.setText(String.valueOf(text));
                    return;
                }
                TextView tv = new TextView(context);
                tv.setText(String.valueOf(text));
                tv.setTextColor(0xFFFFFFFF);
                tv.setBackgroundColor(0xCC000000);
                tv.setPadding(16, 8, 16, 8);
                tv.setTextSize(14);
                WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.y = 48;
                wm.addView(tv, lp);
                overlayView = tv;
            } catch (Exception e) {
                Log.w(TAG, "overlay: " + e.getMessage());
            }
        });
    }

    @JavascriptInterface
    public void hideOverlay() {
        main.post(() -> {
            try {
                if (overlayView != null) {
                    ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).removeView(overlayView);
                    overlayView = null;
                }
            } catch (Exception e) {
            }
        });
    }

    // ------------------------------------------------------------------
    // Background service + dedicated worker script
    // ------------------------------------------------------------------

    @JavascriptInterface
    public void startBackground() {
        Intent i = new Intent(context, ZenService.class);
        i.setAction("start");
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
        else context.startService(i);
    }

    @JavascriptInterface
    public void stopBackground() {
        Intent i = new Intent(context, ZenService.class);
        i.setAction("stop");
        context.startService(i);
    }

    /** Tell the background service to load a SEPARATE script instead of index.html. */
    @JavascriptInterface
    public String setBackgroundScript(String script) {
        try {
            SharedPreferences p = context.getSharedPreferences(BG_PREFS, Context.MODE_PRIVATE);
            p.edit().putString("script", String.valueOf(script)).apply();
            return "ok";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @JavascriptInterface
    public String getBackgroundScript() {
        SharedPreferences p = context.getSharedPreferences(BG_PREFS, Context.MODE_PRIVATE);
        String s = p.getString("script", "index.html");
        return s == null ? "index.html" : s;
    }

    // ------------------------------------------------------------------
    // Vibration / display / audio / clipboard
    // ------------------------------------------------------------------

    @JavascriptInterface
    public String vibrate(long ms) {
        try {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return "ERROR: no vibrator";
            long t = Math.max(1L, Math.min(60000L, ms));
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(t, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(t);
            }
            return "ok";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @JavascriptInterface
    public String setBrightness(int v) {
        try {
            if (activity == null) return "ERROR: not available in background";
            final float f = Math.max(0.01f, Math.min(1f, v / 255f));
            main.post(() -> {
                try {
                    WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
                    lp.screenBrightness = f;
                    activity.getWindow().setAttributes(lp);
                } catch (Exception e) {
                }
            });
            return "ok";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @JavascriptInterface
    public String keepScreenOn(boolean on) {
        try {
            if (activity == null) return "ERROR: not available in background";
            if (on) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            return "ok";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /** Partial CPU wake lock while the app does background-ish JS work. 10 min cap. */
    @JavascriptInterface
    public String wakeLock(boolean on) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (on) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zen:wake");
                    wakeLock.setReferenceCounted(false);
                }
                wakeLock.acquire(10 * 60 * 1000L);
                return "ok";
            }
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            return "ok";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @JavascriptInterface
    public String setVolume(String stream, int pct) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int s;
            switch (String.valueOf(stream).toLowerCase()) {
                case "music": s = AudioManager.STREAM_MUSIC; break;
                case "ring": s = AudioManager.STREAM_RING; break;
                case "alarm": s = AudioManager.STREAM_ALARM; break;
                case "notification": s = AudioManager.STREAM_NOTIFICATION; break;
                default: return "ERROR: unknown stream";
            }
            int max = am.getStreamMaxVolume(s);
            int val = Math.round(max * (Math.max(0, Math.min(100, pct)) / 100f));
            am.setStreamVolume(s, val, 0);
            return "ok";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @JavascriptInterface
    public String setTorch(boolean on) {
        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String id = cm.getCameraIdList()[0];
            if (Build.VERSION.SDK_INT >= 23) {
                cm.setTorchMode(id, on);
            }
            return "ok";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @JavascriptInterface
    public boolean clipboardWrite(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Zen", String.valueOf(text)));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public String clipboardRead() {
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (!cm.hasPrimaryClip()) return "";
            ClipData.Item it = cm.getPrimaryClip().getItemAt(0);
            CharSequence t = (it == null) ? "" : it.coerceToText(context);
            return t == null ? "" : t.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // System / device info
    // ------------------------------------------------------------------

    @JavascriptInterface
    public String battery() {
        try {
            Intent b = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            JSONObject o = new JSONObject();
            if (b == null) return o.toString();
            int level = b.getIntExtra("level", -1);
            int scale = b.getIntExtra("scale", 100);
            o.put("level", level < 0 ? -1 : Math.round(100f * level / Math.max(1, scale)));
            int st = b.getIntExtra("status", 0);
            String status = st == 2 ? "charging" : st == 5 ? "full" : "discharging";
            o.put("status", status);
            int plug = b.getIntExtra("plugged", 0);
            o.put("plugged", plug == 2 ? "usb" : plug == 4 ? "wireless" : plug == 1 ? "ac" : "none");
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public String device() {
        try {
            JSONObject o = new JSONObject();
            o.put("brand", Build.BRAND == null ? "" : Build.BRAND);
            o.put("model", Build.MODEL == null ? "" : Build.MODEL);
            o.put("manufacturer", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("release", Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE);
            String arch = System.getProperty("os.arch");
            o.put("arch", arch == null ? "" : arch);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    // ------------------------------------------------------------------
    // Intents: open URL, share
    // ------------------------------------------------------------------

    @JavascriptInterface
    public String openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(String.valueOf(url)));
            if (!(context instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
            return "ok";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @JavascriptInterface
    public String share(String title, String text) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TITLE, String.valueOf(title));
            i.putExtra(Intent.EXTRA_TEXT, String.valueOf(text));
            Intent chooser = Intent.createChooser(i, String.valueOf(title));
            if (!(context instanceof Activity)) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
            return "ok";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private JSONObject toJson(Location l) throws Exception {
        JSONObject o = new JSONObject();
        o.put("latitude", l.getLatitude());
        o.put("longitude", l.getLongitude());
        o.put("altitude", l.getAltitude());
        o.put("accuracy", l.getAccuracy());
        o.put("speed", l.getSpeed());
        o.put("bearing", l.getBearing());
        o.put("time", l.getTime());
        return o;
    }

    private LocationManager getLocationManager() {
        if (locationManager == null) {
            locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        }
        return locationManager;
    }

    private boolean isMockLocationApp() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
                return appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.getPackageName())
                        == AppOpsManager.MODE_ALLOWED;
            }
            return Settings.Secure.getInt(context.getContentResolver(), "mock_location", 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureTestProviders() {
        addTestProviderSafe(LocationManager.GPS_PROVIDER, false, true);
        addTestProviderSafe(LocationManager.NETWORK_PROVIDER, true, false);
        try {
            getLocationManager().setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
        } catch (Exception e) {
        }
        try {
            getLocationManager().setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
        } catch (Exception e) {
        }
    }

    private void addTestProviderSafe(String name, boolean requiresNetwork, boolean requiresSatellite) {
        try {
            getLocationManager().addTestProvider(
                    name, requiresNetwork, requiresSatellite, false, false,
                    true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
        } catch (Exception e) {
        }
    }
}