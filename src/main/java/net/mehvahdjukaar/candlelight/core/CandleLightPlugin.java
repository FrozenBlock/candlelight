package net.mehvahdjukaar.candlelight.core;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Provider;
import org.gradle.api.publish.tasks.GenerateModuleMetadata;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

public class CandleLightPlugin implements Plugin<Project> {

    private static final String PREFIX = "[CANDLELIGHT] ";

    public static void log(String s) {
        Logging.getLogger("candlelight").lifecycle(PREFIX + s);
    }

    @Override
    public void apply(Project project) {

        CandleLightExtension clExtension = project.getExtensions()
                .create("candlelight", CandleLightExtension.class);

        clExtension.getLogging().convention(true);
        clExtension.getClientOnly().convention(true);
        clExtension.getServerOnly().convention(true);

        configureNeoForgeModuleMetadata(project);

        project.getPlugins().withId("java", plugin -> {
            transformJarInPlace(project, clExtension);

            project.getTasks().withType(JavaCompile.class).configureEach(compileTask -> {
                trackExtensionInputs(project, compileTask, clExtension);
                compileTask.doLast(new TransformJavaClassesAction(project, clExtension));
            });

            project.getTasks().configureEach(task -> {
                String name = task.getName();
                // Kotlin outputs are matched by name to avoid needing the Kotlin Gradle
                // plugin on this plugin's classpath.
                if (name.startsWith("compile") && name.endsWith("Kotlin")) {
                    trackExtensionInputs(project, task, clExtension);
                    task.doLast(new TransformKotlinClassesAction(project, clExtension));
                }
                if (name.equals("curseforge")) {
                    task.dependsOn("jar");
                }
            });
        });

        if (project == project.getRootProject()) {
            registerAggregatorTasks(project);
        } else {
            // Ensure aggregator tasks are registered on the root project even when
            // the plugin is applied only via `subprojects { apply plugin: ... }`.
            // Re-applying the plugin is a no-op thanks to Gradle's de-duplication.
            project.getRootProject().getPlugins().apply(CandleLightPlugin.class);
        }
    }

    private void registerAggregatorTasks(Project root) {
        TaskProvider<Task> cleanAll = root.getTasks().register("cleanAll", t -> {
            t.setGroup("build");
            t.setDescription("Cleans all subprojects atomically.");
            t.dependsOn(subprojectTaskPaths(root, "clean"));
        });

        TaskProvider<Task> buildAll = root.getTasks().register("buildAll", t -> {
            t.setGroup("build");
            t.setDescription("Builds all subprojects. Runs only after cleanAll succeeds when both are scheduled.");
            t.dependsOn(subprojectTaskPaths(root, "build"));
            t.mustRunAfter(cleanAll);
        });

        // Depend on the root-level `:curseforge` and `:modrinth` aggregators created by the
        // helper plugin via addUploadTask(...). We deliberately do NOT depend on `:upload`,
        // because helper's `:upload` also depends on `:publish` — pulling that into our chain
        // creates a cycle once `publish.mustRunAfter(gitTag)` is applied.
        TaskProvider<Task> uploadAll = root.getTasks().register("uploadAll", t -> {
            t.setGroup("publishing");
            t.setDescription("Uploads all subprojects to CurseForge and Modrinth (excludes maven publish).");
            t.dependsOn((Callable<List<Object>>) () -> {
                List<Object> deps = new ArrayList<>();
                Task cf = root.getTasks().findByName("curseforge");
                if (cf != null) deps.add(cf);
                Task mr = root.getTasks().findByName("modrinth");
                if (mr != null) deps.add(mr);
                return deps;
            });
            t.mustRunAfter(buildAll);
        });

        TaskProvider<GitTagTask> gitTag = root.getTasks().register("gitTag", GitTagTask.class, t -> {
            t.setGroup("publishing");
            t.setDescription("Creates a local git tag matching the root project's mod_version (pushed with your next 'git push --follow-tags').");
            t.getTag().convention(root.provider(() -> {
                Object v = root.findProperty("mod_version");
                if (v == null) {
                    throw new IllegalStateException(
                            "gitTag: root project property 'mod_version' is not set. " +
                                    "Override via gitTag { tag.set(\"...\") } if you use a different property.");
                }
                return v.toString();
            }));
            t.mustRunAfter(uploadAll);
        });

        // Ensure root's `publish` (if present) is ordered after gitTag.
        root.getTasks().configureEach(task -> {
            if (task.getName().equals("publish")) {
                task.mustRunAfter(gitTag);
            }
        });

        // Gate EVERY real upload/publish task (in every project, not just the
        // aggregator wrappers) so it cannot start until the COMPLETE buildAll —
        // i.e. every loader's build/compile — has succeeded.
        //
        // mustRunAfter(buildAll) placed on the `uploadAll`/`gitTag` wrappers does
        // NOT propagate to their dependencies: Gradle ordering constraints are not
        // transitive down a dependsOn edge. Without this, the only constraints on
        // e.g. `:neoforge:curseforge` are its own narrow chain (compile -> jar ->
        // curseforge), so the parallel scheduler is free to run it to completion —
        // uploading! — while `:fabric:compileJava` is still running. If fabric then
        // fails, neoforge has already been published. Ordering the leaf tasks after
        // buildAll means no upload/publish can begin until all loaders have built,
        // so a failure in any loader aborts (fail-fast mode) before anything ships.
        root.allprojects(p -> p.getTasks().configureEach(task -> {
            String name = task.getName();
            if (name.equals("curseforge") || name.equals("modrinth") || name.startsWith("publish")) {
                task.mustRunAfter(buildAll);
            }
        }));

        root.getTasks().register("buildAndPublishAll", t -> {
            t.setGroup("build");
            t.setDescription("Full release pipeline: cleanAll -> buildAll -> uploadAll -> gitTag -> publish. Each step runs only if the previous succeeded.");
            t.dependsOn(cleanAll, buildAll, uploadAll, gitTag, "publish");
        });
    }

    private static Callable<List<String>> subprojectTaskPaths(Project root, String taskName) {
        return () -> {
            List<String> paths = new ArrayList<>();
            for (Project sp : root.getSubprojects()) {
                paths.add(sp.getPath() + ":" + taskName);
            }
            return paths;
        };
    }

    private static boolean isPackagedJarTask(String name) {
        return name.equals("jar") || name.equals("shadowJar");
    }

    private static void trackExtensionInputs(Project project, Task compileTask, CandleLightExtension clExtension) {
        compileTask.getInputs().property("candlelight.loader", project.getName());
        compileTask.getInputs().property("candlelight.clientOnly", clExtension.getClientOnly());
        compileTask.getInputs().property("candlelight.serverOnly", clExtension.getServerOnly());
    }

    private void transformJarInPlace(Project project, CandleLightExtension clExtension) {
        project.getTasks().withType(Jar.class).configureEach(jarTask -> {
            if (!isPackagedJarTask(jarTask.getName())) return;
            jarTask.doLast(new TransformArchiveAction(project, clExtension));
        });
    }

    private abstract static class AbstractTransformAction implements Action<Task> {
        private final String projectName;
        private final Provider<Boolean> clientOnly;
        private final Provider<Boolean> serverOnly;
        private final Provider<Boolean> logging;

        AbstractTransformAction(Project project, CandleLightExtension clExtension) {
            this.projectName = project.getName();
            this.clientOnly = clExtension.getClientOnly();
            this.serverOnly = clExtension.getServerOnly();
            this.logging = clExtension.getLogging();
        }

        TransformContext context() {
            return new TransformContext(projectName, clientOnly.get(), serverOnly.get(), logging.get());
        }
    }

    /** Transforms a {@link JavaCompile} task's output in place right after compilation. */
    private static class TransformJavaClassesAction extends AbstractTransformAction {
        TransformJavaClassesAction(Project project, CandleLightExtension clExtension) {
            super(project, clExtension);
        }

        @Override
        public void execute(Task task) {
            File outputDir = ((JavaCompile) task).getDestinationDirectory().get().getAsFile();
            transformDirectory(outputDir, context(), task.getName());
        }
    }

    private static class TransformKotlinClassesAction extends AbstractTransformAction {
        TransformKotlinClassesAction(Project project, CandleLightExtension clExtension) {
            super(project, clExtension);
        }

        @Override
        public void execute(Task task) {
            DirectoryProperty destination = destinationDirectoryOf(task);
            if (destination == null || !destination.isPresent()) {
                log("could not locate output of " + task.getName() + ", skipping classes transform");
                return;
            }
            transformDirectory(destination.get().getAsFile(), context(), task.getName());
        }

        @Nullable
        private static DirectoryProperty destinationDirectoryOf(Task task) {
            try {
                Object value = task.getClass().getMethod("getDestinationDirectory").invoke(task);
                return value instanceof DirectoryProperty property ? property : null;
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
    }

    private static class TransformArchiveAction extends AbstractTransformAction {
        TransformArchiveAction(Project project, CandleLightExtension clExtension) {
            super(project, clExtension);
        }

        @Override
        public void execute(Task task) {
            File archiveFile = ((Jar) task).getArchiveFile().get().getAsFile();
            if (!archiveFile.exists()) return;
            try {
                TransformJarAction.transform(archiveFile, context());
            } catch (IOException e) {
                throw new RuntimeException("Candlelight jar transform failed for " + archiveFile, e);
            }
        }
    }

    private static void transformDirectory(File outputDir, TransformContext ctx, String taskName) {
        try {
            TransformJarAction.transformDirectoryInPlace(outputDir, ctx);
        } catch (IOException e) {
            throw new RuntimeException("Candlelight classes transform failed for " + taskName, e);
        }
    }

    private void configureNeoForgeModuleMetadata(Project project) {
        if (!"neoforge".equals(project.getName())) {
            return;
        }

        project.getPlugins().withId("maven-publish", plugin -> project.afterEvaluate(p -> {
            TaskProvider<EnsureAccessTransformerModuleMetadataTask> ensureTask = p.getTasks().register(
                    "candleEnsureAccessTransformerModuleMetadata",
                    EnsureAccessTransformerModuleMetadataTask.class,
                    task -> task.getAccessTransformerSources().from(
                            p.getLayout().getBuildDirectory().dir("copyAccessTransformersPublications"),
                            p.getLayout().getBuildDirectory().file("generated/accesstransformer.cfg")
                    )
            );

            var moduleMetadataTasks = p.getTasks().withType(GenerateModuleMetadata.class);

            // Set ensureTask's input lazily via a value provider instead of calling
            // ensureTask.configure(...) from inside a configureEach: under Gradle 9 that
            // throws "NamedDomainObjectProvider.configure(Action) on task set cannot be
            // executed in the current context". Resolving the file lazily also keeps this
            // tolerant of afterEvaluate ordering between us and the publishing plugin.
            ensureTask.configure(task -> task.getModuleFile().fileProvider(p.provider(() -> {
                for (GenerateModuleMetadata generateTask : moduleMetadataTasks) {
                    return generateTask.getOutputFile().get().getAsFile();
                }
                return null;
            })));

            Task modifyTask = p.getTasks().findByName("modifyMetadataFile");
            if (modifyTask != null) {
                modifyTask.finalizedBy(ensureTask);
            } else {
                moduleMetadataTasks.configureEach(generateTask ->
                        generateTask.finalizedBy(ensureTask)
                );
            }
        }));
    }

}