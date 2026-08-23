package net.mehvahdjukaar.candlelight.core;

import org.gradle.api.DefaultTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.process.ExecOperations;

import javax.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

@UntrackedTask(because = "creates a local git tag as a side effect, there is no output to track or cache")
public abstract class GitTagTask extends DefaultTask {

    @Inject
    public abstract ExecOperations getExecOperations();

    /**
     * Git tag value. Defaults to the root project's {@code mod_version} property.
     */
    @Input
    public abstract Property<String> getTag();

    @TaskAction
    public void tagAndPush() {
        String tag = getTag().get();
        if (tag.isEmpty()) {
            throw new IllegalStateException("Cannot create git tag: tag value is empty");
        }

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        getExecOperations().exec(spec -> {
            spec.commandLine("git", "tag", "-l", tag);
            spec.setStandardOutput(stdout);
        });

        if (!stdout.toString(StandardCharsets.UTF_8).trim().isEmpty()) {
            getLogger().warn("Git tag '{}' already exists, skipping", tag);
            return;
        }

        getExecOperations().exec(spec ->
                spec.commandLine("git", "tag", "-a", tag, "-m", "Release " + tag)
        );
        getLogger().lifecycle("Created local git tag '{}'. It will be pushed with your next 'git push --follow-tags'.", tag);
    }
}
