import java.nio.file.*;
import java.io.InputStream;
import java.security.*;
import java.util.Base64;
public final class ExportSigning {
  public static void main(String[] args) throws Exception {
    if (args.length != 4) throw new IllegalArgumentException("Usage: ExportSigning <keystore> <alias> <target> <certificate SHA256>");
    String storePassword=System.getenv("ACTION_RELAY_STORE_PASSWORD");
    if (storePassword == null || storePassword.isEmpty()) throw new IllegalStateException("ACTION_RELAY_STORE_PASSWORD is required");
    KeyStore ks=KeyStore.getInstance("PKCS12");
    try(InputStream in=Files.newInputStream(Paths.get(args[0]))){ks.load(in,storePassword.toCharArray());}
    String keyPassword=System.getenv("ACTION_RELAY_KEY_PASSWORD");
    if (keyPassword == null || keyPassword.isEmpty()) throw new IllegalStateException("ACTION_RELAY_KEY_PASSWORD is required");
    Key key=ks.getKey(args[1],keyPassword.toCharArray());
    java.security.cert.Certificate cert=ks.getCertificate(args[1]);
    if (key == null || cert == null) throw new KeyStoreException("Signing alias not found: " + args[1]);
    StringBuilder fingerprint=new StringBuilder();
    for(byte b:MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()))fingerprint.append(String.format("%02X",b&255));
    if(!fingerprint.toString().equals(args[3].replace(":","").toUpperCase(java.util.Locale.ROOT)))throw new SecurityException("Signing certificate differs from the fixed release certificate");
    Path target=Paths.get(args[2]);Files.createDirectories(target);
    Files.write(target.resolve("private.pem"),pem("PRIVATE KEY",key.getEncoded()).getBytes("US-ASCII"));
    Files.write(target.resolve("certificate.pem"),pem("CERTIFICATE",cert.getEncoded()).getBytes("US-ASCII"));
  }
  private static String pem(String kind,byte[] data){return "-----BEGIN "+kind+"-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(data)+"\n-----END "+kind+"-----\n";}
}
