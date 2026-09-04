package veg.mediacapture.sdk.test.rtspserver;

import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal single/multi-client RTSP server: OPTIONS/DESCRIBE/SETUP/PLAY/TEARDOWN over
 * RTP-over-TCP interleaved mode (RFC 2326 10.12). No RTSP/UDP transport.
 * Direct port of ios/RTSPCameraServer/Sources/RTSPServer.swift onto blocking Java sockets.
 */
final class RTSPServer {
    private static final String TAG = "RTSPServer";
    private static final Random RANDOM = new Random();
    private static final long GPS_CLOCK_RATE = 1000;

    interface StatusListener {
        void onStatusChange(String status);
    }

    private final class ClientSession {
        final Socket socket;
        final OutputStream out;
        final Object writeLock = new Object();
        final String rtspSessionId = String.format("%08X", RANDOM.nextInt());
        volatile boolean isPlaying = false;
        int sequenceNumber = RANDOM.nextInt(0xFFFF);
        final int ssrc = RANDOM.nextInt();
        final int rtpChannel = 0;
        final int rtcpChannel = 1;

        int audioSequenceNumber = RANDOM.nextInt(0xFFFF);
        final int audioSsrc = RANDOM.nextInt();
        final int audioRtpChannel = 2;
        final int audioRtcpChannel = 3;

        int secondarySequenceNumber = RANDOM.nextInt(0xFFFF);
        final int secondarySsrc = RANDOM.nextInt();
        final int secondaryRtpChannel = 4;
        final int secondaryRtcpChannel = 5;

        int gpsSequenceNumber = RANDOM.nextInt(0xFFFF);
        final int gpsSsrc = RANDOM.nextInt();
        final int gpsRtpChannel = 6;
        final int gpsRtcpChannel = 7;

        ClientSession(Socket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
        }
    }

    private final int port;
    private final H264Encoder encoder;
    private final AACEncoder audioEncoder;
    private final H264Encoder secondaryEncoder;
    private final GPSLocationManager gpsManager;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final Map<Socket, ClientSession> clients = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    private Long baseTimeUs = null;
    private long audioTimestamp = 0;
    private Long secondaryBaseTimeUs = null;
    private Long gpsBaseTimeUs = null;
    private volatile GPSLocationManager.GPSSample lastGpsSample;

    StatusListener onStatusChange;

    /** {@code audioEncoder}, {@code secondaryEncoder} and {@code gpsManager} are all optional:
     *  pass null (or one whose config never becomes available -- microphone denied / secondary
     *  camera stream failed to start / location permission denied) to serve without that track,
     *  exactly as before each existed. */
    RTSPServer(int port, H264Encoder encoder, AACEncoder audioEncoder, H264Encoder secondaryEncoder,
               GPSLocationManager gpsManager) {
        this.port = port;
        this.encoder = encoder;
        this.audioEncoder = audioEncoder;
        this.secondaryEncoder = secondaryEncoder;
        this.gpsManager = gpsManager;
        this.encoder.addListener(this::broadcast);
        if (this.audioEncoder != null) {
            this.audioEncoder.addListener(this::broadcastAudio);
        }
        if (this.secondaryEncoder != null) {
            this.secondaryEncoder.addListener(this::broadcastSecondary);
        }
        if (this.gpsManager != null) {
            this.gpsManager.addListener(this::broadcastGPS);
        }
    }

    void start() {
        running = true;
        acceptThread = new Thread(this::acceptLoop, "RTSPServer.accept");
        acceptThread.start();
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(port);
            notifyStatus("listening on port " + port);
            while (running) {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                acceptClient(socket);
            }
        } catch (IOException e) {
            if (running) {
                notifyStatus("failed: " + e.getMessage());
                Log.e(TAG, "accept loop failed", e);
            }
        }
    }

    private void acceptClient(Socket socket) {
        try {
            ClientSession client = new ClientSession(socket);
            clients.put(socket, client);
            Thread reader = new Thread(() -> readLoop(client), "RTSPServer.client");
            reader.start();
        } catch (IOException e) {
            Log.e(TAG, "failed to accept client", e);
            closeQuietly(socket);
        }
    }

    private void readLoop(ClientSession client) {
        try {
            InputStream in = client.socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            while (running) {
                String requestLine = reader.readLine();
                if (requestLine == null) break;
                if (requestLine.trim().isEmpty()) continue;

                Map<String, String> headers = new HashMap<>();
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon < 0) continue;
                    String key = line.substring(0, colon).trim().toLowerCase();
                    String value = line.substring(colon + 1).trim();
                    headers.put(key, value);
                }
                handleRequest(requestLine, headers, client);
            }
        } catch (IOException e) {
            // client disconnected
        } finally {
            clients.remove(client.socket);
            closeQuietly(client.socket);
        }
    }

    private void handleRequest(String requestLine, Map<String, String> headers, ClientSession client) {
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return;
        String method = parts[0];
        String url = parts[1];
        String cseq = headers.containsKey("cseq") ? headers.get("cseq") : "0";

        String response;
        switch (method) {
            case "OPTIONS":
                response = optionsResponse(cseq);
                break;
            case "DESCRIBE":
                response = describeResponse(cseq);
                break;
            case "SETUP":
                response = setupResponse(cseq, client, url);
                break;
            case "PLAY":
                client.isPlaying = true;
                response = playResponse(cseq, client);
                break;
            case "TEARDOWN":
                client.isPlaying = false;
                response = simpleResponse(cseq);
                break;
            case "GET_PARAMETER":
                response = simpleResponse(cseq);
                break;
            default:
                response = "RTSP/1.0 501 Not Implemented\r\nCSeq: " + cseq + "\r\n\r\n";
                break;
        }
        send(client, response);
    }

    private String optionsResponse(String cseq) {
        return "RTSP/1.0 200 OK\r\nCSeq: " + cseq
                + "\r\nPublic: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER\r\n\r\n";
    }

    private String describeResponse(String cseq) {
        byte[] sps = encoder.sps;
        byte[] pps = encoder.pps;
        if (sps == null || pps == null) {
            return "RTSP/1.0 503 Service Unavailable\r\nCSeq: " + cseq + "\r\n\r\n";
        }
        String profileLevelId = sps.length >= 3
                ? String.format("%02X%02X%02X", sps[0], sps[1], sps[2])
                : "42001E";
        String spropParameterSets = Base64.encodeToString(sps, Base64.NO_WRAP) + ","
                + Base64.encodeToString(pps, Base64.NO_WRAP);

        List<String> lines = new ArrayList<>();
        lines.add("v=0");
        lines.add("o=- 0 0 IN IP4 0.0.0.0");
        lines.add("s=RTSPCameraServer");
        lines.add("c=IN IP4 0.0.0.0");
        lines.add("t=0 0");
        lines.add("a=tool:RTSPCameraServer");
        lines.add("m=video 0 RTP/AVP 96");
        lines.add("a=rtpmap:96 H264/90000");
        lines.add("a=fmtp:96 packetization-mode=1;profile-level-id=" + profileLevelId
                + ";sprop-parameter-sets=" + spropParameterSets);
        lines.add("a=control:track1");

        // Only advertise the audio track once the encoder has actually produced its
        // AudioSpecificConfig (microphone granted and the encoder started) -- a client that
        // never sees an m=audio line here simply plays video-only, exactly like before audio
        // support existed.
        byte[] audioConfig = audioEncoder != null ? audioEncoder.audioSpecificConfig : null;
        if (audioConfig != null) {
            StringBuilder configHex = new StringBuilder();
            for (byte b : audioConfig) configHex.append(String.format("%02X", b));
            lines.add("m=audio 0 RTP/AVP 97");
            lines.add("a=rtpmap:97 mpeg4-generic/" + AACEncoder.SAMPLE_RATE + "/" + AACEncoder.CHANNELS);
            lines.add("a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;"
                    + "indexdeltalength=3;config=" + configHex);
            lines.add("a=control:track2");
        }

        // Only advertise the secondary video track once ITS encoder has produced its own
        // SPS/PPS -- a client that never sees a second m=video line here simply plays the
        // primary stream, exactly as before the secondary stream existed.
        byte[] secondarySps = secondaryEncoder != null ? secondaryEncoder.sps : null;
        byte[] secondaryPps = secondaryEncoder != null ? secondaryEncoder.pps : null;
        if (secondarySps != null && secondaryPps != null) {
            String secondaryProfileLevelId = secondarySps.length >= 3
                    ? String.format("%02X%02X%02X", secondarySps[0], secondarySps[1], secondarySps[2])
                    : "42001E";
            String secondarySpropParameterSets = Base64.encodeToString(secondarySps, Base64.NO_WRAP) + ","
                    + Base64.encodeToString(secondaryPps, Base64.NO_WRAP);
            lines.add("m=video 0 RTP/AVP 98");
            lines.add("a=rtpmap:98 H264/90000");
            lines.add("a=fmtp:98 packetization-mode=1;profile-level-id=" + secondaryProfileLevelId
                    + ";sprop-parameter-sets=" + secondarySpropParameterSets);
            lines.add("a=control:track3");
        }

        // Only advertise the GPS metadata track once at least one location sample has actually
        // arrived (permission granted and a provider is producing fixes) -- a client that never
        // sees this line simply gets no GPS metadata, exactly like the audio/secondary pattern.
        if (gpsManager != null && lastGpsSample != null) {
            lines.add("m=application 0 RTP/AVP 99");
            lines.add("a=rtpmap:99 gps-metadata/" + GPS_CLOCK_RATE);
            lines.add("a=fmtp:99 encoding=json");
            lines.add("a=control:track4");
        }

        StringBuilder body = new StringBuilder();
        for (String l : lines) body.append(l).append("\r\n");
        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);

        return "RTSP/1.0 200 OK\r\nCSeq: " + cseq
                + "\r\nContent-Base: rtsp://0.0.0.0/\r\nContent-Type: application/sdp"
                + "\r\nContent-Length: " + bodyBytes.length + "\r\n\r\n" + body;
    }

    private String setupResponse(String cseq, ClientSession client, String url) {
        int rtpChannel;
        int rtcpChannel;
        if (url.endsWith("track2")) {
            rtpChannel = client.audioRtpChannel;
            rtcpChannel = client.audioRtcpChannel;
        } else if (url.endsWith("track3")) {
            rtpChannel = client.secondaryRtpChannel;
            rtcpChannel = client.secondaryRtcpChannel;
        } else if (url.endsWith("track4")) {
            rtpChannel = client.gpsRtpChannel;
            rtcpChannel = client.gpsRtcpChannel;
        } else {
            rtpChannel = client.rtpChannel;
            rtcpChannel = client.rtcpChannel;
        }
        String transport = "RTP/AVP/TCP;unicast;interleaved=" + rtpChannel + "-" + rtcpChannel;
        return "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\nTransport: " + transport
                + "\r\nSession: " + client.rtspSessionId + "\r\n\r\n";
    }

    private String playResponse(String cseq, ClientSession client) {
        return "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\nSession: " + client.rtspSessionId
                + "\r\nRange: npt=0.000-\r\n\r\n";
    }

    private String simpleResponse(String cseq) {
        return "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n\r\n";
    }

    private void send(ClientSession client, String response) {
        byte[] data = response.getBytes(StandardCharsets.UTF_8);
        synchronized (client.writeLock) {
            try {
                client.out.write(data);
                client.out.flush();
            } catch (IOException e) {
                // will be cleaned up by the read loop
            }
        }
    }

    private void broadcast(H264Encoder.EncodedFrame frame) {
        if (clients.isEmpty()) return;
        long timestamp = rtpTimestamp(frame.presentationTimeUs);

        for (ClientSession client : clients.values()) {
            if (!client.isPlaying) continue;
            RTPPacketizer.Result result = RTPPacketizer.packetize(
                    frame.nalUnits, client.sequenceNumber, timestamp, client.ssrc, 96);
            client.sequenceNumber = result.nextSequence;
            sendInterleaved(result.packets, client.rtpChannel, client);
        }
    }

    private void broadcastAudio(AACEncoder.EncodedFrame frame) {
        if (clients.isEmpty()) return;

        long timestamp = audioTimestamp;
        // AAC-LC always encodes a fixed 1024 samples per frame, so the RTP clock (which runs at
        // the sample rate for mpeg4-generic) advances by exactly that much each time.
        audioTimestamp = (audioTimestamp + AACEncoder.SAMPLES_PER_FRAME) & 0xFFFFFFFFL;

        for (ClientSession client : clients.values()) {
            if (!client.isPlaying) continue;
            RTPPacketizer.Packet packet = RTPPacketizer.packetizeAAC(
                    frame.data, client.audioSequenceNumber, timestamp, client.audioSsrc, 97);
            client.audioSequenceNumber = (client.audioSequenceNumber + 1) & 0xFFFF;
            sendInterleaved(java.util.Collections.singletonList(packet), client.audioRtpChannel, client);
        }
    }

    private void broadcastSecondary(H264Encoder.EncodedFrame frame) {
        if (clients.isEmpty()) return;
        if (secondaryBaseTimeUs == null) secondaryBaseTimeUs = frame.presentationTimeUs;
        double elapsedSeconds = (frame.presentationTimeUs - secondaryBaseTimeUs) / 1_000_000.0;
        long timestamp = (long) (elapsedSeconds * RTPPacketizer.CLOCK_RATE) & 0xFFFFFFFFL;

        for (ClientSession client : clients.values()) {
            if (!client.isPlaying) continue;
            RTPPacketizer.Result result = RTPPacketizer.packetize(
                    frame.nalUnits, client.secondarySequenceNumber, timestamp, client.secondarySsrc, 98);
            client.secondarySequenceNumber = result.nextSequence;
            sendInterleaved(result.packets, client.secondaryRtpChannel, client);
        }
    }

    /** GPS samples arrive at a low, independent rate (typically ~1Hz) -- unlike video/audio there
     *  is no per-frame correspondence to maintain, each sample is just broadcast as it arrives. */
    private void broadcastGPS(GPSLocationManager.GPSSample sample) {
        lastGpsSample = sample;
        if (clients.isEmpty()) return;

        long presentationTimeUs = sample.elapsedRealtimeNs / 1000L;
        if (gpsBaseTimeUs == null) gpsBaseTimeUs = presentationTimeUs;
        double elapsedSeconds = (presentationTimeUs - gpsBaseTimeUs) / 1_000_000.0;
        long timestamp = (long) (elapsedSeconds * GPS_CLOCK_RATE) & 0xFFFFFFFFL;

        byte[] payload = sample.toJson().getBytes(StandardCharsets.UTF_8);
        for (ClientSession client : clients.values()) {
            if (!client.isPlaying) continue;
            RTPPacketizer.Packet packet = RTPPacketizer.packetizeMetadata(
                    payload, client.gpsSequenceNumber, timestamp, client.gpsSsrc, 99);
            client.gpsSequenceNumber = (client.gpsSequenceNumber + 1) & 0xFFFF;
            sendInterleaved(java.util.Collections.singletonList(packet), client.gpsRtpChannel, client);
        }
    }

    private void sendInterleaved(List<RTPPacketizer.Packet> packets, int channel, ClientSession client) {
        int total = 0;
        for (RTPPacketizer.Packet p : packets) total += 4 + p.payload.length;
        byte[] outgoing = new byte[total];
        int offset = 0;
        for (RTPPacketizer.Packet p : packets) {
            int length = p.payload.length;
            outgoing[offset++] = 0x24; // '$'
            outgoing[offset++] = (byte) channel;
            outgoing[offset++] = (byte) ((length >> 8) & 0xFF);
            outgoing[offset++] = (byte) (length & 0xFF);
            System.arraycopy(p.payload, 0, outgoing, offset, length);
            offset += length;
        }
        synchronized (client.writeLock) {
            try {
                client.out.write(outgoing);
                client.out.flush();
            } catch (IOException e) {
                // dropped; the read loop will notice the disconnect and clean up
            }
        }
    }

    private long rtpTimestamp(long presentationTimeUs) {
        if (baseTimeUs == null) {
            baseTimeUs = presentationTimeUs;
        }
        double elapsedSeconds = (presentationTimeUs - baseTimeUs) / 1_000_000.0;
        return (long) (elapsedSeconds * RTPPacketizer.CLOCK_RATE) & 0xFFFFFFFFL;
    }

    private void notifyStatus(String status) {
        if (onStatusChange != null) onStatusChange.onStatusChange(status);
    }

    void stop() {
        running = false;
        for (ClientSession client : clients.values()) {
            closeQuietly(client.socket);
        }
        clients.clear();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        if (acceptThread != null) {
            try {
                acceptThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            acceptThread = null;
        }
        baseTimeUs = null;
        audioTimestamp = 0;
        secondaryBaseTimeUs = null;
        gpsBaseTimeUs = null;
        lastGpsSample = null;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
