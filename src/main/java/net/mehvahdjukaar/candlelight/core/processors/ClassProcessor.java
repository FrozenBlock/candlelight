package net.mehvahdjukaar.candlelight.core.processors;


import net.mehvahdjukaar.candlelight.core.TransformContext;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.util.List;

public interface ClassProcessor {

    boolean transform(ClassWriter classWriter, ClassReader reader, TransformContext ctx);

    List<String> usedAnnotations();

    default boolean isActive(TransformContext ctx) {
        return true;
    }
}
