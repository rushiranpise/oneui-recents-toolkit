# Recents Toolkit

LSPosed module for **OneUI 8.5 and OneUI 9** that adds **Force stop**, **Restart**, **Screenshot**, a Device care style **memory card** to Recents, and live **charging details** to the lock screen.

<img width="459" height="1021" alt="image" src="https://github.com/user-attachments/assets/9667c30a-1f89-49f9-b024-9e7b71538155" />


- **Force stop** closes the menu and force-stops that one app (the tapped task only). Its Recents card is deliberately left in place - only the process is stopped, so the carousel never has to re-layout behind the menu and cannot get stuck.
- **Restart** force-stops the same app and then starts it again, so it comes back cold in the foreground.
- **Screenshot** saves a picture of the app itself into `Pictures/Screenshots`. It is the task's own snapshot, not the current screen, so the recents UI and the menu are never in it.
- **Memory card** shows used and advertised RAM, a usage bar, reserved memory and RAM Plus above the navigation bar. It refreshes every 2 seconds while Recents is open. If One UI X's tagged memory line is present, the card sits above it.
- **Charging details** adds a line below One UI's charging percentage and time-to-full with live wattage, current, voltage and battery temperature. It appears while charging and updates from battery broadcasts.

## Requirements

- Samsung device on OneUI 8.5 or OneUI 9
- Root via one of:
  - Magisk + Zygisk + LSPosed
  - KernelSU + ZygiskNext + LSPosed

There are no separate Magisk/KernelSU builds. It's a plain LSPosed APK hooking `com.android.systemui` (and the OneUI launcher), so it works anywhere LSPosed runs.

## Install

1. Download the APK from this repository's [Releases](../../releases) and install it.
2. In LSPosed Manager, go to **Modules** and enable **Recents Toolkit**.
3. Set the scope to `com.android.systemui` and `com.sec.android.app.launcher`.
4. Reboot, or restart SystemUI.
5. Open Recents. The memory card appears at the bottom; tap a task's icon for the task menu. Lock the phone while charging to see the extra charging line.

## Credits

The original work this module grew out of is **ForceKill Focused** by the **1ndevelopment** team ([1ndevelopment/lsp-forcekill-focused](https://github.com/1ndevelopment/lsp-forcekill-focused)): the task-menu injection, the `LayoutInflater` hook, the card and target resolution, and the stop sequence all began there and are still the shape of the code here. This repository is that work, unmaintained upstream and carried forward for OneUI 9 on the developer's own device, with the anchor row, the kept Recents card, the Restart row and the Screenshot row added on top. Thank you to 1ndevelopment for the foundation.

## How it works

The task menu lives in `MainHook.java`; the memory card lives in `MemoryCardHook.java`; the charging line lives in `ChargingInfoHook.java`.

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
- **Memory card:** `RecentsActivity.onCreate()` adds a card to the decor view, `onResume()` starts a 2-second refresh, and `onPause()` stops it. RAM comes from `ActivityManager.MemoryInfo`; the advertised capacity is rounded from `totalMem` because `advertisedMem` reports the wrong size on some firmware. RAM Plus comes from `Settings.Global.ram_expand_size`; swap comes from `/proc/meminfo`. The card detects the `oneuix:recents_memory` view and offsets itself above it.
- **Charging details:** on the lock screen, `KeyguardIndicationController.setIndicationArea()` appends a TextView below `keyguard_indication_text_bottom`. On AOD, `PluginAODManager.setAODPlugin()` gets the AOD plugin class loader and `ChargingInfoView.onFinishInflate()` appends the same line below the stock time-to-full text. `ACTION_BATTERY_CHANGED`, `BatteryManager` current, voltage and temperature update both while charging.

## Troubleshooting

Recents internals change between OneUI builds.

- **Button doesn't appear:** get the Recents activity name and check that it matches the module's substring filter:
  ```sh
  adb shell dumpsys activity activities | grep -i recents
  ```
  Then check the LSPosed log for `RecentsToolkit`.
- **Memory card doesn't appear:** confirm the module scope includes `com.sec.android.app.launcher` and that the Recents activity is `com.android.quickstep.RecentsActivity`. Check the LSPosed log for `RecentsToolkit: memory card`.
- **Two memory views:** leave One UI X's memory line enabled and the card will sit above it, or turn that line off if you only want the card.
- **Charging line doesn't appear:** confirm the module scope includes `com.android.systemui`, the AOD plugin loaded, and the LSPosed log contains `RecentsToolkit: charging info`.
