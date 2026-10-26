package cn.local.actionrelay;

/** Tokens invalidate delayed reconnects after stop, forget or target changes. No Android dependency. */
public final class ReconnectPolicy {
    public static final int MAX_ATTEMPTS=5;
    public static final class Ticket {
        public final long delayMs;
        private final int generation;
        private Ticket(int generation,long delayMs){this.generation=generation;this.delayMs=delayMs;}
    }
    private int generation,attempts;
    private Ticket pending;
    private boolean active=true;
    public void reset(){cancel();active=true;attempts=0;}
    public void cancel(){generation++;pending=null;active=false;}
    public Ticket schedule(){
        if(!active||pending!=null||attempts>=MAX_ATTEMPTS)return null;
        long delay=attempts==0?0:Math.min(30000,3000L<<(attempts-1));
        return pending=new Ticket(generation,delay);
    }
    public boolean claim(Ticket ticket){
        if(!active||ticket==null||ticket!=pending||ticket.generation!=generation)return false;
        pending=null;attempts++;return true;
    }
    public boolean exhausted(){return active&&attempts>=MAX_ATTEMPTS&&pending==null;}
}
