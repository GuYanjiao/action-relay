package cn.local.actionrelay.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** DJI's documented 00/17 GPS packet. Values must originate from current GNSS measurements. */
public final class GpsProtocol {
    private GpsProtocol() {}
    public static final long MAX_FIX_AGE_MS=3000;
    public static final float MAX_HORIZONTAL_ACCURACY_METERS=50f;
    public static final int PAYLOAD_LENGTH=48;
    public static final class Fix {
        public final long utcMillis,elapsedMillis;
        public final double latitude,longitude,altitudeMslMeters;
        public final float northMps,eastMps,downMps;
        public final float horizontalAccuracyMeters,verticalAccuracyMeters,speedAccuracyMps;
        public final int satellites;
        public Fix(long utcMillis,long elapsedMillis,double latitude,double longitude,double altitudeMslMeters,
                   float northMps,float eastMps,float downMps,float horizontalAccuracyMeters,
                   float verticalAccuracyMeters,float speedAccuracyMps,int satellites){
            this.utcMillis=utcMillis;this.elapsedMillis=elapsedMillis;this.latitude=latitude;this.longitude=longitude;
            this.altitudeMslMeters=altitudeMslMeters;this.northMps=northMps;this.eastMps=eastMps;this.downMps=downMps;
            this.horizontalAccuracyMeters=horizontalAccuracyMeters;this.verticalAccuracyMeters=verticalAccuracyMeters;
            this.speedAccuracyMps=speedAccuracyMps;this.satellites=satellites;
        }
    }
    public static String invalidReason(Fix fix,long nowElapsedMs){
        if(fix==null)return "尚无完整GNSS定位";
        if(fix.elapsedMillis<0||nowElapsedMs<fix.elapsedMillis)return "定位单调时间无效";
        if(nowElapsedMs-fix.elapsedMillis>MAX_FIX_AGE_MS)return "GNSS定位已过期";
        if(fix.utcMillis<946684800000L||fix.utcMillis>=4102444800000L)return "GNSS时间无效";
        if(!finite(fix.latitude)||!finite(fix.longitude)||Math.abs(fix.latitude)>90||Math.abs(fix.longitude)>180)return "经纬度无效";
        if(!finite(fix.altitudeMslMeters)||Math.abs(fix.altitudeMslMeters)>2147483.0)return "海拔无效或超出协议范围";
        if(!positive(fix.horizontalAccuracyMeters)||fix.horizontalAccuracyMeters>MAX_HORIZONTAL_ACCURACY_METERS)return "水平定位精度不足";
        if(!positive(fix.verticalAccuracyMeters)||!positive(fix.speedAccuracyMps))return "定位精度字段缺测";
        if(!finite(fix.northMps)||!finite(fix.eastMps)||!finite(fix.downMps)
            ||Math.abs(fix.northMps)>500||Math.abs(fix.eastMps)>500||Math.abs(fix.downMps)>500)return "速度字段无效";
        if(fix.satellites<1||fix.satellites>1024)return "没有有效的实测卫星数";
        if(fix.verticalAccuracyMeters*1000d>0xffffffffL||fix.speedAccuracyMps*100d>0xffffffffL)return "精度字段超出协议范围";
        return null;
    }
    public static byte[] encode(int sequence,Fix fix,long nowElapsedMs){
        String reason=invalidReason(fix,nowElapsedMs);if(reason!=null)throw new IllegalArgumentException(reason);
        Calendar local=Calendar.getInstance(TimeZone.getTimeZone("GMT+08:00"),Locale.ROOT);
        local.setTimeInMillis(fix.utcMillis);
        int date=local.get(Calendar.YEAR)*10000+(local.get(Calendar.MONTH)+1)*100+local.get(Calendar.DAY_OF_MONTH);
        int time=local.get(Calendar.HOUR_OF_DAY)*10000+local.get(Calendar.MINUTE)*100+local.get(Calendar.SECOND);
        ByteBuffer data=ByteBuffer.allocate(PAYLOAD_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(date).putInt(time).putInt((int)Math.round(fix.longitude*1e7)).putInt((int)Math.round(fix.latitude*1e7));
        data.putInt((int)Math.round(fix.altitudeMslMeters*1000));
        data.putFloat(fix.northMps*100).putFloat(fix.eastMps*100).putFloat(fix.downMps*100);
        data.putInt((int)(long)Math.ceil(fix.verticalAccuracyMeters*1000d));
        data.putInt((int)(long)Math.ceil(fix.horizontalAccuracyMeters*1000d));
        data.putInt((int)(long)Math.ceil(fix.speedAccuracyMps*100d)).putInt(fix.satellites);
        return DjiProtocol.frame(0,0x17,DjiProtocol.CMD_NO_RESPONSE,sequence,data.array());
    }
    public static final class VerticalSpeed {
        public final float downMps,accuracyMps;
        VerticalSpeed(float down,float accuracy){downMps=down;accuracyMps=accuracy;}
    }
    /** Difference estimate, not an independently measured Doppler vertical velocity. */
    public static VerticalSpeed deriveDown(double previousAltitude,float previousAccuracy,long previousElapsed,
                                            double altitude,float accuracy,long elapsed){
        long dt=elapsed-previousElapsed;
        if(dt<500||dt>5000||!finite(previousAltitude)||!finite(altitude)||!positive(previousAccuracy)||!positive(accuracy))return null;
        double seconds=dt/1000d;
        double down=(previousAltitude-altitude)/seconds;
        double uncertainty=Math.hypot(previousAccuracy,accuracy)/seconds;
        if(!finite(down)||Math.abs(down)>500||!finite(uncertainty)||uncertainty<=0)return null;
        return new VerticalSpeed((float)down,(float)uncertainty);
    }
    public static final class NmeaFix {
        public final long utcMillisOfDay;
        public final double latitude,longitude,altitudeMslMeters,hdop;
        public final int satellites,quality;
        NmeaFix(long utc,double lat,double lon,double altitude,int satellites,int quality,double hdop){
            utcMillisOfDay=utc;latitude=lat;longitude=lon;altitudeMslMeters=altitude;this.satellites=satellites;this.quality=quality;this.hdop=hdop;
        }
        public boolean matches(long utcMillis,double lat,double lon,float horizontalAccuracy){
            long tod=Math.floorMod(utcMillis,86400000L),difference=Math.abs(tod-utcMillisOfDay);
            difference=Math.min(difference,86400000L-difference);
            if(difference>500)return false;
            double north=(latitude-lat)*111320d;
            double east=(longitude-lon)*111320d*Math.cos(Math.toRadians(lat));
            return Math.hypot(north,east)<=Math.max(10,horizontalAccuracy*3d);
        }
    }
    /** Parses checksummed GGA only; quality 0/6/7/8 (no fix/estimated/manual/simulator) is rejected. */
    public static NmeaFix parseGga(String sentence){
        if(sentence==null||sentence.length()>256)return null;
        String value=sentence.trim();int star=value.indexOf('*');
        if(!value.startsWith("$")||star<1||star!=value.length()-3)return null;
        int checksum=0;
        for(int i=1;i<star;i++){char c=value.charAt(i);if(c>127)return null;checksum^=c;}
        try{
            if(checksum!=Integer.parseInt(value.substring(star+1),16))return null;
            String[] f=value.substring(1,star).split(",",-1);
            if(f.length<11||f[0].length()!=5||!f[0].endsWith("GGA")||!"M".equals(f[10]))return null;
            int quality=Integer.parseInt(f[6]),satellites=Integer.parseInt(f[7]);
            if(quality<1||quality>5||satellites<1||satellites>1024)return null;
            double packedTime=Double.parseDouble(f[1]);if(!finite(packedTime)||packedTime<0)return null;
            int hour=(int)(packedTime/10000),minute=(int)(packedTime/100)%100;
            double second=packedTime-hour*10000-minute*100;
            if(hour>23||minute>59||second<0||second>=60)return null;
            double lat=coordinate(f[2],f[3],true),lon=coordinate(f[4],f[5],false);
            double altitude=Double.parseDouble(f[9]);
            if(!finite(altitude)||Math.abs(altitude)>2147483)return null;
            double hdop=f[8].isEmpty()?Double.NaN:Double.parseDouble(f[8]);
            if(!Double.isNaN(hdop)&&(!finite(hdop)||hdop<0))return null;
            long utc=hour*3600000L+minute*60000L+Math.round(second*1000);
            if(utc>=86400000L)return null;
            return new NmeaFix(utc,lat,lon,altitude,satellites,quality,hdop);
        }catch(IllegalArgumentException ignored){return null;}
    }
    private static double coordinate(String packed,String sign,boolean latitude){
        if(!(latitude?("N".equals(sign)||"S".equals(sign)):("E".equals(sign)||"W".equals(sign))))throw new IllegalArgumentException();
        double value=Double.parseDouble(packed);if(!finite(value)||value<0)throw new IllegalArgumentException();
        int degrees=(int)(value/100);double minutes=value-degrees*100;
        double result=degrees+minutes/60;
        if(minutes<0||minutes>=60||result>(latitude?90:180))throw new IllegalArgumentException();
        return ("S".equals(sign)||"W".equals(sign))?-result:result;
    }
    public static boolean finite(double value){return !Double.isNaN(value)&&!Double.isInfinite(value);}
    private static boolean positive(double value){return finite(value)&&value>0;}
}
