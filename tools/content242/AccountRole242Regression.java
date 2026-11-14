package io.ruin.utility;

import io.ruin.model.entity.player.PlayerGroup;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Synthetic registry only. Does not read a real account or print credentials. */
public final class AccountRole242Regression {
    private static int assertions;
    private static void check(boolean ok,String label){assertions++;if(!ok)throw new AssertionError(label);}
    private static Properties read(Path file)throws Exception{Properties p=new Properties();try(InputStream in=Files.newInputStream(file)){p.load(in);}return p;}
    private static void write(Path file,Properties p)throws Exception{try(OutputStream out=Files.newOutputStream(file)){p.store(out,"synthetic regression");}}
    private static void cases(Path file)throws Exception{
        Properties initial=new Properties();initial.setProperty("fixture","true");write(file,initial);
        String password=UUID.randomUUID().toString();
        LocalAccounts registration=new LocalAccounts(file,true);
        LocalAccounts.Account created=registration.authenticate("fixtureuser",password);
        check(created!=null&&created.id>=1000&&!created.admin&&!created.owner,"new registration has no privileges");
        check(OfflineMode.accountGroup(created)==PlayerGroup.REGISTERED.id,"registered role mapping");
        Properties properties=read(file);check("false".equals(properties.getProperty("fixtureuser.owner")),"owner false persisted for new accounts");
        properties.remove("fixtureuser.owner");properties.setProperty("fixtureuser.admin","true");write(file,properties);
        LocalAccounts accounts=new LocalAccounts(file,false);LocalAccounts.Account admin=accounts.authenticate("fixtureuser",password);
        check(admin.admin&&!admin.owner&&admin.id==created.id,"old registry admin and stable ID retained");
        check(OfflineMode.accountGroup(admin)==PlayerGroup.ADMINISTRATOR.id,"absent owner preserves administrator");
        properties.setProperty("fixtureuser.owner","true");write(file,properties);accounts=new LocalAccounts(file,false);
        LocalAccounts.Account owner=accounts.authenticate("FIXTUREUSER",password);
        check(owner.owner&&owner.admin&&owner.id==created.id,"explicit owner authenticated with normalized name");
        check(OfflineMode.accountGroup(owner)==PlayerGroup.OWNER.id,"owner takes precedence over admin");
        check(accounts.authenticate("fixtureuser","wrong-password") == null,"owner cannot bypass password verification");
        check(accounts.authenticate("unknownuser",password)==null,"disabled registration remains closed");
        properties.setProperty("fixtureuser.admin","false");write(file,properties);accounts=new LocalAccounts(file,false);
        check(OfflineMode.accountGroup(accounts.authenticate("fixtureuser",password))==PlayerGroup.OWNER.id,"owner independent of admin flag");
        properties.setProperty("newuser.owner","true");properties.setProperty("newuser.admin","true");write(file,properties);
        LocalAccounts.Account unprivileged=new LocalAccounts(file,true).authenticate("newuser",password);
        check(!unprivileged.owner&&!unprivileged.admin,"registration clears preexisting unregistered role properties");
        LocalAccounts.Account reload=new LocalAccounts(file,false).authenticate("newuser",password);
        check(!reload.owner&&!reload.admin&&reload.id==unprivileged.id,"registration role and ID survive reload");
        properties=read(file);properties.setProperty("fixtureuser.owner","not-true");write(file,properties);
        check(!new LocalAccounts(file,false).authenticate("fixtureuser",password).owner,"malformed owner value does not elevate");
        properties=read(file);properties.setProperty("fixtureuser.id","999");write(file,properties);
        boolean rejected=false;try{new LocalAccounts(file,false).authenticate("fixtureuser",password);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"unstable account IDs still rejected");
        java.lang.reflect.Field registry=OfflineMode.class.getDeclaredField("accounts");registry.setAccessible(true);
        Object prior=registry.get(null);boolean enabled=OfflineMode.enabled;String publicMode=System.getProperty("kronos.public");
        try{
            OfflineMode.enabled=true;registry.set(null,null);System.clearProperty("kronos.public");
            check(OfflineMode.allowDevelopmentPrivileges(),"local unauthenticated development convenience retained");
            registry.set(null,registration);
            check(OfflineMode.hasStableAccountRegistry(),"registry context recognized");
            check(!OfflineMode.allowDevelopmentPrivileges(),"registered users cannot gain owner through development stage");
            registry.set(null,null);System.setProperty("kronos.public","true");
            check(!OfflineMode.allowDevelopmentPrivileges(),"public world never grants automatic developer owner");
            System.clearProperty("kronos.public");OfflineMode.enabled=false;
            check(!OfflineMode.allowDevelopmentPrivileges(),"central authentication never gains local convenience privileges");
        }finally{
            registry.set(null,prior);OfflineMode.enabled=enabled;
            if(publicMode==null)System.clearProperty("kronos.public");else System.setProperty("kronos.public",publicMode);
        }
    }
    public static void main(String[] args)throws Exception{
        Path directory=Files.createTempDirectory("content242-role-fixture-");Path file=directory.resolve("accounts.properties");
        try{cases(file);System.out.println("PASS explicit account roles assertions="+assertions+"; synthetic registry only; no account credentials printed.");}
        finally{Files.deleteIfExists(file);Files.deleteIfExists(directory);}
    }
}
