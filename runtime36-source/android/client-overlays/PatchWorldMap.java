import java.nio.file.Files;
import java.nio.file.Paths;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Applies the shared widget hooks to the pinned desktop and Android client class. */
public final class PatchWorldMap implements Opcodes {
    private static final String PACKAGE = "net/runelite/standalone/";
    private static final String MAP = PACKAGE + "WorldMap";
    private static final String CONFIRM = PACKAGE + "WorldMapConfirm";

    public static void main(String[] args) throws Exception {
        ClassNode type = new ClassNode();
        new ClassReader(Files.readAllBytes(Paths.get(args[0]))).accept(type, 0);
        int inputHooks = 0, drawHooks = 0;
        for (MethodNode method : type.methods) {
            for (AbstractInsnNode node : method.instructions.toArray()) {
                if (!(node instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) node;
                if (method.name.equals("method6054") && method.desc.equals("(IIZZ)V")
                        && call.owner.equals(MAP) && call.name.equals("method5914") && call.desc.equals("(IIZJ)V")) {
                    AbstractInsnNode time = previousOpcode(call);
                    AbstractInsnNode pressed = previousOpcode(time);
                    if (!(time instanceof VarInsnNode) || time.getOpcode() != LLOAD
                            || !(pressed instanceof VarInsnNode) || pressed.getOpcode() != ILOAD
                            || ((VarInsnNode) pressed).var != 4)
                        throw new IllegalStateException("Unexpected world map input stack");
                    method.instructions.insert(pressed, new MethodInsnNode(INVOKESTATIC, CONFIRM, "nativePress", "(Z)Z", false));

                    LabelNode nativeDrag = new LabelNode();
                    InsnList hook = arguments(4);
                    hook.add(new MethodInsnNode(INVOKESTATIC, CONFIRM, "input", "(L" + MAP + ";IIZZ)Z", false));
                    hook.add(new JumpInsnNode(IFEQ, nativeDrag));
                    hook.add(new VarInsnNode(ALOAD, 0));
                    hook.add(new MethodInsnNode(INVOKEVIRTUAL, MAP, "method5878", "()V", false));
                    hook.add(new InsnNode(RETURN));
                    hook.add(nativeDrag);
                    method.instructions.insert(call, hook);
                    inputHooks++;
                }
                if (method.name.equals("method5913") && method.desc.equals("(IIIII)V")
                        && call.owner.equals(PACKAGE + "Rasterizer2D") && call.name.equals("method6429") && call.desc.equals("([I)V")) {
                    InsnList hook = arguments(4);
                    hook.add(new MethodInsnNode(INVOKESTATIC, CONFIRM, "draw", "(L" + MAP + ";IIII)V", false));
                    method.instructions.insertBefore(previousOpcode(call), hook);
                    drawHooks++;
                }
            }
        }
        if (inputHooks != 1 || drawHooks != 1)
            throw new IllegalStateException("Expected one input and one draw hook: " + inputHooks + "/" + drawHooks);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        Files.write(Paths.get(args[1]), writer.toByteArray());
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode node) {
        do node = node.getPrevious(); while (node != null && node.getOpcode() < 0);
        return node;
    }

    private static InsnList arguments(int count) {
        InsnList args = new InsnList();
        args.add(new VarInsnNode(ALOAD, 0));
        for (int i = 1; i <= count; i++) args.add(new VarInsnNode(ILOAD, i));
        return args;
    }
}
