package io.github.rushiranpise.recentstoolkit;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.ref.WeakReference;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Adds a Device care style memory summary to the Recents screen.
 *
 * The card is separate from the task menu in MainHook. It is installed in the
 * Recents process, refreshed while Recents is resumed, and removed with the
 * activity. If One UI X's old memory line is present, the card is moved above
 * its tagged TextView instead of covering it.
 */
final class MemoryCardHook {
    private static final String TAG = "RecentsToolkit";
    private static final String OLD_MEMORY_TAG = "oneuix:recents_memory";
    private static final String CARD_TAG = "recentstoolkit:memory_card";
    private static final long REFRESH_INTERVAL_MS = 2_000L;
    private static final double GB = 1024.0 * 1024.0 * 1024.0;
    private static final int CARD_WIDTH_DP = 320;
    private static final int CARD_SIDE_PADDING_DP = 2;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> sActivity = new WeakReference<>(null);
    private static WeakReference<LinearLayout> sCard = new WeakReference<>(null);
    private static TextView sPercent;
    private static TextView sValue;
    private static TextView sReserved;
    private static TextView sSwap;
    private static MemoryBarView sBar;
    private static ActivityManager sActivityManager;
    private static ContentResolver sResolver;

    private static final Runnable REFRESH = new Runnable() {
        @Override
        public void run() {
            LinearLayout card = sCard.get();
            if (card == null || !card.isAttachedToWindow()) return;
            update();
            MAIN.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private MemoryCardHook() {
    }

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> recentsActivity = Class.forName(
                    "com.android.quickstep.RecentsActivity", false, lpparam.classLoader);

            hook(recentsActivity, "onCreate", new Class<?>[]{Bundle.class}, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        addCard((Activity) param.thisObject);
                    } catch (Throwable t) {
                        log("add failed", t);
                    }
                }
            });
            hook(recentsActivity, "onResume", new Class<?>[0], new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    startRefreshing();
                }
            });
            hook(recentsActivity, "onPause", new Class<?>[0], new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sActivity.get() == param.thisObject) stopRefreshing();
                }
            });
            hook(recentsActivity, "onDestroy", new Class<?>[0], new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sActivity.get() == param.thisObject) {
                        stopRefreshing();
                        removeCard();
                    }
                }
            });
            XposedBridge.log(TAG + ": memory card hooks installed in " + lpparam.packageName);
        } catch (Throwable t) {
            log("hooks skipped", t);
        }
    }

    private static void hook(
            Class<?> type, String name, Class<?>[] args, XC_MethodHook callback) {
        try {
            XposedBridge.hookMethod(type.getDeclaredMethod(name, args), callback);
        } catch (Throwable t) {
            log("hook " + name + " skipped", t);
        }
    }

    private static void addCard(Activity activity) {
        removeCard();
        View decorView = activity.getWindow().getDecorView();
        if (!(decorView instanceof ViewGroup)) return;

        sActivity = new WeakReference<>(activity);
        sActivityManager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        sResolver = activity.getContentResolver();

        LinearLayout card = buildCard(activity);
        sCard = new WeakReference<>(card);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(activity, CARD_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = bottomMargin(activity, decorView);
        ((ViewGroup) decorView).addView(card, params);

        card.post(MemoryCardHook::positionCard);
        update();
    }

    private static void removeCard() {
        LinearLayout card = sCard.get();
        if (card != null && card.getParent() instanceof ViewGroup) {
            ((ViewGroup) card.getParent()).removeView(card);
        }
        sCard = new WeakReference<>(null);
        sPercent = null;
        sValue = null;
        sReserved = null;
        sSwap = null;
        sBar = null;
        sActivity = new WeakReference<>(null);
        sActivityManager = null;
        sResolver = null;
    }

    private static LinearLayout buildCard(Activity activity) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setTag(CARD_TAG);
        card.setPadding(dp(activity, CARD_SIDE_PADDING_DP), 0,
                dp(activity, CARD_SIDE_PADDING_DP), 0);

        TextView percent = text(activity, 20f, Color.WHITE);
        TextView used = text(activity, 11f, 0xB3FFFFFF);
        used.setText("used");
        TextView value = text(activity, 12f, 0xCCFFFFFF);
        value.setGravity(Gravity.END);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.BOTTOM);
        header.addView(percent);
        LinearLayout.LayoutParams usedParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        usedParams.setMarginStart(dp(activity, 3));
        header.addView(used, usedParams);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        valueParams.gravity = Gravity.END | Gravity.BOTTOM;
        header.addView(value, valueParams);

        MemoryBarView bar = new MemoryBarView(activity, 0xFF8AB4F8);

        TextView reserved = text(activity, 9.5f, 0x99FFFFFF);
        TextView swap = text(activity, 9.5f, 0x99FFFFFF);
        swap.setGravity(Gravity.END);

        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(reserved, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        footer.addView(swap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 6));
        barParams.topMargin = dp(activity, 6);
        card.addView(bar, barParams);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerParams.topMargin = dp(activity, 5);
        card.addView(footer, footerParams);

        sPercent = percent;
        sBar = bar;
        sValue = value;
        sReserved = reserved;
        sSwap = swap;
        return card;
    }

    private static TextView text(Activity activity, float sizeSp, int color) {
        TextView view = new TextView(activity);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setFontFeatureSettings("'tnum' 1, 'pnum' 0");
        return view;
    }

    private static void positionCard() {
        LinearLayout card = sCard.get();
        Activity activity = sActivity.get();
        if (card == null || activity == null || !(card.getParent() instanceof ViewGroup)) return;

        View decorView = activity.getWindow().getDecorView();
        int margin = bottomMargin(activity, decorView);
        View oldMemory = decorView.findViewWithTag(OLD_MEMORY_TAG);
        if (oldMemory != null && oldMemory.getVisibility() == View.VISIBLE) {
            int oldHeight = oldMemory.getHeight();
            if (oldHeight <= 0) oldHeight = oldMemory.getMeasuredHeight();
            if (oldHeight <= 0) oldHeight = dp(activity, 20);
            margin += oldHeight + dp(activity, 6);
        }

        ViewGroup.LayoutParams params = card.getLayoutParams();
        if (params instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams frameParams = (FrameLayout.LayoutParams) params;
            if (frameParams.bottomMargin != margin) {
                frameParams.bottomMargin = margin;
                card.setLayoutParams(frameParams);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static int bottomMargin(Context context, View decorView) {
        int bottom = 0;
        WindowInsets insets = decorView.getRootWindowInsets();
        if (insets != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bottom = Math.max(
                        insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
                        insets.getInsets(WindowInsets.Type.systemBars()).bottom);
            } else {
                bottom = insets.getSystemWindowInsetBottom();
            }
        }
        return bottom + dp(context, 12);
    }

    private static void startRefreshing() {
        MAIN.removeCallbacks(REFRESH);
        MAIN.post(REFRESH);
    }

    private static void stopRefreshing() {
        MAIN.removeCallbacks(REFRESH);
    }

    private static void update() {
        positionCard();
        ActivityManager manager = sActivityManager;
        if (manager == null) return;

        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        try {
            manager.getMemoryInfo(info);
        } catch (Throwable t) {
            log("getMemoryInfo failed", t);
            return;
        }

        long advertised = advertisedCapacity(info.totalMem);
        long used = Math.max(0L, info.totalMem - info.availMem);
        float fraction = advertised > 0 ? (float) used / advertised : 0f;
        if (sPercent != null) {
            int percent = Math.round(Math.min(1f, fraction) * 100f);
            sPercent.setText(percent + "%");
        }
        if (sBar != null) sBar.setFraction(fraction);
        if (sValue != null) sValue.setText(formatUsage(used, advertised));
        if (sReserved != null) {
            long reserved = Math.max(0L, advertised - info.totalMem);
            sReserved.setText(String.format(Locale.US, "Reserved: %.1f GB", reserved / GB));
        }

        long[] swap = readSwap();
        long configuredSwap = configuredSwap();
        boolean hasSwap = configuredSwap > 0 && swap[0] > 0;
        if (sSwap != null) {
            if (hasSwap) {
                long usedSwap = Math.max(0L, swap[0] - swap[1]);
                sSwap.setText("RAM Plus " + formatUsage(usedSwap, configuredSwap));
                sSwap.setVisibility(View.VISIBLE);
            } else {
                sSwap.setText("");
                sSwap.setVisibility(View.GONE);
            }
        }
    }

    private static long advertisedCapacity(long totalBytes) {
        double totalGb = totalBytes / GB;
        double capacityGb;
        if (totalGb > 14.5) {
            capacityGb = 16.0;
        } else if (totalGb > 10.0) {
            capacityGb = 12.0;
        } else if (totalGb > 7.0) {
            capacityGb = 8.0;
        } else if (totalGb > 5.0) {
            capacityGb = 6.0;
        } else {
            capacityGb = 4.0;
        }
        return (long) (capacityGb * GB);
    }

    private static String formatUsage(long usedBytes, long totalBytes) {
        return String.format(Locale.US, "%.1f GB / %.0f GB",
                usedBytes / GB, totalBytes / GB);
    }

    private static long[] readSwap() {
        long total = 0L;
        long free = 0L;
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("SwapTotal:")) {
                    total = parseKb(line);
                } else if (line.startsWith("SwapFree:")) {
                    free = parseKb(line);
                }
            }
        } catch (Throwable ignored) {
        }
        return new long[]{total, free};
    }

    private static long parseKb(String line) {
        try {
            String[] parts = line.trim().split("\\s+");
            return Long.parseLong(parts[1]) * 1024L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long configuredSwap() {
        ContentResolver resolver = sResolver;
        if (resolver == null) return 0L;
        try {
            return (long) Settings.Global.getInt(resolver, "ram_expand_size", 0)
                    * 1024L * 1024L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void log(String message, Throwable t) {
        XposedBridge.log(TAG + ": memory card " + message + ": " + t);
    }

    private static final class MemoryBarView extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float fraction;

        MemoryBarView(Context context, int color) {
            super(context);
            trackPaint.setColor(0x33FFFFFF);
            fillPaint.setColor(color);
        }

        void setFraction(float value) {
            fraction = Math.max(0f, Math.min(1f, value));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float radius = getHeight() / 2f;
            canvas.drawRoundRect(0f, 0f, getWidth(), getHeight(),
                    radius, radius, trackPaint);
            if (fraction > 0f) {
                canvas.drawRoundRect(0f, 0f, getWidth() * fraction, getHeight(),
                        radius, radius, fillPaint);
            }
        }
    }
}
