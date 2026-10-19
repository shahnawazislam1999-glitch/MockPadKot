# MockPad

Standalone Android app (Kotlin + WebView). Opens straight into **MockPad**, no launcher or lobby.

- App id: `com.raj.app.mockpad`
- Loads from: `https://shahnawazislam1999-glitch.github.io/mocktest/`
- First launch: a one-time download from GitHub (no permission pop-ups). After that it opens instantly and works offline.
- Every 6 hours (when online) it quietly refreshes its saved copy from GitHub; the new version is used the next time you open it.
- Press **Back twice** to exit.
- Files the page saves go to **Downloads/MockPad**.

## Get the APK

1. Create a new GitHub repository and push everything in this folder to the `main` branch.
2. Open the **Actions** tab. **Build MockPad APK** runs by itself (3 to 5 minutes).
3. Download `MockPad.apk` from **Releases > MockPad (latest build)**, or from the finished run > **Artifacts**.

Every push builds a new APK. New builds install over the old one (same signing key, rising version number).

## Change things

- Name: `app/src/main/res/values/strings.xml`
- Web address / files / fonts: `app/src/main/assets/apps.json`
- Permissions: `app/src/main/AndroidManifest.xml` (only Internet, network state and vibrate; nothing is asked on screen)
- Icon: replace `app/src/main/res/drawable-nodpi/ic_launcher_art.png` (432x432) and `app_logo.png`

The signing key `app/raj-hub.jks` is included on purpose for personal use. Do not use it for the Play Store.
