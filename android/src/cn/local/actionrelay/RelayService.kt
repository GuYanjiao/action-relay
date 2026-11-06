package cn.local.actionrelay

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder

class RelayService: Service() {
    companion object {
        const val START="cn.local.actionrelay.START"
        const val STOP="cn.local.actionrelay.STOP"
        const val RECONFIGURE="cn.local.actionrelay.RECONFIGURE"
        private const val CHANNEL="action_relay"
    }
    private lateinit var hub: RelayHub
    override fun onCreate(){super.onCreate();hub=(application as RelayApp).hub}
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action==STOP){hub.stop();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY}
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED){hub.log("需要允许附近设备权限才能开启中转");stopSelf();return START_NOT_STICKY}
        if(hub.gpsEnabled && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){hub.setGpsEnabled(false);hub.log("缺少精确定位权限，已关闭GPS推送")}
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"相机中转",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,RelayService::class.java).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_relay).setContentTitle("Action 4 手机中转已开启").setContentText("保持手机和手表、相机在蓝牙范围内")
            .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).addAction(Notification.Action.Builder(null,"关闭中转",stop).build()).build()
        var type=ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if(hub.gpsEnabled)type=type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try{startForeground(41,note,type)}catch(error: RuntimeException){
            if(error !is SecurityException && error !is IllegalStateException)throw error
            if(hub.gpsEnabled)hub.setGpsStartError("系统拒绝定位前台服务："+error.javaClass.simpleName)
            hub.log("前台服务启动失败："+error.javaClass.simpleName)
            if(intent?.action!=RECONFIGURE)stopSelf();return START_NOT_STICKY
        }
        if(intent?.action==RECONFIGURE){hub.reconfigureGps();return START_NOT_STICKY}
        hub.start();return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onDestroy(){if(::hub.isInitialized && hub.enabled)hub.stop();super.onDestroy()}
}
