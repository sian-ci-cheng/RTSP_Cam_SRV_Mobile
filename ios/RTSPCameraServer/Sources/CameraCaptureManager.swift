import AVFoundation

/// Captures camera frames and feeds them into the H.264 encoder.
final class CameraCaptureManager: NSObject {
    let session = AVCaptureSession()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let captureQueue = DispatchQueue(label: "CameraCaptureManager.queue")

    let encoder = H264Encoder()

    private(set) var width: Int32 = 1280
    private(set) var height: Int32 = 720

    /// Resolutions the back camera actually supports, in the order declared by StreamResolution.
    static func supportedResolutions() -> [StreamResolution] {
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
            return [.hd720p]
        }
        let supported = StreamResolution.allCases.filter { $0.isSupported(by: camera) }
        return supported.isEmpty ? [.hd720p] : supported
    }

    func requestAccessAndConfigure(resolution: StreamResolution, completion: @escaping (Bool) -> Void) {
        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            guard granted, let self else {
                completion(false)
                return
            }
            self.captureQueue.async {
                self.configureSession(resolution: resolution)
                completion(true)
            }
        }
    }

    private func configureSession(resolution: StreamResolution) {
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

    func stop() {
        captureQueue.async { [weak self] in
            self?.session.stopRunning()
            self?.encoder.stop()
        }
    }
}

extension CameraCaptureManager: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        encoder.encode(sampleBuffer: sampleBuffer)
    }
}
