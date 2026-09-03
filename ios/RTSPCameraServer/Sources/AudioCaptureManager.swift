import AVFoundation

/// Captures microphone audio and feeds it into the AAC encoder. Mirrors CameraCaptureManager
/// but adds its input/output to a session someone else owns and starts/stops, since video and
/// audio share a single AVCaptureSession.
final class AudioCaptureManager: NSObject {
    private let audioOutput = AVCaptureAudioDataOutput()
    private let captureQueue = DispatchQueue(label: "AudioCaptureManager.queue")

    let encoder = AACEncoder()

    func requestAccess(completion: @escaping (Bool) -> Void) {
        AVCaptureDevice.requestAccess(for: .audio, completionHandler: completion)
    }

    /// Adds the microphone input/output pair to `session`. Must be called between
    /// `session.beginConfiguration()` and `session.commitConfiguration()`.
    /// Returns false (leaving the session video-only) if no microphone is available.
    @discardableResult
    func addToSession(_ session: AVCaptureSession) -> Bool {
        guard let microphone = AVCaptureDevice.default(for: .audio),
              let input = try? AVCaptureDeviceInput(device: microphone),
              session.canAddInput(input) else {
            return false
        }
        session.addInput(input)

        audioOutput.audioSettings = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: AACEncoder.sampleRate,
            AVNumberOfChannelsKey: AACEncoder.channels,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsNonInterleaved: false,
        ]
        audioOutput.setSampleBufferDelegate(self, queue: captureQueue)
        guard session.canAddOutput(audioOutput) else { return false }
        session.addOutput(audioOutput)

        encoder.start()
        return true
    }

    func stop() {
        captureQueue.async { [weak self] in
            self?.encoder.stop()
        }
    }
}

extension AudioCaptureManager: AVCaptureAudioDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        encoder.encode(sampleBuffer: sampleBuffer)
    }
}
