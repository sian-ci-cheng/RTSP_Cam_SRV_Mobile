import AVFoundation
import AudioToolbox

/// Encodes microphone PCM into AAC-LC access units using AVAudioConverter,
/// mirroring how H264Encoder wraps VTCompressionSession for video.
final class AACEncoder {
    struct EncodedFrame {
        /// One raw AAC access unit (no ADTS header) — RTP payload per RFC 3640.
        let data: Data
        let presentationTimeStamp: CMTime
    }

    /// Fixed for AAC-LC; used by the SDP fmtp line and to advance the RTP audio clock per frame.
    static let sampleRate: Double = 44100
    static let channels: UInt32 = 1
    static let samplesPerFrame: UInt32 = 1024

    /// 2-byte MPEG-4 AudioSpecificConfig, required by the SDP fmtp `config=` field.
    /// Available once `start()` succeeds.
    private(set) var audioSpecificConfig: Data?

    var onEncodedFrame: ((EncodedFrame) -> Void)?

    private var converter: AVAudioConverter?
    private var inputFormat: AVAudioFormat?
    private var outputFormat: AVAudioFormat?
    private let queue = DispatchQueue(label: "AACEncoder.queue")

    func start() {
        queue.sync {
            guard let inputFormat = AVAudioFormat(
                commonFormat: .pcmFormatInt16,
                sampleRate: Self.sampleRate,
                channels: AVAudioChannelCount(Self.channels),
                interleaved: true
            ), let outputFormat = AVAudioFormat(settings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: Self.sampleRate,
                AVNumberOfChannelsKey: Self.channels,
            ]), let converter = AVAudioConverter(from: inputFormat, to: outputFormat) else {
                print("AACEncoder: failed to create converter")
                return
            }

            self.inputFormat = inputFormat
            self.outputFormat = outputFormat
            self.converter = converter
            self.audioSpecificConfig = Self.buildAudioSpecificConfig(channels: Self.channels)
        }
    }

    func stop() {
        queue.sync {
            converter = nil
            inputFormat = nil
            outputFormat = nil
            audioSpecificConfig = nil
        }
    }

    func encode(sampleBuffer: CMSampleBuffer) {
        queue.async { [weak self] in
            guard let self, let converter = self.converter,
                  let inputFormat = self.inputFormat, let outputFormat = self.outputFormat,
                  let pcmBuffer = self.pcmBuffer(from: sampleBuffer, format: inputFormat) else { return }

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
            self.onEncodedFrame?(EncodedFrame(data: data, presentationTimeStamp: pts))
        }
    }

    private func pcmBuffer(from sampleBuffer: CMSampleBuffer, format: AVAudioFormat) -> AVAudioPCMBuffer? {
        guard let blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else { return nil }
        let numSamples = CMSampleBufferGetNumSamples(sampleBuffer)
        guard numSamples > 0,
              let pcmBuffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(numSamples)),
              let channelData = pcmBuffer.int16ChannelData else { return nil }
        pcmBuffer.frameLength = AVAudioFrameCount(numSamples)

        var length = 0
        var dataPointer: UnsafeMutablePointer<Int8>?
        guard CMBlockBufferGetDataPointer(blockBuffer, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: &length, dataPointerOut: &dataPointer) == noErr,
              let dataPointer else { return nil }

        memcpy(channelData[0], dataPointer, length)
        return pcmBuffer
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

    private static func samplingFrequencyIndex(for sampleRate: Double) -> UInt8 {
        let rates: [Double] = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350]
        return UInt8(rates.firstIndex(of: sampleRate) ?? 4)
    }
}
