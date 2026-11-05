package cn.local.actionrelay.core

import java.nio.ByteBuffer

/** Synchronous bulk JNI adapter; class and method names match the Rust JNI ABI. */
object NativeCore {
    init { System.loadLibrary("action_relay_core") }
    @JvmStatic private external fun sizes(): IntArray
    private val stateSizes = sizes()
    @JvmStatic fun streamState(): ByteBuffer = ByteBuffer.allocateDirect(stateSizes[0])
    @JvmStatic fun queueState(): ByteBuffer = ByteBuffer.allocateDirect(stateSizes[1])
    @JvmStatic external fun crc(input: ByteArray, offset: Int, length: Int, wide: Boolean): Long
    @JvmStatic external fun frame(set: Int, id: Int, kind: Int, sequence: Int, payload: ByteArray?): ByteArray
    @JvmStatic external fun validate(input: ByteArray?)
    @JvmStatic external fun feed(state: ByteBuffer, input: ByteArray?): ByteArray
    @JvmStatic external fun offer(state: ByteBuffer, input: ByteArray, now: Long, gps: Boolean)
    @JvmStatic external fun poll(state: ByteBuffer, now: Long): ByteArray
    @JvmStatic external fun clear(state: ByteBuffer, gpsOnly: Boolean)
    @JvmStatic external fun count(state: ByteBuffer): Int
    @JvmStatic fun read64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0..7) value = value or ((bytes[offset + i].toLong() and 255) shl (i * 8))
        return value
    }
}
