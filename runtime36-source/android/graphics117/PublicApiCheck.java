import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
public class PublicApiCheck {
 static Set<String> signatures(Path jar,String entry)throws Exception {
  try(ZipFile zip=new ZipFile(jar.toFile())){
   ClassNode c=new ClassNode(Opcodes.ASM5);
   new ClassReader(zip.getInputStream(zip.getEntry(entry))).accept(c,ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
   Set<String> names=new HashSet<>();
   for(MethodNode m:(List<MethodNode>)c.methods)if((m.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_PROTECTED))!=0)names.add(m.name+m.desc);
   return names;
  }
 }
 public static void main(String[] args)throws Exception {
  String[][] files={{"owner-plugins.jar","rs117/hd/HdPlugin.class"},{"owner-graphics.jar","rs117/hd/android/AndroidGl.class"},{"bridge.jar","net/runelite/standalone/GameShell.class"}};
  for(String[] file:files){
   Set<String> before=signatures(Paths.get(args[0],file[0]),file[1]),after=signatures(Paths.get(args[1],file[0]),file[1]);
   before.removeAll(after);if(!before.isEmpty())throw new AssertionError(file[1]+before);
  }
  System.out.println("PASS public/protected APIs retained across plugin, GL adapter and actual GameShell overlay");
 }
}
