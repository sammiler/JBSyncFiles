package com.example.syncfiles;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SyncAction extends AnAction {
    public static class Mapping {
        public String sourceUrl;
        public String targetPath;

        public Mapping(String sourceUrl, String targetPath) {
            this.sourceUrl = sourceUrl;
            this.targetPath = targetPath;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Mapping mapping = (Mapping) o;
            return sourceUrl.equals(mapping.sourceUrl) && targetPath.equals(mapping.targetPath);
        }

        @Override
        public int hashCode() {
            return sourceUrl.hashCode() + targetPath.hashCode();
        }
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            Messages.showErrorDialog("No project is open", "Error");
            return;
        }
        syncFiles(project);
    }

    public void syncFiles(Project project) {
        List<Mapping> mappings = SyncFilesConfig.getInstance(project).getMappings();
        if (mappings.isEmpty()) {
            Messages.showWarningDialog("No mappings configured. Please check settings in 'Settings > SyncFiles Settings'.", "Warning");
            return;
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Syncing GitHub Files", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("Starting sync...");
                try {
                    for (Mapping mapping : mappings) {
                        indicator.setText("Syncing: " + mapping.sourceUrl);
                        Path targetPath = mapping.targetPath.startsWith("/") || mapping.targetPath.contains(":")
                                ? Paths.get(mapping.targetPath)
                                : Paths.get(project.getBasePath(), mapping.targetPath);
                        System.out.println("Target path resolved: " + targetPath);
                        if (mapping.sourceUrl.contains("raw.githubusercontent.com")) {
                            fetchFile(mapping.sourceUrl, targetPath);
                        } else if (mapping.sourceUrl.contains("/tree/")) {
                            fetchDirectory(mapping.sourceUrl, targetPath, project.getBasePath());
                        } else {
                            Messages.showWarningDialog("Unsupported URL format: " + mapping.sourceUrl, "Warning");
                        }
                    }
                    Messages.showInfoMessage("Sync completed successfully!", "Success");
                } catch (Exception ex) {
                    Messages.showErrorDialog("Sync failed: " + ex.getMessage(), "Error");
                    ex.printStackTrace();
                }
            }
        });
    }

    private void fetchFile(String url, Path targetPath) throws IOException, InterruptedException {
        System.out.println("Fetching file: " + url);
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 302) {
            String redirectUrl = response.headers().firstValue("location").orElseThrow(() -> new IOException("No redirect location"));
            System.out.println("Redirecting to: " + redirectUrl);
            fetchFile(redirectUrl, targetPath);
            return;
        }

        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch file, status code: " + response.statusCode());
        }

        Files.createDirectories(targetPath.getParent());
        Files.writeString(targetPath, response.body());
        System.out.println("File saved to: " + targetPath);
    }

    private void fetchDirectory(String repoUrl, Path targetPath, String workspacePath) throws IOException, InterruptedException {
        System.out.println("Original repoUrl: " + repoUrl);
        String normalizedUrl = repoUrl.trim().replaceAll("^https?://(www\\.)?github\\.com/", "https://github.com/");

        if (!normalizedUrl.contains("/tree/")) {
            throw new IllegalArgumentException("Invalid GitHub directory URL, missing /tree/: " + repoUrl);
        }

        String[] parts = normalizedUrl.split("/tree/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid URL format, expected /tree/: " + repoUrl);
        }
        String repoPart = parts[0];
        String branchAndPath = parts[1];
        System.out.println("Repo part: " + repoPart + ", Branch and path: " + branchAndPath);

        String[] pathSegments = branchAndPath.split("/", 2);
        String branch = pathSegments[0];
        String subPath = pathSegments.length > 1 ? java.net.URLDecoder.decode(pathSegments[1], "UTF-8") : "";
        System.out.println("Branch: " + branch + ", Sub-path: " + subPath);

        String repoRoot = repoPart.replace("https://github.com/", "https://codeload.github.com/");
        String zipUrl = String.format("%s/zip/refs/heads/%s", repoRoot, branch);
        System.out.println("Fetching ZIP from: " + zipUrl);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(zipUrl)).build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        System.out.println("HTTP status: " + response.statusCode());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch ZIP, status code: " + response.statusCode());
        }

        Path zipPath = Paths.get(workspacePath, "temp-repo.zip");
        System.out.println("Saving ZIP to: " + zipPath);
        Files.createDirectories(zipPath.getParent());

        // 优化下载
        try (InputStream in = response.body(); FileOutputStream fos = new FileOutputStream(zipPath.toFile())) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }

        if (!Files.exists(zipPath) || Files.size(zipPath) == 0) {
            throw new IOException("Downloaded ZIP is empty or missing: " + zipPath);
        }

        Path tempExtractPath = Paths.get(workspacePath, "temp-extract");
        System.out.println("Extracting to: " + tempExtractPath);
        unzip(zipPath, tempExtractPath);

        Path sourceDir;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempExtractPath)) {
            Path firstDir = stream.iterator().next();
            sourceDir = firstDir;
            System.out.println("Top-level directory: " + sourceDir);
        }

        if (!subPath.isEmpty()) {
            sourceDir = sourceDir.resolve(subPath);
            System.out.println("Resolved sub-path: " + sourceDir);
            if (!Files.exists(sourceDir)) {
                throw new IOException("Sub-path does not exist in ZIP: " + subPath);
            }
        }

        System.out.println("Merging from: " + sourceDir + " to: " + targetPath);
        mergeDirectory(sourceDir, targetPath);

        try {
            Files.deleteIfExists(zipPath);
            Files.walk(tempExtractPath).sorted((p1, p2) -> -p1.compareTo(p2)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    System.err.println("Failed to delete: " + p);
                }
            });
        } catch (IOException e) {
            System.err.println("Cleanup failed: " + e.getMessage());
        }

        System.out.println("Directory synced to: " + targetPath);
    }
    private void unzip(Path zipPath, Path extractPath) throws IOException {
        System.out.println("Unzipping: " + zipPath + " to: " + extractPath);
        Files.createDirectories(extractPath);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = extractPath.resolve(entry.getName());
                System.out.println("Processing ZIP entry: " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            throw new IOException("Failed to unzip file: " + zipPath, e);
        }
    }

    private void mergeDirectory(Path sourceDir, Path targetDir) throws IOException {
        if (!Files.exists(sourceDir)) {
            throw new IOException("Source directory does not exist: " + sourceDir);
        }

        System.out.println("Merging directory: " + sourceDir + " to: " + targetDir);
        Files.createDirectories(targetDir);
        Files.walk(sourceDir).forEach(source -> {
            try {
                Path target = targetDir.resolve(sourceDir.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("Copied: " + target);
                }
            } catch (IOException e) {
                System.err.println("Failed to copy: " + source + " - " + e.getMessage());
            }
        });
    }
}