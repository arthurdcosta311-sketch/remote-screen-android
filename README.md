# Remote Screen (Android 14+)

An Android app that serves two things to a receiver you pair with a 6-digit code,
ADB-style:

1. **Screen mirroring** — Android's own MediaProjection consent screen, then H.264
   over TCP on port **7001**.
2. **A second display** — a screen the app renders for the receiver only, on port
   **7002**. It is *not* shown on the phone, so the phone keeps working normally
   while the receiver watches that screen.

Plus: pairing-code pairing (port **7000**), optional **unattended access** for
already-paired receivers, optional **device administrator**, and optional
**touch/key input** through the accessibility service.

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
   Back/Home. Text can be sent into the focused field.
7. **Device administrator** is optional and separate: it gives the standard
   admin powers (lock now). It never blocks uninstall and cannot hide itself.

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
* Real ADB is not implemented. Android's ADB requires the `adbd` daemon and RSA
  host keys, which no ordinary app can host, and an iPhone cannot act as an ADB
  host. The app's pairing copies ADB's *user experience*. If you need literal
  ADB, a device-owner install can switch Wireless debugging on for you from the
  **ADB settings** button, and you pair a real ADB host from Android's own
  dialog.

## Only for devices you own or are authorized to control

Screen capture, input injection and unattended access are powerful. Running this
on someone else's device without their informed consent is illegal in most
places and is not what this app is for. The app keeps the consent dialog, the
approval prompt and the persistent notification in place on purpose.
