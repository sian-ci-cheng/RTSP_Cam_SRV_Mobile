# RTSPCameraServer (iOS)

Minimal iOS counterpart to the Android RTSP server in this repo: turns the phone's
camera into an RTSP source that VLC / ffplay / another device on the same network
can connect to.

**Scope (MVP):** single client, H.264 video + AAC-LC audio (mono, 44.1kHz), RTP
delivered over the RTSP TCP connection itself (interleaved mode, RFC 2326 §10.12)
— no separate RTP/UDP ports to configure, which matters given you're feeding this
through a USB-C→RJ45 adapter. No multi-channel, no HTTP tunneling (see Android
README for what full parity would add). Audio is best-effort: if the user denies
the microphone permission, the app still streams video-only, exactly as before
audio support existed — the SDP simply omits the `m=audio` line in that case.

**Why pure Swift instead of wrapping a C library:** this machine has no full
Xcode installed, so nothing here could actually be build-tested. Cross-compiling
something like live555 for iOS and wiring it up blind is exactly the kind of
thing that fails in ways only a real build log would catch. `Network.framework`
+ `VideoToolbox` are Apple frameworks already available in any iOS SDK, so this
has a much better chance of building on the first try in your own Xcode. If you
still want live555 (e.g. for real UDP/multicast support later), the RTSPServer.swift
/ RTPPacketizer.swift files are the two to replace — CameraCaptureManager and
H264Encoder stay as-is either way.

## Files

- `RTSPCameraServerApp.swift` — SwiftUI app entry point.
- `ContentView.swift` — UI: camera preview, start/stop, audio status, shows the `rtsp://<ip>:8554/` URL to connect to.
- `CameraCaptureManager.swift` — `AVCaptureSession` wiring (back camera, 720p) and integrates `AudioCaptureManager` into the same session.
- `H264Encoder.swift` — `VTCompressionSession` wrapper; hardware H.264 encode, extracts SPS/PPS.
- `AudioCaptureManager.swift` — adds the microphone input/`AVCaptureAudioDataOutput` to the shared capture session.
- `AACEncoder.swift` — `AVAudioConverter` wrapper; encodes mic PCM to AAC-LC, exposes the AudioSpecificConfig the SDP needs.
- `RTPPacketizer.swift` — RFC 6184 H.264 packetization (single-NAL + FU-A fragmentation) and RFC 3640 AAC-hbr packetization.
- `RTSPServer.swift` — the RTSP server itself (`Network.framework`, TCP port 8554): video on interleaved channel 0/1 (track1), audio on channel 2/3 (track2) when available.
- `NetworkUtils.swift` — enumerates the device's IPv4 addresses (WiFi and the USB-C Ethernet adapter both show up as `en*` interfaces on iOS).

## Setting it up in Xcode

1. Create a new project: **File → New → Project → iOS → App**. Interface: SwiftUI. Name it `RTSPCameraServer`.
2. Delete the auto-generated `ContentView.swift` and `RTSPCameraServerApp.swift`, then drag in the eight files from this folder's `Sources/` directory (check "Copy items if needed").
3. In the target's **Info** tab, add:
   - `Privacy - Camera Usage Description` (`NSCameraUsageDescription`) — e.g. "Used to stream the camera over RTSP."
   - `Privacy - Microphone Usage Description` (`NSMicrophoneUsageDescription`) — e.g. "Used to stream audio alongside the camera over RTSP."
4. In **Signing & Capabilities**, set your team so it can install on-device (the camera doesn't work in the Simulator, so this has to run on a real iPhone).
5. Build & run on the S8/S20-equivalent test device... actually run on an **iPhone** — plug it in via USB (or use your Ethernet adapter afterward), select it as the run destination, hit Run.
6. On first launch, accept the camera and microphone permission prompts, then tap **Start** (denying the microphone prompt still streams video-only — the app doesn't require it). The screen will list `rtsp://<ip>:8554/` for each active network interface — use the one on the same LAN as your receiving device (same reasoning as the Android app: prefer the Ethernet-adapter IP over WiFi if both are up, since only one interface is reliably reachable from the other side).
7. From another device on the same network: `ffplay rtsp://<that-ip>:8554/` or open the URL in VLC.

## Installing the pre-built .ipa

`dist/RTSPCameraServer.ipa` is a signed build ready to install without going through Xcode each time.

**Important — free Apple ID signing expires in 7 days.** This project is signed with a personal/free
Apple Developer account (`Automatic` signing, no paid Program membership configured). Apple limits
such builds to a 7-day provisioning profile, after which the app refuses to launch until reinstalled.
Reinstalling (same steps below) fixes it instantly — no rebuild needed as long as the .ipa is still
within its validity window; past that, rebuild via Xcode (`⌘R`) to get a fresh 7-day signature, then
re-run the packaging steps below to refresh `dist/RTSPCameraServer.ipa`.

Two ways to install it on a device already registered to this account (i.e. one that's been run from
this Xcode project at least once):

- **Apple Configurator** (GUI): open the app, drag `RTSPCameraServer.ipa` onto the connected device.
- **`cfgutil` (CLI)**, bundled inside Apple Configurator:
  ```bash
  "/Applications/Apple Configurator.app/Contents/MacOS/cfgutil" list   # confirm the device shows up
  "/Applications/Apple Configurator.app/Contents/MacOS/cfgutil" install-app "dist/RTSPCameraServer.ipa"
  ```

To rebuild the .ipa after code changes (must be done via Xcode GUI once — `xcodebuild` on the command
line can't see the Apple ID session Xcode's GUI is logged into, so `-allowProvisioningUpdates` alone
won't resolve signing):

1. In Xcode, `⌘R` on the **Sian-Ci's iPhone** destination once, to produce a freshly signed `.app` in DerivedData.
2. Re-run:
   ```bash
   APP_SRC="$(find ~/Library/Developer/Xcode/DerivedData -path '*RTSPCameraServer*/Build/Products/Debug-iphoneos/RTSPCameraServer.app' -print -quit)"
   rm -rf dist/Payload dist/RTSPCameraServer.ipa
   mkdir -p dist/Payload
   cp -R "$APP_SRC" dist/Payload/RTSPCameraServer.app
   (cd dist && zip -qr RTSPCameraServer.ipa Payload && rm -rf Payload)
   ```

## Known limitations of this MVP

- One client at a time — a second RTSP connection will get a session but won't receive video until the first disconnects (not explicitly rejected; would need small `ClientSession` bookkeeping to reject cleanly, that's a follow-up if you need it).
- No RTCP handling, no seeking (not meaningful for a live camera source anyway).
- Backgrounding: iOS suspends the camera session when the app isn't foregrounded, so the RTSP server effectively stops (would need `UIBackgroundModes: [voip]` or a broadcast extension to keep running in the background — worth doing once the foreground path is confirmed working).
