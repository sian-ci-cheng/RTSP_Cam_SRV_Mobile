import AVFoundation

/// Captures camera frames and feeds them into the H.264 encoder.
final class CameraCaptureManager: NSObject {
    let session = AVCaptureSession()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let captureQueue = DispatchQueue(label: "CameraCaptureManager.queue")

    let encoder = H264Encoder()
    let audio = AudioCaptureManager()

    private(set) var width: Int32 = 1280
    private(set) var height: Int32 = 720
    private(set) var isAudioEnabled = false

    /// Resolutions the back camera actually supports, in the order declared by StreamResolution.
    static func supportedResolutions() -> [StreamResolution] {
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
            return [.hd720p]
        }
        let supported = StreamResolution.allCases.filter { $0.isSupported(by: camera) }
        return supported.isEmpty ? [.hd720p] : supported
    }

    /// Requests camera (required) and, if `wantsAudio` is true, microphone (best-effort) access,
    /// then configures the session. Denied microphone access still lets the stream start, just
    /// without audio.
    func requestAccessAndConfigure(resolution: StreamResolution, wantsAudio: Bool, completion: @escaping (Bool) -> Void) {
        AVCaptureDevice.requestAccess(for: .video) { [weak self] videoGranted in
            guard let self else {
                completion(false)
                return
            }
            guard videoGranted else {
                completion(false)
                return
            }
            guard wantsAudio else {
                self.captureQueue.async {
                    self.configureSession(resolution: resolution, includeAudio: false)
                    completion(true)
                }
                return
            }
            self.audio.requestAccess { audioGranted in
                self.captureQueue.async {
                    self.configureSession(resolution: resolution, includeAudio: audioGranted)
                    completion(true)
                }
            }
        }
    }

    private func configureSession(resolution: StreamResolution, includeAudio: Bool) {
        session.beginConfiguration()

        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: camera),
              session.canAddInput(input) else {
            session.commitConfiguration()
            return
        }
        session.addInput(input)

        let preset = resolution.isSupported(by: camera) ? resolution.sessionPreset : .hd1280x720
        session.sessionPreset = session.canSetSessionPreset(preset) ? preset : .high

        videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange]
        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.setSampleBufferDelegate(self, queue: captureQueue)
        guard session.canAddOutput(videoOutput) else {
            session.commitConfiguration()
            return
        }
        session.addOutput(videoOutput)

        if let connection = videoOutput.connection(with: .video) {
            connection.videoOrientation = .landscapeRight
        }

        isAudioEnabled = includeAudio && audio.addToSession(session)

        session.commitConfiguration()

        width = resolution.width
        height = resolution.height
        encoder.start(width: width, height: height, bitrate: resolution.bitrate)
    }

    func start() {
        captureQueue.async { [weak self] in
            self?.session.startRunning()
        }
    }

    /// Blocks until the session has actually stopped and the encoder/audio have released the
    /// hardware -- returning early (the previous `captureQueue.async` version) let a fresh
    /// `start()`/`configureSession()` race the old session's teardown and hit the camera while
    /// it was still busy, surfacing as a FigCaptureSourceRemote error from AVFCapture.
    func stop() {
        captureQueue.sync {
            session.stopRunning()
            encoder.stop()
        }
        audio.stop()
    }
}

extension CameraCaptureManager: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        encoder.encode(sampleBuffer: sampleBuffer)
    }
}
