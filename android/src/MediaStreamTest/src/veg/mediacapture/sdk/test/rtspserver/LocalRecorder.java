package veg.mediacapture.sdk.test.rtspserver;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Muxes the same encoded access units RTSPServer streams into a local MP4 file via MediaMuxer.
 * Subscribes to H264Encoder/AACEncoder as an independent listener -- it never touches Camera2,
 * the microphone, or RTSPServer, and a recording failure never affects the live stream (every
 * MediaMuxer call is caught so an exception here can't kill the shared encoder drain thread).
 */
final class LocalRecorder implements H264Encoder.Listener, AACEncoder.Listener, GPSLocationManager.Listener {
    private static final String TAG = "LocalRecorder";

    interface Listener {
        void onError(String message);
    }

    private static final class PendingSample {
        final ByteBuffer data;
        final long presentationTimeUs;
        final int flags;

        PendingSample(ByteBuffer data, long presentationTimeUs, int flags) {
            this.data = data;
            this.presentationTimeUs = presentationTimeUs;
            this.flags = flags;
        }
    }

    private final Object lock = new Object();
    private final String outputPath;
    private final H264Encoder videoEncoder;
    private final AACEncoder audioEncoder; // null if no audio track is expected
    private final GPSLocationManager gpsManager; // null if GPS is not expected
    private final int width;
    private final int height;

    private MediaMuxer muxer;
    private BufferedWriter gpsWriter;
    private boolean videoTrackAdded;
    private boolean audioTrackAdded;
    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;
    private boolean muxerStarted;
    private boolean stopped;

    private Long videoBaseUs;
    private Long audioBaseUs;

    // Holds samples that arrive before every expected track has been added -- MediaMuxer forbids
    // writeSampleData() before start(), and start() forbids addTrack() after it, so whichever of
    // video/audio arrives first has to wait here for the other one.
    private final List<PendingSample> pendingVideo = new ArrayList<>();
    private final List<PendingSample> pendingAudio = new ArrayList<>();

    Listener listener;

    LocalRecorder(String outputPath, H264Encoder videoEncoder, AACEncoder audioEncoder,
                  GPSLocationManager gpsManager, int width, int height) {
        this.outputPath = outputPath;
        this.videoEncoder = videoEncoder;
        this.audioEncoder = audioEncoder;
        this.gpsManager = gpsManager;
        this.width = width;
        this.height = height;
    }

    /** Creates the muxer and starts listening for encoded frames. Recording actually begins once
     *  the video track (and, if audioEncoder is non-null, the audio track too) has real codec
     *  config to build a MediaFormat from -- returns false if the muxer file can't be created.
     *  The GPS sidecar (if gpsManager is non-null) is independent of muxer/track lifecycle: it's
     *  just a plain text file appended to as samples arrive, opened best-effort -- a failure to
     *  open it never blocks the MP4 recording itself. */
    boolean start() {
        try {
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException e) {
            Log.e(TAG, "failed to create MediaMuxer for " + outputPath, e);
            return false;
        }
        videoEncoder.addListener(this);
        if (audioEncoder != null) {
            audioEncoder.addListener(this);
        }
        if (gpsManager != null) {
            try {
                gpsWriter = new BufferedWriter(new FileWriter(gpsSidecarPath()));
            } catch (IOException e) {
                Log.e(TAG, "failed to create GPS sidecar for " + outputPath, e);
                gpsWriter = null;
            }
            gpsManager.addListener(this);
        }
        return true;
    }

    void stop() {
        synchronized (lock) {
            stopped = true;
            videoEncoder.removeListener(this);
            if (audioEncoder != null) {
                audioEncoder.removeListener(this);
            }
            if (gpsManager != null) {
                gpsManager.removeListener(this);
            }
            if (muxer != null) {
                try {
                    if (muxerStarted) muxer.stop();
                } catch (RuntimeException e) {
                    Log.e(TAG, "muxer.stop() failed", e);
                }
                try {
                    muxer.release();
                } catch (RuntimeException ignored) {
                }
                muxer = null;
            }
            if (gpsWriter != null) {
                try {
                    gpsWriter.close();
                } catch (IOException ignored) {
                }
                gpsWriter = null;
            }
            pendingVideo.clear();
            pendingAudio.clear();
        }
    }

    private String gpsSidecarPath() {
        return outputPath.endsWith(".mp4")
                ? outputPath.substring(0, outputPath.length() - 4) + ".gps.jsonl"
                : outputPath + ".gps.jsonl";
    }

    /** GPS samples arrive independently of A/V, at their own (typically ~1Hz) rate -- each one is
     *  just appended as a JSONL line, with no track/muxer lifecycle to coordinate. */
    @Override
    public void onSample(GPSLocationManager.GPSSample sample) {
        synchronized (lock) {
            if (stopped || gpsWriter == null) return;
            try {
                gpsWriter.write(sample.toJson());
                gpsWriter.newLine();
                gpsWriter.flush();
            } catch (IOException e) {
                Log.e(TAG, "failed to write GPS sample: " + e.getMessage());
            }
        }
    }

    @Override
    public void onEncodedFrame(H264Encoder.EncodedFrame frame) {
        synchronized (lock) {
            if (stopped || muxer == null) return;
            try {
                if (!videoTrackAdded) {
                    byte[] sps = videoEncoder.sps;
                    byte[] pps = videoEncoder.pps;
                    if (!frame.isKeyFrame || sps == null || pps == null) return;
                    MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
                    format.setByteBuffer("csd-0", withStartCode(sps));
                    format.setByteBuffer("csd-1", withStartCode(pps));
                    videoTrackIndex = muxer.addTrack(format);
                    videoTrackAdded = true;
                }

                ByteBuffer data = concatAnnexB(frame.nalUnits);
                int flags = frame.isKeyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                PendingSample sample = new PendingSample(data, frame.presentationTimeUs, flags);

                if (!muxerStarted) {
                    pendingVideo.add(sample);
                    maybeStart();
                } else {
                    writeVideoSample(sample);
                }
            } catch (RuntimeException e) {
                reportError("video mux error: " + e.getMessage());
            }
        }
    }

    @Override
    public void onEncodedFrame(AACEncoder.EncodedFrame frame) {
        synchronized (lock) {
            if (stopped || muxer == null) return;
            try {
                if (!audioTrackAdded) {
                    byte[] config = audioEncoder.audioSpecificConfig;
                    if (config == null) return;
                    MediaFormat format = MediaFormat.createAudioFormat(
                            MediaFormat.MIMETYPE_AUDIO_AAC, AACEncoder.SAMPLE_RATE, AACEncoder.CHANNELS);
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(config));
                    audioTrackIndex = muxer.addTrack(format);
                    audioTrackAdded = true;
                }

                PendingSample sample = new PendingSample(ByteBuffer.wrap(frame.data), frame.presentationTimeUs, 0);

                if (!muxerStarted) {
                    pendingAudio.add(sample);
                    maybeStart();
                } else {
                    writeAudioSample(sample);
                }
            } catch (RuntimeException e) {
                reportError("audio mux error: " + e.getMessage());
            }
        }
    }

    /** Starts the muxer once every expected track has been added, then flushes anything buffered
     *  while waiting -- handles video-first or audio-first arrival either way. Called with `lock`
     *  already held. */
    private void maybeStart() {
        boolean audioExpected = audioEncoder != null;
        if (!videoTrackAdded) return;
        if (audioExpected && !audioTrackAdded) return;

        muxer.start();
        muxerStarted = true;

        for (PendingSample sample : pendingVideo) writeVideoSample(sample);
        pendingVideo.clear();
        for (PendingSample sample : pendingAudio) writeAudioSample(sample);
        pendingAudio.clear();
    }

    private void writeVideoSample(PendingSample sample) {
        if (videoBaseUs == null) videoBaseUs = sample.presentationTimeUs;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, sample.data.remaining(), Math.max(0, sample.presentationTimeUs - videoBaseUs), sample.flags);
        try {
            muxer.writeSampleData(videoTrackIndex, sample.data, info);
        } catch (RuntimeException e) {
            reportError("failed to write video sample: " + e.getMessage());
        }
    }

    private void writeAudioSample(PendingSample sample) {
        if (audioBaseUs == null) audioBaseUs = sample.presentationTimeUs;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, sample.data.remaining(), Math.max(0, sample.presentationTimeUs - audioBaseUs), sample.flags);
        try {
            muxer.writeSampleData(audioTrackIndex, sample.data, info);
        } catch (RuntimeException e) {
            reportError("failed to write audio sample: " + e.getMessage());
        }
    }

    private void reportError(String message) {
        Log.e(TAG, message);
        if (listener != null) listener.onError(message);
    }

    private static ByteBuffer withStartCode(byte[] nal) {
        ByteBuffer buffer = ByteBuffer.allocate(4 + nal.length);
        buffer.put(new byte[]{0, 0, 0, 1});
        buffer.put(nal);
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer concatAnnexB(List<byte[]> nalUnits) {
        int total = 0;
        for (byte[] nal : nalUnits) total += 4 + nal.length;
        ByteBuffer buffer = ByteBuffer.allocate(total);
        for (byte[] nal : nalUnits) {
            buffer.put(new byte[]{0, 0, 0, 1});
            buffer.put(nal);
        }
        buffer.flip();
        return buffer;
    }
}
