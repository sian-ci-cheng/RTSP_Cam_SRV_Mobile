package veg.mediacapture.sdk.test.rtspserver;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wraps MediaCodec (configured with an input Surface, so Camera2 renders straight into it)
 * to turn camera frames into H.264 NAL units in real time.
 * Mirrors ios/RTSPCameraServer/Sources/H264Encoder.swift.
 */
final class H264Encoder {
    private static final String TAG = "H264Encoder";
    private static final String MIME_TYPE = "video/avc";

    interface Listener {
        void onEncodedFrame(EncodedFrame frame);
    }

    static final class EncodedFrame {
        /** Individual NAL units (no start code), in decode order. */
        final List<byte[]> nalUnits;
        final long presentationTimeUs;
        final boolean isKeyFrame;

        EncodedFrame(List<byte[]> nalUnits, long presentationTimeUs, boolean isKeyFrame) {
            this.nalUnits = nalUnits;
            this.presentationTimeUs = presentationTimeUs;
            this.isKeyFrame = isKeyFrame;
        }
    }

    private MediaCodec codec;
    private Surface inputSurface;
    private Thread drainThread;
    private volatile boolean running = false;

    volatile byte[] sps;
    volatile byte[] pps;

    // One-time correlation point (captured at the first real encoded frame) between this
    // encoder's presentationTimeUs clock and SystemClock.elapsedRealtimeNanos() -- lets a GPS
    // sample (timestamped via Location.getElapsedRealtimeNanos(), the same clock family) be
    // matched to the video frame captured at roughly the same real time.
    private volatile long referencePresentationTimeUs = -1;
    private volatile long referenceElapsedRealtimeNs = -1;

    /** Encoded access units fan out to every subscriber (RTSP server, later: local recorder)
     *  independently -- none of them reach back into this encoder or the camera. */
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    Runnable onParameterSetsReady;

    void addListener(Listener listener) {
        listeners.add(listener);
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Creates the encoder and returns its input Surface for the camera to target. */
    Surface start(int width, int height, int bitrate, int fps) {
        return start(width, height, bitrate, fps, 1);
    }

    /** Same as {@link #start(int, int, int, int)}, with an explicit keyframe interval -- the
     *  secondary (low-res) stream uses a longer one than the primary stream. */
    Surface start(int width, int height, int bitrate, int fps, int keyFrameIntervalSeconds) {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSeconds);

            codec = MediaCodec.createEncoderByType(MIME_TYPE);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = codec.createInputSurface();
            codec.start();

            running = true;
            drainThread = new Thread(this::drainLoop, "H264Encoder.drain");
            drainThread.start();
            return inputSurface;
        } catch (IOException e) {
            Log.e(TAG, "failed to create encoder", e);
            return null;
        }
    }

    void stop() {
        running = false;
        if (drainThread != null) {
            try {
                drainThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            drainThread = null;
        }
        if (codec != null) {
            try {
                codec.stop();
                codec.release();
            } catch (IllegalStateException ignored) {
            }
            codec = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        sps = null;
        pps = null;
        referencePresentationTimeUs = -1;
        referenceElapsedRealtimeNs = -1;
    }

    /** Converts this encoder's presentationTimeUs clock into SystemClock.elapsedRealtimeNanos(),
     *  using the one-time correlation point captured at the first encoded frame. Returns -1 if no
     *  frame has been encoded yet. */
    long toElapsedRealtimeNs(long presentationTimeUs) {
        long refPts = referencePresentationTimeUs;
        if (refPts < 0) return -1;
        return referenceElapsedRealtimeNs + (presentationTimeUs - refPts) * 1000L;
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            int index;
            try {
                index = codec.dequeueOutputBuffer(info, 100_000);
            } catch (IllegalStateException e) {
                break;
            }
            if (index >= 0) {
                ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                if (outputBuffer != null && info.size > 0) {
                    outputBuffer.position(info.offset);
                    outputBuffer.limit(info.offset + info.size);
                    byte[] data = new byte[info.size];
                    outputBuffer.get(data);
                    handleEncodedData(data, info.presentationTimeUs, info.flags);
                }
                codec.releaseOutputBuffer(index, false);
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = codec.getOutputFormat();
                extractParameterSets(newFormat);
            }
        }
    }

    private void extractParameterSets(MediaFormat format) {
        ByteBuffer csd0 = format.containsKey("csd-0") ? format.getByteBuffer("csd-0") : null;
        ByteBuffer csd1 = format.containsKey("csd-1") ? format.getByteBuffer("csd-1") : null;
        if (csd0 == null) return;

        boolean wasMissing = sps == null || pps == null;
        sps = stripStartCode(toByteArray(csd0));
        pps = csd1 != null ? stripStartCode(toByteArray(csd1)) : pps;
        if (wasMissing && sps != null && pps != null && onParameterSetsReady != null) {
            onParameterSetsReady.run();
        }
    }

    /** Splits an Annex-B buffer (NAL units delimited by 00 00 00 01 / 00 00 01 start codes)
     *  into individual NAL units, capturing SPS/PPS from any codec-config buffer along the way. */
    private void handleEncodedData(byte[] data, long presentationTimeUs, int flags) {
        List<byte[]> nalUnits = splitAnnexB(data);
        boolean isCodecConfig = (flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
        if (isCodecConfig) {
            captureParameterSetsFromNalUnits(nalUnits);
            return;
        }

        if (referencePresentationTimeUs < 0) {
            referencePresentationTimeUs = presentationTimeUs;
            referenceElapsedRealtimeNs = android.os.SystemClock.elapsedRealtimeNanos();
        }

        boolean isKeyFrame = (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        List<byte[]> outUnits = new ArrayList<>();
        if (isKeyFrame) {
            byte[] localSps = sps;
            byte[] localPps = pps;
            if (localSps != null) outUnits.add(localSps);
            if (localPps != null) outUnits.add(localPps);
        }
        outUnits.addAll(nalUnits);
        if (outUnits.isEmpty()) return;

        EncodedFrame frame = new EncodedFrame(outUnits, presentationTimeUs, isKeyFrame);
        for (Listener listener : listeners) {
            listener.onEncodedFrame(frame);
        }
    }

    private void captureParameterSetsFromNalUnits(List<byte[]> nalUnits) {
        boolean wasMissing = sps == null || pps == null;
        for (byte[] nal : nalUnits) {
            if (nal.length == 0) continue;
            int nalType = nal[0] & 0x1F;
            if (nalType == 7) sps = nal;
            else if (nalType == 8) pps = nal;
        }
        if (wasMissing && sps != null && pps != null && onParameterSetsReady != null) {
            onParameterSetsReady.run();
        }
    }

    private static List<byte[]> splitAnnexB(byte[] data) {
        List<byte[]> units = new ArrayList<>();
        int i = 0;
        int start = -1;
        while (i < data.length) {
            int scLen = startCodeLength(data, i);
            if (scLen > 0) {
                if (start >= 0) {
                    units.add(sliceTrim(data, start, i));
                }
                i += scLen;
                start = i;
            } else {
                i++;
            }
        }
        if (start >= 0 && start < data.length) {
            units.add(sliceTrim(data, start, data.length));
        }
        return units;
    }

    private static byte[] sliceTrim(byte[] data, int from, int to) {
        if (to <= from) return new byte[0];
        byte[] out = new byte[to - from];
        System.arraycopy(data, from, out, 0, to - from);
        return out;
    }

    private static int startCodeLength(byte[] data, int i) {
        if (i + 3 < data.length && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
            return 4;
        }
        if (i + 2 < data.length && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
            return 3;
        }
        return 0;
    }

    private static byte[] stripStartCode(byte[] data) {
        List<byte[]> units = splitAnnexB(data);
        return units.isEmpty() ? data : units.get(0);
    }

    private static byte[] toByteArray(ByteBuffer buffer) {
        ByteBuffer dup = buffer.duplicate();
        byte[] out = new byte[dup.remaining()];
        dup.get(out);
        return out;
    }
}
