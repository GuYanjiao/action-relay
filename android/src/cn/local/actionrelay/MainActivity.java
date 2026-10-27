package cn.local.actionrelay;

import android.Manifest;
import android.app.*;
import android.bluetooth.BluetoothAdapter;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.*;

/** Native Android views, using the spacious cards and restrained color of HyperOS. */
public final class MainActivity extends Activity implements RelayHub.UiListener {
    public static final String START_RELAY_FROM_TILE="cn.local.actionrelay.START_RELAY_FROM_TILE";
    private boolean pendingTileStart;
    private int BG,CARD,SURFACE,WHITE,MUTED,BLUE,GREEN,RED,AMBER;
    private RelayHub hub;
    private LinearLayout root,deviceRows;
    private TextView connectionTitle,recordingStatus,relayLabel,cameraHint,gpsDetail,logsView;
    private StatusItem watchStatus,cameraStatus,batteryStatus,gpsStatus;
    private IconView relayPower;
    private Dialog panel;
    private Switch gpsSwitch,autoSwitch;
    private boolean resumed,pendingGpsReconfigure,bindingSwitches;
    private String deviceKey="";

    @Override public void onCreate(Bundle savedState){
        super.onCreate(savedState);hub=((RelayApp)getApplication()).hub;
        consumeStartupIntent(getIntent());
        boolean night=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        if(night){BG=0xff101114;CARD=0xff1c1e23;SURFACE=0xff252830;WHITE=0xfff2f4f7;MUTED=0xff9da6b5;BLUE=0xff86b7ff;GREEN=0xff6ad9a4;RED=0xffff555c;AMBER=0xffefb866;}
        else {BG=0xfff5f7fb;CARD=0xffffffff;SURFACE=0xffe9eef6;WHITE=0xff17202b;MUTED=0xff596574;BLUE=0xff2768c7;GREEN=0xff168a5b;RED=0xffc93642;AMBER=0xff9a6500;}
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(night?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setBackgroundColor(BG);
        root=column();root.setPadding(dp(24),dp(18),dp(24),dp(26));scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{Insets bars=insets.getInsets(WindowInsets.Type.systemBars());root.setPadding(dp(24),bars.top+dp(14),dp(24),bars.bottom+dp(24));return insets;});
        setContentView(scroll);

        LinearLayout header=row();header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout heading=column();addText(heading,"Action",34,WHITE,true);addText(heading,"相机遥控",14,MUTED,false);
        header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout power=column();power.setGravity(Gravity.CENTER);power.setBackground(ripple(SURFACE,26));
        relayPower=new IconView("power",MUTED);power.addView(relayPower,new LinearLayout.LayoutParams(dp(27),dp(27)));
        power.setContentDescription("开启或关闭手机中转");power.setOnClickListener(v->{if(hub.enabled)closeRelay();else requestStart();});
        header.addView(power,new LinearLayout.LayoutParams(dp(64),dp(64)));root.addView(header);
        gap(root,22);

        LinearLayout statusCard=column();statusCard.setPadding(dp(20),dp(18),dp(20),dp(12));statusCard.setBackground(round(CARD,30));
        LinearLayout cardHeader=row();cardHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView model=label("ACTION 4",16,WHITE,true);cardHeader.addView(model,new LinearLayout.LayoutParams(0,-2,1));
        relayLabel=label("中转关闭",12,MUTED,false);cardHeader.addView(relayLabel);statusCard.addView(cardHeader);
        gap(statusCard,8);connectionTitle=addText(statusCard,"等待连接",21,WHITE,true);gap(statusCard,15);
        LinearLayout indicators=row();
        watchStatus=new StatusItem("watch");cameraStatus=new StatusItem("camera");gpsStatus=new StatusItem("gps");batteryStatus=new StatusItem("battery");
        for(StatusItem item:new StatusItem[]{watchStatus,cameraStatus,gpsStatus,batteryStatus})indicators.addView(item,new LinearLayout.LayoutParams(0,dp(68),1));
        watchStatus.setContentDescription("手表连接状态");cameraStatus.setContentDescription("相机连接状态");batteryStatus.setContentDescription("相机电量");gpsStatus.setContentDescription("手机GPS状态");
        watchStatus.setOnClickListener(v->authorizeWatch());cameraStatus.setOnClickListener(v->showCameraPanel());gpsStatus.setOnClickListener(v->showGpsPanel());
        statusCard.addView(indicators);root.addView(statusCard);

        gap(root,14);recordingStatus=label("录像状态：等待连接",15,MUTED,false);recordingStatus.setGravity(Gravity.CENTER);root.addView(recordingStatus,new LinearLayout.LayoutParams(-1,dp(34)));
        gap(root,24);
        LinearLayout toolbar=row();toolbar.setGravity(Gravity.CENTER);
        toolbar.addView(toolButton("camera","相机",this::showCameraPanel),weightedTool());
        toolbar.addView(toolButton("gps","GPS",this::showGpsPanel),weightedTool());
        toolbar.addView(toolButton("more","工具",this::showToolsPanel),weightedTool());root.addView(toolbar);
        gap(root,12);TextView footer=label("S4  ·  手机中转",12,0xff626b7a,false);footer.setGravity(Gravity.CENTER);root.addView(footer);
        render();
    }

    @Override protected void onResume(){super.onResume();resumed=true;hub.addListener(this);hub.onEnvironmentChanged();if(pendingGpsReconfigure)reconfigureGps();render();if(pendingTileStart){pendingTileStart=false;if(!hub.enabled)requestStart();}}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);consumeStartupIntent(intent);if(resumed&&pendingTileStart){pendingTileStart=false;if(!hub.enabled)requestStart();}}
    private void consumeStartupIntent(Intent intent){if(intent!=null&&START_RELAY_FROM_TILE.equals(intent.getAction())){pendingTileStart=true;intent.setAction(Intent.ACTION_MAIN);}}
    @Override protected void onPause(){resumed=false;hub.removeListener(this);super.onPause();}
    @Override protected void onDestroy(){if(panel!=null)panel.dismiss();super.onDestroy();}
    @Override public void onChanged(){runOnUiThread(this::render);}

    private void requestStart(){
        ArrayList<String> needed=new ArrayList<>();
        for(String p:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT})if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)needed.add(p);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!needed.isEmpty()){requestPermissions(needed.toArray(new String[0]),12);return;}startRelay();
    }
    private void requestGps(){
        if(hub.gpsEnabled){hub.setGpsEnabled(false);reconfigureGps();render();return;}
        if(!hub.enabled){toast("先开启手机中转，再打开GPS");render();requestStart();return;}
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},13);return;
        }
        hub.setGpsEnabled(true);reconfigureGps();render();
    }
    private void reconfigureGps(){
        if(!resumed){pendingGpsReconfigure=true;return;}
        pendingGpsReconfigure=false;
        if(!hub.enabled)return;
        try{startForegroundService(new Intent(this,RelayService.class).setAction(RelayService.RECONFIGURE));}
        catch(RuntimeException e){toast("GPS暂未启动，请回到应用后重试");hub.setGpsStartError("无法启动定位服务："+e.getClass().getSimpleName());hub.log("界面启动GPS服务失败："+e.getClass().getSimpleName());}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==12){
            if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)startRelay();
            else toast("需要附近设备权限，可在系统设置中修改");
        }else if(request==13){
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED&&hub.enabled){hub.setGpsEnabled(true);reconfigureGps();}
            else {hub.setGpsEnabled(false);toast("GPS需要精确位置权限");}
            render();
        }
    }
    private void startRelay(){
        if(!hub.ble.bluetoothEnabled()){startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),14);toast("打开蓝牙后继续开启中转");return;}
        startForegroundService(new Intent(this,RelayService.class).setAction(RelayService.START));
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==14&&result==RESULT_OK&&!hub.enabled)requestStart();
    }
    private void closeRelay(){new AlertDialog.Builder(this).setTitle("关闭手机中转？").setMessage("会断开遥控连接。相机若正在录像，会继续录像。").setPositiveButton("关闭中转",(d,w)->startService(new Intent(this,RelayService.class).setAction(RelayService.STOP))).setNegativeButton("取消",null).show();}
    private void authorizeWatch(){hub.bridge.openAuthorization(this);}
    private void scanCamera(){
        if(!hub.enabled){toast("先开启手机中转");requestStart();return;}
        if(!hub.ble.bluetoothEnabled()){startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));return;}
        deviceKey="";hub.scan();
    }

    private void showCameraPanel(){
        LinearLayout content=sheet("相机");
        cameraHint=addText(content,"相机开机后，点击搜索",15,MUTED,false);gap(content,8);
        addAction(content,"search","搜索相机",this::scanCamera);
        if(hub.rememberedCameraAddress!=null&&!hub.rememberedCameraAddress.isEmpty()){
            String name=hub.rememberedCameraName==null||hub.rememberedCameraName.isEmpty()?"上次的相机":hub.rememberedCameraName;
            addAction(content,"reconnect","重连 · "+name,()->{hub.requestReconnect();if(!hub.enabled){requestStart();}});
            if(hub.rememberedCameraLastSuccess>0){
                addText(content,"上次连接 · "+android.text.format.DateFormat.getDateFormat(this).format(new Date(hub.rememberedCameraLastSuccess))
                    +" "+android.text.format.DateFormat.getTimeFormat(this).format(new Date(hub.rememberedCameraLastSuccess)),13,MUTED,false);
            }
            addAction(content,"close","忘记相机",()->new AlertDialog.Builder(this).setTitle("忘记这台相机？")
                .setMessage("清除本应用的设备记忆并断开连接。下次需重新搜索和确认配对。")
                .setPositiveButton("忘记",(d,w)->{hub.forgetCamera();showCameraPanel();}).setNegativeButton("取消",null).show());
        }
        deviceRows=column();content.addView(deviceRows);deviceKey="";
        gap(content,12);addText(content,"首次连接，请在相机确认并核对验证码。",13,MUTED,false);
        showSheet();render();
    }
    private void showGpsPanel(){
        LinearLayout content=sheet("手机 GPS");
        LinearLayout toggle=row();toggle.setGravity(Gravity.CENTER_VERTICAL);toggle.setMinimumHeight(dp(72));
        TextView name=label("将定位发送到相机",17,WHITE,true);toggle.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        gpsSwitch=new Switch(this);gpsSwitch.setContentDescription("开启或关闭GPS");gpsSwitch.setButtonTintList(ColorStateList.valueOf(BLUE));toggle.addView(gpsSwitch,new LinearLayout.LayoutParams(dp(72),dp(64)));content.addView(toggle);
        gpsSwitch.setOnCheckedChangeListener((v,checked)->{if(!bindingSwitches&&checked!=hub.gpsEnabled)requestGps();});
        gap(content,10);gpsDetail=addText(content,"",15,MUTED,false);
        addAction(content,"settings","位置权限设置",this::openAppSettings);
        addText(content,"请在户外定位。GPS就绪表示手机定位可发送；视频仪表盘仍以相机实际素材为准。",13,MUTED,false);
        showSheet();render();
    }
    private void showToolsPanel(){
        LinearLayout content=sheet("工具");
        addAction(content,"key","手表通信授权",this::authorizeWatch);
        addAction(content,"info","保持手表连接",this::showKeepConnectedHelp);
        addAction(content,"tile","添加中转磁贴",()->RecordingTileService.requestAdd(this));
        LinearLayout auto=row();auto.setMinimumHeight(dp(72));auto.setGravity(Gravity.CENTER_VERTICAL);auto.setPadding(dp(16),0,dp(10),0);auto.setBackground(round(SURFACE,22));
        LinearLayout words=column();addText(words,"自动重连",17,WHITE,true);addText(words,"仅重连相机，不自动录像",12,MUTED,false);auto.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        autoSwitch=new Switch(this);autoSwitch.setContentDescription("自动重连已记住的相机");auto.addView(autoSwitch,new LinearLayout.LayoutParams(dp(72),dp(64)));content.addView(auto,spaced(-1,dp(76),0,8));
        autoSwitch.setOnCheckedChangeListener((v,checked)->{if(!bindingSwitches)hub.setAutoReconnect(checked);});
        addAction(content,"settings","本应用的系统设置",this::openAppSettings);
        addAction(content,"log","连接记录",this::showLogsPanel);
        addAction(content,"refresh","重新配对相机",()->new AlertDialog.Builder(this).setTitle("重新配对相机？").setMessage("清除本应用保存的配对授权并断开连接。之后需重新搜索相机。").setPositiveButton("重新配对",(d,w)->hub.forgetCamera()).setNegativeButton("取消",null).show());
        addAction(content,"info","使用提示",()->new AlertDialog.Builder(this).setTitle("使用提示").setMessage("相机请设为普通视频模式。首次连接需在相机确认验证码。\n\n锁屏使用前，允许本应用和小米运动健康后台运行，并按系统提供的选项设置为不限制省电。\n\n自动重连不会自动录像。状态未确认时，主键会暂停使用。").setPositiveButton("知道了",null).show());
        gap(content,8);addText(content,"Action 中转 · 0.4.4",12,MUTED,false);showSheet();render();
    }
    private void showLogsPanel(){LinearLayout content=sheet("连接记录");logsView=addText(content,"",12,MUTED,false);logsView.setTypeface(Typeface.MONOSPACE);logsView.setTextIsSelectable(true);showSheet();render();}
    private void openAppSettings(){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}
    private void openCompanionSettings(){
        for(String pkg:new String[]{"com.mi.health","com.xiaomi.wearable"}){
            try{getPackageManager().getPackageInfo(pkg,0);}catch(Exception e){continue;}
            try{startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+pkg)));return;}catch(Exception ignored){}
        }
        toast("未找到小米运动健康");
    }
    private void showKeepConnectedHelp(){
        new AlertDialog.Builder(this).setTitle("保持手表连接")
            .setMessage("手表断连，多数是“小米运动健康”被系统在后台清理。按下面三步设置一次，之后通常无需再打开它：\n\n1. 省电不受限：在系统设置里找到“小米运动健康”，把省电策略设为“无限制”。\n\n2. 允许自启动：在自启动管理中允许“小米运动健康”开机或后台自启动。\n\n3. 后台锁定：在多任务界面长按“小米运动健康”并点“锁定”。\n\n通信授权只需首次同意一次，本应用会自动检测并重连手表。")
            .setPositiveButton("打开小米运动健康设置",(d,w)->openCompanionSettings())
            .setNegativeButton("知道了",null).show();
    }

    private LinearLayout sheet(String title){
        if(panel!=null)panel.dismiss();deviceRows=null;cameraHint=null;gpsDetail=null;logsView=null;gpsSwitch=null;autoSwitch=null;
        panel=new Dialog(this);panel.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout body=column();body.setPadding(dp(22),dp(18),dp(22),dp(26));body.setBackground(round(CARD,32));
        LinearLayout header=row();header.setGravity(Gravity.CENTER_VERTICAL);TextView name=label(title,26,WHITE,true);header.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        FrameLayout close=new FrameLayout(this);close.setBackground(ripple(SURFACE,24));IconView closeIcon=new IconView("close",MUTED);FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER);close.addView(closeIcon,ip);close.setContentDescription("关闭面板");close.setOnClickListener(v->panel.dismiss());header.addView(close,new LinearLayout.LayoutParams(dp(64),dp(64)));body.addView(header);gap(body,12);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout content=column();scroll.addView(content);int max=(int)(getResources().getDisplayMetrics().heightPixels*.72f);body.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(max,dp(540))));
        panel.setContentView(body);return content;
    }
    private void showSheet(){panel.show();Window w=panel.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);WindowManager.LayoutParams p=w.getAttributes();p.dimAmount=.55f;p.gravity=Gravity.BOTTOM;p.width=-1;p.height=-2;w.setAttributes(p);w.setNavigationBarColor(CARD);}}

    private void render(){
        JSONObject s=hub.snapshot();
        boolean ready="ready".equals(hub.phase);boolean watchConnected=s.optBoolean("watchConnected",false);
        Object truth=s.opt("recording");boolean known=truth instanceof Boolean;boolean rec=known&&(Boolean)truth;
        String phase=s.optString("recordPhase","unknown");boolean canStart=s.optBoolean("canStart");
        String connection=ready?"已连接":"authorizing".equals(hub.phase)?"请在相机确认":"connecting".equals(hub.phase)?"连接中":"等待连接";
        if("authorizing".equals(hub.phase)&&hub.verificationCode>0)connection="确认配对 · "+String.format(Locale.ROOT,"%04d",hub.verificationCode);
        connectionTitle.setText(connection);relayLabel.setText(hub.enabled?"中转开启":"中转关闭");relayLabel.setTextColor(hub.enabled?BLUE:MUTED);relayPower.color=hub.enabled?BLUE:MUTED;relayPower.invalidate();
        watchStatus.update(watchConnected?"已连接":"未连接",watchConnected?GREEN:MUTED);cameraStatus.update(ready?"已连接":"未连接",ready?GREEN:MUTED);
        int battery=s.optInt("battery",-1);batteryStatus.update(battery>=0?battery+"%":"—",battery<0?MUTED:battery<=20?RED:battery<=50?AMBER:GREEN);
        String gps=s.optString("gpsState",hub.gpsState);boolean gpsOn=hub.gpsEnabled;int gpsColor=!gpsOn?MUTED:"ready".equals(gps)?GREEN:"permission".equals(gps)||"denied".equals(gps)||"unavailable".equals(gps)?RED:AMBER;
        String gpsTitle=!gpsOn?"关闭":!hub.enabled?"暂停":"ready".equals(gps)?"已定位":"permission".equals(gps)||"denied".equals(gps)?"未授权":"provider_off".equals(gps)?"请打开定位":"stale".equals(gps)?"定位已过期":"unavailable".equals(gps)?"启动失败":"off".equals(gps)?"未启动":"定位中";
        gpsStatus.update(gpsTitle,gpsColor);
        recordingStatus.setText("录像状态："+("stopping".equals(phase)?"停止中":"starting".equals(phase)?"开始中":rec?"录像中":canStart?"待机":!hub.enabled?"先开启中转":!ready?"等待相机连接":"等待状态"));
        recordingStatus.setTextColor(rec?RED:MUTED);
        if(cameraHint!=null)cameraHint.setText(s.optString("message","相机开机后，点击搜索"));
        if(deviceRows!=null){
            String key=String.join("|",hub.candidates.keySet());if(!key.equals(deviceKey)){deviceKey=key;deviceRows.removeAllViews();for(RelayHub.Candidate candidate:hub.candidates.values()){
                LinearLayout device=row();device.setGravity(Gravity.CENTER_VERTICAL);device.setPadding(dp(16),dp(12),dp(16),dp(12));device.setBackground(ripple(SURFACE,22));
                IconView icon=new IconView("camera",BLUE);device.addView(icon,new LinearLayout.LayoutParams(dp(28),dp(28)));LinearLayout names=column();names.setPadding(dp(15),0,0,0);addText(names,candidate.name,16,WHITE,true);device.addView(names,new LinearLayout.LayoutParams(0,-2,1));device.setOnClickListener(v->hub.connect(candidate.address));deviceRows.addView(device,spaced(-1,dp(78),0,8));
            }}
        }
        bindingSwitches=true;if(gpsSwitch!=null)gpsSwitch.setChecked(hub.gpsEnabled);if(autoSwitch!=null)autoSwitch.setChecked(hub.autoReconnectEnabled);bindingSwitches=false;
        if(gpsDetail!=null){String quality="off".equals(gps)||!gpsOn?"GPS已关闭":s.optString("gpsMessage",hub.gpsMessage);float accuracy=hub.gpsAccuracy,speed=hub.gpsSpeed;gpsDetail.setText(quality+(accuracy>=0?String.format(Locale.ROOT,"\n精度 ±%.0f m",accuracy):"")+(speed>=0?String.format(Locale.ROOT,"\n速度 %.1f km/h",speed*3.6f):""));gpsDetail.setTextColor(gpsColor);}
        if(logsView!=null)logsView.setText(String.join("\n\n",hub.logs));
    }

    private LinearLayout toolButton(String icon,String title,Runnable action){LinearLayout v=column();v.setGravity(Gravity.CENTER);v.setBackground(ripple(SURFACE,25));v.setMinimumHeight(dp(80));v.setContentDescription(title);v.setOnClickListener(w->action.run());v.addView(new IconView(icon,WHITE),new LinearLayout.LayoutParams(dp(28),dp(28)));gap(v,7);TextView text=label(title,13,MUTED,false);text.setGravity(Gravity.CENTER);v.addView(text);return v;}
    private LinearLayout.LayoutParams weightedTool(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(88),1);p.leftMargin=dp(4);p.rightMargin=dp(4);return p;}
    private void addAction(LinearLayout parent,String icon,String title,Runnable action){LinearLayout item=row();item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(dp(17),0,dp(15),0);item.setBackground(ripple(SURFACE,22));item.addView(new IconView(icon,BLUE),new LinearLayout.LayoutParams(dp(25),dp(25)));TextView text=label(title,17,WHITE,false);LinearLayout.LayoutParams textParams=new LinearLayout.LayoutParams(0,-2,1);textParams.leftMargin=dp(16);item.addView(text,textParams);item.addView(new IconView("chevron",MUTED),new LinearLayout.LayoutParams(dp(18),dp(18)));item.setContentDescription(title);item.setOnClickListener(v->action.run());parent.addView(item,spaced(-1,dp(68),0,8));}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private TextView label(String content,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(content);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1f);if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    private TextView addText(LinearLayout parent,String content,int size,int color,boolean bold){TextView t=label(content,size,color,bold);parent.addView(t,new LinearLayout.LayoutParams(-1,-2));return t;}
    private void gap(LinearLayout parent,int height){Space space=new Space(this);parent.addView(space,new LinearLayout.LayoutParams(1,dp(height)));}
    private LinearLayout.LayoutParams spaced(int width,int height,int top,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(width,height);p.topMargin=dp(top);p.bottomMargin=dp(bottom);return p;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private Drawable ripple(int color,int radius){return new RippleDrawable(ColorStateList.valueOf((WHITE&0x00ffffff)|0x22000000),round(color,radius),round(Color.WHITE,radius));}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}

    private final class StatusItem extends LinearLayout {
        final IconView icon;final TextView value;
        StatusItem(String type){super(MainActivity.this);setOrientation(VERTICAL);setGravity(Gravity.CENTER);setMinimumHeight(dp(64));icon=new IconView(type,MUTED);addView(icon,new LinearLayout.LayoutParams(dp(27),dp(27)));Space gap=new Space(MainActivity.this);addView(gap,new LinearLayout.LayoutParams(1,dp(8)));value=label("—",12,MUTED,false);value.setGravity(Gravity.CENTER);addView(value);}
        void update(String text,int color){value.setText(text);value.setTextColor(color);icon.color=color;icon.invalidate();}
    }
    private final class IconView extends View {
        final String kind;int color;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        IconView(String kind,int color){super(MainActivity.this);this.kind=kind;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void line(Canvas c,float x1,float y1,float x2,float y2){c.drawLine(x1,y1,x2,y2,p);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);c.save();c.scale(getWidth()/24f,getHeight()/24f);p.setColor(color);p.setStrokeWidth(1.8f);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            switch(kind){
                case "watch":c.drawRoundRect(6,5,18,19,3,3,p);line(c,9,2,15,2);line(c,9,22,15,22);line(c,11,8,11,12);line(c,11,12,14,14);break;
                case "camera":c.drawRoundRect(2.5f,5,21.5f,20,3,3,p);c.drawCircle(14.5f,12.5f,4,p);line(c,5.5f,8,8.5f,8);line(c,5.5f,16.5f,8,16.5f);break;
                case "gps":c.drawCircle(12,12,7,p);c.drawCircle(12,12,2.5f,p);line(c,12,2,12,5);line(c,12,19,12,22);line(c,2,12,5,12);line(c,19,12,22,12);break;
                case "battery":c.drawRoundRect(2,6,20,18,2,2,p);line(c,22,10,22,14);for(int x=5;x<17;x+=3)line(c,x,9,x,15);break;
                case "power":c.drawArc(new RectF(4,4,20,20),-50,280,false,p);line(c,12,2,12,11);break;
                case "search":c.drawCircle(10,10,6,p);line(c,14.5f,14.5f,21,21);break;
                case "close":line(c,6,6,18,18);line(c,18,6,6,18);break;
                case "chevron":line(c,9,5,16,12);line(c,16,12,9,19);break;
                case "key":c.drawCircle(8,8,4,p);line(c,11,11,21,21);line(c,16,16,19,13);line(c,19,19,22,16);break;
                case "tile":for(int x=3;x<20;x+=11)for(int y=3;y<20;y+=11)c.drawRoundRect(x,y,x+7,y+7,1.5f,1.5f,p);break;
                case "log":c.drawRoundRect(5,3,19,21,2,2,p);for(int y=7;y<19;y+=5)line(c,8,y,16,y);break;
                case "info":c.drawCircle(12,12,9,p);line(c,12,11,12,17);c.drawCircle(12,7,.5f,p);break;
                case "settings":c.drawCircle(12,12,6,p);c.drawCircle(12,12,2.5f,p);for(int i=0;i<8;i++){double a=i*Math.PI/4;line(c,12+(float)Math.cos(a)*8,12+(float)Math.sin(a)*8,12+(float)Math.cos(a)*10,12+(float)Math.sin(a)*10);}break;
                case "refresh":case "reconnect":c.drawArc(new RectF(4,4,20,20),30,285,false,p);line(c,20,3,20,9);line(c,20,9,14,9);break;
                default:p.setStyle(Paint.Style.FILL);c.drawCircle(5,12,1.5f,p);c.drawCircle(12,12,1.5f,p);c.drawCircle(19,12,1.5f,p);break;
            }c.restore();
        }
    }
}
