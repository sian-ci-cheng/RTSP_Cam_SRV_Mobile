# RTSP Camera Server

A simple server for RTSP video streaming from a mobile device's camera over the local network — Android and iOS.

## Android ([src/](src/))

The original app. Key features:

* RTP by TCP and UDP; RTP by HTTP tunneling
* Multi-channel support – simultaneous encoding of 2 streams: Main and Secondary channels.
* Hardware acceleration – a new hardware accelerated encoder up to UHD resolution.
* Low latency for network stream – special API to control encoder latency.
* AAC and G.711 audio codecs

See [scripts/build_and_deploy.sh](scripts/build_and_deploy.sh) to build and install it on a connected device.

## iOS ([ios/RTSPCameraServer/](ios/RTSPCameraServer/))

A minimal counterpart built with `Network.framework` + `VideoToolbox` (no third-party RTSP library).
Single client, H.264 only, RTP delivered over the RTSP TCP connection itself (interleaved mode) —
picks a resolution (480p/720p/1080p/4K, whatever the camera supports) and serves `rtsp://<device-ip>:8554/`.

See [ios/RTSPCameraServer/README.md](ios/RTSPCameraServer/README.md) for setup and known limitations.
