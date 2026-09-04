package veg.mediacapture.sdk.test.rtspserver;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Opens the back camera via Camera2 and streams frames directly into the encoder's input
 * Surface (plus an on-screen preview Surface), avoiding any manual YUV copying.
 * Mirrors ios/RTSPCameraServer/Sources/CameraCaptureManager.swift.
 */
final class CameraCaptureManager {
    private static final String TAG = "CameraCaptureManager";

    private final Context context;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private String cameraId;

    CameraCaptureManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Returns the widths from {@code candidateWidths} actually supported by the back camera. */
    static List<Integer> supportedWidths(CameraManager manager, List<Integer> candidateWidths) {
        List<Integer> supported = new ArrayList<>();
        try {
            String id = findBackCameraId(manager);
            if (id == null) return supported;
            StreamConfigurationMap map = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return supported;
            List<Size> sizes = Arrays.asList(map.getOutputSizes(android.media.MediaCodec.class));
            for (int width : candidateWidths) {
                for (Size size : sizes) {
                    if (size.getWidth() == width) {
                        supported.add(width);
                        break;
                    }
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "supportedWidths failed", e);
        }
        return supported;
    }

    private static String findBackCameraId(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        String[] ids = manager.getCameraIdList();
        return ids.length > 0 ? ids[0] : null;
    }

    /** Nearest size the camera actually supports to the requested one, by matching width first. */
    static Size nearestSupportedSize(CameraManager manager, int requestedWidth, int requestedHeight) {
        try {
            String id = findBackCameraId(manager);
            if (id == null) return new Size(requestedWidth, requestedHeight);
            StreamConfigurationMap map = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return new Size(requestedWidth, requestedHeight);
            Size[] sizes = map.getOutputSizes(android.media.MediaCodec.class);
            for (Size size : sizes) {
                if (size.getWidth() == requestedWidth && size.getHeight() == requestedHeight) {
                    return size;
                }
            }
            Size best = sizes.length > 0 ? sizes[0] : new Size(requestedWidth, requestedHeight);
            long bestDiff = Long.MAX_VALUE;
            for (Size size : sizes) {
                long diff = Math.abs((long) size.getWidth() - requestedWidth);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = size;
                }
            }
            return best;
        } catch (CameraAccessException e) {
            Log.e(TAG, "nearestSupportedSize failed", e);
            return new Size(requestedWidth, requestedHeight);
        }
    }

    interface Callback {
        void onOpened();
        void onError(String message);
    }

    /** {@code extraEncoderSurfaces} lets a second (e.g. secondary/low-res) encoder join the same
     *  capture session -- Camera2 can fan the same sensor output to several targets at once. */
    @SuppressLint("MissingPermission")
    void start(Surface previewSurface, Surface encoderSurface, Callback callback, Surface... extraEncoderSurfaces) {
        backgroundThread = new HandlerThread("CameraCaptureManager.background");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = findBackCameraId(manager);
            if (cameraId == null) {
                callback.onError("no camera available");
                return;
            }
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    cameraDevice = device;
                    createSession(previewSurface, encoderSurface, callback, extraEncoderSurfaces);
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                    device.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(CameraDevice device, int error) {
                    device.close();
                    cameraDevice = null;
                    callback.onError("camera error " + error);
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            callback.onError("camera access exception: " + e.getMessage());
        }
    }

    private void createSession(Surface previewSurface, Surface encoderSurface, Callback callback,
                                Surface[] extraEncoderSurfaces) {
        try {
            List<Surface> targets = new ArrayList<>();
            if (previewSurface != null) targets.add(previewSurface);
            targets.add(encoderSurface);
            for (Surface extra : extraEncoderSurfaces) {
                if (extra != null) targets.add(extra);
            }

            cameraDevice.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        CaptureRequest.Builder builder =
                                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        if (previewSurface != null) builder.addTarget(previewSurface);
                        builder.addTarget(encoderSurface);
                        for (Surface extra : extraEncoderSurfaces) {
                            if (extra != null) builder.addTarget(extra);
                        }
                        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                        callback.onOpened();
                    } catch (CameraAccessException e) {
                        callback.onError("failed to start repeating request: " + e.getMessage());
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    callback.onError("capture session configuration failed");
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            callback.onError("createCaptureSession failed: " + e.getMessage());
        }
    }

    void stop() {
        if (captureSession != null) {
            try {
                captureSession.close();
            } catch (Exception ignored) {
            }
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }
}
