package cn.local.actionrelay;
import android.app.Application;
public final class RelayApp extends Application {
    public RelayHub hub;
    @Override public void onCreate() { super.onCreate(); hub=new RelayHub(this); }
}
