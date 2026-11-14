package net.runelite.standalone;

import java.io.File;
import java.nio.file.*;
import java.util.*;

/** Real client disk-cache I/O: bank limits, persistence, overwrite and legacy boundaries. */
public final class ArchiveDisk242Probe {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static void rejected(ArchiveDisk disk,int group,byte[] data,int length){
        try{disk.method5312(group,data,length);throw new AssertionError("Oversized group accepted: "+group);}
        catch(IllegalArgumentException expected){checks++;}
    }
    static BufferedFile file(Path path)throws Exception{
        return new BufferedFile(new AccessFile(path.toFile(),"rw",64L*1024*1024),8192,0);
    }
    public static void main(String[] args)throws Exception{
        Path output=Paths.get(args[0]);Files.createDirectories(output);
        Path folder=Files.createDirectory(output.resolve("disk-"+System.nanoTime()));
        byte[] bank=new byte[1213311];new Random(242).nextBytes(bank);
        BufferedFile data=file(folder.resolve("data")),index=file(folder.resolve("index"));
        try{
            ArchiveDisk disk=new ArchiveDisk(7,data,index,1000000);
            for(int group:new int[]{65408,65443,65534}){
                check(disk.method5312(group,bank,bank.length),"Native bank write "+group);
                check(Arrays.equals(bank,disk.method5306(group)),"Native bank read "+group);
            }
            rejected(disk,65407,bank,bank.length);rejected(disk,65535,bank,bank.length);
            rejected(disk,65443,bank,8*1024*1024+1);
            rejected(new ArchiveDisk(6,data,index,1000000),65443,bank,bank.length);
            rejected(new ArchiveDisk(255,data,index,500000),65443,bank,bank.length);
        }finally{index.method23();data.method23();}
        data=file(folder.resolve("data"));index=file(folder.resolve("index"));
        try{
            ArchiveDisk disk=new ArchiveDisk(7,data,index,1000000);
            for(int group:new int[]{65408,65443,65534})check(Arrays.equals(bank,disk.method5306(group)),"Reopened bank "+group);
            byte[] small=Arrays.copyOf(bank,65536);
            check(disk.method5312(65443,small,small.length),"Existing bank rewrite");
            check(Arrays.equals(small,disk.method5306(65443)),"Rewrite length/content");
            check(disk.method5312(100,small,small.length),"Legacy small model write");
            check(Arrays.equals(small,disk.method5306(100)),"Legacy model read");
        }finally{index.method23();data.method23();}
        String result="PASS "+checks+" native client ArchiveDisk checks: 1,213,311-byte banks, reopen, overwrite, legacy bounds\n";
        Files.write(output.resolve("result.txt"),result.getBytes("UTF-8"));System.out.print(result);
    }
}
