# Sharable for Android (test build)

Build: needs JDK 17 and the Android SDK (platform 34, build-tools 34). Create a throwaway key:
`keytool -genkeypair -keystore debug.keystore -storepass sharable-test -keypass sharable-test -alias sharable -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Sharable Test"`
then `gradle assembleRelease` (output app/build/outputs/apk/release/app-release.apk). The web UI is shared with the laptop version (../public).

What it does: a foreground service runs the same streaming server as the laptop version (PIN, per-piece SHA-256 checked resumable uploads, Range downloads, bounded memory), keeps Wi-Fi and CPU awake while hosting, and lets you share files straight from the phone without copying. A built-in page viewer connects to another Sharable (laptop or phone).

NOT verified: running on a real phone or emulator (none available when this was built). Server logic was tested on a desktop JVM with the same tests as the laptop version. Real Wi-Fi speed and 10 GB over Wi-Fi are not measured. Received files are stored in the app folder (Android/data/com.sharable.app/files/Sharable-files), downloads via the page viewer go to Downloads.
