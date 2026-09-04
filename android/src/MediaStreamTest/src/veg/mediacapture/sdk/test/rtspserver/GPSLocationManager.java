package veg.mediacapture.sdk.test.rtspserver;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wraps Android's LocationManager and fans out GPS samples to independent subscribers (RTSP
 * server, local recorder) -- mirrors the H264Encoder/AACEncoder multi-listener pattern, and is
 * likewise decoupled from Camera2, the encoders and RTSPServer itself.
 *
 * Each sample carries both a Unix-epoch millisecond timestamp (for human/GCS-readable output)
 * and Location.getElapsedRealtimeNanos() (the same monotonic clock family H264Encoder correlates
 * its presentationTimeUs clock against via toElapsedRealtimeNs()) so a GPS sample can be matched
 * to the video frame captured at roughly the same real time, instead of comparing epoch time
 * directly against a pipeline-relative pts.
 */
final class GPSLocationManager {
    private static final String TAG = "GPSLocationManager";
    private static final long BUFFER_WINDOW_NS = 10_000_000_000L; // keep the last 10s of samples

    interface Listener {
        void onSample(GPSSample sample);
    }

    static final class GPSSample {
        final long timestampMs;       // Location.getTime() -- Unix epoch ms, for output only
        final long elapsedRealtimeNs; // Location.getElapsedRealtimeNanos() -- shared monotonic timeline
        final double latitude;
        final double longitude;
        final double altitude;
        final float accuracy;

        GPSSample(long timestampMs, long elapsedRealtimeNs, double latitude, double longitude,
                  double altitude, float accuracy) {
            this.timestampMs = timestampMs;
            this.elapsedRealtimeNs = elapsedRealtimeNs;
            this.latitude = latitude;
            this.longitude = longitude;
            this.altitude = altitude;
            this.accuracy = accuracy;
        }

        /** Fixed Phase 5 schema: {timestamp: ms, latitude/longitude: degrees, altitude: meters,
         *  accuracy: meters} -- shared verbatim between the RTSP metadata track and the .gps.jsonl
         *  recording sidecar. */
        String toJson() {
            try {
                return new JSONObject()
                        .put("timestamp", timestampMs)
                        .put("latitude", latitude)
                        .put("longitude", longitude)
                        .put("altitude", altitude)
                        .put("accuracy", accuracy)
                        .toString();
            } catch (JSONException e) {
                return "{}";
            }
        }
    }

    private final Context context;
    private final LocationManager locationManager;
    private final long updateIntervalMs;
    private final Deque<GPSSample> buffer = new ArrayDeque<>();
    private final Object bufferLock = new Object();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private LocationListener locationListener;

    GPSLocationManager(Context context, long updateIntervalMs) {
        this.context = context.getApplicationContext();
        this.updateIntervalMs = updateIntervalMs;
        this.locationManager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    void addListener(Listener listener) {
        listeners.add(listener);
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Starts listening for location updates. Returns false if permission was denied or no
     *  provider is available -- caller treats this exactly like the audio/secondary-stream
     *  best-effort pattern: GPS simply isn't wired in, and everything else still works. */
    @SuppressLint("MissingPermission")
    boolean start() {
        if (locationManager == null || !hasPermission(context)) return false;
        String provider = bestProvider();
        if (provider == null) return false;

        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) { handleLocation(location); }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };
        try {
            locationManager.requestLocationUpdates(provider, updateIntervalMs, 0f, locationListener, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) {
            Log.e(TAG, "failed to start location updates", e);
            locationListener = null;
            return false;
        }
        return true;
    }

    void stop() {
        if (locationManager != null && locationListener != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {
            }
        }
        locationListener = null;
        synchronized (bufferLock) {
            buffer.clear();
        }
    }

    private String bestProvider() {
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return LocationManager.GPS_PROVIDER;
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return LocationManager.NETWORK_PROVIDER;
        return null;
    }

    private void handleLocation(Location location) {
        GPSSample sample = new GPSSample(
                location.getTime(),
                location.getElapsedRealtimeNanos(),
                location.getLatitude(),
                location.getLongitude(),
                location.getAltitude(),
                location.getAccuracy());
        synchronized (bufferLock) {
            buffer.addLast(sample);
            long cutoff = sample.elapsedRealtimeNs - BUFFER_WINDOW_NS;
            while (!buffer.isEmpty() && buffer.peekFirst().elapsedRealtimeNs < cutoff) {
                buffer.removeFirst();
            }
        }
        for (Listener listener : listeners) {
            listener.onSample(sample);
        }
    }

    /** The buffered GPS sample nearest (by elapsedRealtimeNanos) to the given monotonic
     *  timestamp -- e.g. H264Encoder.toElapsedRealtimeNs(frame.presentationTimeUs). Returns null
     *  if no sample has arrived yet or the buffer has since aged past BUFFER_WINDOW_NS. */
    GPSSample nearestSample(long videoElapsedRealtimeNs) {
        synchronized (bufferLock) {
            GPSSample best = null;
            long bestDiff = Long.MAX_VALUE;
            for (GPSSample sample : buffer) {
                long diff = Math.abs(sample.elapsedRealtimeNs - videoElapsedRealtimeNs);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = sample;
                }
            }
            return best;
        }
    }
}
