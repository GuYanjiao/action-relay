package cn.local.actionrelay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import cn.local.actionrelay.protocol.GpsProtocol
import kotlin.math.*

/** Real GPS only; retain a current fix and one preceding altitude, never a location track. */
@SuppressLint("MissingPermission")
class PhoneGps(context: Context, private val listener: Listener) {
    interface Listener {
        fun onStateChanged(state: State)
        fun onFix(fix: GpsProtocol.Fix)
        fun onDiagnostic(message: String) {}
    }
    class State(@JvmField val enabled: Boolean, @JvmField val ready: Boolean, @JvmField val phase: String,
        @JvmField val message: String, @JvmField val ageMs: Long, @JvmField val accuracyMeters: Float, @JvmField val satellites: Int)
    private val context=context.applicationContext
    private val manager=this.context.getSystemService(LocationManager::class.java)
    private val main=Handler(Looper.getMainLooper())
    private var running=false
    private var nmeaRegistered=false
    private var gnssRegistered=false
    private var startedElapsed=0L
    private var emittedElapsed=-1L
    private var location: Location?=null
    private var nmea: GpsProtocol.NmeaFix?=null
    private var nmeaElapsed=-1L
    private var satellitesElapsed=-1L
    private var usedSatellites=-1
    private var fix: GpsProtocol.Fix?=null
    private var previousElapsed=-1L
    private var previousAltitude=0.0
    private var previousVerticalAccuracy=0f
    private var previousAltitudeSource=""
    private var issuePhase="off"
    private var issueMessage="GPS推送未开启"
    private var currentDown: GpsProtocol.VerticalSpeed?=null
    private var firstFixLogged=false
    private var lastDiagnostic=""
    fun start(): Boolean {
        if(running)return true
        resetMeasurements();firstFixLogged=false;lastDiagnostic=""
        if(!hasPermission()){issue("permission","需要精确定位权限");notifyState();return false}
        val provider=manager
        if(provider==null || !gpsEnabled()){issue("provider_off","请先打开手机定位和GPS");notifyState();return false}
        running=true;startedElapsed=SystemClock.elapsedRealtime();issue("searching","正在等待真实GNSS定位，请到开阔处")
        try{
            try{gnssRegistered=provider.registerGnssStatusCallback(context.mainExecutor,gnssCallback)}
            catch(denied: SecurityException){throw denied}catch(optional: RuntimeException){diagnostic("卫星监听不可用："+shortError(optional))}
            try{nmeaRegistered=provider.addNmeaListener(context.mainExecutor,nmeaListener)}
            catch(denied: SecurityException){throw denied}catch(optional: RuntimeException){diagnostic("NMEA监听不可用："+shortError(optional))}
            diagnostic("附加测量订阅："+(if(gnssRegistered)"卫星状态成功" else "卫星状态失败")+"，"+(if(nmeaRegistered)"NMEA成功" else "NMEA失败"))
            val request=LocationRequest.Builder(1000).setMinUpdateIntervalMillis(1000).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build()
            provider.requestLocationUpdates(LocationManager.GPS_PROVIDER,request,context.mainExecutor,locationListener)
            diagnostic("GPS_PROVIDER位置订阅成功");main.post(pulse);notifyState();return true
        }catch(denied: SecurityException){diagnostic("GPS订阅被拒绝："+shortError(denied));shutdown();issue("permission","定位权限或前台定位服务未就绪")}
        catch(error: RuntimeException){diagnostic("GPS订阅异常："+shortError(error));shutdown();issue("unavailable","无法启动手机GNSS："+shortError(error))}
        notifyState();return false
    }
    fun stop(){shutdown();issue("off","GPS推送未开启");notifyState()}
    private fun shutdown(){
        running=false;main.removeCallbacks(pulse)
        manager?.let {
            try{it.removeUpdates(locationListener)}catch(_: RuntimeException){}
            if(nmeaRegistered)try{it.removeNmeaListener(nmeaListener)}catch(_: RuntimeException){}
            if(gnssRegistered)try{it.unregisterGnssStatusCallback(gnssCallback)}catch(_: RuntimeException){}
        }
        nmeaRegistered=false;gnssRegistered=false;resetMeasurements()
    }
    private fun resetMeasurements(){
        location=null;nmea=null;fix=null;nmeaElapsed=-1;satellitesElapsed=-1;usedSatellites=-1
        previousElapsed=-1;previousAltitudeSource="";currentDown=null;emittedElapsed=-1
    }
    private val pulse=object: Runnable {override fun run(){if(!running)return;notifyState();main.postDelayed(this,1000)}}
    private val locationListener=object: LocationListener {
        override fun onLocationChanged(result: Location){
            if(!running)return
            if(result.provider!=LocationManager.GPS_PROVIDER || result.isMock){fix=null;issue("waiting_quality","仅接受真实GPS_PROVIDER定位");notifyState();return}
            val elapsed=result.elapsedRealtimeNanos/1000000
            if(elapsed<startedElapsed || location?.let{elapsed<it.elapsedRealtimeNanos/1000000}==true)return
            if(!firstFixLogged){firstFixLogged=true;diagnostic("收到首个真实GPS定位")}
            location=Location(result);rebuild()
        }
        override fun onProviderDisabled(provider: String){if(running && provider==LocationManager.GPS_PROVIDER){fix=null;previousElapsed=-1;issue("provider_off","手机GPS已关闭，已暂停推送");notifyState()}}
        override fun onProviderEnabled(provider: String){if(running && provider==LocationManager.GPS_PROVIDER){resetMeasurements();issue("searching","正在重新等待GNSS定位");notifyState()}}
    }
    private val gnssCallback=object: GnssStatus.Callback(){
        override fun onSatelliteStatusChanged(status: GnssStatus){
            if(!running)return
            var count=0;for(i in 0 until status.satelliteCount)if(status.usedInFix(i))count++
            usedSatellites=count;satellitesElapsed=SystemClock.elapsedRealtime();rebuild()
        }
        override fun onStopped(){if(running){resetMeasurements();issue("searching","GNSS暂未定位，已暂停推送");notifyState()}}
    }
    private val nmeaListener=OnNmeaMessageListener{sentence,_ ->
        if(running){GpsProtocol.parseGga(sentence)?.let{nmea=it;nmeaElapsed=SystemClock.elapsedRealtime();rebuild()}}
    }
    private fun hasPermission()=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
    private fun gpsEnabled()=try{manager?.let{it.isLocationEnabled && it.isProviderEnabled(LocationManager.GPS_PROVIDER)}==true}catch(_: RuntimeException){false}
    private fun satelliteCount(now: Long, matching: GpsProtocol.NmeaFix?): Int {
        return if(satellitesElapsed>=0 && now>=satellitesElapsed && now-satellitesElapsed<=GpsProtocol.MAX_FIX_AGE_MS)usedSatellites else matching?.satellites ?: -1
    }
    private fun rebuild(){
        if(!running)return;fix=null
        if(!hasPermission()){issue("permission","精确定位权限不可用，已暂停推送");notifyState();return}
        val current=location;if(current==null){notifyState();return}
        val now=SystemClock.elapsedRealtime();val elapsed=current.elapsedRealtimeNanos/1000000
        if(now<elapsed || now-elapsed>GpsProtocol.MAX_FIX_AGE_MS){issue("stale","GNSS定位已过期，已暂停推送");notifyState();return}
        if(!current.hasAccuracy() || !positive(current.accuracy.toDouble()) || current.accuracy>GpsProtocol.MAX_HORIZONTAL_ACCURACY_METERS){issue("waiting_quality","等待更准确的GNSS定位");notifyState();return}
        val matching=nmea?.takeIf{nmeaElapsed>=0 && now>=nmeaElapsed && now-nmeaElapsed<=GpsProtocol.MAX_FIX_AGE_MS && it.matches(current.time,current.latitude,current.longitude,current.accuracy)}
        val altitude: Double;val verticalAccuracy: Float;val altitudeSource: String
        if(Build.VERSION.SDK_INT>=34 && current.hasMslAltitude()){
            altitude=current.mslAltitudeMeters;altitudeSource="location-msl"
            verticalAccuracy=if(current.hasMslAltitudeAccuracy())current.mslAltitudeAccuracyMeters else if(current.hasVerticalAccuracy())current.verticalAccuracyMeters else -1f
        }else if(matching!=null){
            altitude=matching.altitudeMslMeters;altitudeSource="nmea-msl";verticalAccuracy=if(current.hasVerticalAccuracy())current.verticalAccuracyMeters else -1f
        }else{issue("waiting_quality","等待GNSS海拔数据（MSL/GGA）");notifyState();return}
        if(!altitude.isFinite() || !positive(verticalAccuracy.toDouble())){issue("waiting_quality","等待有效的海拔和垂直精度");notifyState();return}
        if(elapsed!=previousElapsed || altitudeSource!=previousAltitudeSource){
            currentDown=if(altitudeSource==previousAltitudeSource)GpsProtocol.deriveDown(previousAltitude,previousVerticalAccuracy,previousElapsed,altitude,verticalAccuracy,elapsed)else null
            previousAltitude=altitude;previousVerticalAccuracy=verticalAccuracy;previousElapsed=elapsed;previousAltitudeSource=altitudeSource
        }
        val down=currentDown
        if(down==null){issue("waiting_quality","等待连续GNSS海拔以估计垂直速度");notifyState();return}
        if(!current.hasSpeed() || !current.hasSpeedAccuracy() || !positive(current.speedAccuracyMetersPerSecond.toDouble()) || !current.speed.isFinite() || current.speed<0){issue("waiting_quality","等待GNSS速度及速度精度");notifyState();return}
        val speed=current.speed
        if(speed>0 && (!current.hasBearing() || !current.bearing.isFinite())){issue("waiting_quality","等待GNSS运动方位");notifyState();return}
        val bearing=if(speed==0f)0.0 else Math.toRadians(current.bearing.toDouble());val satellites=satelliteCount(now,matching)
        if(satellites<1){issue("waiting_quality","等待参与定位的实测卫星数");notifyState();return}
        val candidate=GpsProtocol.Fix(current.time,elapsed,current.latitude,current.longitude,altitude,(speed*cos(bearing)).toFloat(),(speed*sin(bearing)).toFloat(),down.downMps,current.accuracy,verticalAccuracy,max(current.speedAccuracyMetersPerSecond,down.accuracyMps),satellites)
        GpsProtocol.invalidReason(candidate,now)?.let{issue("waiting_quality",it);notifyState();return}
        fix=candidate;issue("ready","手机GNSS已定位");notifyState()
        if(candidate.elapsedMillis!=emittedElapsed){emittedElapsed=candidate.elapsedMillis;listener.onFix(candidate)}
    }
    fun getState(): State {
        val now=SystemClock.elapsedRealtime();val current=location
        val age=current?.let{maxOf(0,now-it.elapsedRealtimeNanos/1000000)} ?: -1
        val accuracy=if(current!=null && current.hasAccuracy() && current.accuracy.isFinite())current.accuracy else -1f
        val sats=satelliteCount(now,null)
        if(running && !hasPermission())return State(true,false,"permission","精确定位权限不可用，已暂停推送",age,accuracy,sats)
        if(running && !gpsEnabled())return State(true,false,"provider_off","手机GPS已关闭，已暂停推送",age,accuracy,sats)
        val currentFix=fix
        if(running && currentFix!=null){
            if(GpsProtocol.invalidReason(currentFix,now)==null)return State(true,true,"ready","手机GNSS已定位",age,accuracy,currentFix.satellites)
            return State(true,false,"stale","GNSS定位已过期，已暂停推送",age,accuracy,sats)
        }
        return State(running,false,issuePhase,issueMessage,age,accuracy,sats)
    }
    fun getFix()=if(getState().ready)fix else null
    private fun notifyState()=listener.onStateChanged(getState())
    private fun issue(phase: String, message: String){issuePhase=phase;issueMessage=message}
    private fun diagnostic(message: String){if(message==lastDiagnostic)return;lastDiagnostic=message;Log.i("ActionRelay.GPS",message);listener.onDiagnostic(message)}
    private fun positive(value: Double)=value.isFinite() && value>0
    private fun shortError(error: RuntimeException)=error.javaClass.simpleName.take(60)
}
