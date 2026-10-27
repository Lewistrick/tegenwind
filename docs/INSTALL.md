# Getting the app on the phone

Every push to `main` builds the app on GitHub and publishes it as a release
(`.github/workflows/release.yml`). The phone installs it from there, no computer or cable needed.

## Once: the signing key

Android only installs an update over the app if both are signed with the same key. The app on the
phone was built by Android Studio and signed with that computer's debug key, so GitHub needs a copy
of it. Without it the build stops rather than publish an app that can't update yours (installing
it would mean uninstalling first, which deletes your rides).

1. On the computer that built the app on your phone, turn the key into text:
   - Linux: `base64 -w0 ~/.android/debug.keystore`
   - macOS: `base64 -i ~/.android/debug.keystore`
   - Windows (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore"))`
2. On GitHub: the repo → Settings → Secrets and variables → Actions → New repository secret.
   Name `DEBUG_KEYSTORE_BASE64`, value the text from step 1.
3. Run the workflow once to check: Actions → Release → Run workflow.

### Live sharing

Builds from GitHub have live sharing off: the release is public, and anyone who downloads the APK
could read the key inside it. For live sharing, build and install from Android Studio with the
key in `local.properties`.

## Once: Obtainium on the phone

1. Install [Obtainium](https://obtainium.imranr.dev) (from its GitHub releases or F-Droid).
2. Add app → `https://github.com/Lewistrick/tegenwind` → Add.
3. Obtainium checks for new releases and installs them when you tap Update. Allow it to install
   apps when Android asks.

You can also open the latest release on GitHub in the phone's browser and tap the APK.

## Version numbers

The version is the number of commits (`1.0.<count>`), set in `app/build.gradle.kts`. A build of a
newer `main`, from GitHub or from Android Studio, always counts higher than an older one, so it
installs over it. Installing a build of an older commit than the one on the phone fails as a
downgrade.
