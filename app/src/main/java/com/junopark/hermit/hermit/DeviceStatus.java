package com.junopark.hermit.hermit;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import com.junopark.hermit.R;

import java.util.Locale;

/**
 * Battery level, battery temperature and the system's thermal status of this device, for the
 * performance overlay. Read at most every 5 seconds, on the main thread: describe() is called
 * from the decoder thread and only returns the last values.
 */
public final class DeviceStatus {
    private static final long REFRESH_MS = 5000;

    private static long lastRead;
    private static int batteryPct = -1;
    private static float temperatureC = Float.NaN;
    private static boolean charging;
    private static int thermalStatus = -1;
    private static boolean refreshPosted;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DeviceStatus() {
    }

    // Asks the main thread for new values when the last ones are old
    private static synchronized void requestRefresh(Context context) {
        long now = SystemClock.uptimeMillis();
        if (refreshPosted || (lastRead != 0 && now - lastRead < REFRESH_MS)) {
            return;
        }
        refreshPosted = true;
        final Context app = context.getApplicationContext();
        MAIN.post(() -> read(app));
    }

    private static void read(Context context) {
        int newBatteryPct = -1, newThermal = -1;
        float newTemperature = Float.NaN;
        boolean newCharging = false;
        try {
            Intent battery = context.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                newBatteryPct = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
                int tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                newTemperature = tenths != Integer.MIN_VALUE ? tenths / 10f : Float.NaN;
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                newCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
            }
        } catch (RuntimeException ignored) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            newThermal = pm != null ? pm.getCurrentThermalStatus() : -1;
        }
        synchronized (DeviceStatus.class) {
            batteryPct = newBatteryPct;
            temperatureC = newTemperature;
            charging = newCharging;
            thermalStatus = newThermal;
            lastRead = SystemClock.uptimeMillis();
            refreshPosted = false;
        }
    }

    /** "82% (charging) · 36.5°C · heat: normal", parts missing on devices that do not report them. */
    public static String describe(Context context) {
        requestRefresh(context);
        int batteryPct, thermalStatus;
        float temperatureC;
        boolean charging;
        synchronized (DeviceStatus.class) {
            batteryPct = DeviceStatus.batteryPct;
            thermalStatus = DeviceStatus.thermalStatus;
            temperatureC = DeviceStatus.temperatureC;
            charging = DeviceStatus.charging;
        }
        StringBuilder sb = new StringBuilder();
        if (batteryPct >= 0) {
            sb.append(batteryPct).append('%');
            if (charging) {
                sb.append(' ').append(context.getString(R.string.hermit_device_charging));
            }
        }
        if (!Float.isNaN(temperatureC)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(String.format(Locale.ROOT, "%.1f°C", temperatureC));
        }
        if (thermalStatus >= 0) {
            int[] names = {R.string.hermit_device_heat_none, R.string.hermit_device_heat_light,
                    R.string.hermit_device_heat_moderate, R.string.hermit_device_heat_severe,
                    R.string.hermit_device_heat_critical};
            if (sb.length() > 0) sb.append(" · ");
            sb.append(context.getString(names[Math.min(thermalStatus, names.length - 1)]));
        }
        return sb.length() > 0 ? sb.toString() : "–";
    }
}
