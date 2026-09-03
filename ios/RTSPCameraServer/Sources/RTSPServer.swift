import Foundation
import Network
import CoreMedia

/// Minimal single-client RTSP server: OPTIONS/DESCRIBE/SETUP/PLAY/TEARDOWN over
/// RTP-over-TCP interleaved mode (RFC 2326 §10.12). No RTSP/UDP, no multi-client —
/// this is intentionally the smallest thing that lets VLC/ffplay connect and see video.
final class RTSPServer {
    private final class ClientSession {
        let connection: NWConnection
        var receiveBuffer = Data()
        var rtspSessionID: String = String(format: "%08X", UInt32.random(in: 0...UInt32.max))
        var isPlaying = false
        var sequenceNumber: UInt16 = UInt16.random(in: 0...UInt16.max)
        let ssrc: UInt32 = UInt32.random(in: 0...UInt32.max)
        let rtpChannel: UInt8 = 0
        let rtcpChannel: UInt8 = 1

        init(connection: NWConnection) {
            self.connection = connection
        }
    }

    private let port: UInt16
    private let encoder: H264Encoder
    private var listener: NWListener?
    private var clients: [ObjectIdentifier: ClientSession] = [:]
    private let queue = DispatchQueue(label: "RTSPServer.queue")

    private var baseMediaTime: CMTime?

    var onStatusChange: ((String) -> Void)?

    init(port: UInt16 = 8554, encoder: H264Encoder) {
        self.port = port
        self.encoder = encoder
        self.encoder.onEncodedFrame = { [weak self] frame in
            self?.broadcast(frame: frame)
        }
    }

    func start() {
        queue.async { [weak self] in
            guard let self else { return }
            do {
                let params = NWParameters.tcp
                let listener = try NWListener(using: params, on: NWEndpoint.Port(rawValue: self.port)!)
                listener.newConnectionHandler = { [weak self] connection in
                    self?.accept(connection)
                }
                listener.stateUpdateHandler = { [weak self] state in
                    switch state {
                    case .ready:
                        self?.onStatusChange?("listening on port \(self?.port ?? 0)")
                    case .failed(let error):
                        self?.onStatusChange?("failed: \(error)")
                    default:
                        break
                    }
                }
                listener.start(queue: self.queue)
                self.listener = listener
            } catch {
                self.onStatusChange?("failed to start listener: \(error)")
            }
        }
    }

    func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.clients.values.forEach { $0.connection.cancel() }
            self.clients.removeAll()
            self.listener?.cancel()
            self.listener = nil
        }
    }

    private func accept(_ connection: NWConnection) {
        let client = ClientSession(connection: connection)
        clients[ObjectIdentifier(connection)] = client
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            switch state {
            case .failed, .cancelled:
                self.queue.async {
                    self.clients.removeValue(forKey: ObjectIdentifier(connection))
                }
            default:
                break
            }
        }
        connection.start(queue: queue)
        receiveRequest(client: client)
    }

    private func receiveRequest(client: ClientSession) {
        client.connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                client.receiveBuffer.append(data)
                self.processBuffer(client: client)
            }
            if isComplete || error != nil {
                self.queue.async {
                    self.clients.removeValue(forKey: ObjectIdentifier(client.connection))
                }
                return
            }
            self.receiveRequest(client: client)
        }
    }

    private func processBuffer(client: ClientSession) {
        let terminator = Data("\r\n\r\n".utf8)
        while let range = client.receiveBuffer.range(of: terminator) {
            let headerData = client.receiveBuffer.subdata(in: client.receiveBuffer.startIndex..<range.lowerBound)
            client.receiveBuffer.removeSubrange(client.receiveBuffer.startIndex..<range.upperBound)
            guard let requestText = String(data: headerData, encoding: .utf8) else { continue }
            handleRequest(requestText, client: client)
        }
    }

    private func handleRequest(_ requestText: String, client: ClientSession) {
        let lines = requestText.components(separatedBy: "\r\n")
        guard let requestLine = lines.first else { return }
        let parts = requestLine.split(separator: " ")
        guard parts.count >= 2 else { return }
        let method = String(parts[0])

        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colonIndex = line.firstIndex(of: ":") else { continue }
            let key = line[line.startIndex..<colonIndex].trimmingCharacters(in: .whitespaces)
            let value = line[line.index(after: colonIndex)...].trimmingCharacters(in: .whitespaces)
            headers[key.lowercased()] = value
        }
        let cseq = headers["cseq"] ?? "0"

        switch method {
        case "OPTIONS":
            send(client: client, response: optionsResponse(cseq: cseq))
        case "DESCRIBE":
            send(client: client, response: describeResponse(cseq: cseq))
        case "SETUP":
            send(client: client, response: setupResponse(cseq: cseq, client: client))
        case "PLAY":
            client.isPlaying = true
            send(client: client, response: playResponse(cseq: cseq, client: client))
        case "TEARDOWN":
            client.isPlaying = false
            send(client: client, response: simpleResponse(cseq: cseq))
        case "GET_PARAMETER":
            send(client: client, response: simpleResponse(cseq: cseq))
        default:
            send(client: client, response: "RTSP/1.0 501 Not Implemented\r\nCSeq: \(cseq)\r\n\r\n")
        }
    }

    private func optionsResponse(cseq: String) -> String {
        "RTSP/1.0 200 OK\r\nCSeq: \(cseq)\r\nPublic: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER\r\n\r\n"
    }

    private func describeResponse(cseq: String) -> String {
        guard let sps = encoder.sps, let pps = encoder.pps else {
            return "RTSP/1.0 503 Service Unavailable\r\nCSeq: \(cseq)\r\n\r\n"
        }
        let profileLevelID = sps.count >= 3
            ? String(format: "%02X%02X%02X", sps[0], sps[1], sps[2])
            : "42001E"
        let spropParameterSets = "\(sps.base64EncodedString()),\(pps.base64EncodedString())"

        let sdp = """
        v=0\r
        o=- 0 0 IN IP4 0.0.0.0\r
        s=RTSPCameraServer\r
        c=IN IP4 0.0.0.0\r
        t=0 0\r
        a=tool:RTSPCameraServer\r
        m=video 0 RTP/AVP 96\r
        a=rtpmap:96 H264/90000\r
        a=fmtp:96 packetization-mode=1;profile-level-id=\(profileLevelID);sprop-parameter-sets=\(spropParameterSets)\r
        a=control:track1\r
        """
        let body = sdp
        return "RTSP/1.0 200 OK\r\nCSeq: \(cseq)\r\nContent-Base: rtsp://0.0.0.0/\r\nContent-Type: application/sdp\r\nContent-Length: \(body.utf8.count)\r\n\r\n\(body)"
    }

    private func setupResponse(cseq: String, client: ClientSession) -> String {
        let transport = "RTP/AVP/TCP;unicast;interleaved=\(client.rtpChannel)-\(client.rtcpChannel)"
        return "RTSP/1.0 200 OK\r\nCSeq: \(cseq)\r\nTransport: \(transport)\r\nSession: \(client.rtspSessionID)\r\n\r\n"
    }

    private func playResponse(cseq: String, client: ClientSession) -> String {
        "RTSP/1.0 200 OK\r\nCSeq: \(cseq)\r\nSession: \(client.rtspSessionID)\r\nRange: npt=0.000-\r\n\r\n"
    }

    private func simpleResponse(cseq: String) -> String {
        "RTSP/1.0 200 OK\r\nCSeq: \(cseq)\r\n\r\n"
    }

    private func send(client: ClientSession, response: String) {
        let data = Data(response.utf8)
        client.connection.send(content: data, completion: .contentProcessed { _ in })
    }

    private func broadcast(frame: H264Encoder.EncodedFrame) {
        queue.async { [weak self] in
            guard let self else { return }
            let playingClients = self.clients.values.filter { $0.isPlaying }
            guard !playingClients.isEmpty else { return }

            let timestamp = self.rtpTimestamp(for: frame.presentationTimeStamp)
            for client in playingClients {
                let (packets, nextSeq) = RTPPacketizer.packetize(
                    nalUnits: frame.nalUnits,
                    sequenceStart: client.sequenceNumber,
                    timestamp: timestamp,
                    ssrc: client.ssrc,
                    payloadType: 96
                )
                client.sequenceNumber = nextSeq
                self.sendInterleaved(packets: packets, client: client)
            }
        }
    }

    private func sendInterleaved(packets: [RTPPacketizer.Packet], client: ClientSession) {
        var outgoing = Data()
        for packet in packets {
            let length = UInt16(packet.payload.count)
            outgoing.append(0x24) // '$'
            outgoing.append(client.rtpChannel)
            outgoing.append(UInt8(length >> 8))
            outgoing.append(UInt8(length & 0xFF))
            outgoing.append(packet.payload)
        }
        client.connection.send(content: outgoing, completion: .contentProcessed { _ in })
    }

    private func rtpTimestamp(for pts: CMTime) -> UInt32 {
        if baseMediaTime == nil {
            baseMediaTime = pts
        }
        let elapsedSeconds = CMTimeGetSeconds(CMTimeSubtract(pts, baseMediaTime!))
        let ticks = elapsedSeconds * Double(RTPPacketizer.clockRate)
        return UInt32(truncatingIfNeeded: Int64(ticks))
    }
}
