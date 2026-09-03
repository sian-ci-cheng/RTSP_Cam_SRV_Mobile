import AVFoundation

/// Common output resolutions, mirroring the presets typically offered by RTSP camera apps.
enum StreamResolution: String, CaseIterable, Identifiable {
    case sd480p = "480p"
    case hd720p = "720p"
    case fhd1080p = "1080p"
    case uhd4K = "4K"

    var id: String { rawValue }

    var width: Int32 {
        switch self {
        case .sd480p: return 640
        case .hd720p: return 1280
        case .fhd1080p: return 1920
        case .uhd4K: return 3840
        }
    }

    var height: Int32 {
        switch self {
        case .sd480p: return 480
        case .hd720p: return 720
        case .fhd1080p: return 1080
        case .uhd4K: return 2160
        }
    }

    /// A reasonable average bitrate for real-time H.264 at this resolution.
    var bitrate: Int32 {
        switch self {
        case .sd480p: return 1_500_000
        case .hd720p: return 4_000_000
        case .fhd1080p: return 8_000_000
        case .uhd4K: return 20_000_000
        }
    }

    var sessionPreset: AVCaptureSession.Preset {
        switch self {
        case .sd480p: return .vga640x480
        case .hd720p: return .hd1280x720
        case .fhd1080p: return .hd1920x1080
        case .uhd4K: return .hd4K3840x2160
        }
    }

    /// Whether the given capture device actually supports this preset (4K isn't universal).
    func isSupported(by device: AVCaptureDevice) -> Bool {
        device.supportsSessionPreset(sessionPreset)
    }
}
