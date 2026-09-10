package net.mehvahdjukaar.candlelight.core.processors;

import net.mehvahdjukaar.candlelight.core.TransformContext;
import net.mehvahdjukaar.candlelight.core.ClassUtils;
import org.objectweb.asm.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

public class BeanConventionProcessor implements ClassProcessor {

    private static final String ANNOTATION_DESC =
            ClassUtils.toDescriptor("net.mehvahdjukaar.candlelight.api.BeanAliases");

    private static final String NO_ALIAS_DESC =
            ClassUtils.toDescriptor("net.mehvahdjukaar.candlelight.api.NoBeanAlias");

    private static final String BEAN_ALIAS_DESC =
            ClassUtils.toDescriptor("net.mehvahdjukaar.candlelight.api.BeanAlias");

    @Override
    public List<String> usedAnnotations() {
        return List.of(ANNOTATION_DESC, NO_ALIAS_DESC, BEAN_ALIAS_DESC);
    }

    @Override
    public boolean transform(ClassWriter cw, ClassReader cr, TransformContext ctx) {
        final boolean[] modified = {false};

        cr.accept(new ClassVisitor(ASM9, cw) {
            private String className;
            private final Set<String> existingMethods = new HashSet<>();
            private final List<MethodData> candidates = new ArrayList<>();
            private boolean enabled = false;

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                className = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (ANNOTATION_DESC.equals(descriptor)) {
                    enabled = true;
                }
                return super.visitAnnotation(descriptor, visible);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                existingMethods.add(name);

                // Only public, non-synthetic methods starting with lowercase
                if ((access & ACC_PUBLIC) == 0
                        || (access & (ACC_SYNTHETIC | ACC_BRIDGE)) != 0
                        || !name.matches("[a-z].*")) {
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }


                MethodData data = new MethodData(name, descriptor, access, null);

                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(ASM9, mv) {
                    private boolean noAlias = false;
                    private String aliasPrefix = null;

                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        if (NO_ALIAS_DESC.equals(desc)) {
                            noAlias = true;
                        } else if (BEAN_ALIAS_DESC.equals(desc)) {
                            return new AnnotationVisitor(ASM9, super.visitAnnotation(desc, visible)) {
                                @Override
                                public void visit(String name, Object value) {
                                    if ("value".equals(name) && value instanceof String) {
                                        aliasPrefix = (String) value;
                                    }
                                    super.visit(name, value);
                                }
                            };
                        }
                        return super.visitAnnotation(desc, visible);
                    }

                    @Override
                    public void visitEnd() {
                        // Skip candidates marked NoAlias or that start with "has", "can", "get", "is" followed by uppercase
                        if (!noAlias && !data.name.matches("(has|can|get|is)[A-Z].*")) {
                            candidates.add(new MethodData(data.name, data.descriptor, data.access, aliasPrefix));
                        }
                        super.visitEnd();
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (!enabled) {
                    super.visitEnd();
                    return;
                }

                ctx.log(" Generating getters for " + className.replace('/', '.'));

                for (MethodData m : candidates) {

                    Type returnType = Type.getReturnType(m.descriptor);
                    Type[] args = Type.getArgumentTypes(m.descriptor);

                    boolean isGetter = args.length == 0 && returnType.getSort() != Type.VOID;
                    boolean isSetter = args.length == 1 && returnType.getSort() == Type.VOID;

                    if (!isGetter && !isSetter) continue;

                    String prefix;
                    if (m.aliasPrefix != null) {
                        prefix = m.aliasPrefix;
                    } else if (isGetter) {
                        prefix = returnType.getSort() == Type.BOOLEAN ? "is" : "get";
                    } else {
                        prefix = "set";
                    }

                    String baseName = Character.toUpperCase(m.name.charAt(0)) + m.name.substring(1);
                    String alias = prefix + baseName;

                    // Skip if alias already exists
                    if (existingMethods.contains(alias)) continue;

                    MethodVisitor mv = cv.visitMethod(
                            ACC_PUBLIC | (m.isStatic() ? ACC_STATIC : 0),
                            alias,
                            m.descriptor,
                            null,
                            null
                    );

                    mv.visitCode();

                    int localIndex = 0;

                    if (!m.isStatic()) {
                        mv.visitVarInsn(ALOAD, localIndex++);
                    }

                    for (Type arg : args) {
                        mv.visitVarInsn(arg.getOpcode(ILOAD), localIndex);
                        localIndex += arg.getSize();
                    }

                    int opcode = m.isStatic() ? INVOKESTATIC : INVOKEVIRTUAL;

                    mv.visitMethodInsn(
                            opcode,
                            className,
                            m.name,
                            m.descriptor,
                            false
                    );

                    mv.visitInsn(returnType.getOpcode(IRETURN));
                    mv.visitMaxs(0, 0);
                    mv.visitEnd();

                    ctx.log("  + generated " +
                            (isGetter ? "getter" : "setter") + ": " + alias
                    );

                    modified[0] = true;
                }

                super.visitEnd();
            }

        }, 0);

        return modified[0];
    }

    private record MethodData(String name, String descriptor, int access, String aliasPrefix) {
        boolean isStatic() {
            return (access & ACC_STATIC) != 0;
        }
    }
}
