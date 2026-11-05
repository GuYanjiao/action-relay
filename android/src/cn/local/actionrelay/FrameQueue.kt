package cn.local.actionrelay
import cn.local.actionrelay.core.NativeCore

class FrameQueue {
    class Packet(encoded: ByteArray) {
        @JvmField val telemetry = encoded[0] != 0.toByte()
        @JvmField val queuedAt = NativeCore.read64(encoded, 1)
        @JvmField val deadline = NativeCore.read64(encoded, 9)
        @JvmField val bytes = encoded.copyOfRange(17, encoded.size)
    }
    private val state = NativeCore.queueState()
    @Synchronized fun offerControl(bytes: ByteArray, now: Long) = NativeCore.offer(state, bytes, now, false)
    @Synchronized fun offerGps(bytes: ByteArray, now: Long) = NativeCore.offer(state, bytes, now, true)
    @Synchronized fun poll(now: Long): Packet? = NativeCore.poll(state, now).let { if (it.isEmpty()) null else Packet(it) }
    @Synchronized fun clearGps() = NativeCore.clear(state, true)
    @Synchronized fun clear() = NativeCore.clear(state, false)
    @Synchronized fun controlCount() = NativeCore.count(state)
}
