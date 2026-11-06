package cn.local.actionrelay.protocol

import cn.local.actionrelay.core.NativeCore
import java.util.Locale
import java.util.UUID

/** Kotlin API facade; Rust owns framing, CRC and notification reassembly. */
object DjiProtocol {
    @JvmField val SERVICE_UUID = uuid16(0xfff0)
    @JvmField val NOTIFY_UUID = uuid16(0xfff4)
    @JvmField val WRITE_UUID = uuid16(0xfff5)
    @JvmField val CCCD_UUID = uuid16(0x2902)
    const val CMD_NO_RESPONSE = 0
    const val CMD_OPTIONAL_RESPONSE = 1
    const val CMD_WAIT_RESULT = 2
    const val ACK_NO_RESPONSE = 0x20
    const val ACTION_4_DEVICE_ID = 0xff33L
    const val RECORD_CONTROL_DEVICE_ID = 0x33ff0000L
    @JvmStatic fun uuid16(uuid: Int): UUID = UUID.fromString(String.format(Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", uuid and 0xffff))
    @JvmStatic fun u8(value: Byte) = value.toInt() and 255
    @JvmStatic fun u16(bytes: ByteArray, at: Int) = u8(bytes[at]) or (u8(bytes[at + 1]) shl 8)
    @JvmStatic fun u32(bytes: ByteArray, at: Int): Long = (0..3).fold(0L) { value, i -> value or (u8(bytes[at + i]).toLong() shl (8 * i)) }
    private fun put16(bytes: ByteArray, at: Int, value: Int) { bytes[at] = value.toByte(); bytes[at + 1] = (value ushr 8).toByte() }
    private fun put32(bytes: ByteArray, at: Int, value: Long) { for (i in 0..3) bytes[at + i] = (value ushr (8 * i)).toByte() }
    @JvmStatic fun crc16(bytes: ByteArray, offset: Int, length: Int) = NativeCore.crc(bytes, offset, length, false).toInt()
    @JvmStatic fun crc32(bytes: ByteArray, offset: Int, length: Int) = NativeCore.crc(bytes, offset, length, true)
    @JvmStatic fun frame(commandSet: Int, commandId: Int, commandType: Int, sequence: Int, payload: ByteArray?): ByteArray = NativeCore.frame(commandSet, commandId, commandType, sequence, payload)
    @JvmStatic fun connectionRequest(sequence: Int, controllerId: Long, identityMac: ByteArray?, firstPairing: Boolean, verificationCode: Int): ByteArray {
        require(identityMac?.size == 6) { "Expected six identity bytes" }
        require(verificationCode in 0..9999) { "Verification code must be 0..9999" }
        val payload = ByteArray(33)
        put32(payload, 0, controllerId); payload[4] = 6
        identityMac.copyInto(payload, 5)
        payload[26] = if (firstPairing) 1 else 0; put16(payload, 27, verificationCode)
        return frame(0, 0x19, CMD_WAIT_RESULT, sequence, payload)
    }
    @JvmStatic fun connectionResponse(cameraSequence: Int, controllerId: Long): ByteArray {
        val payload = ByteArray(9); put32(payload, 0, controllerId)
        return frame(0, 0x19, ACK_NO_RESPONSE, cameraSequence, payload)
    }
    @JvmStatic fun subscribeStatus(sequence: Int) = frame(0x1d, 5, CMD_NO_RESPONSE, sequence, byteArrayOf(3,20,0,0,0,0))
    @JvmStatic fun recording(sequence: Int, start: Boolean): ByteArray {
        val payload = ByteArray(9); put32(payload, 0, RECORD_CONTROL_DEVICE_ID); payload[4] = if (start) 0 else 1
        return frame(0x1d, 3, CMD_OPTIONAL_RESPONSE, sequence, payload)
    }
    @JvmStatic fun switchMode(sequence: Int, mode: Int): ByteArray {
        require(mode == 1 || mode == 5) { "Only normal video/photo supported" }
        val payload = ByteArray(9); put32(payload, 0, RECORD_CONTROL_DEVICE_ID); payload[4] = mode.toByte()
        return frame(0x1d, 4, CMD_OPTIONAL_RESPONSE, sequence, payload)
    }
    class Frame(bytes: ByteArray) {
        @JvmField val commandType = u8(bytes[3])
        @JvmField val sequence = u16(bytes, 8)
        @JvmField val commandSet = u8(bytes[12])
        @JvmField val commandId = u8(bytes[13])
        @JvmField val payload = bytes.copyOfRange(14, bytes.size - 4)
        fun isResponse() = commandType and 0x20 != 0
        fun `is`(set: Int, id: Int) = commandSet == set && commandId == id
    }
    @JvmStatic fun decode(bytes: ByteArray?): Frame { NativeCore.validate(bytes); return Frame(requireNotNull(bytes)) }
    class StreamDecoder {
        private val state = NativeCore.streamState()
        @JvmField var discardedBytes = 0L
        @Synchronized fun feed(bytes: ByteArray?): List<Frame> {
            if (bytes == null || bytes.isEmpty()) return emptyList()
            val decoded = NativeCore.feed(state, bytes)
            discardedBytes = NativeCore.read64(decoded, 0)
            val frames = ArrayList<Frame>(); var offset = 8
            while (offset < decoded.size) {
                val length = u16(decoded, offset); offset += 2
                frames.add(Frame(decoded.copyOfRange(offset, offset + length))); offset += length
            }
            return frames
        }
        @Synchronized fun reset() { NativeCore.clear(state, false); discardedBytes = 0 }
    }
    class CameraStatus(payload: ByteArray, nowMs: Long) {
        @JvmField val mode = u8(payload[0])
        @JvmField val cameraState = u8(payload[1])
        @JvmField val recordingSeconds = u16(payload, 5)
        @JvmField val videoResolution = u8(payload[2])
        @JvmField val fpsIndex = u8(payload[3])
        @JvmField val remainingCapacityMb = optional32(payload, 15)
        @JvmField val remainingRecordSeconds = optional32(payload, 23)
        @JvmField val remainingPhotos = optional32(payload, 19)
        @JvmField val powerMode = payload.getOrNull(28)?.let(::u8) ?: -1
        @JvmField val nextMode = payload.getOrNull(29)?.let(::u8) ?: -1
        @JvmField val temperatureState = payload.getOrNull(30)?.let(::u8) ?: -1
        @JvmField val batteryPercent = (payload.getOrNull(37)?.let(::u8) ?: -1).let { if (it <= 100) it else -1 }
        @JvmField val receivedAtMs = nowMs
        fun isNormalVideoMode() = mode == 1
        fun isRecording() = isNormalVideoMode() && cameraState == 3
        fun isPreRecording() = isNormalVideoMode() && cameraState == 5
        fun isExplicitlyStopped() = isNormalVideoMode() && (cameraState == 1 || cameraState == 5)
        fun hasKnownRecordingState() = isNormalVideoMode() && (cameraState == 1 || cameraState == 3 || cameraState == 5)
        fun isModeTransition() = nextMode >= 0 && nextMode != mode
        fun isControllableState() = hasKnownRecordingState()
    }
    private fun optional32(payload: ByteArray, at: Int): Long = if (payload.size >= at + 4) u32(payload, at).let { if (it == 0xffffffffL) -1 else it } else -1
    @JvmStatic fun cameraStatus(frame: Frame, nowMs: Long): CameraStatus {
        require(frame.`is`(0x1d, 2) && !frame.isResponse() && frame.payload.size >= 7) { "Not a camera-status push" }
        return CameraStatus(frame.payload, nowMs)
    }
    @JvmStatic fun hex(bytes: ByteArray): String {
        val digits = "0123456789ABCDEF"; val result = CharArray(bytes.size * 2)
        for (i in bytes.indices) { val value = u8(bytes[i]); result[2*i] = digits[value ushr 4]; result[2*i+1] = digits[value and 15] }
        return String(result)
    }
}
