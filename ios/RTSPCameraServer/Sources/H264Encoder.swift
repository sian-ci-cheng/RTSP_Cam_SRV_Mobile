import AVFoundation
import VideoToolbox

/// Wraps VTCompressionSession to turn camera frames into H.264 NAL units in real time.
final class H264Encoder {
    struct EncodedFrame {
        /// Individual NAL units (no start code / length prefix), in decode order -- used for RTP.
        let nalUnits: [Data]
        let presentationTimeStamp: CMTime
        let isKeyFrame: Bool
        /// The original VideoToolbox output sample buffer (AVCC-formatted, with its own format
        /// description carrying SPS/PPS) -- LocalRecorder passes this straight to AVAssetWriter
        /// unmodified instead of reconstructing it from nalUnits.
        let sampleBuffer: CMSampleBuffer
    }

    /// SPS/PPS become available once the encoder produces its first frame.
    private(set) var sps: Data?
    private(set) var pps: Data?

    /// Encoded access units fan out to every subscriber (RTSP server, local recorder)
    /// independently -- none of them reach back into this encoder or the camera.
    typealias Listener = (EncodedFrame) -> Void
    private var listeners: [UUID: Listener] = [:]
    var onParameterSetsReady: (() -> Void)?

    private var session: VTCompressionSession?
    private let queue = DispatchQueue(label: "H264Encoder.queue")

    @discardableResult
    func addListener(_ listener: @escaping Listener) -> UUID {
        let id = UUID()
        queue.sync { listeners[id] = listener }
        return id
    }

    func removeListener(_ id: UUID) {
        queue.sync { listeners.removeValue(forKey: id) }
    }

    func start(width: Int32, height: Int32, bitrate: Int32 = 4_000_000, fps: Int32 = 30) {
        queue.sync {
            var newSession: VTCompressionSession?
            let status = VTCompressionSessionCreate(
                allocator: nil,
                width: width,
                height: height,
                codecType: kCMVideoCodecType_H264,
                encoderSpecification: nil,
                imageBufferAttributes: nil,
                compressedDataAllocator: nil,
                outputCallback: nil,
                refcon: nil,
                compressionSessionOut: &newSession
            )
            guard status == noErr, let compressionSession = newSession else {
                print("H264Encoder: failed to create compression session, status=\(status)")
                return
            }

            VTSessionSetProperty(compressionSession, key: kVTCompressionPropertyKey_ProfileLevel, value: kVTProfileLevel_H264_Main_AutoLevel)
            VTSessionSetProperty(compressionSession, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
            VTSessionSetProperty(compressionSession, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
            VTSessionSetProperty(compressionSession, key: kVTCompressionPropertyKey_AverageBitRate, value: NSNumber(value: bitrate))
            VTSessionSetProperty(compressionSession, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: NSNumber(value: fps))
            VTSessionSetProperty(compressionSession, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: NSNumber(value: fps * 2))
            VTCompressionSessionPrepareToEncodeFrames(compressionSession)

            self.session = compressionSession
        }
    }

    func stop() {
        queue.sync {
            if let session = session {
                VTCompressionSessionInvalidate(session)
            }
            session = nil
            sps = nil
            pps = nil
        }
    }

    func encode(sampleBuffer: CMSampleBuffer) {
        queue.async { [weak self] in
            guard let self, let session = self.session,
                  let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

            let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
            let duration = CMSampleBufferGetDuration(sampleBuffer)

            VTCompressionSessionEncodeFrame(
                session,
                imageBuffer: imageBuffer,
                presentationTimeStamp: pts,
                duration: duration,
                frameProperties: nil,
                infoFlagsOut: nil
            ) { [weak self] status, _, sampleBuffer in
                guard status == noErr, let sampleBuffer else { return }
                self?.handleEncodedSampleBuffer(sampleBuffer)
            }
        }
    }

    private func handleEncodedSampleBuffer(_ sampleBuffer: CMSampleBuffer) {
        guard CMSampleBufferDataIsReady(sampleBuffer) else { return }

        let isKeyFrame: Bool = {
            guard let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[CFString: Any]],
                  let first = attachments.first else { return true }
            return (first[kCMSampleAttachmentKey_NotSync] as? Bool) != true
        }()

        if isKeyFrame, let formatDescription = CMSampleBufferGetFormatDescription(sampleBuffer) {
            extractParameterSets(from: formatDescription)
        }

        guard let dataBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else { return }
        var totalLength = 0
        var dataPointer: UnsafeMutablePointer<Int8>?
        guard CMBlockBufferGetDataPointer(dataBuffer, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: &totalLength, dataPointerOut: &dataPointer) == noErr,
              let dataPointer else { return }

        var nalUnits: [Data] = []
        var offset = 0
        // VideoToolbox emits AVCC: each NAL is prefixed by a 4-byte big-endian length.
        while offset + 4 <= totalLength {
            let lengthBytes = Data(bytes: dataPointer + offset, count: 4)
            let nalLength = Int(lengthBytes[0]) << 24 | Int(lengthBytes[1]) << 16 | Int(lengthBytes[2]) << 8 | Int(lengthBytes[3])
            offset += 4
            guard nalLength > 0, offset + nalLength <= totalLength else { break }
            nalUnits.append(Data(bytes: dataPointer + offset, count: nalLength))
            offset += nalLength
        }

        if isKeyFrame, let sps, let pps {
            nalUnits.insert(pps, at: 0)
            nalUnits.insert(sps, at: 0)
        }

        guard !nalUnits.isEmpty else { return }
        let frame = EncodedFrame(
            nalUnits: nalUnits,
            presentationTimeStamp: CMSampleBufferGetPresentationTimeStamp(sampleBuffer),
            isKeyFrame: isKeyFrame,
            sampleBuffer: sampleBuffer
        )
        // VTCompressionSession's completion callback can run on an internal VideoToolbox thread,
        // not necessarily `queue` -- snapshot the listener list under the lock, then invoke
        // outside it so a listener can't block out addListener/removeListener.
        let currentListeners = queue.sync { Array(listeners.values) }
        for listener in currentListeners { listener(frame) }
    }

    private func extractParameterSets(from formatDescription: CMFormatDescription) {
        var spsPointer: UnsafePointer<UInt8>?
        var spsLength = 0
        var ppsPointer: UnsafePointer<UInt8>?
        var ppsLength = 0
        var count = 0

        guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(formatDescription, parameterSetIndex: 0, parameterSetPointerOut: &spsPointer, parameterSetSizeOut: &spsLength, parameterSetCountOut: &count, nalUnitHeaderLengthOut: nil) == noErr,
              CMVideoFormatDescriptionGetH264ParameterSetAtIndex(formatDescription, parameterSetIndex: 1, parameterSetPointerOut: &ppsPointer, parameterSetSizeOut: &ppsLength, parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil) == noErr,
              let spsPointer, let ppsPointer else { return }

        let wasMissing = sps == nil || pps == nil
        sps = Data(bytes: spsPointer, count: spsLength)
        pps = Data(bytes: ppsPointer, count: ppsLength)
        if wasMissing {
            onParameterSetsReady?()
        }
    }
}
