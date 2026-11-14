package io.ruin.utility;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Properties;

/** Password-protected local accounts, with optional first-login registration. */
public final class LocalAccounts {
    private final Properties properties = new Properties();
    private final Path file;
    private final boolean registration;
    public LocalAccounts(Path file) throws Exception { this(file,Boolean.getBoolean("kronos.registration")); }
    public LocalAccounts(Path file,boolean registration) throws Exception {
        this.file=file.toAbsolutePath();this.registration=registration;
        try (InputStream in = Files.newInputStream(file)) { properties.load(in); }
        if(properties.isEmpty())throw new IllegalArgumentException("Account registry is empty");
    }
    public synchronized Account authenticate(String username,String password) throws Exception {
        if(username==null||password==null)return null;
        username=username.trim().toLowerCase(Locale.ROOT);password=password.trim();
        if(!username.matches("[a-z0-9_ ]{3,12}")||password.isEmpty()||password.length()>64)return null;
        String prefix=username+".";
        String encoded=properties.getProperty(prefix+"hash");
        if(encoded==null){
            if(!registration)return null;
            return register(prefix,password);
        }
        byte[] salt=Base64.getDecoder().decode(properties.getProperty(prefix+"salt"));
        int iterations=Integer.parseInt(properties.getProperty(prefix+"iterations"));
        if(iterations<100000||iterations>1000000)throw new IllegalArgumentException("Invalid account KDF cost");
        if(!MessageDigest.isEqual(hash(password,salt,iterations),Base64.getDecoder().decode(encoded)))return null;
        int id=Integer.parseInt(properties.getProperty(prefix+"id"));
        if(id<1000)throw new IllegalArgumentException("Remote account ID must be stable and >= 1000");
        return new Account(id,Boolean.parseBoolean(properties.getProperty(prefix+"admin","false")),
                Boolean.parseBoolean(properties.getProperty(prefix+"owner","false")));
    }
    private static byte[] hash(String password,byte[] salt,int iterations) throws Exception {
        PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,iterations,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
        finally{spec.clearPassword();}
    }
    private Account register(String prefix,String password) throws Exception {
        int id=1000;
        for(String key:properties.stringPropertyNames())
            if(key.endsWith(".id"))id=Math.max(id,Integer.parseInt(properties.getProperty(key)));
        if(id==Integer.MAX_VALUE)throw new IllegalStateException("Account IDs exhausted");
        id++;
        Properties updated=new Properties();updated.putAll(properties);
        byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);
        updated.setProperty(prefix+"id",Integer.toString(id));
        updated.setProperty(prefix+"admin","false");
        updated.setProperty(prefix+"owner","false");
        updated.setProperty(prefix+"iterations","210000");
        updated.setProperty(prefix+"salt",Base64.getEncoder().encodeToString(salt));
        updated.setProperty(prefix+"hash",Base64.getEncoder().encodeToString(hash(password,salt,210000)));
        Path temporary=Files.createTempFile(file.getParent(),"accounts-",".tmp");
        try{
            try(FileOutputStream out=new FileOutputStream(temporary.toFile())){
                updated.store(out,"Kronos password-protected local accounts");
                out.getFD().sync();
            }
            try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
            properties.clear();properties.putAll(updated);
        }finally{Files.deleteIfExists(temporary);}
        return new Account(id,false,false);
    }
    public static final class Account {
        public final int id;public final boolean admin,owner;
        Account(int id,boolean admin,boolean owner){this.id=id;this.admin=admin;this.owner=owner;}
    }
}
