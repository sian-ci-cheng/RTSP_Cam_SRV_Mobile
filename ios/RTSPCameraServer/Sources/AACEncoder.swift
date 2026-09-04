import AVFoundation
import AudioToolbox

/// Encodes microphone PCM into AAC-LC access units using AVAudioConverter,
/// mirroring how H264Encoder wraps VTCompressionSession for video.
final class AACEncoder {
    struct EncodedFrame {
        /// One raw AAC access unit (no ADTS header) — RTP payload per RFC 3640.
        let data: Data
        let presentationTimeStamp: CMTime
        /// The same access unit wrapped as a CMSampleBuffer -- LocalRecorder passes this
        /// straight to AVAssetWriter instead of re-deriving AudioSpecificConfig by hand.
        let sampleBuffer: CMSampleBuffer
    }

    /// Fixed for AAC-LC; used by the SDP fmtp line and to advance the RTP audio clock per frame.
    static let sampleRate: Double = 44100
    static let channels: UInt32 = 1
    static let samplesPerFrame: UInt32 = 1024

    /// 2-byte MPEG-4 AudioSpecificConfig, required by the SDP fmtp `config=` field.
    /// Available once `start()` succeeds.
    private(set) var audioSpecificConfig: Data?

    /// Encoded access units fan out to every subscriber (RTSP server, local recorder)
    /// independently, mirroring H264Encoder's listener list.
    typealias Listener = (EncodedFrame) -> Void
    private var listeners: [UUID: Listener] = [:]

    private var converter: AVAudioConverter?
    private var nativeFormat: AVAudioFormat?
    private var outputFormat: AVAudioFormat?
    private let queue = DispatchQueue(label: "AACEncoder.queue")

    @discardableResult
    func addListener(_ listener: @escaping Listener) -> UUID {
        let id = UUID()
        queue.sync { listeners[id] = listener }
        return id
    }

    func removeListener(_ id: UUID) {
        queue.sync { listeners.removeValue(forKey: id) }
    }

    func start() {
        queue.sync {
            guard let outputFormat = AVAudioFormat(settings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: Self.sampleRate,
                AVNumberOfChannelsKey: Self.channels,
            ]) else {
                print("AACEncoder: failed to create output format")
                return
            }

            self.outputFormat = outputFormat
            self.audioSpecificConfig = Self.buildAudioSpecificConfig(channels: Self.channels)
        }
    }

    func stop() {
        queue.sync {
            converter = nil
            nativeFormat = nil
            outputFormat = nil
            audioSpecificConfig = nil
        }
    }

    /// `AVCaptureAudioDataOutput.audioSettings` (which used to force a fixed PCM format) is
    /// unavailable on iOS, so buffers arrive in whatever format the microphone natively uses.
    /// The converter is built from that native format the first time it's observed.
    func encode(sampleBuffer: CMSampleBuffer) {
        queue.async { [weak self] in
            guard let self, let formatDescription = CMSampleBufferGetFormatDescription(sampleBuffer) else { return }
            let sourceFormat = AVAudioFormat(cmAudioFormatDescription: formatDescription)

            if self.converter == nil {
                self.configureConverter(from: sourceFormat)
            }

            guard let converter = self.converter, let outputFormat = self.outputFormat,
                  let (pcmBuffer, retainedBlockBuffer) = self.pcmBuffer(from: sampleBuffer, format: sourceFormat) else { return }
            // `retainedBlockBuffer` backs pcmBuffer's storage (no-copy) and must outlive the convert() call below.
            _ = retainedBlockBuffer

            let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
            let outputBuffer = AVAudioCompressedBuffer(format: outputFormat, packetCapacity: 1, maximumPacketSize: 4096)

            var suppliedInput = false
            var conversionError: NSError?
            let status = converter.convert(to: outputBuffer, error: &conversionError) { _, inputStatus in
                if suppliedInput {
                    inputStatus.pointee = .noDataNow
                    return nil
                }
                suppliedInput = true
                inputStatus.pointee = .haveData
                return pcmBuffer
            }

            guard status == .haveData, conversionError == nil, outputBuffer.byteLength > 0 else { return }
            let data = Data(bytes: outputBuffer.data, count: Int(outputBuffer.byteLength))
            guard let sampleBuffer = Self.makeSampleBuffer(from: outputBuffer, formatDescription: outputFormat.formatDescription, presentationTimeStamp: pts) else { return }
            let frame = EncodedFrame(data: data, presentationTimeStamp: pts, sampleBuffer: sampleBuffer)
            // Already running on `queue` here, so this is safe without an extra lock/snapshot.
            for listener in self.listeners.values { listener(frame) }
        }
    }

    private func configureConverter(from sourceFormat: AVAudioFormat) {
        guard let outputFormat, let converter = AVAudioConverter(from: sourceFormat, to: outputFormat) else {
            print("AACEncoder: failed to create converter for native format \(sourceFormat)")
            return
        }
        self.nativeFormat = sourceFormat
        self.converter = converter
    }

    /// Returns the PCM buffer alongside the CMBlockBuffer backing its storage (the PCM buffer is
    /// a no-copy view into it) — callers must keep the block buffer alive as long as they use the PCM buffer.
    private func pcmBuffer(from sampleBuffer: CMSampleBuffer, format: AVAudioFormat) -> (AVAudioPCMBuffer, CMBlockBuffer)? {
        var audioBufferList = AudioBufferList()
        var blockBuffer: CMBlockBuffer?
        let status = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer,
            bufferListSizeNeededOut: nil,
            bufferListOut: &audioBufferList,
            bufferListSize: MemoryLayout<AudioBufferList>.size,
            blockBufferAllocator: nil,
            blockBufferMemoryAllocator: nil,
            flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment,
            blockBufferOut: &blockBuffer
        )
        guard status == noErr, let blockBuffer,
              let pcmBuffer = AVAudioPCMBuffer(pcmFormat: format, bufferListNoCopy: &audioBufferList) else { return nil }
        return (pcmBuffer, blockBuffer)
    }

    /// Builds the 2-byte MPEG-4 AudioSpecificConfig for AAC-LC:
    /// 5 bits object type (2 = AAC LC), 4 bits sampling-frequency index, 4 bits channel config, 3 bits padding.
    private static func buildAudioSpecificConfig(channels: UInt32) -> Data {
        let objectType: UInt16 = 2
        let freqIndex = UInt16(samplingFrequencyIndex(for: sampleRate))
        let channelConfig = UInt16(channels)

        let config: UInt16 = (objectType << 11) | (freqIndex << 7) | (channelConfig << 3)
        return Data([UInt8(config >> 8), UInt8(config & 0xFF)])
    }

    /// Wraps one compressed AAC access unit into a CMSampleBuffer via the CoreMedia API built
    /// specifically for this (compressed audio + per-packet size, no fixed frame layout).
    private static func makeSampleBuffer(from outputBuffer: AVAudioCompressedBuffer, formatDescription: CMFormatDescription, presentationTimeStamp: CMTime) -> CMSampleBuffer? {
        let byteLength = Int(outputBuffer.byteLength)
        guard byteLength > 0 else { return nil }

        var blockBuffer: CMBlockBuffer?
        let blockStatus = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: byteLength,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: byteLength,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard blockStatus == noErr, let blockBuffer else { return nil }

        let copyStatus = CMBlockBufferReplaceDataBytes(with: outputBuffer.data, blockBuffer: blockBuffer, offsetIntoDestination: 0, dataLength: byteLength)
        guard copyStatus == noErr else { return nil }

        var packetDescription = AudioStreamPacketDescription(
            mStartOffset: 0,
            mVariableFramesInPacket: 0,
            mDataByteSize: UInt32(byteLength)
        )

        var sampleBuffer: CMSampleBuffer?
        let sbStatus = CMAudioSampleBufferCreateWithPacketDescriptions(
            allocator: kCFAllocatorDefault,
            dataBuffer: blockBuffer,
            dataReady: true,
            makeDataReadyCallback: nil,
            refcon: nil,
            formatDescription: formatDescription,
            sampleCount: 1,
            presentationTimeStamp: presentationTimeStamp,
            packetDescriptions: &packetDescription,
            sampleBufferOut: &sampleBuffer
        )
        guard sbStatus == noErr else { return nil }
        return sampleBuffer
    }

    private static func samplingFrequencyIndex(for sampleRate: Double) -> UInt8 {
        let rates: [Double] = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350]
        return UInt8(rates.firstIndex(of: sampleRate) ?? 4)
    }
}
