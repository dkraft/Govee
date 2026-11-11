package dev.offgrid.govee.gateway

/**
 * The 20-byte Govee GATT frame. Byte 0 is 0x33, byte 1 is the command,
 * and the last byte is the XOR of the first 19.
 *
 * [statusQuery] is a read. This object does not build power, reset, or
 * Wi-Fi provisioning frames.
 */
object GatewayFrames {
    const val SERVICE = "00010203-0405-0607-0809-0a0b0c0d1910"
    const val WRITE = "00010203-0405-0607-0809-0a0b0c0d2b11"
    const val NOTIFY = "00010203-0405-0607-0809-0a0b0c0d2b10"
    const val STATUS_COMMAND = 0xAA

    fun statusQuery(): ByteArray = frame(STATUS_COMMAND, ByteArray(0))

    fun frame(command: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(20)
        out[0] = 0x33
        out[1] = command.toByte()
        val n = payload.size.coerceAtMost(17)
        payload.copyInto(out, destinationOffset = 2, endIndex = n)
        out[19] = checksum(out)
        return out
    }

    fun checksumOk(frame: ByteArray): Boolean {
        if (frame.size != 20) return false
        return checksum(frame) == frame[19]
    }

    fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }

    private fun checksum(frame: ByteArray): Byte {
        var xor = 0
        for (i in 0 until 19) xor = xor xor (frame[i].toInt() and 0xff)
        return xor.toByte()
    }
}
