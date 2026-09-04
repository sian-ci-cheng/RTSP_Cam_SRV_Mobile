import AVFoundation
import CoreMedia

/// Muxes the same encoded access units RTSPServer streams into a local MP4 file via AVAssetWriter.
/// Subscribes to H264Encoder/AACEncoder as an independent listener -- mirrors Android's
/// LocalRecorder.java: it never touches AVCaptureSession, VTCompressionSession or RTSPServer, and
/// a recording failure never affects the live stream (every AVAssetWriter call is guarded so an
/// error here can't propagate back into the encoder's callback chain).
final class LocalRecorder {
    var onError: ((String) -> Void)?

    private let outputURL: URL
    private let videoEncoder: H264Encoder
    private let audioEncoder: AACEncoder? // nil if no audio track is expected

    private var writer: AVAssetWriter?
    private var videoInput: AVAssetWriterInput?
    private var audioInput: AVAssetWriterInput?

    private var videoListenerID: UUID?
    private var audioListenerID: UUID?

    private let lock = NSLock()
    private var videoTrackAdded = false
    private var audioTrackAdded = false
    private var sessionStarted = false
    private var stopped = false

    // Holds samples that arrive before every expected track has been added -- AVAssetWriter
    // forbids append() before startSession(atSourceTime:), and forbids adding inputs after
    // startWriting(), so whichever of video/audio arrives first has to wait here for the other.
    private var pendingVideo: [CMSampleBuffer] = []
    private var pendingAudio: [CMSampleBuffer] = []

    init(outputURL: URL, videoEncoder: H264Encoder, audioEncoder: AACEncoder?) {
        self.outputURL = outputURL
        self.videoEncoder = videoEncoder
        self.audioEncoder = audioEncoder
    }

    /// Creates the writer and starts listening for encoded frames. Recording actually begins once
    /// the video track (and, if audioEncoder is non-nil, the audio track too) has a real format
    /// description to build an AVAssetWriterInput from -- returns false if the file can't be created.
    @discardableResult
    func start() -> Bool {
        do {
            writer = try AVAssetWriter(outputURL: outputURL, fileType: .mp4)
        } catch {
            onError?("failed to create AVAssetWriter: \(error.localizedDescription)")
            return false
        }
        videoListenerID = videoEncoder.addListener { [weak self] frame in
            self?.handleVideo(frame)
        }
        if let audioEncoder {
            audioListenerID = audioEncoder.addListener { [weak self] frame in
                self?.handleAudio(frame)
            }
        }
        return true
    }

    func stop() {
        lock.lock()
        stopped = true
        lock.unlock()

        if let videoListenerID { videoEncoder.removeListener(videoListenerID) }
        if let audioListenerID { audioEncoder?.removeListener(audioListenerID) }

        guard let writer else { return }
        if sessionStarted, writer.status == .writing {
            videoInput?.markAsFinished()
            audioInput?.markAsFinished()
            let semaphore = DispatchSemaphore(value: 0)
            writer.finishWriting { semaphore.signal() }
            semaphore.wait()
        } else {
            writer.cancelWriting()
        }
        self.writer = nil
        videoInput = nil
        audioInput = nil
    }

    private func handleVideo(_ frame: H264Encoder.EncodedFrame) {
        lock.lock()
        defer { lock.unlock() }
        guard !stopped, let writer else { return }

        if !videoTrackAdded {
            guard frame.isKeyFrame, let formatDescription = CMSampleBufferGetFormatDescription(frame.sampleBuffer) else { return }
            let input = AVAssetWriterInput(mediaType: .video, outputSettings: nil, sourceFormatHint: formatDescription)
            input.expectsMediaDataInRealTime = true
            guard writer.canAdd(input) else { onError?("cannot add video track"); return }
            writer.add(input)
            videoInput = input
            videoTrackAdded = true
        }

        if !sessionStarted {
            pendingVideo.append(frame.sampleBuffer)
            maybeStartSession()
        } else {
            append(frame.sampleBuffer, to: videoInput)
        }
    }

    private func handleAudio(_ frame: AACEncoder.EncodedFrame) {
        lock.lock()
        defer { lock.unlock() }
        guard !stopped, let writer else { return }

        if !audioTrackAdded {
            guard let formatDescription = CMSampleBufferGetFormatDescription(frame.sampleBuffer) else { return }
            let input = AVAssetWriterInput(mediaType: .audio, outputSettings: nil, sourceFormatHint: formatDescription)
            input.expectsMediaDataInRealTime = true
            guard writer.canAdd(input) else { onError?("cannot add audio track"); return }
            writer.add(input)
            audioInput = input
            audioTrackAdded = true
        }

        if !sessionStarted {
            pendingAudio.append(frame.sampleBuffer)
            maybeStartSession()
        } else {
            append(frame.sampleBuffer, to: audioInput)
        }
    }

    /// Starts the writer session once every expected track has been added, then flushes anything
    /// buffered while waiting -- handles video-first or audio-first arrival either way. Called
    /// with `lock` already held.
    private func maybeStartSession() {
        let audioExpected = audioEncoder != nil
        guard videoTrackAdded else { return }
        guard !audioExpected || audioTrackAdded else { return }
        guard let writer, writer.status == .unknown else { return }
        guard let firstVideoSample = pendingVideo.first else { return }

        guard writer.startWriting() else {
            onError?("failed to start writer: \(writer.error?.localizedDescription ?? "unknown")")
            return
        }
        let startTime = CMSampleBufferGetPresentationTimeStamp(firstVideoSample)
        writer.startSession(atSourceTime: startTime)
        sessionStarted = true

        for sample in pendingVideo { append(sample, to: videoInput) }
        pendingVideo.removeAll()
        for sample in pendingAudio { append(sample, to: audioInput) }
        pendingAudio.removeAll()
    }

    private func append(_ sampleBuffer: CMSampleBuffer, to input: AVAssetWriterInput?) {
        guard let input, input.isReadyForMoreMediaData else { return }
        if !input.append(sampleBuffer) {
            onError?("failed to append sample: \(writer?.error?.localizedDescription ?? "unknown")")
        }
    }
}
