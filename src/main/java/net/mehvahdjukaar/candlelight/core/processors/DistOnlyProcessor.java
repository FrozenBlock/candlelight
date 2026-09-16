package net.mehvahdjukaar.candlelight.core.processors;

import net.mehvahdjukaar.candlelight.core.TransformContext;
import org.objectweb.asm.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Rewrites a candlelight dist annotation ({@code @ClientOnly} / {@code @ServerOnly}) into the
 * loader-specific side-only annotation ({@code @Environment(EnvType.X)} on Fabric,
 * {@code @OnlyIn(Dist.X)} on Forge).
 */
abstract class DistOnlyProcessor implements ClassProcessor {

    private final String candlelightAnnotation;
    private final String fabricName;
    private final String neoName;

    DistOnlyProcessor(String candlelightAnnotation, String fabricName, String neoName) {
        this.candlelightAnnotation = candlelightAnnotation;
        this.fabricName = fabricName;
        this.neoName = neoName;
    }

    private enum LoaderType {
        FABRIC("net.fabricmc.api.Environment", "Lnet/fabricmc/api/EnvType;"),
        FORGE("net.minecraftforge.api.distmarker.OnlyIn", "Lnet/minecraftforge/api/distmarker/Dist;");

        final String annotationDesc;
        final String enumValueDesc;

        LoaderType(String annotationClass, String enumValueDesc) {
            this.annotationDesc = "L" + annotationClass.replace('.', '/') + ";";
            this.enumValueDesc = enumValueDesc;
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
        return List.of(candlelightAnnotation);
    }

    @Override
    public boolean isActive(TransformContext ctx) {
        return isEnabled(ctx) && LoaderType.infer(ctx.getProjectName()) != null;
    }

    @Override
    public boolean transform(ClassWriter writer, ClassReader reader, TransformContext ctx) {
        LoaderType loader = LoaderType.infer(ctx.getProjectName());
        if (loader == null) return false;

        AtomicBoolean modified = new AtomicBoolean(false);

        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (candlelightAnnotation.equals(desc)) {
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
                        if (candlelightAnnotation.equals(desc)) {
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
                        if (candlelightAnnotation.equals(desc)) {
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

    protected abstract boolean isEnabled(TransformContext ctx);

    private AnnotationVisitor rewrite(AnnotationVisitor newAv, AtomicBoolean modified, LoaderType loader) {
        modified.set(true);
        return new AnnotationVisitor(Opcodes.ASM9, newAv) {
            @Override
            public void visitEnd() {
                newAv.visitEnum("value", loader.enumValueDesc, loader == LoaderType.FABRIC ? fabricName : neoName);
                super.visitEnd();
            }

            // The original candlelight annotation has no attributes of its own to preserve.
            @Override public void visit(String name, Object value) {}
            @Override public void visitEnum(String name, String desc, String value) {}
            @Override public AnnotationVisitor visitAnnotation(String name, String desc) { return null; }
            @Override public AnnotationVisitor visitArray(String name) { return null; }
        };
    }
}