package io.jenkins.tools.pluginmodernizer.core.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A utility class for fetching the latest JDK releases from the Adoptium GitHub repository.
 */
@SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "false, positive")
public class JdkFetcher {
    private static final Logger LOG = LoggerFactory.getLogger(JdkFetcher.class);

    private static final String GITHUB_API_URL = "https://api.github.com/repos/adoptium";
    private static final String DOWNLOAD_DIR = System.getProperty("user.home") + "/.jdks/";

    /**
     * Gets the path to the JDK directory for the specified JDK version. If the JDK is not already downloaded,
     * it triggers the download and setup process.
     *
     * @param jdkVersion The version of the JDK (e.g., "8").
     * @return The path to the JDK directory.
     * @throws IOException          If an I/O error occurs.
     * @throws InterruptedException If the operation is interrupted.
     */
    public Path getJdkPath(String jdkVersion) throws IOException, InterruptedException {
        Path jdkPath = Paths.get(DOWNLOAD_DIR, "plugin-modernizer-jdk-" + jdkVersion);
        if (Files.notExists(jdkPath)) {
            downloadAndSetupJdk(jdkVersion);
        }
        return jdkPath;
    }

    /**
     * Downloads and extracts the JDK for the specified version. The method determines the appropriate extraction
     * method based on the operating system.
     *
     * @param jdkVersion The version of the JDK (e.g., "8").
     * @throws IOException          If an I/O error occurs.
     * @throws InterruptedException If the operation is interrupted.
     */
    private void downloadAndSetupJdk(String jdkVersion) throws IOException, InterruptedException {
        LOG.info("Downloading the jdk...");
        Path downloadedFile = downloadJdk(jdkVersion);
        LOG.info("Download Successful");
        String os = getOSName();
        LOG.info("Extracting...");
        Path extractionDir = Paths.get(DOWNLOAD_DIR, "plugin-modernizer-jdk-" + jdkVersion);
        Files.createDirectories(extractionDir);
        if (os.contains("windows")) {
            extractZip(downloadedFile, extractionDir);
        } else if (os.contains("linux") || os.contains("mac")) {
            extractTarGz(downloadedFile, extractionDir);
        }
        LOG.info("Extraction successful");
    }

    /**
     * Downloads the JDK for the specified version from the appropriate URL and saves it to the local directory.
     *
     * @param jdkVersion The version of the JDK (e.g., "8").
     * @return The path to the downloaded JDK file.
     * @throws IOException          If an I/O error occurs.
     * @throws InterruptedException If the operation is interrupted.
     */
    @SuppressFBWarnings(value = "URLCONNECTION_SSRF_FD", justification = "false positive")
    private Path downloadJdk(String jdkVersion) throws IOException, InterruptedException {
        String downloadUrl = fetchLatestReleaseUrl(jdkVersion);
        if (downloadUrl != null) {
            Path downloadPath = Paths.get(DOWNLOAD_DIR, "jdk" + jdkVersion + getExtension(downloadUrl));
            Path parentPath = downloadPath.getParent();
            if (parentPath != null) {
                Files.createDirectories(parentPath);
            }

            try (InputStream in = URI.create(downloadUrl).toURL().openStream()) {
                Files.copy(in, downloadPath, StandardCopyOption.REPLACE_EXISTING);
            }
            return downloadPath;
        }
        return null;
    }

    /**
     * Determines the file extension based on the URL.
     *
     * @param url The URL of the JDK download.
     * @return The file extension (e.g., ".zip" or ".tar.gz").
     */
    private String getExtension(String url) {
        return url.toLowerCase().endsWith(".zip") ? ".zip" : ".tar.gz";
    }

    /**
     * Fetches the latest release download URL for a specified JDK version and OS.
     *
     * @param jdkVersion The version of the JDK (e.g., "8").
     * @return The download URL of the latest release, or null if not found.
     * @throws IOException          If an I/O error occurs.
     * @throws InterruptedException If the operation is interrupted.
     */
    private String fetchLatestReleaseUrl(String jdkVersion) throws IOException, InterruptedException {
        String latestUrl = String.format("%s/temurin%s-binaries/releases", GITHUB_API_URL, jdkVersion);
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request =
                HttpRequest.newBuilder().uri(URI.create(latestUrl)).build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            JsonArray releases = JsonParser.parseString(response.body()).getAsJsonArray();
            for (JsonElement releaseElement : releases) {
                JsonObject release = releaseElement.getAsJsonObject();
                JsonArray assets = release.getAsJsonArray("assets");
                String url = getDownloadUrl(assets, jdkVersion);
                if (url != null) {
                    return url;
                }
            }
        } else {
            LOG.error("Failed to fetch releases. HTTP Status Code: {}", response.statusCode());
        }
        return null;
    }

    /**
     * Finds the download URL for the JDK based on the JDK version and operating system.
     *
     * @param assets     A JSON array of assets from a GitHub release.
     * @param jdkVersion The version of the JDK (e.g., "8").
     * @return The download URL if a matching asset is found, otherwise null.
     */
    private String getDownloadUrl(JsonArray assets, String jdkVersion) {
        String jdkFileName = buildJDKFileName(jdkVersion);
        for (JsonElement element : assets) {
            JsonObject asset = element.getAsJsonObject();
            String name = asset.get("name").getAsString();
            if (name.toLowerCase().contains(jdkFileName.toLowerCase())
                    && (name.toLowerCase().endsWith(".zip")
                            || name.toLowerCase().endsWith(".tar.gz"))) {
                return asset.get("browser_download_url").getAsString();
            }
        }
        return null;
    }

    /**
     * Constructs the JDK file name based on the version and operating system.
     *
     * @param jdkVersion The version of the JDK (e.g., "17").
     * @return The constructed JDK file name.
     * @throws IllegalArgumentException If the JDK version or OS is null or empty.
     */
    public String buildJDKFileName(String jdkVersion) {
        String os = getOSName();
        if (jdkVersion == null || jdkVersion.isEmpty() || os.isEmpty()) {
            throw new IllegalArgumentException("JDK version and OS must not be null or empty");
        }

        String normalizedOS = normalizeOS(os);

        return String.format("OpenJDK%sU-jdk_x64_%s_hotspot_%s", jdkVersion, normalizedOS, jdkVersion);
    }

    /**
     * Normalizes the operating system string to match expected formats.
     *
     * @param os The operating system input (e.g., "Windows 11", "Mac OS X", "Linux").
     * @return The normalized OS string (e.g., "windows", "mac", "linux").
     * @throws IllegalArgumentException If the OS is not supported.
     */
    private String normalizeOS(String os) {
        String normalizedOS = os.toLowerCase().trim();
        if (normalizedOS.contains("windows")) {
            return "windows";
        } else if (normalizedOS.contains("mac") || normalizedOS.contains("os x") || normalizedOS.contains("macos")) {
            return "mac";
        } else if (normalizedOS.contains("linux")) {
            return "linux";
        } else {
            throw new IllegalArgumentException("Unsupported OS: " + os);
        }
    }

    /**
     * Gets the operating system name from system properties.
     *
     * @return The operating system name (e.g., "Windows", "Linux", "Mac OS X").
     */
    private String getOSName() {
        return System.getProperty("os.name").toLowerCase();
    }

    /**
     * Extracts a ZIP file to the specified directory without nested directories.
     *
     * @param zipFile     The path to the ZIP file.
     * @param extractionDir The directory to extract the files into.
     * @throws IOException If an I/O error occurs.
     */
    private void extractZip(Path zipFile, Path extractionDir) throws IOException {
        try (ZipInputStream zipIn = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    extractFile(entry.getName(), zipIn, extractionDir);
                }
                zipIn.closeEntry();
            }
        }
    }

    /**
     * Extracts a TAR.GZ file to the specified directory without nested directories.
     *
     * @param tarGzFile   The path to the TAR.GZ file.
     * @param extractionDir The directory to extract the files into.
     * @throws IOException If an I/O error occurs.
     */
    private void extractTarGz(Path tarGzFile, Path extractionDir) throws IOException {
        try (InputStream fileStream = Files.newInputStream(tarGzFile);
                InputStream gzipStream = new java.util.zip.GZIPInputStream(fileStream);
                TarArchiveInputStream tarStream = new TarArchiveInputStream(gzipStream)) {

            TarArchiveEntry entry;
            while ((entry = tarStream.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    extractFile(entry.getName(), tarStream, extractionDir);
                }
            }
        }
    }

    /**
     * Extracts a file from an input stream to the specified directory without nested directories.
     *
     * @param entryName     The name of the entry in the archive.
     * @param inputStream   The input stream from which the file data will be read.
     * @param extractionDir The directory to extract the file into.
     * @throws IOException If an I/O error occurs.
     */
    private void extractFile(String entryName, InputStream inputStream, Path extractionDir) throws IOException {
        Path entryPath = Paths.get(entryName);
        Path strippedPath = entryPath.subpath(1, entryPath.getNameCount());

        Path filePath = extractionDir.resolve(strippedPath);
        Path parentPath = filePath.getParent();
        if (parentPath != null) {
            Files.createDirectories(parentPath);
        }
        Files.copy(inputStream, filePath, StandardCopyOption.REPLACE_EXISTING);
    }
}
