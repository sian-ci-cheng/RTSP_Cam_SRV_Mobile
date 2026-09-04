package veg.mediacapture.sdk.test.rtspserver;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;

/** Reads the device's current Wi-Fi IPv4 address for building a human-readable rtsp:// URL. */
final class NetworkUtils {
    private NetworkUtils() {}

    static String wifiIpAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return "0.0.0.0";
        int ip = wifiManager.getConnectionInfo().getIpAddress();
        if (ip == 0) return "0.0.0.0";
        return Formatter.formatIpAddress(ip);
    }
}
