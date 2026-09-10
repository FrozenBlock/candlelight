package net.mehvahdjukaar.candlelight.core.processors;

import net.mehvahdjukaar.candlelight.core.TransformContext;
import net.mehvahdjukaar.candlelight.core.ClassUtils;
import org.objectweb.asm.*;

import java.util.ArrayList;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;

public class PlatImplProcessor implements ClassProcessor {

    private static final String FLAVOUR_ANNOTATION_DESC =
            ClassUtils.toDescriptor("net.mehvahdjukaar.candlelight.api.PlatformImpl");

    @Override
    public List<String> usedAnnotations() {
        return List.of(FLAVOUR_ANNOTATION_DESC);
    }

    @Override
    public boolean transform(ClassWriter writer, ClassReader reader, TransformContext ctx) {

        final String[] className = new String[1];
        final boolean[] modified = {false};
        final List<MethodInfo> methodsToReplace = new ArrayList<>();

        // First pass: collect annotated methods and class name
        reader.accept(new ClassVisitor(ASM9, null) {
            @Override
            public void visit(int version, int access, String name,
                              String signature, String superName, String[] interfaces) {
                className[0] = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(ASM9, mv) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        if (desc.equals(FLAVOUR_ANNOTATION_DESC)) {
                            modified[0] = true;
                            methodsToReplace.add(new MethodInfo(access, name, descriptor, signature, exceptions));
                        }
                        return super.visitAnnotation(desc, visible);
                    }
                };
            }
        }, 0);

        if (!modified[0]) {
            return false;
        }

        // Second pass: write class, skipping original annotated methods
        String implInternalName = computeImplInternalName(className[0]);
        ClassVisitor cv = new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                // Skip the original methods that will be replaced
                for (MethodInfo mi : methodsToReplace) {
                    if (mi.name.equals(name) && mi.descriptor.equals(descriptor)) {
                        return null; // do not include the original method
                    }
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }

            @Override
            public void visitEnd() {
                // Add the replacement methods
                for (MethodInfo mi : methodsToReplace) {
                    addDelegatingMethod(writer, mi, implInternalName);
                    ctx.log(
                            " Replaced method: " + className[0].replace('/', '.') + "#" + mi.name);
                }
                super.visitEnd();
            }
        };

        reader.accept(cv, 0);
        return true;
    }

    private void addDelegatingMethod(ClassVisitor cv, MethodInfo mi, String implInternalName) {
        MethodVisitor mv = cv.visitMethod(mi.access, mi.name, mi.descriptor, mi.signature, mi.exceptions);
        if (mv != null) {
            mv.visitCode();

            // Determine if the original method is static
            boolean isStatic = (mi.access & ACC_STATIC) != 0;
            int slotIndex = isStatic ? 0 : 1; // args start at 0 for static, 1 for instance

            Type[] argTypes = Type.getArgumentTypes(mi.descriptor);
            for (Type argType : argTypes) {
                loadArg(mv, argType, slotIndex);
                slotIndex += argType.getSize();
            }

            // Invoke the static impl method
            mv.visitMethodInsn(INVOKESTATIC, implInternalName, mi.name, mi.descriptor, false);

            // Return based on return type
            Type returnType = Type.getReturnType(mi.descriptor);
            switch (returnType.getSort()) {
                case Type.VOID:
                    mv.visitInsn(RETURN);
                    break;
                case Type.BOOLEAN:
                case Type.BYTE:
                case Type.CHAR:
                case Type.SHORT:
                case Type.INT:
                    mv.visitInsn(IRETURN);
                    break;
                case Type.LONG:
                    mv.visitInsn(LRETURN);
                    break;
                case Type.FLOAT:
                    mv.visitInsn(FRETURN);
                    break;
                case Type.DOUBLE:
                    mv.visitInsn(DRETURN);
                    break;
                default:
                    mv.visitInsn(ARETURN);
            }

            mv.visitMaxs(0, 0); // Will be automatically computed due to COMPUTE_FRAMES
            mv.visitEnd();
        }
    }

    private void loadArg(MethodVisitor mv, Type type, int index) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
            case Type.BYTE:
            case Type.CHAR:
            case Type.SHORT:
            case Type.INT:
                mv.visitVarInsn(ILOAD, index);
                break;
            case Type.LONG:
                mv.visitVarInsn(LLOAD, index);
                break;
            case Type.FLOAT:
                mv.visitVarInsn(FLOAD, index);
                break;
            case Type.DOUBLE:
                mv.visitVarInsn(DLOAD, index);
                break;
            default:
                mv.visitVarInsn(ALOAD, index);
        }
    }

    private static String computeImplInternalName(String originalInternalName) {
        String platPackage = "platform";
        int lastSlash = originalInternalName.lastIndexOf('/');
        String pkg = lastSlash >= 0 ? originalInternalName.substring(0, lastSlash) : "";
        String simple = lastSlash >= 0 ? originalInternalName.substring(lastSlash + 1) : originalInternalName;

        return pkg.isEmpty()
                ? platPackage + "/" + simple + "Impl"
                : pkg + "/" + platPackage + "/" + simple + "Impl";
    }

    private record MethodInfo(int access, String name, String descriptor, String signature, String[] exceptions) {
    }
}