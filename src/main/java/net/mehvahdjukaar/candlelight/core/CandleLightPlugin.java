package net.mehvahdjukaar.candlelight.core;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.publish.tasks.GenerateModuleMetadata;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

public class CandleLightPlugin implements Plugin<Project> {

    private static final String PREFIX = "[CANDLELIGHT] ";

    public static void log(Project project, String s) {
        project.getLogger().lifecycle(PREFIX + s);
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

            project.getTasks().configureEach(task -> {
                if (task.getName().equals("curseforge")) {
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

    private void transformJarInPlace(Project project, CandleLightExtension clExtension) {
        project.getTasks().withType(Jar.class).configureEach(jarTask -> {
            if (!isPackagedJarTask(jarTask.getName())) return;
            jarTask.doLast(t -> {
                var archiveFile = jarTask.getArchiveFile().get().getAsFile();
                if (!archiveFile.exists()) return;
                try {
                    TransformJarAction.transform(archiveFile, project, clExtension);
                } catch (IOException e) {
                    throw new RuntimeException("Candlelight jar transform failed for " + archiveFile, e);
                }
            });
        });
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