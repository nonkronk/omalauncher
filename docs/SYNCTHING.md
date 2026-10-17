# Syncthing inside OmaLauncher (ADB-free recovery)

## Deployed phone workaround (18 October 2026, local phone time)

**Current live build:** `dev.omalauncher.debug` version 0.7.1 / versionCode 13. It does **not** contain this branch's Kotlin `SyncthingSupervisor`; the source-built APK (versionCode 12 and/or different package ID) cannot safely update it. The installed APK's signing certificate also differs from the Mac's present debug signing key. **Do not uninstall the app or force an APK replacement**: the private Arch rootfs, Pi state, and Syncthing database must be preserved.

The deployed interim fix is `scripts/phone-syncthing-guardian.py`, copied to `/root/.local/bin/syncthing-guardian.py` *inside the OmaLauncher Arch rootfs* and launched as an independent, persistent PRoot child under OmaLauncher's app UID. It does not rely on Mac-side ADB for ongoing checks and does not expose an HTTP listener. On-device guard: every 120 seconds check local Syncthing API and the configured Mac's Tailscale TCP address; when the Mac is reachable but the peer stays disconnected for three consecutive observations, gracefully restart the daemon (10-minute cooldown). If the daemon exits, start it without creating a second database owner. When Mac/Tailscale is unreachable, leave the running daemon alone rather than waste CPU cycling it.

Android's Doze allowlist now includes `dev.omalauncher.debug` (only that app), alongside Tailscale's existing allowance. This preserves background execution at the cost of potentially higher phone battery consumption. The existing Mac `~/.local/bin/omalauncher-syncthing` + launchd watcher remains an ADB-based **fallback**, and must not initiate a competing restart when the phone-local guardian is running. Its repetitive unavailable-ADB error logging has been rate-limited.

Verification completed: direct phone-to-M4 Syncthing connection over Tailscale, both `dotfiles` and `pi-agent` at 100% completion as viewed from the M4 REST API, and the phone guardian process/log both alive. Phone guardian logs: `/root/.local/state/syncthing/guardian.log` and `guardian-boot.log`; Syncthing logs: `service.log`. Source of guardian and its intended behavior is in this repository's `scripts/phone-syncthing-guardian.py`. It has a `--once` observation option (does not modify the daemon, but writes its diagnostic log) and `/root/.local/state/syncthing/guardian.disabled` stop/recovery pause marker. Do not delete the database or rescan/re-pair devices as a recovery step.

**Important limitation:** the installed OmaLauncher APK has no boot receiver or native supervisor. On a full phone reboot, force-stop, or death of the entire app's Linux foreground service, the independent PRoot guardian may not relaunch automatically. This workaround eliminates routine dependence on ADB for *daemon disconnects while the app is alive*, not a reboot-proof guarantee. Truly autonomous recovery after full app termination requires building and installing the Kotlin supervisor with the **installed APK's matching signing key**, or a separate explicitly approved and safely verified app-data migration. Neither is currently available or performed. Never reinstall merely to test this.

Rollback: the guardian does not change existing Syncthing folder configurations. To pause on-device recovery, create the `guardian.disabled` marker inside the Arch rootfs; to resume, remove it. To remove the power allowance, run `adb shell cmd deviceidle whitelist -dev.omalauncher.debug` while authorized. Existing Syncthing and Pi data remain untouched.

This integration is intended for a user-managed OmaLauncher debug build with an existing Arch Linux rootfs and a configured Syncthing installation. Syncthing is **not** bundled or installed automatically, and existing folders/configuration are never rewritten.

## Intended native Kotlin supervisor behavior (source only; not yet deployed)

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
