# Syncthing inside OmaLauncher (ADB-free recovery)

This integration is intended for a user-managed OmaLauncher debug build with an existing Arch Linux rootfs and a configured Syncthing installation. Syncthing is **not** bundled or installed automatically, and existing folders/configuration are never rewritten.

## Behavior

- Android's existing `LinuxService` foreground service owns a `SyncthingSupervisor`.
- Once the Linux guest reports `Installed`, if `/usr/bin/syncthing` and `/root/.local/state/syncthing/config.xml` exist, it starts the existing Syncthing 2.x daemon as a PRoot child. The daemon therefore survives opening/closing guest terminal windows.
- Every 60 seconds the service checks its loopback-only API, `127.0.0.1:8384`, using the private API key from the app's own Syncthing config file.
- Three consecutive checks reporting no connected peers trigger a clean shutdown and restart, subject to a 10-minute cooldown. A missing daemon is started on detection.
- The persistent Linux session notification includes **Reconnect Sync**. Tapping it immediately requests a graceful restart without M4, SSH or ADB.
- All existing Syncthing device addresses and the user's Tailscale configuration remain unchanged. No additional listener or remotely exposed HTTP management endpoint is introduced.

The supervisor does not require a hard-coded device ID, folder ID or private network address. For example, a user may configure `pi-agent` at `/root/.pi/agent` and `dotfiles` at `/root/.local/share/chezmoi`, both Send & Receive. Device-local credentials such as `auth.json` should be excluded via the device's `.stignore`.

## Reliability limitations

The supervisor is bound to OmaLauncher's existing foreground service. Android force-stop, device reboot, app-process termination, or system battery restrictions can stop it. Launching OmaLauncher again starts its Linux session and supervisor. This is **not** an Android boot receiver or a permanent daemon independent of OmaLauncher.

If all configured peers are offline, the supervisor may make periodic restart attempts, subject to cooldown. Syncthing already retries transport connections itself; restarts are intended for connections that remain stuck.

On Linux removal the foreground service is stopped with the guest, and sync stops too. Existing Pi and Syncthing state must not be deleted for sync maintenance.

## Updating an installed debug APK: signature warning

Android updates require the **same signing certificate** as the currently installed package. Do not uninstall the existing application to force an update: doing so can erase the private Linux rootfs and user data. In-place updates require the *original installed APK's signing key*, which must be verified before deployment. A successfully built APK alone does not prove signature compatibility.

GitHub Actions `build-apk.yml` uses a fresh runner's default debug keystore. APKs from different runs need not share a signing identity. A stable signing strategy is necessary for safe in-place updates.

## Build validation

On macOS with Homebrew JDK and Android SDK installed, for example:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug
```

Inspect the signing identity before distributing the APK:

```bash
$ANDROID_HOME/build-tools/37.0.0/apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk
```

Testing on-device requires an in-place compatible APK update and subsequent verification of the foreground service, notification, actual M4 dashboard connection and both folder completions. No runtime claim should be made from compilation alone.
