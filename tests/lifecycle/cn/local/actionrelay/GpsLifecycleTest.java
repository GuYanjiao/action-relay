package cn.local.actionrelay;

/** Exercises the production GPS coordinator with a subscription driver, without an Android device. */
public final class GpsLifecycleTest {
    private static final class FakeGps implements GpsLifecycle.Driver {
        int starts,stops; boolean succeeds=true,subscribed;
        public boolean start(){starts++;subscribed=succeeds;return succeeds;}
        public void stop(){stops++;subscribed=false;}
    }
    private static void check(boolean ok,String detail){if(!ok)throw new AssertionError(detail);}
    public static void main(String[] args){
        FakeGps gps=new FakeGps();GpsLifecycle lifecycle=new GpsLifecycle(gps);
        lifecycle.reconfigure(true,false);
        check(gps.starts==0&&!gps.subscribed,"Relay running with GPS off must not subscribe");
        lifecycle.reconfigure(true,true);
        check(gps.starts==1&&gps.subscribed,"Enabling GPS on an already running relay must subscribe");
        lifecycle.reconfigure(true,true);
        check(gps.starts==1,"Repeated service reconfiguration must not duplicate subscriptions");
        lifecycle.reconfigure(true,false);
        check(!gps.subscribed,"Turning GPS off must unsubscribe");
        lifecycle.reconfigure(true,true);
        check(gps.starts==2&&gps.subscribed,"Turning GPS back on must subscribe again");
        lifecycle.reconfigure(false,true);
        check(!gps.subscribed,"Stopping the relay must unsubscribe even with remembered GPS intent");
        lifecycle.reconfigure(true,true);
        check(gps.starts==3&&gps.subscribed,"Restart with remembered intent must subscribe");
        lifecycle.stop();gps.succeeds=false;
        lifecycle.reconfigure(true,true);
        check(gps.starts==4&&!gps.subscribed,"A failed startup must not be marked active");
        gps.succeeds=true;lifecycle.reconfigure(true,true);
        check(gps.starts==5&&gps.subscribed,"A failed startup must remain retryable");
        lifecycle.stop();lifecycle.stop();
        check(!gps.subscribed,"Repeated cleanup must leave GPS stopped");
        System.out.println("PASS: 10 GPS lifecycle checks (running relay toggle, deduplication, restart, failure/retry, cleanup)");
    }
}
