# AppLock

A small Android app that controls which apps can be used on a device and whether new apps can be installed, all behind a password. Handy for a tablet or phone that you want to keep limited to a set of apps.

**Download:** [AppLock.apk](https://github.com/flipphoneguy/applock/releases/latest/download/AppLock.apk) (Android 9 and up)

AppLock uses Android's built-in device owner controls. Nothing runs in the background: hidden apps, suspended apps and restrictions are all kept by Android itself, so AppLock only does anything while it's open.

## Features

*   **Password lock**: Tap **Unlock** and enter the password to make changes. It stays unlocked until you tap **Lock** or the app is closed (swiped away from recents or the device restarts). While locked, everything is visible but greyed out. Passwords are only ever shown as dots unless you tick **Show password**.
*   **Allow installing apps**: One switch that blocks all app installs and updates, including the Play Store.
*   **Suspend**: Greys out an app's icon. Opening it shows a "blocked" message.
*   **Hide**: Removes an app completely, including from Settings. Its data is kept, and it comes back exactly as it was when unhidden. Hidden apps always stay in AppLock's list so they can be unhidden.
*   **Filters and search**: Launchable (the default), Installed, System, Hidden, Suspended and All. Search matches the app name or package name.
*   **Factory reset blocked**: Resetting the device from Settings is blocked while AppLock is active.
*   **No extra users**: Adding users, guests, work profiles or Secure Folder is blocked, since AppLock's rules only cover the main user.
*   **Protected from Settings**: Force stop, Clear storage and Uninstall are disabled for AppLock.
*   **Updates**: AppLock can check for a new version each time it starts and offer to install it. You can also check manually, or install an update from an APK file. Only genuine, newer versions of AppLock are accepted.
*   **Light and dark theme**: Follows the system, or pick one in Settings.
*   **D-pad and keyboard**: Everything can also be used with a d-pad or a keyboard's arrow keys.
*   **Phones and tablets**: On tablets the controls stay pinned above the app list. On phones everything scrolls together to leave more room for the list, with a button to jump back to the top.
*   **Remove AppLock**: Unhides and unsuspends everything, lifts all restrictions and uninstalls itself.

## Setup

AppLock has to be set as the device owner, which is done once from a computer with [adb](https://developer.android.com/tools/releases/platform-tools).

1.  Install AppLock on the device.
2.  Remove **every** account in Settings > Accounts (Google, Samsung and so on). Also remove Secure Folder and any other users or work profiles. Android won't allow a device owner while any of these exist.
3.  Turn on USB debugging in Developer options (to show Developer options, tap Build number 7 times in the About section of Settings). Connect the device and run:
    ```sh
    adb shell dpm set-device-owner com.flipphoneguy.applock/.AdminReceiver
    ```
4.  Open AppLock, tap **Unlock** and choose a password.
5.  Add your accounts back if you want.
6.  You can turn USB debugging off again.

## Using it

Open AppLock and tap **Unlock**. Flip the **Suspend** or **Hide** switch next to any app, and use **Allow installing apps** to block or allow installs. Tap **Lock** when you're done, or just close the app.

The ⚙ button opens Settings, where you can change the theme, check for updates, change the password and remove AppLock.

**Be careful what you hide.** Hiding the launcher, System UI or Settings can leave the device hard or impossible to use.

## Removing

Unlock, open Settings and tap **Remove AppLock**. If Android refuses to unhide some app, you get a list of them and can cancel or remove anyway. Apps left hidden that way can be brought back by setting up AppLock again.

## Build

No Gradle. `./build.sh` builds the APK on the phone in Termux, using the setup from [app-template](https://github.com/flipphoneguy/app-template). The version comes from `VERSION`.

Builds signed with your own key can't be updated by the official releases, and the other way around, since AppLock only accepts updates signed with the same key.

## License

GPL-3.0
