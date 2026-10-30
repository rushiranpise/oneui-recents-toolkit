package io.github.ndevlabs.fkf;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.PopupWindow;
import android.app.Dialog;
import java.util.ArrayList;
import android.widget.TextView;
import android.os.Debug;

import java.util.concurrent.TimeUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "ForceKillFocused";
    private static final String TITLE = "Force stop";
    private static final String MARKER = "forcestop_item";

    // OneUI's task menu is a custom DataBinding view (task_menu_layout with
    // task_menu_item_layout rows), NOT a framework PopupMenu. Verified on-device.
    private static final Set<String> TARGETS = new HashSet<>();
    static {
        TARGETS.add("com.sec.android.app.launcher"); // OneUI
        TARGETS.add("com.android.systemui");
        TARGETS.add("com.android.launcher3");
        TARGETS.add("com.google.android.apps.nexuslauncher");
        TARGETS.add("com.miui.home");
    }

    private static final Set<String> SKIP = new HashSet<>();
    static {
        SKIP.add("android");
        SKIP.add("com.android.systemui");
        SKIP.add("com.android.launcher3");
        SKIP.add("com.sec.android.app.launcher");
        SKIP.add("com.google.android.apps.nexuslauncher");
        SKIP.add("com.miui.home");
        SKIP.add("io.github.ndevlabs.fkf");
    }

    private static int sItemLayoutId = -1; // -1 = unresolved, 0 = absent
    private static volatile String sMenuTaskPkg;
    private static volatile long sMenuTaskTime;
    private static volatile int sMenuTaskId = -1;
    private static final ArrayList<Object> sMenuHosts = new ArrayList<>(); // PopupWindow|Dialog
    private static volatile Class<?> sTaskViewClass;
    private static volatile boolean sTaskViewLookedUp;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGETS.contains(lpparam.packageName)) return;
        final String processPkg = lpparam.packageName;
        XposedBridge.log(TAG + ": loaded in " + processPkg);

        // The menu's task: TaskIconView (the card's icon) builds the rows.
        // Its TaskView ancestor is the card; getTaskPackageName() names the task.
        // Menu build is always the most recent writer before a tap lands.
        try {
            Class<?> tiv = Class.forName(
                    "com.honeyspace.ui.honeypots.tasklist.presentation.TaskIconView",
                    false, lpparam.classLoader);
            XposedBridge.hookAllMethods(tiv, "c", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.thisObject instanceof View)) return;
                        View card = findTaskCard((View) param.thisObject);
                        if (card == null) return;
                        Object pkg = XposedHelpers.callMethod(card, "getTaskPackageName");
                        if (pkg instanceof String && !SKIP.contains(pkg)) {
                            sMenuTaskPkg = (String) pkg;
                            sMenuTaskTime = System.currentTimeMillis();
                            try {
                                Object tid = XposedHelpers.callMethod(card, "getFirstTaskId");
                                sMenuTaskId = tid instanceof Number ? ((Number) tid).intValue() : -1;
                            } catch (Throwable t) {
                                sMenuTaskId = -1;
                                XposedBridge.log(TAG + ": getFirstTaskId failed: " + t);
                            }
                            XposedBridge.log(TAG + ": menu task pkg=" + sMenuTaskPkg
                                    + " id=" + sMenuTaskId);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": TaskIconView hook skipped: " + t);
        }

        // Record popup/dialog hosts so the menu can be dismissed after a tap.
        XC_MethodHook recordHost = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                synchronized (sMenuHosts) {
                    for (int i = sMenuHosts.size() - 1; i >= 0; i--) {
                        if (sMenuHosts.get(i) == null) sMenuHosts.remove(i);
                    }
                    if (sMenuHosts.size() > 8) sMenuHosts.remove(0);
                    sMenuHosts.add(param.thisObject);
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(PopupWindow.class, "showAsDropDown", recordHost);
            XposedBridge.hookAllMethods(PopupWindow.class, "showAtLocation", recordHost);
            XposedBridge.hookAllMethods(Dialog.class, "show", recordHost);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": host hooks skipped: " + t);
        }

        // Every task_menu_item_layout inflation is a menu row being built.
        XposedHelpers.findAndHookMethod(LayoutInflater.class, "inflate",
                int.class, ViewGroup.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.getResult() instanceof View)) return;
                        View row = (View) param.getResult();
                        if (sItemLayoutId == -1) {
                            try {
                                sItemLayoutId = row.getResources().getIdentifier(
                                        "task_menu_item_layout", "layout", processPkg);
                            } catch (Throwable ignored) {
                                return;
                            }
                        }
                        if (sItemLayoutId == 0 || ((int) param.args[0]) != sItemLayoutId) return;
                        // ponytail: post() runs after the synchronous menu build, so all
                        // rows (incl. App info with bound titles) are attached by then.
                        row.post(() -> insertBelowAnchor(row, processPkg));
                    }
                });
    }

    private static void insertBelowAnchor(View row, String processPkg) {
        try {
            ViewParent pp = row.getParent();
            if (!(pp instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) pp;
            for (int i = 0; i < parent.getChildCount(); i++) {
                if (MARKER.equals(parent.getChildAt(i).getTag())) return; // done already
            }
            Context ctx = parent.getContext();
            int anchorIndex = indexOfAnchor(parent, anchorTitle(ctx, processPkg));
            if (anchorIndex < 0) {
                XposedBridge.log(TAG + ": app info row not found, skipping");
                return;
            }
            View fk = LayoutInflater.from(ctx).inflate(sItemLayoutId, parent, false);
            TextView tv = findTextView(fk);
            if (tv != null) tv.setText(TITLE);
            fk.setTag(MARKER);
            fk.setOnClickListener(v -> {
                Context c = v.getContext();
                String pkg = pkgFromChain(v);
                if (pkg == null && System.currentTimeMillis() - sMenuTaskTime < 60000) {
                    pkg = sMenuTaskPkg;
                }
                if (pkg == null) pkg = topRecentPkg(c);
                if (pkg == null) {
                    return;
                }
                final String target = pkg;
                final Context fctx = c;
                dismissMenuPopup();
                // The app is stopped in the background so the menu closes straight away. The
                // recents card is deliberately left alone: the task stays in recents, only its
                // process is stopped, so the carousel keeps its layout and never gets stuck.
                new Thread(() -> forceStopPkg(fctx, target), "ForceStop").start();
            });
            parent.addView(fk, anchorIndex + 1);
            XposedBridge.log(TAG + ": Force stop inserted below App info");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": insert failed: " + t);
        }
    }

    /** Nearest TaskView ancestor (the recents card), or null. */
    private static View findTaskCard(View v) {
        try {
            if (!sTaskViewLookedUp) {
                sTaskViewLookedUp = true;
                try {
                    sTaskViewClass = Class.forName(
                            "com.honeyspace.ui.honeypots.tasklist.presentation.TaskView",
                            false, v.getClass().getClassLoader());
                } catch (Throwable ignored) {}
            }
            if (sTaskViewClass == null) return null;
            View cur = v;
            for (int d = 0; d < 12 && cur != null; d++) {
                if (sTaskViewClass.isInstance(cur)) return cur;
                ViewParent p = cur.getParent();
                cur = p instanceof View ? (View) p : null;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Dismiss the task menu popup like the other buttons do. */
    private static void dismissMenuPopup() {
        try {
            synchronized (sMenuHosts) {
                for (Object h : sMenuHosts) {
                    try {
                        if (h instanceof PopupWindow) {
                            PopupWindow pw = (PopupWindow) h;
                            if (pw.isShowing() && containsMarker(pw.getContentView())) {
                                pw.dismiss();
                            }
                        } else if (h instanceof Dialog) {
                            Dialog d = (Dialog) h;
                            if (d.isShowing()) {
                                android.view.Window w = d.getWindow();
                                if (w != null && containsMarker(w.getDecorView())) d.dismiss();
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    private static boolean containsMarker(View v) {
        if (v == null) return false;
        try {
            if (MARKER.equals(v.getTag())) return true;
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (containsMarker(g.getChildAt(i))) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Index of the menu row this module sits under, which is the App info row.
     *
     * App info is the anchor rather than the pin row: it is in the menu for every task and its
     * title never flips, while the pin row reads "Unpin from Recents" once a task is pinned.
     */
    private static int indexOfAnchor(ViewGroup parent, String anchorTitle) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            TextView tv = findTextView(parent.getChildAt(i));
            if (tv == null || tv.getText() == null) continue;
            String t = tv.getText().toString();
            if (anchorTitle != null ? t.equals(anchorTitle)
                    : t.toLowerCase().contains("app info")) return i;
        }
        return -1;
    }

    private static String anchorTitle(Context ctx, String processPkg) {
        try {
            int id = ctx.getResources().getIdentifier(
                    "task_option_app_info", "string", processPkg);
            if (id != 0) return ctx.getResources().getString(id);
        } catch (Throwable ignored) {}
        return null;
    }

    private static TextView findTextView(View v) {
        if (v instanceof TextView) return (TextView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView t = findTextView(g.getChildAt(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    /** Exact task: walk the view chain for a tagged Task/ComponentName. */
    private static String pkgFromChain(View v) {
        try {
            View cur = v;
            for (int d = 0; d < 12 && cur != null; d++) {
                String pkg = pkgFrom(cur.getTag());
                if (pkg != null && !SKIP.contains(pkg)) return pkg;
                ViewParent p = cur.getParent();
                cur = p instanceof View ? (View) p : null;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Fallback: top non-system recent = carousel-centered app. */
    private static String topRecentPkg(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RecentTaskInfo> recents = am.getRecentTasks(20, 0);
            if (recents != null) {
                String self = ctx.getPackageName();
                for (ActivityManager.RecentTaskInfo t : recents) {
                    ComponentName c = t.baseIntent != null ? t.baseIntent.getComponent() : null;
                    if (c == null) continue;
                    String pkg = c.getPackageName();
                    if (pkg.equals(self) || SKIP.contains(pkg)) continue;
                    return pkg;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String pkgFrom(Object o) {
        if (o == null) return null;
        // The row carries this module's own marker as its tag, so it must not be read as a package.
        if (o instanceof String) {
            String s = (String) o;
            return MARKER.equals(s) ? null : s;
        }
        if (o instanceof ComponentName) return ((ComponentName) o).getPackageName();
        try {
            Object v = XposedHelpers.getObjectField(o, "packageName");
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) {}
        try {
            Object v = XposedHelpers.callMethod(o, "getPackageName");
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) {}
        try {
            Object v = XposedHelpers.callMethod(o, "getPackage");
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) {}
        return null;
    }

    private static void forceStopPkg(Context ctx, String pkg) {
        long beforeKb = pssOf(ctx, pkg);
        // 1. hidden forceStopPackage (launcher holds FORCE_STOP_PACKAGES).
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.class.getMethod("forceStopPackage", String.class).invoke(am, pkg);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": forceStopPackage failed: " + t);
        }
        sleep(400);
        // 2. root fallback: LSPosed implies root (Magisk or KernelSU).
        if (!isDead(ctx, pkg)) {
            try {
                Process p = Runtime.getRuntime().exec(
                        new String[]{"su", "-c", "am force-stop " + pkg});
                p.waitFor(10, TimeUnit.SECONDS);
                p.destroy();
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": su force-stop failed: " + t);
            }
            sleep(400);
        }
        // The recents card is left in place on purpose: the task keeps its entry, only the
        // process is stopped, so nothing has to re-layout the carousel behind the menu.
        XposedBridge.log(TAG + ": force stopped pkg=" + pkg
                + " dead=" + isDead(ctx, pkg) + " freedKb=" + beforeKb);
    }

    /** Sum of the target's process PSS before it is stopped. */
    private static long pssOf(Context ctx, String pkg) {
        long total = 0;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return 0;
            int count = 0;
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (p.pkgList != null) {
                    for (String p2 : p.pkgList) {
                        if (pkg.equals(p2)) {
                            count++;
                            break;
                        }
                    }
                }
            }
            if (count == 0) return 0;
            int[] pids = new int[count];
            int i = 0;
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (p.pkgList != null) {
                    for (String p2 : p.pkgList) {
                        if (pkg.equals(p2)) {
                            pids[i++] = p.pid;
                            break;
                        }
                    }
                }
            }
            Debug.MemoryInfo[] infos = am.getProcessMemoryInfo(pids);
            if (infos != null) {
                for (Debug.MemoryInfo mi : infos) {
                    if (mi != null) total += mi.getTotalPss();
                }
            }
        } catch (Throwable ignored) {}
        return total;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isDead(Context ctx, String pkg) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo p : procs) {
                    if (p.pkgList != null) {
                        for (String p2 : p.pkgList) {
                            if (pkg.equals(p2)) return false;
                        }
                    }
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

}
