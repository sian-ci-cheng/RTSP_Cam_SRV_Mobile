package veg.mediacapture.sdk.test.rtspserver;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.hardware.camera2.CameraManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;

import java.io.File;

/**
 * Orchestrates camera capture, H.264 encoding and the RTSP server as one unit.
 * Mirrors the role ios/RTSPCameraServer/Sources/ContentView.swift plays for the iOS app:
 * owns one CameraCaptureManager + H264Encoder + RTSPServer and exposes a simple
 * start/stop + status-string listener for the Activity to bind to.
 */
public final class CameraStreamer {
    public interface StatusListener {
        void onStatusChanged(String status);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final int SECONDARY_WIDTH = 320;
    private static final int SECONDARY_HEIGHT = 240;
    private static final int SECONDARY_FPS = 5;
    private static final int SECONDARY_BITRATE_KBPS = 128;
    private static final int SECONDARY_KEY_FRAME_INTERVAL_SECONDS = 2;

    private CameraCaptureManager cameraManager;
    private H264Encoder encoder;
    private H264Encoder secondaryEncoder;
    private AACEncoder audioEncoder;
    private AudioCaptureManager audioCaptureManager;
    private GPSLocationManager gpsManager;
    private RTSPServer rtspServer;
    private LocalRecorder localRecorder;

    private int port;
    public StatusListener statusListener;
    /** Path of the file the most recent recording was (or is being) written to, if any. */
    public String lastRecordingPath;

    public CameraStreamer(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Widths (from CameraCapabilities.CANDIDATE_WIDTHS) the back camera actually supports. */
    public static java.util.List<Integer> supportedWidths(Context context) {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        return CameraCapabilities.supportedWidths(manager);
    }

    public void start(int requestedWidth, int requestedHeight, int bitrateKbps, int fps, int port,
                       boolean audioEnabled, int audioBitrateKbps, boolean recordEnabled,
                       boolean secondaryEnabled, boolean gpsEnabled, long gpsUpdateIntervalMs,
                       TextureView preview) {
        this.port = port;
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        Size size = CameraCaptureManager.nearestSupportedSize(manager, requestedWidth, requestedHeight);

        encoder = new H264Encoder();
        encoder.onParameterSetsReady = () -> notifyStatus(readyStatus());
        Surface encoderSurface = encoder.start(size.getWidth(), size.getHeight(), bitrateKbps * 1000, fps);
        if (encoderSurface == null) {
            notifyStatus("failed to start encoder");
            return;
        }

        // Best-effort: denied microphone access or a start failure just leaves the stream
        // video-only, exactly like before audio support existed.
        if (audioEnabled && AudioCaptureManager.hasPermission(context)) {
            AACEncoder candidateAudioEncoder = new AACEncoder();
            candidateAudioEncoder.onConfigReady = () -> notifyStatus(readyStatus());
            if (candidateAudioEncoder.start(audioBitrateKbps * 1000)) {
                AudioCaptureManager candidateAudioCapture = new AudioCaptureManager(candidateAudioEncoder);
                if (candidateAudioCapture.start()) {
                    audioEncoder = candidateAudioEncoder;
                    audioCaptureManager = candidateAudioCapture;
                } else {
                    candidateAudioEncoder.stop();
                }
            }
        }

        // Best-effort, like audio: a failure here just leaves the stream primary-only, and the
        // secondary encoder's Surface is simply omitted from the capture session below.
        Surface secondaryEncoderSurface = null;
        Size secondarySize = null;
        if (secondaryEnabled) {
            CameraManager secondaryManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            secondarySize = CameraCaptureManager.nearestSupportedSize(secondaryManager, SECONDARY_WIDTH, SECONDARY_HEIGHT);
            H264Encoder candidateSecondaryEncoder = new H264Encoder();
            Surface candidateSurface = candidateSecondaryEncoder.start(secondarySize.getWidth(), secondarySize.getHeight(),
                    SECONDARY_BITRATE_KBPS * 1000, SECONDARY_FPS, SECONDARY_KEY_FRAME_INTERVAL_SECONDS);
            if (candidateSurface != null) {
                secondaryEncoder = candidateSecondaryEncoder;
                secondaryEncoderSurface = candidateSurface;
            }
        }

        // Best-effort, like audio/secondary: denied permission or no provider just leaves the
        // stream/recording without GPS metadata, exactly as before GPS support existed.
        if (gpsEnabled && GPSLocationManager.hasPermission(context)) {
            GPSLocationManager candidateGpsManager = new GPSLocationManager(context, gpsUpdateIntervalMs);
            if (candidateGpsManager.start()) {
                gpsManager = candidateGpsManager;
            }
        }

        // A failure here (storage full, muxer error) only ever affects the recording, never the
        // live stream: LocalRecorder is just another independent subscriber of the same encoders.
        if (recordEnabled) {
            File dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (dir != null && (dir.exists() || dir.mkdirs())) {
                String path = new File(dir, "stream_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();
                LocalRecorder recorder = new LocalRecorder(path, encoder, audioEncoder, gpsManager,
                        size.getWidth(), size.getHeight());
                recorder.listener = message -> notifyStatus("recording error: " + message);
                if (recorder.start()) {
                    localRecorder = recorder;
                    lastRecordingPath = path;
                } else {
                    notifyStatus("recording failed to start");
                }
            } else {
                notifyStatus("recording failed to start: no storage directory");
            }
        }

        rtspServer = new RTSPServer(port, encoder, audioEncoder, secondaryEncoder, gpsManager);
        rtspServer.onStatusChange = status -> notifyStatus(status);
        rtspServer.start();

        Surface previewSurface = null;
        if (preview != null && preview.isAvailable()) {
            preview.getSurfaceTexture().setDefaultBufferSize(size.getWidth(), size.getHeight());
            previewSurface = new Surface(preview.getSurfaceTexture());
            configurePreviewTransform(preview, size);
        }

        cameraManager = new CameraCaptureManager(context);
        cameraManager.start(previewSurface, encoderSurface, new CameraCaptureManager.Callback() {
            @Override
            public void onOpened() {
                notifyStatus("camera started (" + size.getWidth() + "x" + size.getHeight() + ")");
            }

            @Override
            public void onError(String message) {
                notifyStatus("camera error: " + message);
            }
        }, secondaryEncoderSurface);
    }

    public void stop() {
        if (cameraManager != null) {
            cameraManager.stop();
            cameraManager = null;
        }
        if (audioCaptureManager != null) {
            audioCaptureManager.stop();
            audioCaptureManager = null;
        }
        if (localRecorder != null) {
            localRecorder.stop();
            localRecorder = null;
        }
        if (rtspServer != null) {
            rtspServer.stop();
            rtspServer = null;
        }
        if (encoder != null) {
            encoder.stop();
            encoder = null;
        }
        if (audioEncoder != null) {
            audioEncoder.stop();
            audioEncoder = null;
        }
        if (secondaryEncoder != null) {
            secondaryEncoder.stop();
            secondaryEncoder = null;
        }
        if (gpsManager != null) {
            gpsManager.stop();
            gpsManager = null;
        }
        notifyStatus("stopped");
    }

    /** Corrects the TextureView's on-screen rendering for the sensor/display rotation mismatch
     *  (Android's standard Camera2 preview-transform formula: the raw camera buffer arrives in
     *  the sensor's native orientation, and this app is locked to a fixed screen orientation
     *  rather than rotating with the device, so the mismatch is fixed and known up front). This
     *  is a GL-level view transform only -- it does not touch the actual pixel data the encoder
     *  (and therefore RTSP/local recording) receives. */
    private void configurePreviewTransform(TextureView textureView, Size previewSize) {
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int rotation = windowManager.getDefaultDisplay().getRotation();
        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();
        if (viewWidth == 0 || viewHeight == 0) return;

        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();

        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) viewHeight / previewSize.getHeight(), (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    private String readyStatus() {
        return "RTSP ON (rtsp://" + NetworkUtils.wifiIpAddress(context) + ":" + port + ")";
    }

    private void notifyStatus(String status) {
        if (statusListener == null) return;
        mainHandler.post(() -> statusListener.onStatusChanged(status));
    }
}
