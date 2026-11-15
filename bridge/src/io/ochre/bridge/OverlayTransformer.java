package io.ochre.bridge;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.Set;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/// Narrow, optional 26.2 render/input hooks. Original Minecraft archives are never rewritten.
public final class OverlayTransformer implements ClassFileTransformer {
    public static volatile boolean gui, mouse;
    public static final Set<String> observations = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final String HOOK = "io/ochre/bridge/bootstrap/v1/OverlayHooks";
    private final Instrumentation instrumentation;
    /// Instrumentation is absent only in isolated transformer unit tests.
    public OverlayTransformer(Instrumentation instrumentation) { this.instrumentation = instrumentation; }
    /// Named module loaders receive one narrow read edge to the already-visible bridge module.
    @Override public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefining, ProtectionDomain domain, byte[] bytes) {
        if (!target(name)) return null;
        try {
            Module bridge = Class.forName(BootstrapHooks.NAME, false, null).getModule();
            if (module != null && module.isNamed() && !module.canRead(bridge)) {
                if (instrumentation == null || !instrumentation.isModifiableModule(module)) return null;
                instrumentation.redefineModule(module, Set.of(bridge), Map.of(), Map.of(), Set.of(), Map.of());
            }
            return transform(loader, name, redefining, domain, bytes);
        } catch (Throwable error) { System.err.println("[OchreBridge] Optional overlay module hook unavailable: " + name); return null; }
    }
    private static boolean target(String name) { return Set.of("net/minecraft/client/gui/Gui","net/minecraft/client/MouseHandler","net/minecraft/client/multiplayer/MultiPlayerGameMode","net/minecraft/client/multiplayer/ClientPacketListener","net/minecraft/world/entity/LivingEntity").contains(name); }
    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining, ProtectionDomain domain, byte[] bytes) {
        if (!target(name)) return null;
        try {
            // Do not inject a symbolic reference that this loader cannot resolve to this exact helper.
            if (loader == null || Class.forName(BootstrapHooks.NAME, false, loader) != Class.forName(BootstrapHooks.NAME, false, null)) return null;
            ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); boolean changed = false, guiHook = false, mouseHook = false;
            if ((name.endsWith("/Gui")||name.endsWith("/MouseHandler")) && node.fields.stream().noneMatch(f -> f.name.equals("minecraft") && f.desc.equals("Lnet/minecraft/client/Minecraft;"))) return null;
            for (MethodNode method : node.methods) {
                if (name.endsWith("/Gui") && method.name.equals("extractRenderState") && method.desc.equals("(Lnet/minecraft/client/DeltaTracker;ZZ)V") && node.fields.stream().anyMatch(f -> f.name.equals("guiRenderState") && f.desc.equals("Lnet/minecraft/client/renderer/state/gui/GuiRenderState;"))) {
                    for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN) {
                        InsnList hook = new InsnList(); hook.add(new VarInsnNode(Opcodes.ALOAD, 0)); hook.add(new FieldInsnNode(Opcodes.GETFIELD, name, "minecraft", "Lnet/minecraft/client/Minecraft;")); hook.add(new VarInsnNode(Opcodes.ALOAD, 0)); hook.add(new FieldInsnNode(Opcodes.GETFIELD, name, "guiRenderState", "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;")); hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "render", "(Ljava/lang/Object;Ljava/lang/Object;)V", false)); method.instructions.insertBefore(instruction, hook);
                    }
                    changed = true; guiHook = true;
                }
                boolean button = method.name.equals("onButton") && method.desc.equals("(JLnet/minecraft/client/input/MouseButtonInfo;I)V");
                boolean move = method.name.equals("onMove") && method.desc.equals("(JDD)V");
                if (name.endsWith("/MouseHandler") && (button || move)) {
                    InsnList hook = new InsnList(); hook.add(new VarInsnNode(Opcodes.ALOAD, 0)); hook.add(new FieldInsnNode(Opcodes.GETFIELD, name, "minecraft", "Lnet/minecraft/client/Minecraft;"));
                    if (button) { hook.add(new VarInsnNode(Opcodes.ALOAD, 3)); hook.add(new VarInsnNode(Opcodes.ILOAD, 4)); }
                    else { hook.add(new VarInsnNode(Opcodes.DLOAD, 3)); hook.add(new VarInsnNode(Opcodes.DLOAD, 5)); }
                    hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, button ? "button" : "move", button ? "(Ljava/lang/Object;Ljava/lang/Object;I)Z" : "(Ljava/lang/Object;DD)Z", false)); LabelNode proceed = new LabelNode(); hook.add(new JumpInsnNode(Opcodes.IFEQ, proceed)); hook.add(new InsnNode(Opcodes.RETURN)); hook.add(proceed); hook.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null)); method.instructions.insert(hook); changed = true; if (button) mouseHook = true;
                }
            }
            changed |= observe(node, name);
            if (!changed) return null;
            ClassWriter output = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(output); byte[] transformed = output.toByteArray(); if (guiHook) gui = true; if (mouseHook) mouse = true; return transformed;
        } catch (Throwable error) { System.err.println("[OchreBridge] Optional overlay hook unavailable: " + name + ": " + error.getClass().getSimpleName()); return null; }
    }
    /// Exact descriptor matches keep unsupported versions unchanged, and only add passive calls.
    private static boolean observe(ClassNode node,String name) {
        boolean changed=false;
        for(MethodNode method:node.methods) {
            if(name.endsWith("/MouseHandler")&&method.name.equals("onScroll")&&method.desc.equals("(JDD)V")) { InsnList code=new InsnList();code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new FieldInsnNode(Opcodes.GETFIELD,name,"minecraft","Lnet/minecraft/client/Minecraft;"));code.add(new VarInsnNode(Opcodes.DLOAD,3));code.add(new VarInsnNode(Opcodes.DLOAD,5));code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"scroll","(Ljava/lang/Object;DD)V",false));method.instructions.insert(code);observations.add("mouse_scroll");changed=true; }
            String before=null,after=null; int argument=-1,extra=-1; boolean primitive=false;
            if(name.endsWith("/MultiPlayerGameMode")) {
                if(method.name.equals("attack")&&method.desc.equals("(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)V")){before="attack";argument=2;}
                if(method.name.equals("destroyBlock")&&method.desc.equals("(Lnet/minecraft/core/BlockPos;)Z")){before="break_before";after="break_after";argument=1;primitive=true;}
                if(method.name.equals("useItemOn")&&method.desc.equals("(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;")){before="place_before";after="place_after";argument=3;extra=2;}
                if(method.name.equals("useItem")&&method.desc.equals("(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;")){before="use_before";after="use_after";argument=2;}
            }
            if(name.endsWith("/ClientPacketListener")&&method.name.equals("handleDamageEvent")&&method.desc.equals("(Lnet/minecraft/network/protocol/game/ClientboundDamageEventPacket;)V")){after="damage";argument=1;}
            if(name.endsWith("/LivingEntity")&&method.name.equals("completeUsingItem")&&method.desc.equals("()V")){before="consume_before";after="consume_after";}
            if(before==null&&after==null)continue;
            if(before!=null)method.instructions.insert(callback(before,argument,extra,false,false));
            if(after!=null)for(AbstractInsnNode instruction:method.instructions.toArray())if(instruction.getOpcode()==Opcodes.RETURN||instruction.getOpcode()==Opcodes.ARETURN||instruction.getOpcode()==Opcodes.IRETURN)method.instructions.insertBefore(instruction,callback(after,argument,-1,instruction.getOpcode()!=Opcodes.RETURN,primitive));
            observations.add(before!=null?before:after);changed=true;
        }
        return changed;
    }
    private static InsnList callback(String kind,int argument,int extra,boolean result,boolean primitive) {
        InsnList code=new InsnList();if(result){code.add(new InsnNode(Opcodes.DUP));if(primitive)code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/Boolean","valueOf","(Z)Ljava/lang/Boolean;",false));code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new InsnNode(Opcodes.SWAP));}else{code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(argument<0?new InsnNode(Opcodes.ACONST_NULL):new VarInsnNode(Opcodes.ALOAD,argument));}code.add(extra<0?new InsnNode(Opcodes.ACONST_NULL):new VarInsnNode(Opcodes.ALOAD,extra));code.add(new LdcInsnNode(kind));code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"action","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V",false));return code;
    }

}
