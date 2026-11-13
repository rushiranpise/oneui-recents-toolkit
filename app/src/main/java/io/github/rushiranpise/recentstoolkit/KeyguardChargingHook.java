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
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Adds the charging details line to the keyguard, below the stock charging
 * percentage and time-to-full text.
 */
final class KeyguardChargingHook {
    private static final String TAG = "RecentsToolkit";
    private static final String LINE_TAG = "recentstoolkit:keyguard_charging_details";
    private static final Map<View, BroadcastReceiver> RECEIVERS = new WeakHashMap<>();

    private KeyguardChargingHook() {
    }

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> controller = Class.forName(
                    "com.android.systemui.statusbar.KeyguardIndicationController",
                    false, lpparam.classLoader);

            XposedBridge.hookMethod(
                    controller.getDeclaredMethod("setIndicationArea", ViewGroup.class),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length > 0 && param.args[0] instanceof ViewGroup) {
                                    addLine((ViewGroup) param.args[0]);
                                }
                            } catch (Throwable t) {
                                log("add failed", t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": keyguard charging hook installed");
        } catch (Throwable t) {
            log("hooks skipped", t);
        }
    }

    private static void addLine(ViewGroup area) {
        Context context = area.getContext();
        int areaId = context.getResources().getIdentifier(
                "keyguard_indication_area", "id", "com.android.systemui");
        if (areaId != 0) {
            View inner = area.findViewById(areaId);
            if (inner instanceof LinearLayout) area = (LinearLayout) inner;
        }
        if (!(area instanceof LinearLayout)) return;

        LinearLayout container = (LinearLayout) area;
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

        int stockId = context.getResources().getIdentifier(
                "keyguard_indication_text_bottom", "id", "com.android.systemui");
        if (stockId != 0) {
            View stockView = container.findViewById(stockId);
            if (stockView instanceof TextView) {
                TextView stock = (TextView) stockView;
                line.setTextSize(TypedValue.COMPLEX_UNIT_PX, stock.getTextSize());
                line.setTextColor(stock.getTextColors());
                line.setIncludeFontPadding(stock.getIncludeFontPadding());
                line.setAlpha(stock.getAlpha());
                line.setLetterSpacing(stock.getLetterSpacing());
                line.setTextScaleX(stock.getTextScaleX());
                line.getPaint().set(stock.getPaint());
                Typeface typeface = stock.getTypeface();
                if (typeface != null) line.setTypeface(typeface);
                line.post(() -> {
                    line.setTextSize(TypedValue.COMPLEX_UNIT_PX, stock.getTextSize());
                    line.setTextColor(stock.getTextColors());
                    line.setLetterSpacing(stock.getLetterSpacing());
                    line.setTextScaleX(stock.getTextScaleX());
                    line.getPaint().set(stock.getPaint());
                    line.requestLayout();
                    line.invalidate();
                });
            }
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(context, 2);
        container.addView(line, params);

        container.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                start(container, line);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                stop(container);
            }
        });
        if (container.isAttachedToWindow()) start(container, line);
    }

    private static void start(ViewGroup area, TextView line) {
        synchronized (RECEIVERS) {
            if (RECEIVERS.containsKey(area)) return;
            BatteryReceiver receiver = new BatteryReceiver(line);
            try {
                Intent sticky = area.getContext().registerReceiver(
                        receiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                RECEIVERS.put(area, receiver);
                if (sticky != null) update(line, sticky);
            } catch (Throwable t) {
                log("battery receiver failed", t);
            }
        }
    }

    private static void stop(ViewGroup area) {
        BroadcastReceiver receiver;
        synchronized (RECEIVERS) {
            receiver = RECEIVERS.remove(area);
        }
        if (receiver == null) return;
        try {
            area.getContext().unregisterReceiver(receiver);
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

        String text = ChargingInfoHook.buildText(line.getContext(), battery);
        if (text.isEmpty()) {
            line.setVisibility(View.GONE);
        } else {
            line.setText(text);
            line.setVisibility(View.VISIBLE);
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void log(String message, Throwable t) {
        XposedBridge.log(TAG + ": keyguard charging " + message + ": " + t);
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
