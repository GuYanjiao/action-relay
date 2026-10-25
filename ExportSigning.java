import java.nio.file.*;
import java.io.InputStream;
import java.security.*;
import java.util.Base64;
public final class ExportSigning {
  public static void main(String[] args) throws Exception {
    KeyStore ks=KeyStore.getInstance("PKCS12");
    try(InputStream in=Files.newInputStream(Paths.get(args[0]))){ks.load(in,args[1].toCharArray());}
    Key key=ks.getKey("actionrelay",args[1].toCharArray());
    java.security.cert.Certificate cert=ks.getCertificate("actionrelay");
    Path target=Paths.get(args[2]);Files.createDirectories(target);
    Files.write(target.resolve("private.pem"),pem("PRIVATE KEY",key.getEncoded()).getBytes("US-ASCII"));
    Files.write(target.resolve("certificate.pem"),pem("CERTIFICATE",cert.getEncoded()).getBytes("US-ASCII"));
  }
  private static String pem(String kind,byte[] data){return "-----BEGIN "+kind+"-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(data)+"\n-----END "+kind+"-----\n";}
}
