package net.mehvahdjukaar.candlelight.core.processors;

import net.mehvahdjukaar.candlelight.core.TransformContext;
import net.mehvahdjukaar.candlelight.core.ClassUtils;
import org.objectweb.asm.*;

import java.util.List;

public class OptionalInterfaceProcessor implements ClassProcessor {

    private static final String ANNOTATION_DESC =
            ClassUtils.toDescriptor("net.mehvahdjukaar.candlelight.api.OptionalInterface");


    @Override
    public List<String> usedAnnotations() {
        return List.of(ANNOTATION_DESC);
    }

    @Override
    public boolean transform(ClassWriter writer, ClassReader reader, TransformContext ctx) {

        // We use a 1-element array so the anonymous inner class can modify it
        final String[] foundInterface = {null};

        // Pass 1: Scan for the @OptionalInterface annotation
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (ANNOTATION_DESC.equals(descriptor)) {
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String name, Object value) {
                            if ("value".equals(name) && value instanceof String strValue) {
                                foundInterface[0] = strValue.replace('.', '/');
                            }
                            super.visit(name, value);
                        }
                    };
                }
                return super.visitAnnotation(descriptor, visible);
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        // Pass 2: If found, actually inject the interface
        if (foundInterface[0] != null) {
            String targetInterface = foundInterface[0];

            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                    // Check if class already has the interface
                    boolean exists = false;
                    for (String itf : interfaces) {
                        if (itf.equals(targetInterface)) {
                            exists = true;
                            break;
                        }
                    }

                    if (!exists) {
                        String[] newInterfaces = new String[interfaces.length + 1];
                        System.arraycopy(interfaces, 0, newInterfaces, 0, interfaces.length);
                        newInterfaces[interfaces.length] = targetInterface;

                        ctx.log(" Added OptionalInterface [" +
                                targetInterface.replace('/', '.') + "] to class: " + name.replace('/', '.'));

                        super.visit(version, access, name, signature, superName, newInterfaces);
                    } else {
                        super.visit(version, access, name, signature, superName, interfaces);
                    }
                }
            }, 0);
            return true;
        }

        return false;
    }

}