package {{PKG}};

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
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

public class ZenBridge {

    public static final String TAG = "Zen";
    private static final String CHANNEL_ID = "zen";

    private final Context context;
    private final Activity activity;
    private final WebView webView;
    private final Handler main = new Handler(Looper.getMainLooper());

    private LocationManager locationManager;
    private LocationListener listener;
    private boolean listening = false;
    private TextView overlayView;
    private boolean mockErrorReported = false;

    public ZenBridge(Context context, Activity activity, WebView webView) {
        this.context = context;
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public String version() {
        return "1.1";
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
        main.post(() -> Toast.makeText(context, String.valueOf(msg), Toast.LENGTH_SHORT).show());
    }

    @JavascriptInterface
    public void log(String msg) {
        Log.i(TAG, String.valueOf(msg));
    }

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
            return null;
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

    private void dispatchLocation(Location loc) {
        try {
            final String json = toJson(loc).toString();
            main.post(() -> {
                try {
                    if (webView != null) {
                        webView.evaluateJavascript("window.__zenOnLocation && window.__zenOnLocation(" + json + ")", null);
                    }
                } catch (Exception e) {
                }
            });
        } catch (Exception e) {
        }
    }

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