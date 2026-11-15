package dev.remotescreen

object Ports {
    /** Pairing handshake. The phone listens, the receiver connects. */
    const val PAIR = 7000

    /** Live screen mirroring session. */
    const val MIRROR = 7001

    /** Virtual second display session (a screen that is not drawn on the phone). */
    const val VIRTUAL = 7002
}

object Msg {
    const val CONTROL = 0x10
    const val VIDEO_INFO = 0x11
    const val VIDEO_FRAME = 0x12
    const val BYE = 0x13
}

object Handshake {
    const val MAGIC = 0x52535631 // "RSV1"
}
