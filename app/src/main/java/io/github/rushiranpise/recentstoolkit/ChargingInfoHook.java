package io.github.rushiranpise.recentstoolkit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Adds a live charging-details line under the stock One UI charging percentage
 * and time-to-full block. The stock block lives in the AOD plugin, which
 * SystemUI loads through PluginAODManager.
 */
final class ChargingInfoHook {
    private static final String TAG = "RecentsToolkit";
    private static final String LINE_TAG = "recentstoolkit:charging_details";
    private static final String AOD_PACKAGE = "com.samsung.android.app.aodservice";
    private static final Set<ClassLoader> HOOKED =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<View, BroadcastReceiver> RECEIVERS = new WeakHashMap<>();

    private ChargingInfoHook() {
    }

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> manager = Class.forName(
                    "com.android.systemui.doze.PluginAODManager", false, lpparam.classLoader);
            Class<?> plugin = Class.forName(
                    "com.android.systemui.plugins.aod.PluginAOD", false, lpparam.classLoader);

            XposedBridge.hookMethod(
                    manager.getDeclaredMethod("setAODPlugin", plugin),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object aod = param.args.length > 0 ? param.args[0] : null;
                                if (aod != null) {
                                    hookChargingView(aod.getClass().getClassLoader());
                                }
                            } catch (Throwable t) {
                                log("plugin hook failed", t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": charging info hook installed in " + lpparam.packageName);
        } catch (Throwable t) {
            log("hooks skipped", t);
        }
    }

    private static void hookChargingView(ClassLoader loader) {
        if (loader == null) return;
        synchronized (HOOKED) {
            if (!HOOKED.add(loader)) return;
        }
        try {
            Class<?> viewClass = Class.forName(
                    "com.samsung.android.uniform.widget.charginginfo.ChargingInfoView",
                    false, loader);

            hook(viewClass, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        addLine((View) param.thisObject);
                    } catch (Throwable t) {
                        log("add line failed", t);
                    }
                }
            });
            hook(viewClass, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    start((View) param.thisObject);
                }
            });
            hook(viewClass, "onDetachedFromWindow", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    stop((View) param.thisObject);
                }
            });
            XposedBridge.log(TAG + ": charging info view hooked");
        } catch (Throwable t) {
            log("view class skipped", t);
        }
    }

    private static void hook(Class<?> type, String name, XC_MethodHook callback) {
        try {
            XposedBridge.hookMethod(type.getDeclaredMethod(name), callback);
        } catch (Throwable t) {
            log("hook " + name + " skipped", t);
        }
    }

    private static void addLine(View root) {
        Context context = root.getContext();
        int containerId = resourceId(context, "common_charging_info_container");
        if (containerId == 0) return;

        View containerView = root.findViewById(containerId);
        if (!(containerView instanceof LinearLayout)) return;
        LinearLayout container = (LinearLayout) containerView;
        if (container.findViewWithTag(LINE_TAG) != null) return;

        TextView line = new TextView(context);
        line.setTag(LINE_TAG);
        line.setGravity(Gravity.CENTER);
        line.setSingleLine(true);
        line.setIncludeFontPadding(false);
        line.setVisibility(View.GONE);
        line.setTextColor(0xCCFFFFFF);
        line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        line.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

        int timeId = resourceId(context, "common_charging_info_remaining_time_text_view");
        if (timeId != 0) {
            View timeView = root.findViewById(timeId);
            if (timeView instanceof TextView) {
                TextView time = (TextView) timeView;
                line.setTextSize(TypedValue.COMPLEX_UNIT_PX, time.getTextSize());
                line.setTextColor(time.getTextColors());
                line.setIncludeFontPadding(time.getIncludeFontPadding());
                line.setAlpha(time.getAlpha());
                line.setLetterSpacing(time.getLetterSpacing());
                line.setTextScaleX(time.getTextScaleX());
                line.getPaint().set(time.getPaint());
                Typeface typeface = time.getTypeface();
                if (typeface != null) line.setTypeface(typeface);
                line.post(() -> {
                    line.setTextSize(TypedValue.COMPLEX_UNIT_PX, time.getTextSize());
                    line.setTextColor(time.getTextColors());
                    line.setLetterSpacing(time.getLetterSpacing());
                    line.setTextScaleX(time.getTextScaleX());
                    line.getPaint().set(time.getPaint());
                    line.requestLayout();
                    line.invalidate();
                });
            }
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(context, 3);
        container.addView(line, params);
    }

    private static void start(View root) {
        TextView line = root.findViewWithTag(LINE_TAG);
        if (line == null) {
            addLine(root);
            line = root.findViewWithTag(LINE_TAG);
        }
        if (line == null) return;

        synchronized (RECEIVERS) {
            if (RECEIVERS.containsKey(root)) return;
            BatteryReceiver receiver = new BatteryReceiver(line);
            try {
                Intent sticky = root.getContext().registerReceiver(
                        receiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                RECEIVERS.put(root, receiver);
                if (sticky != null) update(line, sticky);
            } catch (Throwable t) {
                log("battery receiver failed", t);
            }
        }
    }

    private static void stop(View root) {
        BroadcastReceiver receiver;
        synchronized (RECEIVERS) {
            receiver = RECEIVERS.remove(root);
        }
        if (receiver == null) return;
        try {
            root.getContext().unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
    }

    private static void update(TextView line, Intent battery) {
        if (battery == null) {
            line.setText("");
            line.setVisibility(View.GONE);
            return;
        }
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        if (!charging) {
            line.setText("");
            line.setVisibility(View.GONE);
            return;
        }

        String text = buildText(line.getContext(), battery);
        if (text.isEmpty()) {
            line.setVisibility(View.GONE);
        } else {
            line.setText(text);
            line.setVisibility(View.VISIBLE);
        }
    }

    static String buildText(Context context, Intent battery) {
        BatteryManager manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        int currentMicro = 0;
        if (manager != null) {
            currentMicro = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (currentMicro == 0 || currentMicro == Integer.MIN_VALUE) {
                currentMicro = manager.getIntProperty(
                        BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
            }
        }
        if (currentMicro == Integer.MIN_VALUE) currentMicro = 0;

        long currentAbs = Math.abs((long) currentMicro);
        int currentMa = (int) (currentAbs > 10_000L ? currentAbs / 1000L : currentAbs);
        int voltageMv = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        int tempTenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);

        StringBuilder text = new StringBuilder();
        if (currentMa > 0 && voltageMv > 0) {
            append(text, String.format(Locale.US, "%.1fW",
                    currentMa * (double) voltageMv / 1_000_000.0));
        }
        if (currentMa > 0) {
            append(text, currentMa + "mA");
        }
        if (voltageMv > 0) {
            double volts = voltageMv > 100 ? voltageMv / 1000.0 : voltageMv;
            append(text, String.format(Locale.US, "%.2fV", volts));
        }
        if (tempTenths > 0) {
            append(text, String.format(Locale.US, "%.1f\u00B0C", tempTenths / 10.0));
        }
        return text.toString();
    }

    private static void append(StringBuilder text, String part) {
        if (text.length() > 0) text.append(" \u00B7 ");
        text.append(part);
    }

    private static int resourceId(Context context, String name) {
        try {
            int id = context.getResources().getIdentifier(name, "id", context.getPackageName());
            if (id == 0) id = context.getResources().getIdentifier(name, "id", AOD_PACKAGE);
            if (id == 0) id = context.getResources().getIdentifier(name, "id", "com.android.systemui");
            return id;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void log(String message, Throwable t) {
        XposedBridge.log(TAG + ": charging info " + message + ": " + t);
    }

    private static final class BatteryReceiver extends BroadcastReceiver {
        private final WeakReference<TextView> mLine;

        BatteryReceiver(TextView line) {
            mLine = new WeakReference<>(line);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            TextView line = mLine.get();
            if (line != null) update(line, intent);
        }
    }
}
