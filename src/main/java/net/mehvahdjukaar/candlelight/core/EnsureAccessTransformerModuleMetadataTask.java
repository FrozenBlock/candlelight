package net.mehvahdjukaar.candlelight.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@UntrackedTask(because = "mutates getModuleFile() in place, there is no input/output to cache")
public abstract class EnsureAccessTransformerModuleMetadataTask extends DefaultTask {

    private static final String LIBRARY_VARIANT_NAME = "accessTransformersElements2";
    private static final String LIBRARY_CATEGORY = "library";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getModuleFile();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getAccessTransformerSources();

    @TaskAction
    public void ensureAccessTransformerVariant() throws IOException {
        File moduleFile = getModuleFile().getAsFile().get();
        JsonObject json = GSON.fromJson(Files.readString(moduleFile.toPath()), JsonObject.class);
        JsonArray variants = json.getAsJsonArray("variants");
        if (variants == null) {
            return;
        }

        if (hasAccessTransformerLibraryVariant(variants)) {
            return;
        }

        if (!isNeoForgePublication(variants)) {
            return;
        }

        JsonObject component = json.getAsJsonObject("component");
        if (component == null) {
            return;
        }

        String module = component.get("module").getAsString();
        String version = component.get("version").getAsString();
        String publishedName = module + "-" + version + "-accesstransformer.cfg";

        File accessTransformerFile = findAccessTransformerFile(publishedName);
        if (accessTransformerFile == null) {
            CandleLightPlugin.log(
                    "Skipping access transformer module metadata: no file found for " + publishedName);
            return;
        }

        variants.add(buildAccessTransformerLibraryVariant(accessTransformerFile, publishedName));
        Files.writeString(moduleFile.toPath(), GSON.toJson(json));
        CandleLightPlugin.log(
                "Added " + LIBRARY_VARIANT_NAME + " variant to module metadata (" + publishedName + ")");
    }

    private static boolean hasAccessTransformerLibraryVariant(JsonArray variants) {
        for (int i = 0; i < variants.size(); i++) {
            JsonObject variant = variants.get(i).getAsJsonObject();
            if (LIBRARY_VARIANT_NAME.equals(variant.get("name").getAsString())) {
                return true;
            }

            JsonObject attributes = variant.getAsJsonObject("attributes");
            if (attributes == null
                    || !LIBRARY_CATEGORY.equals(attributes.get("org.gradle.category").getAsString())) {
                continue;
            }

            JsonArray files = variant.getAsJsonArray("files");
            if (files == null) {
                continue;
            }
            for (int j = 0; j < files.size(); j++) {
                String fileName = files.get(j).getAsJsonObject().get("name").getAsString();
                if (fileName.endsWith("-accesstransformer.cfg")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isNeoForgePublication(JsonArray variants) {
        for (int i = 0; i < variants.size(); i++) {
            JsonObject attributes = variants.get(i).getAsJsonObject().getAsJsonObject("attributes");
            if (attributes == null) {
                continue;
            }
            if (attributes.has("io.github.mcgradleconventions.loader")
                    && "neoforge".equals(attributes.get("io.github.mcgradleconventions.loader").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private File findAccessTransformerFile(String publishedName) {
        for (File source : getAccessTransformerSources()) {
            if (!source.exists()) {
                continue;
            }
            if (source.isFile()) {
                if (matchesAccessTransformer(source, publishedName)) {
                    return source;
                }
                continue;
            }
            File[] children = source.listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                if (matchesAccessTransformer(child, publishedName)) {
                    return child;
                }
            }
        }
        return null;
    }

    private static boolean matchesAccessTransformer(File file, String publishedName) {
        if (!file.isFile()) {
            return false;
        }
        return file.getName().equals(publishedName) || file.getName().endsWith("-accesstransformer.cfg");
    }

    private static JsonObject buildAccessTransformerLibraryVariant(File file, String publishedName) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());

        JsonObject attributes = new JsonObject();
        attributes.addProperty("org.gradle.category", LIBRARY_CATEGORY);

        JsonObject fileEntry = new JsonObject();
        fileEntry.addProperty("name", publishedName);
        fileEntry.addProperty("url", publishedName);
        fileEntry.addProperty("size", file.length());
        fileEntry.addProperty("md5", hash(bytes, "MD5"));
        fileEntry.addProperty("sha1", hash(bytes, "SHA-1"));
        fileEntry.addProperty("sha256", hash(bytes, "SHA-256"));
        fileEntry.addProperty("sha512", hash(bytes, "SHA-512"));

        JsonArray files = new JsonArray();
        files.add(fileEntry);

        JsonObject variant = new JsonObject();
        variant.addProperty("name", LIBRARY_VARIANT_NAME);
        variant.add("attributes", attributes);
        variant.add("files", files);
        return variant;
    }

    private static String hash(byte[] bytes, String algorithm) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Missing digest algorithm: " + algorithm, e);
        }
    }
}
