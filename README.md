# ForceKill Focused

LSPosed module for **OneUI 8.5 and OneUI 9** that adds **Force stop** and **Restart** items to the per-task popup menu in Recents, directly below **App info**.

- **Force stop** closes the menu and force-stops that one app (the tapped task only). Its Recents card is deliberately left in place - only the process is stopped, so the carousel never has to re-layout behind the menu and cannot get stuck.
- **Restart** force-stops the same app and then starts it again, so it comes back cold in the foreground.
- **Screenshot** saves a picture of the app itself into `Pictures/Screenshots`. It is the task's own snapshot, not the current screen, so the recents UI and the menu are never in it.

## Requirements

- Samsung device on OneUI 8.5
- Root via one of:
  - Magisk + Zygisk + LSPosed
  - KernelSU + ZygiskNext + LSPosed

There are no separate Magisk/KernelSU builds. It's a plain LSPosed APK hooking `com.android.systemui` (and the OneUI launcher), so it works anywhere LSPosed runs.

## Install

1. Download the APK from [Releases](https://github.com/1ndevelopment/lsp-forcekill-focused/releases) and install it.
2. In LSPosed Manager, go to **Modules** and enable **ForceKill Focused**.
3. Set the scope to `com.android.systemui` and `com.sec.android.app.launcher`.
4. Reboot, or restart SystemUI.
5. Open Recents, tap a task's icon, then tap **Force stop**, **Restart** or **Screenshot**.

## How it works

Everything lives in one file, `MainHook.java`.

- **Menu injection:** OneUI's task menu is a custom DataBinding view (`task_menu_layout` containing `task_menu_item_layout` rows), not a framework `PopupMenu`. This was verified via on-device resource and stack-trace inspection.
- **Hook point:** `LayoutInflater.inflate()` is hooked. Each `task_menu_item_layout` inflation schedules a `post()` that clones the rows below the App info row, which is matched by its `task_option_app_info` string. A marker tag per row ensures each is added only once.
- **Anchor row:** App info is used rather than the pin row because it is present for every task and its title never changes. OneUI 9 renamed the old Keep open row to Pin to Recents (`task_option_pin_to_recents`), which is why the previous anchor stopped matching.
- **Target resolution**, in order:
  1. The tapped card's `TaskView.getTaskPackageName()` (exact)
  2. The view-tag chain (`TaskIconView` args)
  3. The top non-system recent task
- **Stop sequence:** hidden `ActivityManager.forceStopPackage()`, with a `su am force-stop` fallback if the app survives. This runs inside the hooked process (SystemUI / launcher), which holds the required permission.
- **Restart:** the same stop sequence, then the package's launch intent is started with `FLAG_ACTIVITY_NEW_TASK` on the main thread, so the app comes back as a fresh process.
- **Screenshot:** the card's `TaskSceneView` holds the task snapshot (`TaskSceneData.thumbnail`), which is copied out of a hardware bitmap and written through `MediaStore` into `Pictures/Screenshots`. If a task has no snapshot to hand, the card's on-screen pixels are copied with `PixelCopy` and cropped to the card instead.

## Troubleshooting

Recents internals change between OneUI builds.

- **Button doesn't appear:** get the Recents activity name and check that it matches the module's substring filter:
  ```sh
  adb shell dumpsys activity activities | grep -i recents

