import io.ruin.utility.LocalAccounts;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class TestRegistration {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory(Paths.get(args[1]),"registration-"),file=dir.resolve("accounts.properties");
        Files.copy(Paths.get(args[0]),file);
        LocalAccounts accounts=new LocalAccounts(file,true);
        LocalAccounts.Account first=accounts.authenticate("fresh_test","first pass");
        check(first!=null&&!first.admin&&first.id>1000,"first login creates regular account");
        check(accounts.authenticate("FRESH_TEST","first pass").id==first.id,"case insensitive stable identity");
        check(accounts.authenticate("fresh_test","changed pass")==null,"existing password cannot be replaced");
        check(accounts.authenticate("owner","wrong password")==null,"owner stays protected");
        check(accounts.authenticate("ab","pass")==null&&accounts.authenticate("../owner","pass")==null&&accounts.authenticate("empty_pass"," ")==null,"invalid input rejected");
        ExecutorService pool=Executors.newFixedThreadPool(4);List<Future<Integer>> futures=new ArrayList<>();
        for(int i=0;i<8;i++){final int n=i;futures.add(pool.submit(()->accounts.authenticate("race_"+n,"one-time").id));}
        Set<Integer> ids=new HashSet<>();for(Future<Integer> f:futures)ids.add(f.get());pool.shutdown();
        check(ids.size()==8&&!ids.contains(first.id),"concurrent registration gets unique IDs");
        LocalAccounts reopened=new LocalAccounts(file,true);
        check(reopened.authenticate("fresh_test","first pass").id==first.id,"survives server restart");
        String saved=new String(Files.readAllBytes(file),"ISO-8859-1");
        check(!saved.contains("first pass")&&!saved.contains("one-time"),"passwords stored only as hashes");
        System.out.println("PASS: registration, password protection, normal privileges, atomic persistence, case normalization, concurrent unique IDs.");
    }
}
