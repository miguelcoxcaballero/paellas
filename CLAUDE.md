@AGENTS.md

The shared Android installer in `vendor/inhouse-services-updater/` (and its synced copy in `android/app/src/main/java/com/inhouse/services/updater/`) is FROZEN: read @vendor/inhouse-services-updater/FROZEN_INSTALLER.md before touching it. If a task seems to need it, stop and ask the user.

The Android app is a WebView shell around https://paellas.net: read `android/README.md` before changing `android/`, the sign-in return (`app-auth.html`, the early script in `index.html`) or anything about signing.
