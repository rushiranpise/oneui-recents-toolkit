package io.github.rushiranpise.recentstoolkit;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContextWrapper;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.OutputStream;
import java.lang.ref.WeakReference;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
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

    private static final String TAG = "RecentsToolkit";
    private static final String TITLE = "Force stop";
    private static final String MARKER = "forcestop_item";
    private static final String RESTART_TITLE = "Restart";
    private static final String RESTART_MARKER = "restart_item";
    private static final String SHOT_TITLE = "Screenshot";
    private static final String SHOT_MARKER = "screenshot_item";

    /** Tags that belong to this module's own rows, which must never be read as a package name. */
    private static final Set<String> MARKERS = Set.of(MARKER, RESTART_MARKER, SHOT_MARKER);

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
    private static volatile WeakReference<View> sMenuTaskCard;
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
                            sMenuTaskCard = new WeakReference<>(card);
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
            if (hasRow(parent, MARKER) && hasRow(parent, RESTART_MARKER) && hasRow(parent, SHOT_MARKER)) {
                return; // done already
            }
            Context ctx = parent.getContext();
            int anchorIndex = indexOfAnchor(parent, anchorTitle(ctx, processPkg));
            if (anchorIndex < 0) {
                XposedBridge.log(TAG + ": app info row not found, skipping");
                return;
            }

            if (!hasRow(parent, MARKER)) {
                parent.addView(
                        menuRow(ctx, parent, TITLE, MARKER, MainHook::onForceStop),
                        anchorIndex + 1);
                anchorIndex++;
            }
            if (!hasRow(parent, RESTART_MARKER)) {
                parent.addView(
                        menuRow(ctx, parent, RESTART_TITLE, RESTART_MARKER, MainHook::onRestart),
                        anchorIndex + 1);
                anchorIndex++;
            }
            if (!hasRow(parent, SHOT_MARKER)) {
                parent.addView(
                        menuRow(ctx, parent, SHOT_TITLE, SHOT_MARKER, MainHook::onScreenshot),
                        anchorIndex + 1);
            }
            XposedBridge.log(TAG + ": Force stop, Restart and Screenshot inserted below App info");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": insert failed: " + t);
        }
    }

    /** Whether the menu already carries a row this module added. */
    private static boolean hasRow(ViewGroup parent, String tag) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (tag.equals(parent.getChildAt(i).getTag())) return true;
        }
        return false;
    }

    /**
     * One row of this module's own, built from the launcher's row layout so it matches the rows
     * around it, then named and given its action here.
     */
    private static View menuRow(
            Context ctx, ViewGroup parent, String title, String tag, View.OnClickListener action) {
        View row = LayoutInflater.from(ctx).inflate(sItemLayoutId, parent, false);
        TextView tv = findTextView(row);
        if (tv != null) tv.setText(title);
        row.setTag(tag);
        row.setOnClickListener(action);
        return row;
    }

    /** The task's package: the menu's recorded task first, then the view chain, then recents. */
    private static String menuTarget(View v) {
        Context c = v.getContext();
        String pkg = pkgFromChain(v);
        if (pkg == null && System.currentTimeMillis() - sMenuTaskTime < 60000) {
            pkg = sMenuTaskPkg;
        }
        if (pkg == null) pkg = topRecentPkg(c);
        return pkg;
    }

    /** Stops the task's process. The menu closes straight away and the recents card is left alone. */
    private static void onForceStop(View v) {
        String pkg = menuTarget(v);
        if (pkg == null) return;
        Context ctx = v.getContext();
        dismissMenuPopup();
        new Thread(() -> forceStopPkg(ctx, pkg), "ForceStop").start();
    }

    /** Stops the task's process, then starts it again, so the app comes back cold. */
    private static void onRestart(View v) {
        String pkg = menuTarget(v);
        if (pkg == null) return;
        Context ctx = v.getContext();
        dismissMenuPopup();
        new Thread(() -> restartPkg(ctx, pkg), "Restart").start();
    }

    /**
     * Saves a picture of the task itself, the way the Recents screenshot works on Pixel: the card's
     * own snapshot is drawn, so nothing that is currently on screen (the menu, the carousel) ends
     * up in it.
     */
    private static void onScreenshot(View v) {
        final Context ctx = v.getContext();
        final String pkg = menuTarget(v);
        final View card = sMenuTaskCard != null ? sMenuTaskCard.get() : null;
        dismissMenuPopup();
        if (card == null) {
            XposedBridge.log(TAG + ": screenshot: no card for the menu, nothing captured");
            return;
        }
        // The task's own snapshot is the picture of the app, so it is used before anything that is
        // on screen: it is the full resolution one, and it has no recents chrome around it.
        Bitmap snapshot = sceneBitmap(card, pkg);
        if (snapshot != null) {
            final Bitmap shot = snapshot;
            new Thread(() -> saveShot(ctx, shot, pkg), "Screenshot").start();
            return;
        }
        // Fallback: copy the pixels the card has on screen, once the menu is out of the way.
        mainPostDelayed(() -> pixelCopyCard(ctx, card, pkg), 350);
    }

    /**
     * The snapshot the card already holds (`TaskSceneData.thumbnail`), which is the app's own
     * picture at the size the task was captured. A hardware bitmap is copied out to a software one
     * so it can be written to a PNG, and the size the task was captured at is logged with it.
     */
    private static Bitmap sceneBitmap(View card, String pkg) {
        try {
            Object data = sceneData(card);
            if (data == null) return null;
            Object thumb = data.getClass().getMethod("getThumbnail").invoke(data);
            XposedBridge.log(TAG + ": screenshot: thumbnail=" + (thumb == null
                    ? "null" : thumb.getClass().getName()));
            if (!(thumb instanceof Bitmap)) return null;
            Bitmap bitmap = (Bitmap) thumb;
            if (bitmap.getConfig() == Bitmap.Config.HARDWARE) {
                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                if (bitmap == null) {
                    XposedBridge.log(TAG + ": screenshot: hardware bitmap would not copy");
                    return null;
                }
            }
            XposedBridge.log(TAG + ": screenshot: task snapshot for " + pkg
                    + " " + bitmap.getWidth() + "x" + bitmap.getHeight());
            return bitmap;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": screenshot: no task snapshot: " + t);
            return null;
        }
    }

    /**
     * The scene data holding the task's snapshot. It lives on the card's scene view rather than on
     * the card itself, so both are tried.
     */
    private static Object sceneData(View card) {
        View scene = snapshotView(card);
        for (Object candidate : new Object[] {scene, card}) {
            if (candidate == null) continue;
            try {
                Object data = candidate.getClass().getMethod("getTaskSceneData").invoke(candidate);
                // The scene view answers with one entry per task in the scene; a single task card
                // has one, and a split screen pair has one per app.
                if (data instanceof List) {
                    List<?> list = (List<?>) data;
                    for (Object entry : list) {
                        if (entry != null) return entry;
                    }
                    continue;
                }
                if (data != null) return data;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * Copies the card's on-screen pixels, for when the task has no snapshot to hand. The whole
     * window is copied and then cropped to the card, since PixelCopy only takes a window.
     */
    private static void pixelCopyCard(Context ctx, View card, String pkg) {
        try {
            final View target = snapshotView(card);
            if (target == null || target.getWidth() <= 0 || target.getHeight() <= 0) {
                XposedBridge.log(TAG + ": screenshot: card has nothing to copy");
                return;
            }
            final Window window = windowOf(ctx);
            if (window == null) {
                XposedBridge.log(TAG + ": screenshot: no window to copy from");
                return;
            }
            final View root = window.getDecorView();
            final int width = root.getWidth();
            final int height = root.getHeight();
            if (width <= 0 || height <= 0) return;
            final Bitmap full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            android.view.PixelCopy.request(window, full, result -> {
                if (result != android.view.PixelCopy.SUCCESS) {
                    XposedBridge.log(TAG + ": screenshot: copy failed with " + result);
                    return;
                }
                int[] at = new int[2];
                target.getLocationInWindow(at);
                int left = Math.max(0, at[0]);
                int top = Math.max(0, at[1]);
                int cropW = Math.min(target.getWidth(), width - left);
                int cropH = Math.min(target.getHeight(), height - top);
                if (cropW <= 0 || cropH <= 0) return;
                final Bitmap shot = Bitmap.createBitmap(full, left, top, cropW, cropH);
                XposedBridge.log(TAG + ": screenshot: copied " + cropW + "x" + cropH
                        + " for " + pkg);
                new Thread(() -> saveShot(ctx, shot, pkg), "Screenshot").start();
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": screenshot: copy failed: " + t);
        }
    }

    /** The window hosting the recents card, so its pixels can be copied. */
    private static Window windowOf(Context ctx) {
        Context c = ctx;
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return ((Activity) c).getWindow();
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** The largest visible child of the card, preferring one that actually draws an image. */
    private static View snapshotView(View card) {
        if (!(card instanceof ViewGroup)) return card;
        ViewGroup group = (ViewGroup) card;
        View best = null;
        long bestArea = 0;
        View bestImage = null;
        long bestImageArea = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            long area = (long) child.getWidth() * child.getHeight();
            XposedBridge.log(TAG + ": screenshot: child " + describe(child) + " area=" + area);
            if (area > bestArea) {
                bestArea = area;
                best = child;
            }
            if (child instanceof ImageView && area > bestImageArea) {
                bestImageArea = area;
                bestImage = child;
            }
        }
        // A thumbnail is the picture of the task; a larger plain container only holds it and the
        // unchanged recents chrome with it.
        return bestImage != null ? bestImage : best;
    }

    private static String describe(View view) {
        String id;
        try {
            int resId = view.getId();
            id = resId == View.NO_ID ? "(no id)"
                    : view.getResources().getResourceEntryName(resId);
        } catch (Throwable t) {
            id = "(id?)";
        }
        return view.getClass().getName() + "#" + id;
    }

    /** Writes the picture into Pictures/Screenshots through MediaStore, so Gallery picks it up. */
    private static void saveShot(Context ctx, Bitmap bitmap, String pkg) {
        String name = "Screenshot_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date()) + ".png";
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Screenshots");
            Uri uri = ctx.getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                XposedBridge.log(TAG + ": screenshot: MediaStore refused the entry");
                return;
            }
            try (OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("no stream for " + uri);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            XposedBridge.log(TAG + ": screenshot saved " + uri + " for " + pkg);
            mainPost(() -> toast(ctx, "Screenshot saved"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": screenshot save failed: " + t);
            mainPost(() -> toast(ctx, "Screenshot failed"));
        }
    }

    private static void mainPost(Runnable r) {
        mainPostDelayed(r, 0);
    }

    private static void mainPostDelayed(Runnable r, long delayMs) {
        try {
            new Handler(Looper.getMainLooper()).postDelayed(r, delayMs);
        } catch (Throwable ignored) {}
    }

    private static void toast(Context ctx, String text) {
        try {
            Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": toast failed: " + t);
        }
    }

    private static void restartPkg(Context ctx, String pkg) {
        forceStopPkg(ctx, pkg);
        final Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            XposedBridge.log(TAG + ": no launch intent for " + pkg);
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // Starting an activity belongs on the main thread, which is also where the menu just was.
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ctx.startActivity(launch);
                XposedBridge.log(TAG + ": restarted " + pkg);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": restart failed: " + t);
            }
        });
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
        // A row carries this module's own marker as its tag, so it must not be read as a package.
        if (o instanceof String) {
            String s = (String) o;
            return MARKERS.contains(s) ? null : s;
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
