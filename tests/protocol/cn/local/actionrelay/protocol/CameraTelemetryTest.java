package cn.local.actionrelay.protocol;

public final class CameraTelemetryTest {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static DjiProtocol.CameraStatus parse(byte[] p){return DjiProtocol.cameraStatus(DjiProtocol.decode(DjiProtocol.frame(0x1d,2,0,1,p)),123);}
    public static void main(String[] args){
        byte[] shortPacket=new byte[7];shortPacket[0]=1;shortPacket[2]=16;shortPacket[3]=6;
        DjiProtocol.CameraStatus shortStatus=parse(shortPacket);
        check(shortStatus.remainingCapacityMb==-1,"short status must not fabricate zero storage");
        check(shortStatus.remainingRecordSeconds==-1,"short status must not fabricate remaining time");
        check(shortStatus.videoResolution==16&&shortStatus.fpsIndex==6,"4K/60 codes are decoded independently of storage");
        byte[] packet=new byte[38];packet[0]=1;packet[15]=0x78;packet[16]=0x56;packet[17]=0x34;packet[18]=(byte)0x92;
        check(parse(packet).remainingCapacityMb==0x92345678L,"capacity is unsigned little endian, not a negative int");
        for(int i=15;i<19;i++)packet[i]=0;
        check(parse(packet).remainingCapacityMb==0,"an actual full card remains zero, not unknown");
        for(int i=15;i<19;i++)packet[i]=(byte)0xff;
        check(parse(packet).remainingCapacityMb==-1,"invalid all-ones capacity is not displayed as terabytes");
        for(int length=7;length<19;length++)check(parse(new byte[length]).remainingCapacityMb==-1,"truncated storage field is absent");
        packet[23]=(byte)0x8b;packet[24]=0x0e;packet[25]=0;packet[26]=0;
        check(parse(packet).remainingRecordSeconds==3723,"remaining time uses offset 23 in seconds");
        packet[23]=0x78;packet[24]=0x56;packet[25]=0x34;packet[26]=(byte)0x92;
        check(parse(packet).remainingRecordSeconds==0x92345678L,"remaining time is unsigned little endian");
        for(int i=23;i<27;i++)packet[i]=(byte)0xff;
        check(parse(packet).remainingRecordSeconds==-1,"all-ones remaining time is unknown");
        for(int i=23;i<27;i++)packet[i]=0;
        check(parse(packet).remainingRecordSeconds==0,"reported zero is distinct from missing time");
        for(int length=7;length<27;length++)check(parse(new byte[length]).remainingRecordSeconds==-1,"partial remaining-time field is absent");
        System.out.println("PASS: "+checks+" camera telemetry decoding checks");
    }
}
