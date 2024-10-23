package net.minecraftforge.gradle.tasks;

import com.google.common.io.ByteStreams;
import net.minecraftforge.gradle.FileUtils;
import net.minecraftforge.gradle.StringUtils;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.delayed.DelayedString;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@CacheableTask
public class ObtainFernFlowerTask extends DefaultTask {
    private final boolean isOffline = getProject().getGradle().getStartParameter().isOffline();
    @Input
    private DelayedString mcpUrl;

    @OutputFile
    private DelayedFile ffJar;

    @TaskAction
    public void doTask() throws IOException, URISyntaxException {
        if (isOffline) {
            getLogger().error("Offline mode! not downloading Fernflower!");
            this.setDidWork(false);
            return;
        }
        File ff = getFfJar();
        String url = getMcpUrl();

        getLogger().debug("Downloading " + url);
        getLogger().debug("Fernflower output location " + ff);

        HttpURLConnection connect = (HttpURLConnection) new URI(url).toURL().openConnection();
        connect.setRequestProperty("User-Agent", Constants.USER_AGENT);
        connect.setInstanceFollowRedirects(true);

        final ZipInputStream zin = new ZipInputStream(connect.getInputStream());
        ZipEntry entry;

        while ((entry = zin.getNextEntry()) != null) {
            if (StringUtils.lower(entry.getName()).endsWith("fernflower.jar")) {
                ff.getParentFile().mkdirs();
                FileUtils.updateDate(ff);
                Files.write(ff.toPath(), ByteStreams.toByteArray(zin));
            }
        }

        zin.close();

        getLogger().info("Download and Extraction complete");
    }

    public String getMcpUrl() {
        return mcpUrl.call();
    }

    public void setMcpUrl(DelayedString mcpUrl) {
        this.mcpUrl = mcpUrl;
    }

    public File getFfJar() {
        return ffJar.call();
    }

    public void setFfJar(DelayedFile ffJar) {
        this.ffJar = ffJar;
    }
}
