package cn.local.actionrelay;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

public final class RelayService extends Service {
    public static final String START="cn.local.actionrelay.START", STOP="cn.local.actionrelay.STOP", RECONFIGURE="cn.local.actionrelay.RECONFIGURE";
    private static final String CHANNEL="action_relay";
    private RelayHub hub;
    @Override public void onCreate(){super.onCreate();hub=((RelayApp)getApplication()).hub;}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&STOP.equals(intent.getAction())){hub.stop();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED
            ||checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED){hub.log("需要允许附近设备权限才能开启中转");stopSelf();return START_NOT_STICKY;}
        if(hub.gpsEnabled&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            hub.setGpsEnabled(false);hub.log("缺少精确定位权限，已关闭GPS推送");
        }
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"相机中转",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,RelayService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification note=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_relay)
            .setContentTitle("Action 4 手机中转已开启").setContentText("保持手机和手表、相机在蓝牙范围内")
            .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(new Notification.Action.Builder(null,"关闭中转",stop).build()).build();
        int type=ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if(hub.gpsEnabled)type|=ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        startForeground(41, note, type);
        hub.start();return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){if(hub!=null&&hub.enabled)hub.stop();super.onDestroy();}
}
