# Remote Screen wire protocol, v1

Everything is big-endian. All crypto is standard: **P-256 ECDH**, **HKDF-SHA256**,
**HMAC-SHA256**, **AES-256-GCM**. The phone is always the server; the receiver
(iPhone) is always the client.

| Port | Purpose |
| --- | --- |
| 7000 | Pairing handshake (only while the app is open) |
| 7001 | Mirrored screen stream |
| 7002 | Second (virtual) display stream |
| 7003 | Remote shell: commands, app/process lists, APK push |

A receiver that has paired can reconnect to 7001/7002 at any time the phone has
the corresponding service running.

---

## 1. Pairing (port 7000)

```
phone                                            receiver
  |  MAGIC u32 = 0x52535631 ("RSV1")               |
  |  version u32 = 1                               |
  |  devicePubLen u16 | devicePub (X.509 SPKI)     |
  |  saltLen u16 | salt (16 random bytes)          |
  |  deviceName: java writeUTF (u16 length + utf8) |
  |----------------------------------------------->|
  |                                                |
  |<-----------------------------------------------|
  |  receiverPubLen u16 | receiverPub (X.509 SPKI) |
  |  receiverName: writeUTF                        |
  |                                                |
  |  both compute:                                 |
  |    shared  = ECDH(priv, peerPub)   (32 bytes)  |
  |    master  = HKDF-SHA256(ikm=shared,           |
  |              salt=salt,                        |
  |              info="RemoteScreen v1 pairing",   |
  |              L=32)                             |
  |    code    = first 4 bytes of                  |
  |              HMAC-SHA256(master,               |
  |                "code" || devicePub || receiverPub)
  |              as u32 % 1_000_000, zero padded   |
  |                                                |
  |<-----------------------------------------------|
  |  typedLen u16 | typed code (6 ASCII digits)    |
  |  proofLen u16 | HMAC-SHA256(master,            |
  |                  "confirm" || typed)           |
  |                                                |
  |  phone checks typed == code and the proof,     |
  |  then the device owner taps "Pair".            |
  |----------------------------------------------->|
  |  status u8: 1 = paired                         |
  |  deviceName: writeUTF                          |
  |  (on failure: status 0 + writeUTF reason)      |
```

After a successful pairing both sides keep

```
streamKey = HKDF-SHA256(shared, salt, "RemoteScreen v1 session", 32)
```

The receiver must also remember its own public key bytes; the phone stores the
receiver's key and the derived `streamKey` (encrypted with the Android
Keystore). The short code is what authenticates the exchange: a man in the
middle produces two different codes, and the phone refuses the proof because the
typed code no longer matches the code it derived.

## 2. Session handshake (ports 7001, 7002)

```
phone                                            receiver
  |  MAGIC u32 = 0x52535631                       |
  |  version u32 = 1                              |
  |  serverNonce: 32 random bytes                 |
  |  deviceName: writeUTF                         |
  |----------------------------------------------->|
  |<-----------------------------------------------|
  |  fingerprint: 32 bytes = SHA-256(receiverPub) |
  |  clientNonce: 32 random bytes                 |
  |  proof: 32 bytes = HMAC-SHA256(streamKey,     |
  |                           serverNonce||clientNonce)
  |                                                |
  |  phone looks up the fingerprint, checks the    |
  |  proof, asks the owner if unattended is off.   |
  |----------------------------------------------->|
  |  status u8: 1 = go, 0 = refused + writeUTF why |
```

Then both derive the per-session key:

```
sessionKey = HKDF-SHA256(streamKey, serverNonce||clientNonce,
                         "RemoteScreen v1 stream", 32)
```

## 3. Framing and encryption

After the status byte, every message in both directions is:

```
u32 length            (length of the sealed blob)
sealed blob           AES-256-GCM(plaintext)
```

* Key: `sessionKey` (32 bytes, AES-256).
* Nonce: 12 bytes, `00 00 00 00` followed by an 8-byte big-endian counter.
  Each direction has its own counter starting at **0** and incrementing by one
  per message. No AAD. 16-byte tag appended by GCM.
* Plaintext = `u8 type || payload`.

| type | name | payload |
| --- | --- | --- |
| 0x10 | CONTROL | UTF-8 JSON (see below) |
| 0x11 | VIDEO_INFO | UTF-8 JSON: `{"t":"video","mode":"mirror"\|"virtual","codec":"avc","width":W,"height":H,"fps":F,"touch":bool,"keys":bool}` |
| 0x12 | VIDEO_FRAME | `u8 flags` (bit0 = key frame) · `u64 ptsUs` · Annex-B H.264 bytes |
| 0x13 | BYE | empty |

The phone sends `VIDEO_INFO` once the encoder reports its format, then frames as
they are produced. Frames are Annex-B: `00 00 00 01` start codes. Every key
frame already has SPS and PPS prepended, so the receiver can configure
VideoToolbox from any IDR it receives.

## 4. Control messages (receiver → phone)

```json
{"t":"touch","a":"down","x":0.51,"y":0.33}
{"t":"touch","a":"move","x":0.52,"y":0.34}
{"t":"touch","a":"up"}
{"t":"touch","a":"tap","x":0.5,"y":0.5}
{"t":"key","k":"back"|"home"|"recents"|"notifications"|"lock"}
{"t":"text","v":"hello"}            // virtual display: sets the message text
                                    // mirror: types into the focused field
{"t":"name","v":"my iPhone"}        // virtual display: shown in the header
{"t":"bye"}
```

`x`/`y` are normalised 0..1 against the video frame. Touch input only works
when the phone's accessibility service is enabled; otherwise the stream is
view-only (`"touch":false`). `"keys":bool` in `VIDEO_INFO` is the same test
for the phone-wide `key` actions and for typing: the second display is
`"touch":false, "keys":true` because taps there make no sense but Back/Home
still do.

## 5. Notes for the iOS receiver

* Transport: raw TCP (`Network.framework` `NWConnection` with
  `.tcp`). No TLS is used; the pairing key authenticates the session instead.
* Key agreement: `CryptoKit` `P256.KeyAgreement.PrivateKey` and
  `sharedSecretFromKeyAgreement(with:)`, then HKDF-SHA256 with the exact `info`
  strings above. The public key format on the wire is X.509
  `SubjectPublicKeyInfo`, which is what `publicKey.x963Representation`
  prefixed with the standard SPKI header gives you — or use
  `SecKeyCreateWithData` with `kSecAttrKeyClassPublic`.
* Decoding: parse the Annex-B byte stream, split on start codes, convert SPS,
  PPS and slices into AVCC (4-byte length prefixes), build a
  `CMVideoFormatDescription` with
  `CMVideoFormatDescriptionCreateFromH264ParameterSets`, and feed
  `VTDecompressionSession`. New IDR frames always carry fresh SPS/PPS, so
  re-configuring on each key frame is safe.
* The first byte of `VIDEO_FRAME` tells you whether the access unit is an IDR.
* Latency: the phone encodes 1280-wide H.264 at 3 Mbit/s (mirror) or 2 Mbit/s
  (second display). On a busy Wi-Fi link lower the expectation, not the code.

## 6. Remote shell (port 7003)

The same handshake, approval and AES-GCM framing as 7001/7002; only the message
types differ. Every request is a `CONTROL` message with an `id` the receiver
chooses; the phone answers with the same `id`.

```
{"t":"exec","id":7,"cmd":"apps user"}                receiver → phone
{"t":"shell","id":7,"code":0,"out":"…","err":"…"}    phone → receiver
```

The command line is the phone's app-level shell (`help` lists it): `access`,
`device`, `apps`, `ps`, `inspect`, `battery`, `storage`, `network`, `start`,
`kill`, `stop`, `disable`, `enable`, `uninstall`, `rotate`, `brightness`,
`timeout`, `volume`, `key`, `tap`, `swipe`, `open`, `notify`, `reboot`,
`mirror`, `lock`, `shell`.

`access` reports the level the install is at and what it unlocks:

* **app** — no unlock needed: mirroring, input, app and process lists, `kill`,
  `rotate`, `brightness`, `timeout`, `volume`, `key`, `tap`, `swipe`, `open`,
  `notify`, and the APK push.
* **device owner** (`adb shell dpm set-device-owner …` from an authorised
  computer) — adds `disable`/`enable` (hide/unhide a package) and `reboot`.
* **root** — adds `stop` (force-stop), `uninstall` and `reboot`.

Android's wireless-debugging pairing is deliberately not part of this protocol:
Android 11+ pairs a debugging *host* over TLS with a SPAKE2 key exchange that
lives in Android's own BoringSSL, and the debugging port then requires the
client certificate that pairing produced. No app — on the phone or behind a
receiver — can perform that handshake, so `adb pair` remains a computer-side
tool and enabling wireless debugging gives this app nothing.

APK push is chunked, base64 in JSON, and always ends with Android's own install
dialog:

```
{"t":"apk","id":9,"name":"app.apk","size":2446517}
{"t":"apk-chunk","id":9,"data":"<base64, 48 KB of APK per message>"}
{"t":"apk-end","id":9}
{"t":"apk-status","id":9,"state":"receiving"|"received"|"error","message":"…"}
```

## 7. Why this is not the ADB protocol

Real `adb` requires the `adbd` daemon (root/system component), the ADB
transport protocol and RSA host-key exchange. An ordinary app cannot host
`adbd`, and an iPhone cannot be an ADB host without a full ADB client stack.
This app therefore implements **ADB-style pairing** — a short numeric code
derived from an ephemeral ECDH exchange — and its own authenticated stream
protocol. If you want literal ADB, enable Android's Wireless debugging (the
app can flip that switch on a device-owner install) and use a real ADB host on
a computer.
