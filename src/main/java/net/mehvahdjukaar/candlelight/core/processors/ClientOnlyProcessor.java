package net.mehvahdjukaar.candlelight.core.processors;

import net.mehvahdjukaar.candlelight.core.CandleLightExtension;
import org.gradle.api.Project;
import org.objectweb.asm.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Rewrites {@code @ClientOnly} into the loader-specific client-side-only annotation
 * ({@code @Environment(EnvType.CLIENT)} on Fabric, {@code @OnlyIn(Dist.CLIENT)} on
 * Forge).
 */
public class ClientOnlyProcessor implements ClassProcessor {

    private static final String CLIENT_ONLY = "Lnet/mehvahdjukaar/candlelight/api/ClientOnly;";

    private enum LoaderType {
        FABRIC("net.fabricmc.api.Environment", "Lnet/fabricmc/api/EnvType;", "CLIENT"),
        FORGE("net.minecraftforge.api.distmarker.OnlyIn", "Lnet/minecraftforge/api/distmarker/Dist;", "CLIENT");

        final String annotationDesc;
        final String enumValueDesc;
        final String enumConstantName;

        LoaderType(String annotationClass, String enumValueDesc, String enumConstantName) {
            this.annotationDesc = "L" + annotationClass.replace('.', '/') + ";";
            this.enumValueDesc = enumValueDesc;
            this.enumConstantName = enumConstantName;
        }

        static LoaderType infer(String projectName) {
            String n = projectName.toLowerCase();
            if (n.contains("fabric")) return FABRIC;
            if (n.contains("neoforge")) return null;
            if (n.contains("forge")) return FORGE;
            return null;
        }
    }

    @Override
    public List<String> usedAnnotations() {
        return List.of(CLIENT_ONLY);
    }

    @Override
    public boolean transform(ClassWriter writer, ClassReader reader, Project project, CandleLightExtension ext) {
        if (!ext.getClientOnly().get()) return false;

        LoaderType loader = LoaderType.infer(project.getName());
        if (loader == null) return false;

        AtomicBoolean modified = new AtomicBoolean(false);

        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (CLIENT_ONLY.equals(desc)) {
                    return rewrite(super.visitAnnotation(loader.annotationDesc, visible), modified, loader);
                }
                return super.visitAnnotation(desc, visible);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                FieldVisitor fv = super.visitField(access, name, desc, signature, value);
                return new FieldVisitor(Opcodes.ASM9, fv) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        if (CLIENT_ONLY.equals(desc)) {
                            return rewrite(super.visitAnnotation(loader.annotationDesc, visible), modified, loader);
                        }
                        return super.visitAnnotation(desc, visible);
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        if (CLIENT_ONLY.equals(desc)) {
                            return rewrite(super.visitAnnotation(loader.annotationDesc, visible), modified, loader);
                        }
                        return super.visitAnnotation(desc, visible);
                    }
                };
            }
        };

        reader.accept(visitor, 0);
        return modified.get();
    }

    private static AnnotationVisitor rewrite(AnnotationVisitor newAv, AtomicBoolean modified, LoaderType loader) {
        modified.set(true);
        return new AnnotationVisitor(Opcodes.ASM9, newAv) {
            @Override
            public void visitEnd() {
                newAv.visitEnum("value", loader.enumValueDesc, loader.enumConstantName);
                super.visitEnd();
            }

            // The original @ClientOnly annotation has no attributes of its own to preserve.
            @Override public void visit(String name, Object value) {}
            @Override public void visitEnum(String name, String desc, String value) {}
            @Override public AnnotationVisitor visitAnnotation(String name, String desc) { return null; }
            @Override public AnnotationVisitor visitArray(String name) { return null; }
        };
    }
}
