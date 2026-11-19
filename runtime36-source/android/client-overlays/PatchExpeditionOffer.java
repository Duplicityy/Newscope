import java.nio.file.Files;
import java.nio.file.Paths;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Recognize the staged server's normal ledger menu before running its native script. */
public final class PatchExpeditionOffer implements Opcodes {
    private static final String HANDLER = "net/runelite/standalone/KeyHandler";
    private static final String EVENT = "Lnet/runelite/standalone/ScriptEvent;";
    private static final String LEDGER = "net/runelite/standalone/CustomDungeoneeringExpedition";

    public static void main(String[] args) throws Exception {
        ClassReader reader = new ClassReader(Files.readAllBytes(Paths.get(args[0])));
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        if (!HANDLER.equals(node.name)) throw new IllegalStateException("Unexpected client script host");
        MethodNode method = node.methods.stream()
            .filter(m -> m.name.equals("method506") && m.desc.equals("(" + EVENT + "IB)V"))
            .findFirst().orElseThrow(() -> new IllegalStateException("Missing client script hook"));
        boolean existingRunner = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) if (insn instanceof MethodInsnNode) {
            MethodInsnNode invoke = (MethodInsnNode)insn;
            if (invoke.owner.equals(LEDGER)) throw new IllegalStateException("Expedition hook already installed");
            if (invoke.owner.equals("net/runelite/standalone/Client") && invoke.name.equals("copy$runScript"))
                existingRunner = true;
        }
        if (!existingRunner) throw new IllegalStateException("Unrecognized client script host");
        InsnList offer = new InsnList();
        offer.add(new VarInsnNode(ALOAD, 0));
        offer.add(new MethodInsnNode(INVOKESTATIC, LEDGER, "offer", "(" + EVENT + ")V", false));
        method.instructions.insert(offer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Files.write(Paths.get(args[1]), writer.toByteArray());
    }
}
