import java.nio.file.Files;
import java.nio.file.Paths;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Advertises raid-click support when the owner types the normal raid setup command. */
public final class PatchRaidClickCommand implements Opcodes {
    public static void main(String[] args) throws Exception {
        ClassReader reader = new ClassReader(Files.readAllBytes(Paths.get(args[0])));
        ClassNode type = new ClassNode();
        reader.accept(type, 0);
        if (!type.name.equals("net/runelite/standalone/WorldMapSection0"))
            throw new IllegalStateException("Expected the native client command entry");
        MethodNode command = type.methods.stream()
                .filter(method -> method.name.equals("method3906") && method.desc.equals("(Ljava/lang/String;)V")
                        && (method.access & ACC_STATIC) != 0)
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing native client command method"));
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(ALOAD, 0));
        hook.add(new MethodInsnNode(INVOKESTATIC, "net/runelite/standalone/RaidClickIndicator", "command",
                "(Ljava/lang/String;)Ljava/lang/String;", false));
        hook.add(new VarInsnNode(ASTORE, 0));
        command.instructions.insert(hook);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        Files.write(Paths.get(args[1]), writer.toByteArray());
    }
}
