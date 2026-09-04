package veg.mediacapture.sdk.test.rtspserver;

import android.hardware.camera2.CameraManager;

import java.util.Arrays;
import java.util.List;

/**
 * The fixed candidate resolution widths the app's settings UI offers, and which of them the
 * back camera actually supports. Replaces MediaCaptureConfig.getVideoSupportedRes().
 */
public final class CameraCapabilities {
    private CameraCapabilities() {}

    public static final List<Integer> CANDIDATE_WIDTHS =
            Arrays.asList(3840, 1920, 1280, 721, 720, 640, 352, 320, 176);

    public static List<Integer> supportedWidths(CameraManager manager) {
        return CameraCaptureManager.supportedWidths(manager, CANDIDATE_WIDTHS);
    }
}
