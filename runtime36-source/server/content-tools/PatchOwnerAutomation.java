import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Adds narrow integration hooks to the exact classes selected by the current private runtime. */
public final class PatchOwnerAutomation implements Opcodes {
    private static final String ROOT = "io/ruin/";
    private static final String SOURCE = ROOT + "model/activities/automation/AutomationActionSource";
    private static final String BOT = ROOT + "model/activities/automation/OwnerAutomation";
    private static final String ENTITY = ROOT + "model/entity/Entity";
    private static final String PLAYER = "L" + ROOT + "model/entity/player/Player;";
    private static final String EVENT = "L" + ROOT + "process/event/Event;";
    private static final String INCOMING = ROOT + "network/incoming/Incoming";
    private static final String BUFFER = "L" + ROOT + "api/buffer/InBuffer;";

    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args[0]);
        String networking = ROOT + "network/incoming/";
        edit(output, networking + "handlers/CommandHandler", type -> {
            MethodNode method = method(type, "handle", "(" + PLAYER + "Ljava/lang/String;)V");
            guard(method, "command", "(" + PLAYER + "Ljava/lang/String;)Z", IFNE, 0, 1);
        });
        edit(output, networking + "IncomingDecoder", type -> {
            MethodNode method = method(type, "handle", "(" + PLAYER + BUFFER + "I)V");
            int matches = 0;
            for (AbstractInsnNode node : method.instructions.toArray()) {
                if (!(node instanceof MethodInsnNode)) continue;
                MethodInsnNode invoke = (MethodInsnNode) node;
                if (!invoke.owner.equals(INCOMING) || !invoke.name.equals("handle")) continue;
                InsnList hook = new InsnList();
                hook.add(new VarInsnNode(ALOAD, 1));
                hook.add(new VarInsnNode(ALOAD, 4));
                hook.add(new VarInsnNode(ALOAD, 2));
                hook.add(new MethodInsnNode(INVOKESTATIC, BOT, "manualInput",
                        "(" + PLAYER + "L" + INCOMING + ";" + BUFFER + ")V", false));
                method.instructions.insertBefore(node, hook);
                matches++;
            }
            if (matches != 1) throw new IllegalStateException("Expected one native dispatch");
        });
        edit(output, ENTITY, PatchOwnerAutomation::actionLease);
        edit(output, ROOT + "model/entity/player/PlayerAttributes", PatchOwnerAutomation::preferences);
        edit(output, ROOT + "model/activities/dungeoneering/IDungeon", PatchOwnerAutomation::legacy);
        edit(output, ROOT + "model/activities/raids/auto/RaidAuto", PatchOwnerAutomation::legacy);
    }

    private static void legacy(ClassNode type) {
        guard(method(type, "start", "(" + PLAYER + ")V"), "allowLegacyStart", "(" + PLAYER + ")Z", IFEQ, 0);
    }

    private static void guard(MethodNode method, String call, String descriptor, int ifReturn, int... locals) {
        InsnList code = new InsnList();
        LabelNode proceed = new LabelNode();
        for (int local : locals) code.add(new VarInsnNode(ALOAD, local));
        code.add(new MethodInsnNode(INVOKESTATIC, BOT, call, descriptor, false));
        code.add(new JumpInsnNode(ifReturn == IFNE ? IFEQ : IFNE, proceed));
        code.add(new InsnNode(RETURN));
        code.add(proceed);
        method.instructions.insert(code);
    }

    private static void preferences(ClassNode type) {
        String preferences = ROOT + "model/activities/automation/AutomationPreferences";
        String descriptor = "L" + preferences + ";";
        String name = "ownerAutomationPreferences";
        if (type.interfaces.contains(preferences + "$Source")) throw new IllegalStateException("Preferences already patched");
        type.interfaces.add(preferences + "$Source");
        FieldNode field = new FieldNode(ACC_PRIVATE, name, descriptor, null, null);
        field.visibleAnnotations = java.util.Collections.singletonList(new AnnotationNode("Lcom/google/gson/annotations/Expose;"));
        type.fields.add(field);
        MethodNode get = new MethodNode(ACC_PUBLIC | ACC_FINAL, "automationPreferences", "()" + descriptor, null, null);
        InsnList code = get.instructions;
        LabelNode exists = new LabelNode();
        code.add(new VarInsnNode(ALOAD, 0));
        code.add(new FieldInsnNode(GETFIELD, type.name, name, descriptor));
        code.add(new InsnNode(DUP));
        code.add(new JumpInsnNode(IFNONNULL, exists));
        code.add(new InsnNode(POP));
        code.add(new VarInsnNode(ALOAD, 0));
        code.add(new TypeInsnNode(NEW, preferences));
        code.add(new InsnNode(DUP));
        code.add(new MethodInsnNode(INVOKESPECIAL, preferences, "<init>", "()V", false));
        code.add(new FieldInsnNode(PUTFIELD, type.name, name, descriptor));
        code.add(new VarInsnNode(ALOAD, 0));
        code.add(new FieldInsnNode(GETFIELD, type.name, name, descriptor));
        code.add(exists);
        code.add(new InsnNode(ARETURN));
        type.methods.add(get);
    }

    private static void actionLease(ClassNode type) {
        if (type.interfaces.contains(SOURCE)) throw new IllegalStateException("Overlay already patched");
        for (String field : new String[]{"activeEvent", "nextEvent"})
            if (type.fields.stream().noneMatch(f -> f.name.equals(field) && f.desc.equals(EVENT)))
                throw new IllegalStateException("Missing Entity event slot: " + field);
        type.interfaces.add(SOURCE);
        MethodNode get = new MethodNode(ACC_PUBLIC | ACC_FINAL, "ownerAutomationAction", "()" + EVENT, null, null);
        LabelNode useActive = new LabelNode();
        InsnList code = get.instructions;
        loadEvent(code, "nextEvent");
        code.add(new InsnNode(DUP));
        code.add(new JumpInsnNode(IFNULL, useActive));
        code.add(new InsnNode(ARETURN));
        code.add(useActive);
        code.add(new InsnNode(POP));
        loadEvent(code, "activeEvent");
        code.add(new InsnNode(ARETURN));
        type.methods.add(get);
        MethodNode cancel = new MethodNode(ACC_PUBLIC | ACC_FINAL, "cancelOwnerAutomationAction", "(" + EVENT + ")Z", null, null);
        code = cancel.instructions;
        LabelNode fail = new LabelNode(), tryActive = new LabelNode();
        code.add(new VarInsnNode(ALOAD, 1));
        code.add(new JumpInsnNode(IFNULL, fail));
        code.add(new VarInsnNode(ALOAD, 1));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, ROOT + "process/event/Event", "persistent", "()Z", false));
        code.add(new JumpInsnNode(IFNE, fail));
        clearIfSame(code, "nextEvent", tryActive);
        code.add(tryActive);
        clearIfSame(code, "activeEvent", fail);
        code.add(fail);
        code.add(new InsnNode(ICONST_0));
        code.add(new InsnNode(IRETURN));
        type.methods.add(cancel);
    }

    private static void clearIfSame(InsnList code, String field, LabelNode mismatch) {
        loadEvent(code, field);
        code.add(new VarInsnNode(ALOAD, 1));
        code.add(new JumpInsnNode(IF_ACMPNE, mismatch));
        code.add(new VarInsnNode(ALOAD, 0));
        code.add(new InsnNode(ACONST_NULL));
        code.add(new FieldInsnNode(PUTFIELD, ENTITY, field, EVENT));
        code.add(new InsnNode(ICONST_1));
        code.add(new InsnNode(IRETURN));
    }

    private static void loadEvent(InsnList code, String field) {
        code.add(new VarInsnNode(ALOAD, 0));
        code.add(new FieldInsnNode(GETFIELD, ENTITY, field, EVENT));
    }

    private static MethodNode method(ClassNode type, String name, String descriptor) {
        return type.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor))
                .findFirst().orElseThrow(() -> new IllegalStateException(type.name + "." + name + descriptor));
    }

    private static void edit(Path output, String name, java.util.function.Consumer<ClassNode> install) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = PatchOwnerAutomation.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (in == null) throw new IllegalStateException("Missing selected class: " + name);
            new ClassReader(in).accept(node, 0);
        }
        install.accept(node);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        Path file = output.resolve(name + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, writer.toByteArray());
    }
}
