package veg.mediacapture.sdk.test.rtspserver;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/** Reads the device's current IPv4 address for building a human-readable rtsp:// URL. Scans all
 *  network interfaces (not just Wi-Fi) so it also works over an external USB Ethernet/Wi-Fi
 *  adapter, USB tethering, etc. -- anything with a real, non-loopback IPv4 address. */
final class NetworkUtils {
    private NetworkUtils() {}

    static String wifiIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return "0.0.0.0";
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) continue;

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to the placeholder below.
        }
        return "0.0.0.0";
    }
}
