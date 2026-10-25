package cn.local.actionrelay;

import android.Manifest;
import android.app.*;
import android.bluetooth.BluetoothAdapter;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.*;

public final class MainActivity extends Activity implements RelayHub.UiListener {
    private RelayHub hub;
    private LinearLayout root,deviceRows;
    private TextView state,detail,watch,record,battery,logs;
    private Button enable,start,stop;
    private String deviceKey="";
    private boolean showLogs;
    @Override public void onCreate(Bundle savedState){
        super.onCreate(savedState);hub=((RelayApp)getApplication()).hub;
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(10,16,26));
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(24),dp(18),dp(24),dp(32));scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{Insets bars=insets.getInsets(WindowInsets.Type.systemBars());root.setPadding(dp(24),bars.top+dp(18),dp(24),bars.bottom+dp(32));return insets;});
        setContentView(scroll);
        text("Action 中转",31,Color.WHITE,true);
        text("S4  →  小米手机  →  Action 4",15,0xff9caec4,false);
        gap(22);state=text("未开启中转",25,0xff6dbaff,true);detail=text("",16,0xffd6e0ed,false);
        gap(12);watch=text("等待手表连接",15,0xff9caec4,false);
        record=text("录像状态未知",29,Color.WHITE,true);battery=text("",15,0xff9caec4,false);
        gap(14);enable=button("开启手机中转",0xff247bcd,()->{if(hub.enabled)closeRelay();else requestStart();});
        button("手表通信授权",0xff1a2b41,this::authorizeWatch);
        text("首次连接时，按授权页面提示允许本应用与手表通信。",14,0xff9caec4,false);
        gap(18);text("相机连接",20,Color.WHITE,true);
        button("搜索附近的 Action 4",0xff1a2b41,()->{if(!hub.enabled){toast("请先开启手机中转");return;}if(!hub.ble.bluetoothEnabled()){startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));return;}hub.scan();});
        deviceRows=new LinearLayout(this);deviceRows.setOrientation(LinearLayout.VERTICAL);root.addView(deviceRows);
        text("相机需开机；首次连接请在相机上允许并核对4位验证码。",14,0xff9caec4,false);
        gap(18);text("录像控制",20,Color.WHITE,true);
        start=button("开始录像",0xff247bcd,()->hub.phoneCommand(true));
        stop=button("停止录像",0xffa72f48,()->hub.phoneCommand(false));
        text("先将相机设为普通视频模式。状态未确认时，按钮暂停使用。",14,0xff9caec4,false);
        gap(18);button("本应用的系统设置",0xff1a2b41,()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));
        text("锁屏测试前，允许本应用和小米运动健康后台运行，并按系统提供的选项设置为不限制省电。",14,0xff9caec4,false);
        button("重新申请相机配对",0xff1a2b41,()->new AlertDialog.Builder(this).setMessage("仅清除本应用保存的授权标记，并断开当前连接。之后需重新搜索相机。").setPositiveButton("继续",(d,w)->hub.forgetCamera()).setNegativeButton("取消",null).show());
        button("查看连接记录",0xff1a2b41,()->{showLogs=!showLogs;render();});
        logs=text("",13,0xff9caec4,false);logs.setTypeface(Typeface.MONOSPACE);logs.setTextIsSelectable(true);
        text("测试版 0.2.0 · 需实机验证",13,0xff70849e,false);
        render();
    }
    @Override protected void onResume(){super.onResume();hub.addListener(this);render();}
    @Override protected void onPause(){hub.removeListener(this);super.onPause();}
    private void requestStart(){
        ArrayList<String> needed=new ArrayList<>();
        for(String p:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT})if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)needed.add(p);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!needed.isEmpty()){requestPermissions(needed.toArray(new String[0]),12);return;}startRelay();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==12){
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)startRelay();
        else toast("需要允许附近设备权限；可在本应用系统设置中修改");
    }}
    private void startRelay(){if(!hub.ble.bluetoothEnabled()){startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));toast("打开蓝牙后，再点击开启手机中转");return;}startForegroundService(new Intent(this,RelayService.class).setAction(RelayService.START));}
    private void closeRelay(){new AlertDialog.Builder(this).setTitle("关闭手机中转？").setMessage("会断开遥控连接；相机若正在录像，会继续录像。").setPositiveButton("关闭中转",(d,w)->startService(new Intent(this,RelayService.class).setAction(RelayService.STOP))).setNegativeButton("取消",null).show();}
    private void authorizeWatch(){hub.bridge.openAuthorization(this);}
    @Override public void onChanged(){runOnUiThread(this::render);}
    private void render(){
        if(enable==null)return;
        state.setText(hub.enabled?"ready".equals(hub.phase)?"相机已连接":"authorizing".equals(hub.phase)?"等待相机授权":"connecting".equals(hub.phase)?"正在连接相机":"中转已开启":"中转未开启");
        JSONObject s=hub.snapshot();detail.setText(s.optString("message"));watch.setText("手表通信："+hub.watchMessage);
        Object r=s.opt("recording");boolean known=r instanceof Boolean;boolean rec=known&&(Boolean)r;
        record.setText(!known?"录像状态未知":rec?"● 正在录像":"相机未在录像");record.setTextColor(rec?0xffff6d8c:Color.WHITE);
        int sec=s.optInt("recordSeconds",-1),bat=s.optInt("battery",-1);
        battery.setText((bat>=0?"相机电量 "+bat+"%":"")+(known&&rec&&sec>=0?String.format(Locale.ROOT,"    %02d:%02d",sec/60,sec%60):""));
        boolean control=s.optBoolean("canControl");start.setEnabled(control&&!rec);stop.setEnabled(control&&rec);start.setAlpha(start.isEnabled()?1f:.35f);stop.setAlpha(stop.isEnabled()?1f:.35f);
        enable.setText(hub.enabled?"关闭手机中转":"开启手机中转");
        String key=String.join("|",hub.candidates.keySet());
        if(!key.equals(deviceKey)){deviceKey=key;deviceRows.removeAllViews();for(RelayHub.Candidate c:hub.candidates.values()){
            Button row=new Button(this);row.setAllCaps(false);row.setText(c.name+"\n"+c.address);row.setTextColor(Color.WHITE);row.setBackgroundColor(0xff142337);
            row.setOnClickListener(v->hub.connect(c.address));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(66));p.bottomMargin=dp(6);deviceRows.addView(row,p);
        }}
        logs.setVisibility(showLogs?View.VISIBLE:View.GONE);if(showLogs)logs.setText(String.join("\n\n",hub.logs));
    }
    private TextView text(String content,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(content);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(4),1f);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(8);root.addView(t,p);return t;}
    private Button button(String title,int color,Runnable action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextSize(17);b.setTextColor(Color.WHITE);GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(dp(14));b.setBackground(bg);b.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(54));p.bottomMargin=dp(10);root.addView(b,p);return b;}
    private void gap(int height){Space space=new Space(this);root.addView(space,new LinearLayout.LayoutParams(1,dp(height)));}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
}
