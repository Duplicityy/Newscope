import java.nio.file.Files;
import java.nio.file.Paths;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Suppresses only matching, server-authored replies to the current owner panel request. */
public final class PatchOwnerAutomationClient implements Opcodes {
    public static void main(String[] args) throws Exception {
        ClassNode type = new ClassNode();
        new ClassReader(Files.readAllBytes(Paths.get(args[0]))).accept(type, 0);
        String descriptor = "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)";
        MethodNode method = type.methods.stream()
                .filter(m -> m.name.equals("method514") && m.desc.equals(descriptor + "V"))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing verified chat entry"));
        InsnList hook = new InsnList();
        LabelNode normal = new LabelNode();
        hook.add(new VarInsnNode(ILOAD, 0));
        for (int i = 1; i <= 3; i++) hook.add(new VarInsnNode(ALOAD, i));
        hook.add(new MethodInsnNode(INVOKESTATIC, "net/runelite/standalone/OwnerAutomationClient",
                "receive", descriptor + "Z", false));
        hook.add(new JumpInsnNode(IFEQ, normal));
        hook.add(new InsnNode(RETURN));
        hook.add(normal);
        method.instructions.insert(hook);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        Files.write(Paths.get(args[1]), writer.toByteArray());
    }
}
