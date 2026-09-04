package veg.mediacapture.sdk.test.rtspserver;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wraps MediaCodec to encode raw 16-bit PCM mono audio into AAC-LC access units in real time.
 * Mirrors ios/RTSPCameraServer/Sources/AACEncoder.swift; fed by AudioCaptureManager the same
 * way H264Encoder is fed by Camera2 -- this class never reaches back into the audio source.
 */
final class AACEncoder {
    private static final String TAG = "AACEncoder";

    static final int SAMPLE_RATE = 44100;
    static final int CHANNELS = 1;
    static final int SAMPLES_PER_FRAME = 1024;

    interface Listener {
        void onEncodedFrame(EncodedFrame frame);
    }

    static final class EncodedFrame {
        /** One raw AAC-LC access unit (no ADTS header) -- RTP payload per RFC 3640. */
        final byte[] data;
        final long presentationTimeUs;

        EncodedFrame(byte[] data, long presentationTimeUs) {
            this.data = data;
            this.presentationTimeUs = presentationTimeUs;
        }
    }

    private MediaCodec codec;
    private Thread drainThread;
    private volatile boolean running = false;
    private long sampleCount = 0;

    /** 2-byte MPEG-4 AudioSpecificConfig, required by the SDP fmtp `config=` field.
     *  Available once the encoder produces its first output. */
    volatile byte[] audioSpecificConfig;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    Runnable onConfigReady;

    void addListener(Listener listener) {
        listeners.add(listener);
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    boolean start(int bitrate) {
        try {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            sampleCount = 0;
            running = true;
            drainThread = new Thread(this::drainLoop, "AACEncoder.drain");
            drainThread.start();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "failed to create encoder", e);
            return false;
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
        audioSpecificConfig = null;
        sampleCount = 0;
    }

    /** Feeds one chunk of 16-bit PCM mono audio sampled at SAMPLE_RATE. */
    void encode(byte[] pcm, int length) {
        if (codec == null) return;
        try {
            int index = codec.dequeueInputBuffer(10_000);
            if (index < 0) return;
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null) return;
            buffer.clear();
            // Defensive: never overflow the codec's input buffer even if handed more than one
            // frame's worth of PCM -- the excess is dropped rather than crashing the capture thread.
            int toWrite = Math.min(length, buffer.remaining());
            buffer.put(pcm, 0, toWrite);
            long presentationTimeUs = (sampleCount * 1_000_000L) / SAMPLE_RATE;
            sampleCount += toWrite / 2; // 16-bit samples
            codec.queueInputBuffer(index, 0, toWrite, presentationTimeUs, 0);
        } catch (IllegalStateException ignored) {
        }
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
                boolean isCodecConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                if (outputBuffer != null && info.size > 0 && !isCodecConfig) {
                    outputBuffer.position(info.offset);
                    outputBuffer.limit(info.offset + info.size);
                    byte[] data = new byte[info.size];
                    outputBuffer.get(data);
                    EncodedFrame frame = new EncodedFrame(data, info.presentationTimeUs);
                    for (Listener listener : listeners) {
                        listener.onEncodedFrame(frame);
                    }
                }
                codec.releaseOutputBuffer(index, false);
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                extractAudioSpecificConfig(codec.getOutputFormat());
            }
        }
    }

    private void extractAudioSpecificConfig(MediaFormat format) {
        boolean wasMissing = audioSpecificConfig == null;
        if (format.containsKey("csd-0")) {
            audioSpecificConfig = toByteArray(format.getByteBuffer("csd-0"));
        } else {
            audioSpecificConfig = buildAudioSpecificConfig();
        }
        if (wasMissing && onConfigReady != null) {
            onConfigReady.run();
        }
    }

    /** Builds the 2-byte MPEG-4 AudioSpecificConfig for AAC-LC (used as a fallback if the codec
     *  doesn't surface csd-0): 5 bits object type (2 = AAC LC), 4 bits sampling-frequency index,
     *  4 bits channel config, 3 bits padding. */
    private static byte[] buildAudioSpecificConfig() {
        int objectType = 2;
        int freqIndex = samplingFrequencyIndex(SAMPLE_RATE);
        int channelConfig = CHANNELS;
        int config = (objectType << 11) | (freqIndex << 7) | (channelConfig << 3);
        return new byte[]{(byte) ((config >> 8) & 0xFF), (byte) (config & 0xFF)};
    }

    private static int samplingFrequencyIndex(int sampleRate) {
        int[] rates = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
        for (int i = 0; i < rates.length; i++) {
            if (rates[i] == sampleRate) return i;
        }
        return 4;
    }

    private static byte[] toByteArray(ByteBuffer buffer) {
        ByteBuffer dup = buffer.duplicate();
        byte[] out = new byte[dup.remaining()];
        dup.get(out);
        return out;
    }
}
