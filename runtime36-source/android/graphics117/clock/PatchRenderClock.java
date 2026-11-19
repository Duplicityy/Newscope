import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Changes one invocation, preserving control flow, locals and stack-map types. */
public final class PatchRenderClock implements Opcodes {
    public static void main(String[] args) throws Exception {
        ClassNode type = new ClassNode(ASM5);
        new ClassReader(Files.readAllBytes(Paths.get(args[0]))).accept(type, ClassReader.EXPAND_FRAMES);
        ClassWriter original = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        type.accept(original);
        MethodNode changedMethod = null;
        MethodInsnNode originalCall = null, changedCall = null;
        int matches = 0;
        for (MethodNode method : (List<MethodNode>)type.methods) {
            if (!method.name.equals("run") || !method.desc.equals("()V")) continue;
            for (AbstractInsnNode node = method.instructions.getFirst(); node != null; node = node.getNext()) {
                if (!(node instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode)node;
                if (!call.owner.equals("net/runelite/standalone/Clock") || !call.name.equals("vmethod3511") || !call.desc.equals("(II)I")) continue;
                changedCall = new MethodInsnNode(INVOKESTATIC,
                    "net/runelite/standalone/AndroidRenderClock", "waitCycles",
                    "(Lnet/runelite/standalone/Clock;II)I", false);
                changedMethod = method;
                originalCall = call;
                method.instructions.set(call, changedCall);
                matches++;
            }
        }
        if (matches != 1) throw new IllegalStateException("Expected exactly one native clock wait, found " + matches);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        Files.write(Paths.get(args[1]), writer.toByteArray());
        changedMethod.instructions.set(changedCall, originalCall);
        ClassWriter restored = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        type.accept(restored);
        if (!java.util.Arrays.equals(original.toByteArray(), restored.toByteArray()))
            throw new AssertionError("Unexpected changes outside the one clock invocation");
        System.out.println("PASS one render clock invocation replaced; canonical bytecode reverts exactly");
    }
}
