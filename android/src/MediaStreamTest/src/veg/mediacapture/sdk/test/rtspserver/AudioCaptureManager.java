package veg.mediacapture.sdk.test.rtspserver;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

/**
 * Captures microphone audio via AudioRecord and feeds raw PCM chunks into an AACEncoder.
 * Mirrors ios/RTSPCameraServer/Sources/AudioCaptureManager.swift; only ever talks to the
 * encoder it was given, never to the RTSP server or the video pipeline.
 */
final class AudioCaptureManager {
    private static final String TAG = "AudioCaptureManager";

    private final AACEncoder encoder;
    private AudioRecord audioRecord;
    private Thread captureThread;
    private volatile boolean running = false;

    AudioCaptureManager(AACEncoder encoder) {
        this.encoder = encoder;
    }

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Starts capturing. Returns false (leaving the caller to fall back to video-only) if the
     *  microphone can't be opened. */
    boolean start() {
        int minBufferSize = AudioRecord.getMinBufferSize(
                AACEncoder.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize <= 0) {
            Log.e(TAG, "unsupported AudioRecord configuration");
            return false;
        }
        int bufferSize = minBufferSize * 2;

        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, AACEncoder.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        } catch (SecurityException e) {
            Log.e(TAG, "microphone permission denied", e);
            return false;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release();
            audioRecord = null;
            return false;
        }

        audioRecord.startRecording();
        running = true;
        captureThread = new Thread(this::captureLoop, "AudioCaptureManager.capture");
        captureThread.start();
        return true;
    }

    /** Reads exactly one AAC access unit's worth of PCM per iteration (SAMPLES_PER_FRAME
     *  16-bit mono samples) so every chunk handed to the encoder fits its input buffer,
     *  regardless of how large AudioRecord's own internal buffer is. */
    private void captureLoop() {
        int frameBytes = AACEncoder.SAMPLES_PER_FRAME * 2;
        byte[] buffer = new byte[frameBytes];
        while (running) {
            int read = audioRecord.read(buffer, 0, frameBytes);
            if (read > 0) {
                encoder.encode(buffer, read);
            }
        }
    }

    void stop() {
        running = false;
        if (captureThread != null) {
            try {
                captureThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException ignored) {
            }
            audioRecord.release();
            audioRecord = null;
        }
    }
}
