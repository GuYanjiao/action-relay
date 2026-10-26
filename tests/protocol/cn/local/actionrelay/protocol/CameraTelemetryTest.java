package cn.local.actionrelay.protocol;

public final class CameraTelemetryTest {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static DjiProtocol.CameraStatus parse(byte[] p){return DjiProtocol.cameraStatus(DjiProtocol.decode(DjiProtocol.frame(0x1d,2,0,1,p)),123);}
    public static void main(String[] args){
        byte[] shortPacket=new byte[7];shortPacket[0]=1;shortPacket[2]=16;shortPacket[3]=6;
        DjiProtocol.CameraStatus shortStatus=parse(shortPacket);
        check(shortStatus.remainingCapacityMb==-1,"short status must not fabricate zero storage");
        check(shortStatus.videoResolution==16&&shortStatus.fpsIndex==6,"4K/60 codes are decoded independently of storage");
        byte[] packet=new byte[38];packet[0]=1;packet[15]=0x78;packet[16]=0x56;packet[17]=0x34;packet[18]=(byte)0x92;
        check(parse(packet).remainingCapacityMb==0x92345678L,"capacity is unsigned little endian, not a negative int");
        for(int i=15;i<19;i++)packet[i]=0;
        check(parse(packet).remainingCapacityMb==0,"an actual full card remains zero, not unknown");
        for(int i=15;i<19;i++)packet[i]=(byte)0xff;
        check(parse(packet).remainingCapacityMb==-1,"invalid all-ones capacity is not displayed as terabytes");
        for(int length=7;length<19;length++)check(parse(new byte[length]).remainingCapacityMb==-1,"truncated storage field is absent");
        System.out.println("PASS: "+checks+" camera telemetry decoding checks");
    }
}
