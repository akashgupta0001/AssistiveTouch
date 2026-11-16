# AssistiveTouch for Android

An iPhone-style AssistiveTouch floating button for Android 8.0+.

## Features
- Translucent floating button that you can drag anywhere. It snaps to the nearest edge and fades when you're not using it.
- Tap it to open a menu with Notifications, Control Centre, Screenshot, Back, Home, Recents, Volume −/+, Lock Screen, Flashlight, Power and Customise.
- You can set a double-tap action and a long-press action, including Split Screen.
- Adjustable button size and idle opacity.
- No ads, no internet permission, no extra libraries.

## Build the APK

### Option A: GitHub, no PC tools needed
1. Create a new GitHub repository and upload everything in this folder, including the hidden `.github` folder.
2. Open the **Actions** tab. The "Build APK" workflow starts by itself. You can also start it with **Run workflow**.
3. When it finishes (about 3–5 minutes), open the run and download **AssistiveTouch-apk**. Unzip it to get `app-release.apk`.

### Option B: Android Studio
1. In Android Studio, choose **File → Open** and select this folder.
2. Wait for Gradle sync to finish, then choose **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
3. You'll find the APK in `app/build/outputs/apk/`.

## Install and turn on
1. Copy the APK to your phone and open it. If Android asks, allow installs from that app.
2. Open **AssistiveTouch** and tap **Turn on in Accessibility settings**. Choose **AssistiveTouch** and switch it on.
3. **Android 13 and newer:** if the switch is greyed out ("Restricted setting"), go to **Settings → Apps → AssistiveTouch**. Tap **⋮** in the top-right corner, choose **Allow restricted settings**, then repeat step 2.

The app uses the Accessibility API only to perform the actions you tap. It does not read what's on your screen.
