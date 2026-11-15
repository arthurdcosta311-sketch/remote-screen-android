package dev.remotescreen

import java.net.Inet4Address
import java.net.NetworkInterface

object Net {

    /** Every non-loopback IPv4 address, so the code the user types has a host to go with it. */
    fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    } catch (e: Exception) {
        emptyList()
    }
}
