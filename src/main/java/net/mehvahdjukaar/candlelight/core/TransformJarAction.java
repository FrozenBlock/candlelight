package net.mehvahdjukaar.candlelight.core;

import net.mehvahdjukaar.candlelight.core.processors.ClassProcessor;
import net.mehvahdjukaar.candlelight.core.processors.ClientOnlyProcessor;
import net.mehvahdjukaar.candlelight.core.processors.PlatImplProcessor;
import org.gradle.api.Project;
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
 * {@link ClientOnlyProcessor}) against an already-built jar, rewriting it in place.
 */
public final class TransformJarAction {

    private static final List<ClassProcessor> PROCESSORS = List.of(
            new PlatImplProcessor(),
            new ClientOnlyProcessor()
    );

    private static final List<String> OUR_ANNOTATIONS = PROCESSORS.stream()
            .flatMap(p -> p.usedAnnotations().stream())
            .distinct()
            .toList();

    private TransformJarAction() {
    }

    public static void transform(File jarFile, Project project, CandleLightExtension ext) throws IOException {
        long startMillis = System.currentTimeMillis();
        CandleLightPlugin.log(project, "processing annotations");

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

                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    byte[] transformed = transformClass(data, project, ext);
                    if (transformed != null) {
                        CandleLightPlugin.log(project, " transformed: " + entry.getName());
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
        CandleLightPlugin.log(project, String.format("Transformation finished in %d ms", elapsedMillis));
    }

    private static byte @Nullable [] transformClass(byte[] input, Project project, CandleLightExtension ext) {
        // PASS 1: Lightweight pre-scan - only care about annotations, so skip code/debug info.
        ClassReader scanReader = new ClassReader(input);
        PreScannerVisitor scanner = new PreScannerVisitor();
        scanReader.accept(scanner, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        if (!scanner.shouldTransform) {
            return null; // Exit early - no expensive ClassWriter work.
        }

        boolean changed = false;
        for (ClassProcessor processor : PROCESSORS) {
            ClassReader cr = new ClassReader(input);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

            boolean success = processor.transform(cw, cr, project, ext);

            if (success) {
                input = cw.toByteArray();
                changed = true;
            }
        }
        return changed ? input : null;
    }

    private static class PreScannerVisitor extends ClassVisitor {
        private boolean shouldTransform = false;

        PreScannerVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            if (OUR_ANNOTATIONS.contains(desc)) shouldTransform = true;
            return null;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    if (OUR_ANNOTATIONS.contains(desc)) shouldTransform = true;
                    return null;
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    if (OUR_ANNOTATIONS.contains(desc)) shouldTransform = true;
                    return null;
                }
            };
        }
    }
}
