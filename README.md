# SteamFetch

An Android app that signs in to Steam and downloads a game's depot files (the raw game files)
into a normal folder on your phone or SD card. It is built on
[JavaSteam](https://github.com/Longi94/JavaSteam) and its depot downloader module, which follows the same
approach as SteamRE's DepotDownloader.

## What it does

- **Login:** username + password with Steam Guard (approve in the Steam mobile app, or type a code),
  or QR login scanned from the Steam mobile app. The password is never stored; only a Steam session
  token is kept, encrypted on the device.
- **Multiple accounts:** saved accounts appear on the login screen. "Switch account" returns there.
- **Family Sharing:** when you sign in, your library includes games shared by other accounts in your Steam
  Family group. They are tagged "Family Sharing" and can be filtered. For the "other account" case,
  sign in with the account that is the *borrower* (the one in the family group that has access).
- **Download:** pick a game, a platform (windows / linux / macos) and a folder. Files are written as plain
  files, one subfolder per game. Downloads run in a foreground service so they continue with the screen off.

## Build the APK without installing anything (GitHub Actions)

1. Create a free GitHub account and a new empty repository (private is fine).
2. Upload everything in this folder to the repository root (including the hidden `.github` folder).
   Using the GitHub website: "Add file" > "Upload files", drag in the contents of the folder.
3. Open the repository's **Actions** tab. The "Build APK" workflow runs on every push (or press "Run workflow").
4. When it finishes (a few minutes), open the run and download the **SteamFetch-debug-apk** artifact.
   Unzip it and install `app-debug.apk` on your phone (allow "install unknown apps" for your browser/file manager).

If the build fails, open the failing step in the log and send me the error text. This project was written
without being compiled, so a small fix-up round is expected, most likely in `DepotEngine.kt`
(see below).

## Build locally

Open the folder in Android Studio (a recent version), let it sync, then **Build > Build APK(s)**.
Or from a terminal with JDK 17 and Gradle 8.11+: `gradle assembleDebug`
(the APK is in `app/build/outputs/apk/debug/`).

## First run

1. Sign in.
2. Tap **Grant file access** (Android's "All files access" setting) so the app can write to shared storage.
3. Choose the platform. Most Steam games only have Windows builds, so keep "windows" unless a game ships a
   Linux or macOS build you want.
4. Tap **Download** next to a game. Allow notifications so you can see progress.

## Notes and limits

- Windows builds will not run natively on Android. You get the files; running them needs something like
  Winlator, or a native build of the game.
- Only games your account owns, or can access via Family Sharing, will download. Steam excludes some titles
  from Family Sharing, and it will not list games that are not in your family group.
- Android 15 limits "data sync" foreground services to about 6 hours per run, so a very large download on a
  slow connection may be cut off and need restarting.
- Steam's terms restrict unofficial clients, so use this at your own risk.
- Games with Steam DRM / Steamworks may still need Steam to launch even with the files present.

## Where to fix things if the build fails

- `app/src/main/java/com/example/steamfetch/DepotEngine.kt` is the only file that uses the
  `javasteam-depotdownloader` module. The class and listener names there (`DepotDownloader`, `AppItem`,
  `IDownloadListener` and its callbacks) are written from JavaSteam 1.8.0's documented layout and may need small
  adjustments (see the library's sample 023, "download app").
- `SteamManager.kt` uses JavaSteam's login and PICS (library) APIs.
- Dependency versions are in `app/build.gradle.kts`; if `zstd-jni` or another artifact fails to resolve, adjust
  its version to the one JavaSteam's `gradle/libs.versions.toml` lists.
