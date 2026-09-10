package net.mehvahdjukaar.candlelight.core;

import net.mehvahdjukaar.candlelight.core.processors.ClassProcessor;
import net.mehvahdjukaar.candlelight.core.processors.ClientOnlyProcessor;
import net.mehvahdjukaar.candlelight.core.processors.PlatImplProcessor;
import net.mehvahdjukaar.candlelight.core.processors.ServerOnlyProcessor;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Runs candlelight's bytecode transforms (see {@link PlatImplProcessor},
 * {@link ClientOnlyProcessor}, {@link ServerOnlyProcessor}) against compiled classes, either
 * a compile task's output directory or an already-built jar, rewriting them in place.
 */
public final class TransformJarAction {

    private static final List<ClassProcessor> PROCESSORS = List.of(
            new PlatImplProcessor(),
            new ClientOnlyProcessor(),
            new ServerOnlyProcessor()
    );

    private TransformJarAction() {
    }

    static void transformDirectoryInPlace(File classesDir, TransformContext ctx) throws IOException {
        if (!classesDir.isDirectory()) {
            return;
        }
        List<ClassProcessor> processors = activeProcessors(ctx);
        if (processors.isEmpty()) {
            return;
        }
        List<String> annotations = annotationsOf(processors);

        long startMillis = System.currentTimeMillis();
        // Each class is read and pre-scanned exactly once, and nothing is logged until a
        // class is actually about to be rewritten.
        ClassUtils.walkClasses(classesDir, file -> {
            byte[] inputBytes = ClassUtils.readAllBytes(file);
            if (!needsTransform(inputBytes, annotations)) {
                return;
            }
            byte[] outputBytes = applyProcessors(inputBytes, processors, ctx);
            if (outputBytes == null) {
                return;
            }
            ctx.log(" transformed: " + classesDir.toPath().relativize(file.toPath()));
            Files.write(file.toPath(), outputBytes);
        });

        if (ctx.hasLoggedHeader()) {
            long elapsedMillis = System.currentTimeMillis() - startMillis;
            ctx.log(String.format("Transformation finished in %d ms", elapsedMillis));
        }
    }

    public static void transform(File jarFile, TransformContext ctx) throws IOException {
        List<ClassProcessor> processors = activeProcessors(ctx);
        if (processors.isEmpty()) {
            return;
        }
        List<String> annotations = annotationsOf(processors);

        if (!containsAnnotatedClass(jarFile, annotations)) {
            return;
        }

        long startMillis = System.currentTimeMillis();

        File tmp = File.createTempFile("candlelight-", ".jar", jarFile.getAbsoluteFile().getParentFile());
        boolean anyChanged = false;
        try (ZipFile zipIn = new ZipFile(jarFile);
             ZipOutputStream zipOut = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            Enumeration<? extends ZipEntry> entries = zipIn.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] data;
                try (InputStream in = zipIn.getInputStream(entry)) {
                    data = in.readAllBytes();
                }

                if (!entry.isDirectory() && entry.getName().endsWith(".class")
                        && needsTransform(data, annotations)) {
                    byte[] transformed = applyProcessors(data, processors, ctx);
                    if (transformed != null) {
                        ctx.log(" transformed: " + entry.getName());
                        data = transformed;
                        anyChanged = true;
                    }
                }

                ZipEntry outEntry = new ZipEntry(entry.getName());
                outEntry.setTime(entry.getTime());
                zipOut.putNextEntry(outEntry);
                zipOut.write(data);
                zipOut.closeEntry();
            }
        }

        if (anyChanged) {
            try {
                Files.move(tmp.toPath(), jarFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), jarFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            Files.deleteIfExists(tmp.toPath());
        }

        long elapsedMillis = System.currentTimeMillis() - startMillis;
        ctx.log(String.format("Transformation finished in %d ms", elapsedMillis));
    }

    private static List<ClassProcessor> activeProcessors(TransformContext ctx) {
        return PROCESSORS.stream().filter(p -> p.isActive(ctx)).toList();
    }

    private static List<String> annotationsOf(List<ClassProcessor> processors) {
        return processors.stream()
                .flatMap(p -> p.usedAnnotations().stream())
                .distinct()
                .toList();
    }

    private static boolean containsAnnotatedClass(File jarFile, List<String> annotations) throws IOException {
        try (ZipFile zipIn = new ZipFile(jarFile)) {
            Enumeration<? extends ZipEntry> entries = zipIn.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] data;
                try (InputStream in = zipIn.getInputStream(entry)) {
                    data = in.readAllBytes();
                }
                if (needsTransform(data, annotations)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean needsTransform(byte[] input, List<String> annotations) {
        ClassReader scanReader = new ClassReader(input);
        PreScannerVisitor scanner = new PreScannerVisitor(annotations);
        scanReader.accept(scanner, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        return scanner.shouldTransform;
    }

    /** Returns the rewritten bytes, or {@code null} if no processor changed anything. */
    private static byte @Nullable [] applyProcessors(byte[] input, List<ClassProcessor> processors, TransformContext ctx) {
        ctx.logHeaderOnce();
        boolean changed = false;
        for (ClassProcessor processor : processors) {
            ClassReader cr = new ClassReader(input);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

            if (processor.transform(cw, cr, ctx)) {
                input = cw.toByteArray();
                changed = true;
            }
        }
        return changed ? input : null;
    }

    private static class PreScannerVisitor extends ClassVisitor {
        private final List<String> annotations;
        private boolean shouldTransform = false;

        PreScannerVisitor(List<String> annotations) {
            super(Opcodes.ASM9);
            this.annotations = annotations;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            if (annotations.contains(desc)) shouldTransform = true;
            return null;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    if (annotations.contains(desc)) shouldTransform = true;
                    return null;
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    if (annotations.contains(desc)) shouldTransform = true;
                    return null;
                }
            };
        }
    }
}
