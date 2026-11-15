# Remote Screen (Android 14+)

An Android app that serves two things to a receiver you pair with a 6-digit code,
ADB-style:

1. **Screen mirroring** — Android's own MediaProjection consent screen, then H.264
   over TCP on port **7001**.
2. **A second display** — a screen the app renders for the receiver only, on port
   **7002**. It is *not* shown on the phone, so the phone keeps working normally
   while the receiver watches that screen.

Plus: pairing-code pairing (port **7000**), a **remote shell** (port **7003**)
with app/process control, rotation, mirroring and APK push, optional
**unattended access** for already-paired receivers, optional **device
administrator**, and optional **touch/key input** through the accessibility
service.

Application id: `com.projectbysketch.inputbridge` (both the APK and the iPhone
app).

The receiver is a separate app (you are writing the iPhone side); the wire
protocol is in [PROTOCOL.md](PROTOCOL.md).

## Build

Requirements: JDK 17, Android SDK platform 34 + build-tools 34.0.0.

```bash
# Android Studio: just open the folder.
# CLI:
export ANDROID_HOME=/path/to/android-sdk        # or set sdk.dir in local.properties
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

It is a debug build, so it is signed with the local debug key (installable as-is).
`minSdk` is 34, `targetSdk` is 34.

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## iPhone receiver

The receiver lives in [`ios/`](ios) (SwiftUI, CryptoKit, Network.framework,
VideoToolbox; iOS 16+). The Xcode project is generated from `ios/project.yml`
with [XcodeGen](https://github.com/yonaskolb/XcodeGen):

```bash
python3 tools/make_icon.py     # draws the app icon into the asset catalog
cd ios && xcodegen generate && open RemoteScreen.xcodeproj
```

The GitHub Actions workflow `.github/workflows/release.yml` draws the icon,
builds the debug APK and an **unsigned** IPA, and attaches both to a GitHub
release (on pushes to `main`, on `v*` tags, or manually with a tag name). The
IPA has to be signed by whatever installs it (AltStore, Sideloadly, TrollStore).

In the iPhone app tap **+**, enter the phone's IP address, check that the six
digits shown there match the phone's dialog, and tap **Pair** on the phone.
Each paired phone then offers **Mirror**, **Second screen** and **Console**.

## Using it

1. Put the phone and the receiver on the same network (Wi-Fi or the phone's
   hotspot). The address and ports are shown at the top of the app.
2. With the app open, the phone listens for pairing on port 7000. Your receiver
   connects, both sides derive the same 6-digit code, you type it on the
   receiver, and the phone shows a **Pair with …?** dialog. Nothing is stored
   until you tap **Pair**.
3. **Start screen mirroring** → accept Android's screen-capture dialog → the
   receiver connects to 7001 and sees the phone screen.
4. **Start second screen** → the receiver connects to 7002 and gets the
   independent display instead.
5. **Unattended access** (off by default): when on, a receiver that already
   paired can open a session without the Allow/Deny notification. The ongoing
   session notification is still always shown.
6. **Input** is view-only by default. Enable *Remote Screen input* in
   Accessibility settings to let the receiver tap, swipe and press
   Back/Home. Text can be sent into the focused field. On the second display
   taps are meaningless, so only the keys and typing are offered there.
7. **Remote shell** → **Start remote shell** listens on port **7003** and gives
   the receiver a console: `access`, `apps`, `ps`, `inspect`, `start`, `kill`,
   `stop`, `disable`, `enable`, `uninstall`, `rotate`, `brightness`, `timeout`,
   `volume`, `key`, `tap`, `swipe`, `open`, `notify`, `reboot`, `mirror`,
   `lock` and `shell <command>`. Most of it needs no unlock; `access` says
   exactly what this install may do. Two permissions add the rest: *Modify
   system settings* (rotation, brightness, screen timeout) and *Install unknown
   apps* (the APK push).
8. **Push an APK** from the receiver's console toolbar button: the file goes
   over the encrypted session into the phone's cache, and the phone posts a
   notification that opens Android's install dialog.
9. **Device administrator** is optional and separate: it gives the standard
   admin powers (lock now). It never blocks uninstall and cannot hide itself.

## Unlocking the rest

`stop` (force-stop), `disable`/`enable` and `reboot` need more than an app is
normally given. The app can use two things, and the **Access level** panel at
the top of the phone's screen shows which one it has:

* **Root** — if the phone is rooted, tap **Test root** and grant the prompt.
  Force-stop, disable, uninstall and reboot all work through `su`.
* **Device owner** — tap **Copy device-owner command** and run it once from a
  computer that is already allowed to debug the phone:

  ```bash
  adb shell dpm set-device-owner com.projectbysketch.inputbridge/dev.remotescreen.AdminReceiver
  ```

  That makes the app the device owner, which unlocks hiding packages
  (`disable`, `enable`) and `reboot`. Force-stop still needs root: Android
  gives no ordinary app that power.

**Wireless debugging is not a door.** Android 11 pairs a debugging *host* to
the phone over TLS with a SPAKE2 key exchange implemented only in Android's
own BoringSSL, and the debugging port then requires the client certificate that
the pairing produced. Neither the Android SDK nor an iPhone exposes that
handshake, so `adb pair` can only run on a computer — turning wireless
debugging on does not give any app on the phone a shell.

## Security model

* Pairing uses ephemeral P-256 ECDH. The 6-digit code is a short authentication
  string derived from the transcript; the receiver proves it typed the code with
  an HMAC over the transcript, so an eavesdropper or MITM gets a different code.
* The receiver's key and the derived long-term secret are stored encrypted with
  an AES-256 key held in the Android Keystore.
* Every session authenticates with HMAC over fresh nonces and derives a
  per-session AES-256-GCM key, so the stored secret is never used to encrypt
  traffic directly. Session traffic is encrypted per message with independent
  counters.
* There is **no TLS** on the wire: sessions are protected by the pairing key and
  AES-GCM, but the server key exchange is authenticated by the pairing key only.
  Treat the network as hostile-ish (home Wi-Fi, hotspot, or a VPN you trust) and
  forget receivers you no longer use.
* Capture cannot start without the Android consent dialog, and every session is
  announced by an ongoing notification. There is no silent mode.

## Limits worth knowing

* One receiver at a time; the mirror and second display can run together.
* No audio, clipboard or file transfer.
* Drags are injected as a single stroke on release, because a normal app can
  only dispatch completed gestures. Taps and swipes feel normal, slow drags do
  not.
* Resolution is fixed when a session starts (1280 px long edge for mirror,
  1280×720 for the second display). Rotating the phone mid-session does not
  renegotiate the stream.
* The second display is rendered by this app: it shows its own dashboard plus a
  text message sent from the receiver. It cannot host other apps' windows.
* Real ADB is not implemented, and wireless debugging does not change that. The
  remote shell on port 7003 is an **app-level** shell with an ADB-like command
  set: it runs as the app's own uid, and the commands that need system rights
  (`stop`, `disable`, `reboot`) work with root or a device-owner install and say
  so when neither is there — see **Unlocking the rest** above. If you need
  literal ADB, a device-owner install can switch Wireless debugging on for you
  from the **ADB settings** button, and you pair a real ADB host from Android's
  own dialog.
* Installing a pushed APK always ends with Android's install dialog; the app
  cannot install silently, and it does not try to.

## Only for devices you own or are authorized to control

Screen capture, input injection and unattended access are powerful. Running this
on someone else's device without their informed consent is illegal in most
places and is not what this app is for. The app keeps the consent dialog, the
approval prompt and the persistent notification in place on purpose.
