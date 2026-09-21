package com.androidharness.app.tools

import java.net.InetAddress
import java.io.IOException

internal object PublicHttpAddresses {
    fun validate(addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.isEmpty() || addresses.any { !isPublic(it) }) {
            throw IOException("http_request blocks localhost, private and non-public addresses")
        }
        return addresses
    }

    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val b = address.address.map { it.toInt() and 255 }
        return when (b.size) {
            4 -> !(b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 ||
                (b[0] == 100 && b[1] in 64..127) ||
                (b[0] == 169 && b[1] == 254) ||
                (b[0] == 172 && b[1] in 16..31) ||
                (b[0] == 192 && (b[1] == 168 || b[1] == 0 || (b[1] == 88 && b[2] == 99))) ||
                (b[0] == 198 && (b[1] in 18..19 || (b[1] == 51 && b[2] == 100))) ||
                (b[0] == 203 && b[1] == 0 && b[2] == 113))
            // Only global unicast; exclude transition and documentation ranges.
            16 -> (b[0] and 224) == 32 &&
                !(b[0] == 32 && b[1] == 2) &&
                !(b[0] == 32 && b[1] == 1 && (b[2] < 2 || (b[2] == 13 && b[3] == 184))) &&
                !(b[0] == 63 && b[1] == 255 && b[2] < 16)
            else -> false
        }
    }
}
