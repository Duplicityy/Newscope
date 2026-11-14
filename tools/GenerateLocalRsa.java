import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.math.BigInteger;

/** Configure a separate clone; never prints or commits private key material. */
public final class GenerateLocalRsa {
    public static void main(String[] args) throws Exception {
        Path privateFile=Paths.get(args[0]), publicFile=Paths.get(args[1]);
        if(Files.exists(privateFile)||Files.exists(publicFile))throw new IllegalStateException("Refusing to replace an existing key");
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024,new SecureRandom()); // Existing revision184 RSA packet width.
        KeyPair pair=generator.generateKeyPair();
        RSAPublicKey pub=(RSAPublicKey)pair.getPublic();
        RSAPrivateKey priv=(RSAPrivateKey)pair.getPrivate();
        BigInteger sample=new BigInteger(800,new SecureRandom());
        if(!sample.modPow(pub.getPublicExponent(),pub.getModulus()).modPow(priv.getPrivateExponent(),pub.getModulus()).equals(sample))throw new IllegalStateException("Key self-check failed");
        if(!pub.getPublicExponent().equals(BigInteger.valueOf(65537)))throw new IllegalStateException("Unexpected public exponent");
        Files.write(privateFile,priv.getPrivateExponent().toString().getBytes(StandardCharsets.US_ASCII),StandardOpenOption.CREATE_NEW);
        Files.write(publicFile,pub.getModulus().toString().getBytes(StandardCharsets.US_ASCII),StandardOpenOption.CREATE_NEW);
        System.out.println("New clone-local RSA pair generated and self-checked. Private value was not printed.");
    }
}
