package cn.local.actionrelay.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.*

/** DJI 00/17: only current, complete, measured GNSS fixes are eligible. */
object GpsProtocol {
    const val MAX_FIX_AGE_MS = 3000L
    const val MAX_HORIZONTAL_ACCURACY_METERS = 50f
    const val PAYLOAD_LENGTH = 48
    class Fix(
        @JvmField val utcMillis: Long, @JvmField val elapsedMillis: Long,
        @JvmField val latitude: Double, @JvmField val longitude: Double, @JvmField val altitudeMslMeters: Double,
        @JvmField val northMps: Float, @JvmField val eastMps: Float, @JvmField val downMps: Float,
        @JvmField val horizontalAccuracyMeters: Float, @JvmField val verticalAccuracyMeters: Float,
        @JvmField val speedAccuracyMps: Float, @JvmField val satellites: Int,
    )
    @JvmStatic fun invalidReason(fix: Fix?, nowElapsedMs: Long): String? {
        if (fix == null) return "尚无完整GNSS定位"
        if (fix.elapsedMillis < 0 || nowElapsedMs < fix.elapsedMillis) return "定位单调时间无效"
        if (nowElapsedMs - fix.elapsedMillis > MAX_FIX_AGE_MS) return "GNSS定位已过期"
        if (fix.utcMillis < 946684800000L || fix.utcMillis >= 4102444800000L) return "GNSS时间无效"
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite() || abs(fix.latitude) > 90 || abs(fix.longitude) > 180) return "经纬度无效"
        if (!fix.altitudeMslMeters.isFinite() || abs(fix.altitudeMslMeters) > 2147483.0) return "海拔无效或超出协议范围"
        if (!positive(fix.horizontalAccuracyMeters.toDouble()) || fix.horizontalAccuracyMeters > MAX_HORIZONTAL_ACCURACY_METERS) return "水平定位精度不足"
        if (!positive(fix.verticalAccuracyMeters.toDouble()) || !positive(fix.speedAccuracyMps.toDouble())) return "定位精度字段缺测"
        if (!fix.northMps.isFinite() || !fix.eastMps.isFinite() || !fix.downMps.isFinite() || abs(fix.northMps) > 500 || abs(fix.eastMps) > 500 || abs(fix.downMps) > 500) return "速度字段无效"
        if (fix.satellites !in 1..1024) return "没有有效的实测卫星数"
        if (fix.verticalAccuracyMeters * 1000.0 > 0xffffffffL || fix.speedAccuracyMps * 100.0 > 0xffffffffL) return "精度字段超出协议范围"
        return null
    }
    @JvmStatic fun encode(sequence: Int, input: Fix?, nowElapsedMs: Long): ByteArray {
        invalidReason(input, nowElapsedMs)?.let { throw IllegalArgumentException(it) }
        val fix = requireNotNull(input)
        val local = Calendar.getInstance(TimeZone.getTimeZone("GMT+08:00"), Locale.ROOT).apply { timeInMillis = fix.utcMillis }
        val date = local.get(Calendar.YEAR)*10000 + (local.get(Calendar.MONTH)+1)*100 + local.get(Calendar.DAY_OF_MONTH)
        val time = local.get(Calendar.HOUR_OF_DAY)*10000 + local.get(Calendar.MINUTE)*100 + local.get(Calendar.SECOND)
        val data = ByteBuffer.allocate(PAYLOAD_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(date).putInt(time).putInt(Math.round(fix.longitude*1e7).toInt()).putInt(Math.round(fix.latitude*1e7).toInt())
        data.putInt(Math.round(fix.altitudeMslMeters*1000).toInt())
        data.putFloat(fix.northMps*100).putFloat(fix.eastMps*100).putFloat(fix.downMps*100)
        data.putInt(ceil(fix.verticalAccuracyMeters*1000.0).toLong().toInt())
        data.putInt(ceil(fix.horizontalAccuracyMeters*1000.0).toLong().toInt())
        data.putInt(ceil(fix.speedAccuracyMps*100.0).toLong().toInt()).putInt(fix.satellites)
        return DjiProtocol.frame(0, 0x17, DjiProtocol.CMD_NO_RESPONSE, sequence, data.array())
    }
    class VerticalSpeed(@JvmField val downMps: Float, @JvmField val accuracyMps: Float)
    /** Difference estimate, not an independently measured Doppler vertical velocity. */
    @JvmStatic fun deriveDown(previousAltitude: Double, previousAccuracy: Float, previousElapsed: Long, altitude: Double, accuracy: Float, elapsed: Long): VerticalSpeed? {
        val dt = elapsed - previousElapsed
        if (dt !in 500..5000 || !previousAltitude.isFinite() || !altitude.isFinite() || !positive(previousAccuracy.toDouble()) || !positive(accuracy.toDouble())) return null
        val seconds = dt/1000.0
        val down = (previousAltitude-altitude)/seconds
        val uncertainty = hypot(previousAccuracy.toDouble(), accuracy.toDouble())/seconds
        if (!down.isFinite() || abs(down)>500 || !positive(uncertainty)) return null
        return VerticalSpeed(down.toFloat(), uncertainty.toFloat())
    }
    class NmeaFix(@JvmField val utcMillisOfDay: Long, @JvmField val latitude: Double, @JvmField val longitude: Double,
        @JvmField val altitudeMslMeters: Double, @JvmField val satellites: Int, @JvmField val quality: Int, @JvmField val hdop: Double) {
        fun matches(utcMillis: Long, lat: Double, lon: Double, horizontalAccuracy: Float): Boolean {
            val tod = Math.floorMod(utcMillis, 86400000L)
            val raw = abs(tod-utcMillisOfDay); val difference = min(raw, 86400000L-raw)
            if (difference > 500) return false
            val north = (latitude-lat)*111320.0
            val east = (longitude-lon)*111320.0*cos(Math.toRadians(lat))
            return hypot(north, east) <= max(10.0, horizontalAccuracy*3.0)
        }
    }
    @JvmStatic fun parseGga(sentence: String?): NmeaFix? {
        if (sentence == null || sentence.length>256) return null
        val value = sentence.trim(); val star = value.indexOf('*')
        if (!value.startsWith("$") || star<1 || star!=value.length-3) return null
        var checksum = 0
        for (i in 1 until star) { val c = value[i].code; if (c>127) return null; checksum = checksum xor c }
        return try {
            if (checksum != value.substring(star+1).toInt(16)) return null
            val f = value.substring(1, star).split(',')
            if (f.size<11 || f[0].length!=5 || !f[0].endsWith("GGA") || f[10]!="M") return null
            val quality=f[6].toInt(); val satellites=f[7].toInt()
            if (quality !in 1..5 || satellites !in 1..1024) return null
            val packedTime=f[1].toDouble(); if (!packedTime.isFinite() || packedTime<0) return null
            val hour=(packedTime/10000).toInt(); val minute=(packedTime/100).toInt()%100
            val second=packedTime-hour*10000-minute*100
            if (hour>23 || minute>59 || second<0 || second>=60) return null
            val lat=coordinate(f[2], f[3], true); val lon=coordinate(f[4], f[5], false)
            val altitude=f[9].toDouble(); if (!altitude.isFinite() || abs(altitude)>2147483) return null
            val hdop=if (f[8].isEmpty()) Double.NaN else f[8].toDouble()
            if (!hdop.isNaN() && (!hdop.isFinite() || hdop<0)) return null
            val utc=hour*3600000L+minute*60000L+Math.round(second*1000)
            if (utc>=86400000L) return null
            NmeaFix(utc, lat, lon, altitude, satellites, quality, hdop)
        } catch (_: IllegalArgumentException) { null }
    }
    private fun coordinate(packed: String, sign: String, latitude: Boolean): Double {
        require(if (latitude) sign=="N" || sign=="S" else sign=="E" || sign=="W")
        val value=packed.toDouble(); require(value.isFinite() && value>=0)
        val degrees=(value/100).toInt(); val minutes=value-degrees*100
        val result=degrees+minutes/60; require(minutes>=0 && minutes<60 && result<=if(latitude)90 else 180)
        return if (sign=="S" || sign=="W") -result else result
    }
    @JvmStatic fun finite(value: Double) = value.isFinite()
    private fun positive(value: Double) = value.isFinite() && value>0
}
