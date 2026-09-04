package veg.mediacapture.sdk.test.rtspserver;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Packetizes H.264 NAL units into RTP payloads per RFC 6184 (single NAL unit + FU-A modes).
 * Direct port of ios/RTSPCameraServer/Sources/RTPPacketizer.swift.
 */
final class RTPPacketizer {
    static final int MAX_PAYLOAD_SIZE = 1400;
    static final long CLOCK_RATE = 90_000;

    private RTPPacketizer() {}

    static final class Packet {
        final byte[] payload; // includes the 12-byte RTP header

        Packet(byte[] payload) {
            this.payload = payload;
        }
    }

    static final class Result {
        final List<Packet> packets;
        final int nextSequence;

        Result(List<Packet> packets, int nextSequence) {
            this.packets = packets;
            this.nextSequence = nextSequence;
        }
    }

    /** Builds RTP packets for one access unit (frame), made of one or more NAL units.
     *  The marker bit is set on the last packet, per RFC 3984, to signal end-of-frame. */
    static Result packetize(List<byte[]> nalUnits, int sequenceStart, long timestamp, int ssrc, int payloadType) {
        List<Packet> packets = new ArrayList<>();
        int seq = sequenceStart & 0xFFFF;

        for (int index = 0; index < nalUnits.size(); index++) {
            byte[] nal = nalUnits.get(index);
            boolean isLastNal = index == nalUnits.size() - 1;
            if (nal.length <= MAX_PAYLOAD_SIZE) {
                byte[] header = rtpHeader(seq, timestamp, ssrc, isLastNal, payloadType);
                packets.add(new Packet(concat(header, nal)));
                seq = (seq + 1) & 0xFFFF;
            } else {
                List<byte[]> fragments = fragmentNAL(nal);
                for (int fragIndex = 0; fragIndex < fragments.size(); fragIndex++) {
                    boolean isLastFragment = fragIndex == fragments.size() - 1;
                    boolean marker = isLastNal && isLastFragment;
                    byte[] header = rtpHeader(seq, timestamp, ssrc, marker, payloadType);
                    packets.add(new Packet(concat(header, fragments.get(fragIndex))));
                    seq = (seq + 1) & 0xFFFF;
                }
            }
        }
        return new Result(packets, seq);
    }

    /** Builds a single RTP packet for one AAC access unit, per RFC 3640 3.2.1 ("AAC-hbr").
     *  Assumes the AU fits in one packet. */
    static Packet packetizeAAC(byte[] auData, int sequence, long timestamp, int ssrc, int payloadType) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        // AU-headers-length, in bits: one 16-bit AU-header follows.
        payload.write(0x00);
        payload.write(0x10);
        // AU-header: 13-bit AU-size, 3-bit AU-Index (0 for the first/only AU in this packet).
        int auHeader = (auData.length & 0x1FFF) << 3;
        payload.write((auHeader >> 8) & 0xFF);
        payload.write(auHeader & 0xFF);
        payload.write(auData, 0, auData.length);

        byte[] header = rtpHeader(sequence, timestamp, ssrc, true, payloadType);
        return new Packet(concat(header, payload.toByteArray()));
    }

    /** Builds a single RTP packet carrying a raw metadata payload (e.g. GPS JSON) -- no framing
     *  beyond the RTP header itself, since one sample is always well under the MTU. */
    static Packet packetizeMetadata(byte[] payload, int sequence, long timestamp, int ssrc, int payloadType) {
        byte[] header = rtpHeader(sequence, timestamp, ssrc, true, payloadType);
        return new Packet(concat(header, payload));
    }

    private static List<byte[]> fragmentNAL(byte[] nal) {
        List<byte[]> fragments = new ArrayList<>();
        if (nal.length == 0) return fragments;

        int nalHeader = nal[0] & 0xFF;
        int forbiddenAndNRI = nalHeader & 0b1110_0000;
        int nalType = nalHeader & 0b0001_1111;

        int payloadLength = nal.length - 1;
        int chunkSize = MAX_PAYLOAD_SIZE - 2; // FU indicator + FU header
        int offset = 0;

        while (offset < payloadLength) {
            int end = Math.min(offset + chunkSize, payloadLength);
            boolean isFirst = offset == 0;
            boolean isLast = end == payloadLength;

            int fuIndicator = forbiddenAndNRI | 28; // FU-A type
            int fuHeader = nalType;
            if (isFirst) fuHeader |= 0b1000_0000;
            if (isLast) fuHeader |= 0b0100_0000;

            byte[] fragment = new byte[2 + (end - offset)];
            fragment[0] = (byte) fuIndicator;
            fragment[1] = (byte) fuHeader;
            System.arraycopy(nal, 1 + offset, fragment, 2, end - offset);
            fragments.add(fragment);

            offset = end;
        }
        return fragments;
    }

    private static byte[] rtpHeader(int sequence, long timestamp, int ssrc, boolean marker, int payloadType) {
        byte[] header = new byte[12];
        header[0] = (byte) 0x80; // version 2, no padding/extension/CSRC
        header[1] = (byte) ((marker ? 0x80 : 0x00) | (payloadType & 0x7F));
        header[2] = (byte) ((sequence >> 8) & 0xFF);
        header[3] = (byte) (sequence & 0xFF);
        header[4] = (byte) ((timestamp >> 24) & 0xFF);
        header[5] = (byte) ((timestamp >> 16) & 0xFF);
        header[6] = (byte) ((timestamp >> 8) & 0xFF);
        header[7] = (byte) (timestamp & 0xFF);
        header[8] = (byte) ((ssrc >> 24) & 0xFF);
        header[9] = (byte) ((ssrc >> 16) & 0xFF);
        header[10] = (byte) ((ssrc >> 8) & 0xFF);
        header[11] = (byte) (ssrc & 0xFF);
        return header;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
