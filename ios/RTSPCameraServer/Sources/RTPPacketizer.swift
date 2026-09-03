import Foundation

/// Packetizes H.264 NAL units into RTP payloads per RFC 6184 (single NAL unit + FU-A modes).
enum RTPPacketizer {
    static let maxPayloadSize = 1400
    static let clockRate: UInt32 = 90_000

    struct Packet {
        let payload: Data // includes the 12-byte RTP header
    }

    /// Builds RTP packets for one access unit (frame), made of one or more NAL units.
    /// `marker` is set on the last packet, per RFC 3984, to signal end-of-frame.
    static func packetize(nalUnits: [Data], sequenceStart: UInt16, timestamp: UInt32, ssrc: UInt32, payloadType: UInt8) -> (packets: [Packet], nextSequence: UInt16) {
        var packets: [Packet] = []
        var seq = sequenceStart

        for (index, nal) in nalUnits.enumerated() {
            let isLastNal = index == nalUnits.count - 1
            if nal.count <= maxPayloadSize {
                let header = rtpHeader(sequence: seq, timestamp: timestamp, ssrc: ssrc, marker: isLastNal, payloadType: payloadType)
                packets.append(Packet(payload: header + nal))
                seq = seq &+ 1
            } else {
                let fragments = fragmentNAL(nal)
                for (fragIndex, fragment) in fragments.enumerated() {
                    let isLastFragment = fragIndex == fragments.count - 1
                    let marker = isLastNal && isLastFragment
                    let header = rtpHeader(sequence: seq, timestamp: timestamp, ssrc: ssrc, marker: marker, payloadType: payloadType)
                    packets.append(Packet(payload: header + fragment))
                    seq = seq &+ 1
                }
            }
        }
        return (packets, seq)
    }

    private static func fragmentNAL(_ nal: Data) -> [Data] {
        guard let nalHeader = nal.first else { return [] }
        let forbiddenAndNRI = nalHeader & 0b1110_0000
        let nalType = nalHeader & 0b0001_1111

        let payload = nal.dropFirst()
        let chunkSize = maxPayloadSize - 2 // FU indicator + FU header
        var fragments: [Data] = []
        var offset = payload.startIndex

        while offset < payload.endIndex {
            let end = payload.index(offset, offsetBy: chunkSize, limitedBy: payload.endIndex) ?? payload.endIndex
            let isFirst = offset == payload.startIndex
            let isLast = end == payload.endIndex

            var fuIndicator = forbiddenAndNRI | 28 // FU-A type
            var fuHeader = nalType
            if isFirst { fuHeader |= 0b1000_0000 }
            if isLast { fuHeader |= 0b0100_0000 }

            var fragment = Data([fuIndicator, fuHeader])
            fragment.append(payload[offset..<end])
            fragments.append(fragment)

            offset = end
            _ = fuIndicator // silence unused warning in some toolchains
        }
        return fragments
    }

    private static func rtpHeader(sequence: UInt16, timestamp: UInt32, ssrc: UInt32, marker: Bool, payloadType: UInt8) -> Data {
        var header = Data(count: 12)
        header[0] = 0x80 // version 2, no padding/extension/CSRC
        header[1] = (marker ? 0x80 : 0x00) | (payloadType & 0x7F)
        header[2] = UInt8(sequence >> 8)
        header[3] = UInt8(sequence & 0xFF)
        header[4] = UInt8((timestamp >> 24) & 0xFF)
        header[5] = UInt8((timestamp >> 16) & 0xFF)
        header[6] = UInt8((timestamp >> 8) & 0xFF)
        header[7] = UInt8(timestamp & 0xFF)
        header[8] = UInt8((ssrc >> 24) & 0xFF)
        header[9] = UInt8((ssrc >> 16) & 0xFF)
        header[10] = UInt8((ssrc >> 8) & 0xFF)
        header[11] = UInt8(ssrc & 0xFF)
        return header
    }
}
