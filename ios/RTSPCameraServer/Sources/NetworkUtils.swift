import Foundation

enum NetworkUtils {
    /// Returns the IPv4 address of the given interface name prefix (e.g. "en" for
    /// WiFi/Ethernet on iOS, since USB-C Ethernet adapters also surface as "en*").
    static func ipv4Address(interfacePrefixes: [String] = ["en"]) -> [(name: String, address: String)] {
        var results: [(String, String)] = []
        var ifaddrPtr: UnsafeMutablePointer<ifaddrs>?

        guard getifaddrs(&ifaddrPtr) == 0, let firstAddr = ifaddrPtr else { return results }
        defer { freeifaddrs(ifaddrPtr) }

        var ptr: UnsafeMutablePointer<ifaddrs>? = firstAddr
        while let current = ptr {
            defer { ptr = current.pointee.ifa_next }

            let interface = current.pointee
            guard let addr = interface.ifa_addr else { continue }
            guard addr.pointee.sa_family == UInt8(AF_INET) else { continue }

            let name = String(cString: interface.ifa_name)
            guard interfacePrefixes.contains(where: { name.hasPrefix($0) }) else { continue }

            var addrCopy = addr.pointee
            var hostBuffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let result = withUnsafePointer(to: &addrCopy) { ptr -> Int32 in
                ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPtr in
                    getnameinfo(sockaddrPtr, socklen_t(interface.ifa_addr.pointee.sa_len),
                                &hostBuffer, socklen_t(hostBuffer.count),
                                nil, 0, NI_NUMERICHOST)
                }
            }
            if result == 0 {
                let address = String(cString: hostBuffer)
                if address != "127.0.0.1" {
                    results.append((name, address))
                }
            }
        }
        return results
    }
}
