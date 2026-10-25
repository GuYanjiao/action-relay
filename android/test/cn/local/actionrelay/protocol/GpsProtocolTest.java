package cn.local.actionrelay.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Run with javac/java, without Android runtime or actual location access. */
public final class GpsProtocolTest {
    private static int checks;
    private static void ok(boolean condition,String name){checks++;if(!condition)throw new AssertionError(name);}
    private static long utc(int y,int m,int d,int h,int min,int sec){Calendar c=Calendar.getInstance(TimeZone.getTimeZone("UTC"),Locale.ROOT);c.clear();c.set(y,m-1,d,h,min,sec);return c.getTimeInMillis();}
    private static GpsProtocol.Fix fix(long time,long elapsed,double lat,double lon,double alt,float north,float east,float down,float hacc,float vacc,float sacc,int sats){return new GpsProtocol.Fix(time,elapsed,lat,lon,alt,north,east,down,hacc,vacc,sacc,sats);}
    private static GpsProtocol.Fix good(){return fix(utc(2026,10,26,16,1,2),10000,31.2345678,-121.8765432,-12.345,1.5f,-2.25f,-.5f,3.2f,5.1f,.12f,12);}
    private static void rejects(GpsProtocol.Fix value,long now,String name){boolean rejected=false;try{GpsProtocol.encode(1,value,now);}catch(IllegalArgumentException expected){rejected=true;}ok(rejected,name);}
    private static String nmea(String fields){int c=0;for(int i=0;i<fields.length();i++)c^=fields.charAt(i);return "$"+fields+"*"+String.format(Locale.ROOT,"%02X",c);}
    public static void main(String[] args){
        GpsProtocol.Fix f=good();byte[] raw=GpsProtocol.encode(42,f,11000);DjiProtocol.Frame frame=DjiProtocol.decode(raw);
        ok(raw.length==66,"66-byte full frame");ok(frame.commandSet==0&&frame.commandId==0x17,"official 00/17 command");
        ok(frame.commandType==DjiProtocol.CMD_NO_RESPONSE&&frame.sequence==42,"no-ACK GPS, caller sequence preserved");
        ok(frame.payload.length==48,"48-byte payload");ByteBuffer b=ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN);
        ok(b.getInt(0)==20261027&&b.getInt(4)==102,"UTC+8 day rollover is normalized");
        ok(b.getInt(8)==-1218765432&&b.getInt(12)==312345678,"signed WGS84 longitude then latitude");
        ok(b.getInt(16)==-12345,"MSL altitude millimetres signed");
        ok(b.getFloat(20)==150&&b.getFloat(24)==-225&&b.getFloat(28)==-50,"N/E/down cm/s floats");
        ok(b.getInt(32)>=5100&&b.getInt(36)>=3200&&b.getInt(40)>=12,"accuracy conservatively rounds upward");
        ok(b.getInt(44)==12,"real supplied satellite count");
        rejects(null,10000,"null fix");rejects(f,9999,"future monotonic timestamp");rejects(f,13001,"expired fix");
        ok(GpsProtocol.invalidReason(f,13000)==null,"freshness exact boundary");
        rejects(fix(f.utcMillis,10000,Double.NaN,0,0,0,0,0,1,1,1,5),10000,"NaN latitude");
        rejects(fix(f.utcMillis,10000,0,181,0,0,0,0,1,1,1,5),10000,"longitude range");
        rejects(fix(f.utcMillis,10000,0,0,Double.POSITIVE_INFINITY,0,0,0,1,1,1,5),10000,"unknown altitude");
        rejects(fix(f.utcMillis,10000,0,0,0,0,0,0,51,1,1,5),10000,"poor horizontal accuracy");
        rejects(fix(f.utcMillis,10000,0,0,0,0,0,0,1,0,1,5),10000,"missing vertical accuracy");
        rejects(fix(f.utcMillis,10000,0,0,0,0,0,0,1,1,0,5),10000,"missing speed accuracy");
        rejects(fix(f.utcMillis,10000,0,0,0,0,0,0,1,1,1,0),10000,"unknown satellites not fabricated");
        rejects(fix(f.utcMillis,10000,0,0,0,Float.NaN,0,0,1,1,1,5),10000,"unknown direction speed");
        rejects(fix(0,10000,0,0,0,0,0,0,1,1,1,5),10000,"invalid GNSS UTC");
        GpsProtocol.VerticalSpeed vertical=GpsProtocol.deriveDown(100,3,1000,104,4,3000);
        ok(vertical!=null&&vertical.downMps==-2&&vertical.accuracyMps==2.5f,"derived upward velocity and propagated uncertainty");
        ok(GpsProtocol.deriveDown(100,3,1000,104,4,1000)==null,"same fix not used for derivative");
        ok(GpsProtocol.deriveDown(100,3,1000,104,4,6001)==null,"old altitude not used for derivative");
        ok(GpsProtocol.deriveDown(100,0,1000,104,4,2000)==null,"no invented derivative precision");
        String fields="GNGGA,000001.000,3114.0000,N,12130.0000,E,1,12,0.9,20.5,M,4.0,M,,";
        GpsProtocol.NmeaFix g=GpsProtocol.parseGga(nmea(fields));
        ok(g!=null&&g.satellites==12&&g.quality==1&&g.altitudeMslMeters==20.5,"checksummed GGA data");
        ok(Math.abs(g.latitude-31.2333333333)<1e-9&&g.longitude==121.5,"NMEA degrees/minutes conversion");
        ok(g.hdop==.9,"HDOP retained as actual optional field, not transmitted");
        ok(g.matches(utc(2026,10,26,0,0,1),g.latitude,g.longitude,3),"GGA matches current fix");
        ok(!g.matches(utc(2026,10,26,0,0,2),g.latitude,g.longitude,3),"stale epoch GGA rejected");
        ok(!g.matches(utc(2026,10,26,0,0,1),0,0,3),"different-location GGA rejected");
        ok(GpsProtocol.parseGga(nmea(fields).replace("*","*0"))==null,"malformed checksum rejected");
        ok(GpsProtocol.parseGga(nmea(fields).substring(0,nmea(fields).length()-1)+"Z")==null,"invalid checksum hex rejected");
        ok(GpsProtocol.parseGga(nmea(fields.replace(",1,12,",",0,12,")))==null,"no-fix GGA rejected");
        ok(GpsProtocol.parseGga(nmea(fields.replace(",1,12,",",6,12,")))==null,"dead reckoning GGA rejected");
        ok(GpsProtocol.parseGga(nmea(fields.replace(",1,12,",",8,12,")))==null,"simulator GGA rejected");
        ok(GpsProtocol.parseGga(nmea(fields.replace("3114.0000","3164.0000")))==null,"invalid coordinate minutes rejected");
        ok(GpsProtocol.parseGga(nmea(fields.replace("20.5,M","20.5,F")))==null,"altitude units must be metres");
        GpsProtocol.NmeaFix south=GpsProtocol.parseGga(nmea(fields.replace(",N,",",S,").replace(",E,",",W,")));
        ok(south!=null&&south.latitude<0&&south.longitude<0,"southern/western hemisphere");
        GpsProtocol.NmeaFix midnight=GpsProtocol.parseGga(nmea(fields.replace("000001.000","235959.800")));
        ok(midnight!=null&&midnight.matches(utc(2026,10,26,0,0,0),midnight.latitude,midnight.longitude,3),"GGA matching across UTC midnight");
        String official="AA420000000000000300D2DD00174F0035018AA50200BCB2E943A34F750DB96D0000DE5824418D9AC54100000000E8030000E80300000A0000000E00000063B5D131";
        byte[] expected=new byte[official.length()/2];for(int i=0;i<expected.length;i++)expected[i]=(byte)Integer.parseInt(official.substring(i*2,i*2+2),16);
        DjiProtocol.Frame reference=DjiProtocol.decode(expected);
        ok(reference.is(0,0x17)&&reference.payload.length==48,"official captured GPS test frame accepted");
        ok(Arrays.equals(expected,DjiProtocol.frame(0,0x17,0,3,reference.payload)),"official framing and CRC vector exact");
        System.out.println("PASS "+checks+" GPS protocol/quality checks");
    }
}
