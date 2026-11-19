import java.nio.file.Files;
import java.nio.file.Paths;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Runs panel requests on the native game thread, independently of rendering. */
public final class PatchOwnerAutomationTick implements Opcodes {
    public static void main(String[] args) throws Exception {
        ClassReader reader = new ClassReader(Files.readAllBytes(Paths.get(args[0])));
        ClassNode type = new ClassNode();
        reader.accept(type, 0);
        if (!type.name.equals("net/runelite/standalone/MobileFrame"))
            throw new IllegalStateException("Expected the Android logic hook class");
        MethodNode tick = type.methods.stream()
                .filter(m -> m.name.equals("afterLogic") && m.desc.equals("()V") && (m.access & ACC_STATIC) != 0)
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing Android logic hook"));
        String automation = "net/runelite/standalone/OwnerAutomationClient";
        for (AbstractInsnNode instruction : tick.instructions) {
            if (instruction instanceof MethodInsnNode && ((MethodInsnNode) instruction).owner.equals(automation))
                throw new IllegalStateException("Android logic hook already patched");
        }
        tick.instructions.insert(new MethodInsnNode(INVOKESTATIC, automation, "pump", "()V", false));
        ClassWriter writer = new ClassWriter(reader, 0);
        type.accept(writer);
        Files.write(Paths.get(args[1]), writer.toByteArray());
    }
}
