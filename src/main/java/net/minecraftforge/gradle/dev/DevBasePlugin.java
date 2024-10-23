package net.minecraftforge.gradle.dev;

import edu.sc.seis.launch4j.Launch4jPluginExtension;
import groovy.lang.Closure;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import net.minecraftforge.gradle.common.BasePlugin;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.json.JsonFactory;
import net.minecraftforge.gradle.json.version.Library;
import net.minecraftforge.gradle.json.version.OS;
import net.minecraftforge.gradle.tasks.CopyAssetsTask;
import net.minecraftforge.gradle.tasks.GenSrgTask;
import net.minecraftforge.gradle.tasks.MergeJarsTask;
import net.minecraftforge.gradle.tasks.abstractutil.DownloadTask;
import net.minecraftforge.gradle.tasks.abstractutil.ExtractTask;
import net.minecraftforge.gradle.tasks.dev.CompressLZMA;
import net.minecraftforge.gradle.tasks.dev.ObfuscateTask;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.process.ExecSpec;

import com.google.common.base.Throwables;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public abstract class DevBasePlugin extends BasePlugin<DevExtension>
{
    protected static final String[] JAVA_FILES = new String[] { "**.java", "*.java", "**/*.java" };

    @Override
    public void applyPlugin() {
        ExtractTask extractWorkspaceTask = makeTask("extractWorkspace", ExtractTask.class);
        extractWorkspaceTask.getOutputs().upToDateWhen(new Closure<Boolean>(null) {
            @Override
            public Boolean call(Object... obj) {
                File file = new File(project.getProjectDir(), "eclipse");
                return (file.exists() && file.isDirectory());
            }
        });
        extractWorkspaceTask.from(delayedFile(DevConstants.WORKSPACE_ZIP));
        extractWorkspaceTask.into(delayedFile(DevConstants.WORKSPACE));

        if (hasInstaller()) {
            // Apply L4J plugin
            this.applyExternalPlugin("launch4j");

            // Ensure uploadArchives depends on launch4j
            if (project.getTasks().findByName("uploadArchives") != null) {
                project.getTasks().getByName("uploadArchives").dependsOn("launch4j");
            }

            // Download base installer
            DownloadTask downloadBaseInstaller = makeTask("downloadBaseInstaller", DownloadTask.class);
            downloadBaseInstaller.setOutput(delayedFile(DevConstants.INSTALLER_BASE));
            downloadBaseInstaller.setUrl(delayedString(DevConstants.INSTALLER_URL));

            // Download Launch4j
            DownloadTask downloadL4JTask = makeTask("downloadL4J", DownloadTask.class);
            downloadL4JTask.setOutput(delayedFile(DevConstants.LAUNCH4J));
            downloadL4JTask.setUrl(delayedString(DevConstants.LAUNCH4J_URL));

            // Extract L4J
            ExtractTask extractL4JTask = makeTask("extractL4J", ExtractTask.class);
            extractL4JTask.dependsOn(downloadL4JTask);
            extractL4JTask.from(delayedFile(DevConstants.LAUNCH4J));
            extractL4JTask.into(delayedFile(DevConstants.LAUNCH4J_DIR));
        }

        // Update JSON
        DownloadTask updateJsonTask = makeTask("updateJson", DownloadTask.class);
        updateJsonTask.getOutputs().upToDateWhen(Constants.CALL_FALSE);
        updateJsonTask.setUrl(delayedString(Constants.MC_JSON_URL));
        updateJsonTask.setOutput(delayedFile(DevConstants.JSON_BASE));
        updateJsonTask.doLast(new Closure<Void>(project) {
            @Override
            public Void call() {
                File jsonFile = delayedFile(DevConstants.JSON_BASE).call();
                if (jsonFile.exists()) {
                    try {
                        List<String> lines = Files.readAllLines(jsonFile.toPath(), StandardCharsets.UTF_8);
                        String content = String.join("\n", lines);
                        Files.write(jsonFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new RuntimeException("Error updating JSON file: " + e.getMessage(), e);
                    }
                }
                return null;
            }
        });

        // Compress Deobf Data
        CompressLZMA compressTask = makeTask("compressDeobfData", CompressLZMA.class);
        compressTask.setInputFile(delayedFile(DevConstants.NOTCH_2_SRG_SRG));
        compressTask.setOutputFile(delayedFile(DevConstants.DEOBF_DATA));
        compressTask.dependsOn("genSrgs");

        // Merge Jars
        MergeJarsTask mergeJarsTask = makeTask("mergeJars", MergeJarsTask.class);
        mergeJarsTask.setClient(delayedFile(Constants.JAR_CLIENT_FRESH));
        mergeJarsTask.setServer(delayedFile(Constants.JAR_SERVER_FRESH));
        mergeJarsTask.setOutJar(delayedFile(Constants.JAR_MERGED));
        mergeJarsTask.setMergeCfg(delayedFile(DevConstants.MERGE_CFG));
        mergeJarsTask.setMcVersion(delayedString("{MC_VERSION}"));
        mergeJarsTask.dependsOn("downloadClient", "downloadServer", "updateJson");

        // Copy Assets
        CopyAssetsTask copyAssetsTask = makeTask("copyAssets", CopyAssetsTask.class);
        copyAssetsTask.setAssetsDir(delayedFile(Constants.ASSETS));
        copyAssetsTask.setOutputDir(delayedFile(DevConstants.ECLIPSE_ASSETS));
        copyAssetsTask.setAssetIndex(getAssetIndexClosure());
        copyAssetsTask.dependsOn("getAssets", "extractWorkspace");

        // Generate SRGs
        GenSrgTask genSrgTask = makeTask("genSrgs", GenSrgTask.class);
        genSrgTask.setInSrg(delayedFile(DevConstants.JOINED_SRG));
        genSrgTask.setInExc(delayedFile(DevConstants.JOINED_EXC));
        genSrgTask.setMethodsCsv(delayedFile(DevConstants.METHODS_CSV));
        genSrgTask.setFieldsCsv(delayedFile(DevConstants.FIELDS_CSV));
        genSrgTask.setNotchToSrg(delayedFile(DevConstants.NOTCH_2_SRG_SRG));
        genSrgTask.setNotchToMcp(delayedFile(DevConstants.NOTCH_2_MCP_SRG));
        genSrgTask.setSrgToMcp(delayedFile(DevConstants.SRG_2_MCP_SRG));
        genSrgTask.setMcpToSrg(delayedFile(DevConstants.MCP_2_SRG_SRG));
        genSrgTask.setMcpToNotch(delayedFile(DevConstants.MCP_2_NOTCH_SRG));
        genSrgTask.setSrgExc(delayedFile(DevConstants.SRG_EXC));
        genSrgTask.setMcpExc(delayedFile(DevConstants.MCP_EXC));
        genSrgTask.dependsOn("extractMcpData");
    }


    @Override
    public final void applyOverlayPlugin()
    {
        // nothing.
    }

    @Override
    public final boolean canOverlayPlugin()
    {
        return false;
    }

    private void configureLaunch4J()
    {
        if (!hasInstaller())
            return;

        final File installer = new File(((Zip) project.getTasks().getByName("packageInstaller")).getPath());

        File output = new File(installer.getParentFile(), installer.getName().replace(".jar", "-win.exe"));
        project.getArtifacts().add("archives", output);

        Launch4jPluginExtension ext = (Launch4jPluginExtension) project.getExtensions().getByName("launch4j");
        ext.setOutfile(output.getAbsolutePath());
        ext.setJar(installer.getAbsolutePath());

        String command = delayedFile(DevConstants.LAUNCH4J_DIR).call().getAbsolutePath();
        command += "/launch4j";

        if (Constants.OPERATING_SYSTEM == OS.WINDOWS)
            command += "c.exe";
        else
        {
            final String extraCommand = command;

            Task task = project.getTasks().getByName("extractL4J");
            task.doLast(task1 -> {
                File f = new File(extraCommand);
                if (!f.canExecute())
                {
                    boolean worked = f.setExecutable(true);
                    project.getLogger().debug("Setting file +X {} : {}", worked, f.getPath());
                }
            });
        }

        ext.setLaunch4jCmd(command);

        Task task = project.getTasks().getByName("generateXmlConfig");
        task.dependsOn("packageInstaller", "extractL4J");
        task.getInputs().file(installer);

        String icon = ext.getIcon();
        if (icon == null || icon.isEmpty())
        {
            icon = delayedFile(DevConstants.LAUNCH4J_DIR + "/demo/SimpleApp/l4j/SimpleApp.ico").call().getAbsolutePath();
        }
        icon = new File(icon).getAbsolutePath();
        ext.setIcon(icon);
        ext.setMainClassName(delayedString("{MAIN_CLASS}").call());
    }

    @Override
    protected DelayedFile getDevJson()
    {
        return delayedFile(DevConstants.JSON_DEV);
    }

    @Override
    public void afterEvaluate()
    {
        super.afterEvaluate();

        configureLaunch4J();

        // set obfuscate extras
        Task t = project.getTasks().getByName("obfuscateJar");
        ObfuscateTask obf = ((ObfuscateTask) t);
        obf.setExtraSrg(getExtension().getSrgExtra());
        obf.configureProject(getExtension().getSubprojects());
        obf.configureProject(getExtension().getDirtyProject());

        try
        {
            ExtractTask extractNatives = makeTask("extractNativesNew", ExtractTask.class);
            extractNatives.exclude("META-INF", "META-INF/**", "META-INF/*");
            extractNatives.into(delayedFile(Constants.NATIVES_DIR));

            Copy copyNatives = makeTask("extractNatives", Copy.class);
            copyNatives.from(delayedFile(Constants.NATIVES_DIR));
            copyNatives.exclude("META-INF", "META-INF/**", "META-INF/*");
            copyNatives.into(delayedFile(DevConstants.ECLIPSE_NATIVES));
            copyNatives.dependsOn("extractWorkspace", extractNatives);

            DelayedFile devJson = getDevJson();
            if (devJson == null)
            {
                project.getLogger().info("Dev json not set, could not create native downloads tasks");
                return;
            }

            if (version == null)
            {
                File jsonFile = devJson.call().getAbsoluteFile();
                try
                {
                    version = JsonFactory.loadVersion(jsonFile, jsonFile.getParentFile());
                }
                catch (Exception e)
                {
                    project.getLogger().error("{} could not be parsed", jsonFile);
                    Throwables.throwIfUnchecked(e);
                }
            }

            for (Library lib : version.getLibraries())
            {
                if (lib.natives != null)
                {
                    String path = lib.getPathNatives();
                    String taskName = "downloadNatives-" + lib.getArtifactName().split(":")[1];

                    DownloadTask task = makeTask(taskName, DownloadTask.class);
                    task.setOutput(delayedFile("{CACHE_DIR}/minecraft/" + path));
                    task.setUrl(delayedString(lib.getUrl() + path));

                    extractNatives.from(delayedFile("{CACHE_DIR}/minecraft/" + path));
                    extractNatives.dependsOn(taskName);
                }
            }

        }
        catch (Exception e)
        {
            Throwables.throwIfUnchecked(e);
        }
    }

    @Override
    protected Class<DevExtension> getExtensionClass()
    {
        return DevExtension.class;
    }

    protected DevExtension getOverlayExtension()
    {
        // never happens.
        return null;
    }

    protected String getServerClassPath(File json)
    {
        try
        {
            JsonObject node = JsonParser.parseString(json.getAbsolutePath()).getAsJsonObject();


            StringBuilder buf = new StringBuilder();

            for (JsonElement libElement : node.get("versionInfo").getAsJsonObject().get("libraries").getAsJsonArray())
            {
                JsonObject lib = libElement.getAsJsonObject();

                if (lib.has("serverreq") && lib.get("serverreq").getAsBoolean())
                {
                    String[] pts = lib.get("name").getAsString().split(":");
                    buf.append(String.format("libraries/%s/%s/%s/%s-%s.jar ", pts[0].replace('.', '/'), pts[1], pts[2], pts[1], pts[2]));
                }
            }
            buf.append(delayedString("minecraft_server.{MC_VERSION}.jar").call());
            return buf.toString();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String resolve(String pattern, Project project, DevExtension exten)
    {
        pattern = super.resolve(pattern, project, exten);

        // MCP_DATA_DIR wont be resolved if the data dir doesnt eixts,,, hence...
        pattern = pattern.replace("{MCP_DATA_DIR}", "{FML_CONF_DIR}");

        String version = project.getVersion().toString();
        String mcSafe = exten.getVersion().replace('-', '_');
        if (version.startsWith(mcSafe + "-"))
        {
            version = version.substring(mcSafe.length() + 1);
        }
        pattern = pattern.replace("{VERSION}", version);
        pattern = pattern.replace("{MAIN_CLASS}", exten.getMainClass());
        pattern = pattern.replace("{FML_TWEAK_CLASS}", exten.getTweakClass());
        pattern = pattern.replace("{INSTALLER_VERSION}", exten.getInstallerVersion());
        pattern = pattern.replace("{FML_DIR}", exten.getFmlDir());
        pattern = pattern.replace("{FORGE_DIR}", exten.getForgeDir());
        pattern = pattern.replace("{BUKKIT_DIR}", exten.getBukkitDir());
        pattern = pattern.replace("{FML_CONF_DIR}", exten.getFmlDir() + "/conf");
        return pattern;
    }

    protected static String runGit(final Project project, final File workDir, final String... args)
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        project.exec(new Closure<ExecSpec>(project, project)
        {
            @Override
            public ExecSpec call()
            {
                ExecSpec exec = (ExecSpec) getDelegate();
                exec.setExecutable("git");
                exec.args((Object[]) args);
                exec.setStandardOutput(out);
                exec.setWorkingDir(workDir);
                return exec;
            }
        });

        return out.toString().trim();
    }

    protected boolean hasInstaller()
    {
        return true;
    }
}