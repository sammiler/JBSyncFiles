package com.example.syncfiles;

import com.example.syncfiles.notifiers.FileDownloadFinishedNotifier;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SyncAction extends AnAction {

    private boolean workflowCall = false;
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT; // Background thread is fine for update check
    }

    // REMOVED: No static watcher needed
    // public static DirectoryWatcher directoryWatcher = null;

    public SyncAction() {
        super("Sync Files");
    }
    public SyncAction(boolean workflowCall)
    {
        this.workflowCall = workflowCall;
    }
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            Messages.showErrorDialog("No active project found.", "Error");
            return;
        }
        syncFiles(project);
    }

    // Method remains mostly the same, but no longer interacts with a static watcher
    public void syncFiles(Project project) {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        List<Mapping> mappings = config.getMappings();

        if (mappings.isEmpty()) {
            Messages.showWarningDialog(project, "No mappings configured. Please check 'Settings > SyncFiles Settings'.", "Warning");
            return;
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Syncing GitHub Files", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false); // Allow progress reporting
                indicator.setText("Starting synchronization...");
                int count = 0;
                final int total = mappings.size();

                try {
                    for (Mapping mapping : mappings) {
                        count++;
                        indicator.setFraction((double) count / total);
                        String shortUrl = mapping.sourceUrl.length() > 50 ? mapping.sourceUrl.substring(0, 47) + "..." : mapping.sourceUrl;
                        indicator.setText(String.format("Syncing (%d/%d): %s", count, total, shortUrl));

                        if (indicator.isCanceled()) {
                            break;
                        }

                        Path targetPath = resolveTargetPath(project, mapping.targetPath);
                        System.out.println("Target path resolved to: " + targetPath);

                        if (mapping.sourceUrl.contains("raw.githubusercontent.com") || mapping.sourceUrl.matches("https://github.com/.+/.+/blob/.+")) {
                            // Handle raw links or direct blob links which often redirect to raw
                            String rawUrl = mapping.sourceUrl.replace("/blob/", "/raw/"); // Convert blob to raw just in case
                            fetchFile(rawUrl, targetPath, indicator);
                        } else if (mapping.sourceUrl.contains("/tree/")) {
                            fetchDirectory(mapping.sourceUrl, targetPath, project.getBasePath(), indicator);
                        } else {
                            String message = "Unsupported URL format: " + mapping.sourceUrl + "\nSupports raw URLs, .../tree/... directory URLs, or .../blob/... file URLs.";
                            System.err.println(message);
                            ApplicationManager.getApplication().invokeLater(() ->
                                    Messages.showWarningDialog(project, message, "Warning")
                            );
                        }
                    }

                    // Refresh VFS - still useful after downloads
                    Util.refreshAllFiles(project);

                    // Watcher update is handled by settings changes or startup, not needed here typically.
                    // If a download *creates* a directory that *should* be watched based on config,
                    // then maybe trigger an update, but usually config defines watches.
                    // Example: project.getService(ProjectDirectoryWatcherService.class).updateWatchedDirectories();

                    if (indicator.isCanceled()) {
                        indicator.setText("Synchronization canceled.");
                    } else {
                        indicator.setText("Synchronization complete.");
                    }
                    if (workflowCall)
                    {
                        ApplicationManager.getApplication().getMessageBus()
                                .syncPublisher(FileDownloadFinishedNotifier.TOPIC)
                                .downloadFinished();
                    }


                } catch (Exception ex) {
                    final String errorMessage = "Synchronization failed: " + ex.getMessage();
                    System.err.println(errorMessage);
                    ex.printStackTrace(); // Log full stack trace for debugging
                    ApplicationManager.getApplication().invokeLater(() -> {
                        Messages.showErrorDialog(project, errorMessage, "Error");
                    });
                    indicator.setText("Synchronization failed.");
                }
            }
        });
    }

    // Helper to resolve target path consistently
    private Path resolveTargetPath(Project project, String targetPathString) {
        Path targetPath;
        if (targetPathString == null || targetPathString.trim().isEmpty()) {
            throw new IllegalArgumentException("Target path cannot be empty.");
        }
        targetPathString = targetPathString.trim();
        // Check if absolute (Windows C:\ or Unix /)
        if (Paths.get(targetPathString).isAbsolute()) {
            targetPath = Paths.get(targetPathString);
        } else {
            // Relative path - resolve against project base path
            String projectBasePath = project.getBasePath();
            if (projectBasePath == null) {
                throw new IllegalStateException("Project base path is null, cannot resolve relative target path.");
            }
            targetPath = Paths.get(projectBasePath, targetPathString);
        }
        // Normalize the path (e.g., remove ../)
        return targetPath.normalize();
    }


    // Pass indicator to potentially cancel downloads
    private void fetchFile(String url, Path targetPath, ProgressIndicator indicator) throws IOException, InterruptedException {
        System.out.println("Fetching file: " + url);
        indicator.setText2("Downloading: " + url); // More detailed progress

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL) // Follow redirects automatically
                .build();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            // Try reading error body if available
            String errorBody = "";
            try (InputStream errorStream = response.body()) { // response.body() gives the stream even for errors sometimes
                if (errorStream != null) {
                    errorBody = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (IOException readError) { /* Ignore if can't read error body */ }
            throw new IOException("Failed to fetch file. Status: " + response.statusCode() + "\nURL: " + url + "\nResponse: " + errorBody);
        }

        // Ensure parent directory exists
        Files.createDirectories(targetPath.getParent());

        // Download the file content
        try (InputStream inputStream = response.body();
             OutputStream outputStream = Files.newOutputStream(targetPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                if (indicator.isCanceled()) {
                    throw new IOException("Download cancelled by user.");
                }
                outputStream.write(buffer, 0, bytesRead);
                // Optional: Update indicator progress based on Content-Length if available
            }
        }
        indicator.setText2(""); // Clear detailed progress
        System.out.println("File saved to: " + targetPath);
    }

    private void fetchDirectory(String repoUrl, Path targetPath, String workspacePath, ProgressIndicator indicator) throws IOException, InterruptedException {
        System.out.println("Original repoUrl: " + repoUrl);
        indicator.setText2("Parsing GitHub URL...");
        String normalizedUrl = repoUrl.trim().replaceAll("^https?://(www\\.)?github\\.com/", "https://github.com/");

        if (!normalizedUrl.contains("/tree/")) {
            throw new IllegalArgumentException("Invalid GitHub directory URL. Must contain '/tree/'. URL: " + repoUrl);
        }

        String[] parts = normalizedUrl.split("/tree/", 2); // Limit split to 2 parts
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid URL format. Expected format like '.../tree/branch/path'. URL: " + repoUrl);
        }
        String repoPart = parts[0];
        String branchAndPath = parts[1];
        System.out.println("Repo part: " + repoPart + ", Branch/Path part: " + branchAndPath);

        String[] pathSegments = branchAndPath.split("/", 2); // Split branch from the rest of the path
        String branch = pathSegments[0];
        String subPath = pathSegments.length > 1 ? java.net.URLDecoder.decode(pathSegments[1], StandardCharsets.UTF_8) : "";
        // Normalize subPath: remove leading/trailing slashes, replace backslashes
        subPath = subPath.replaceAll("^/|/$", "").replace('\\', '/');
        System.out.println("Branch: " + branch + ", SubPath in repo: " + subPath);

        String repoApiBase = repoPart.replace("https://github.com/", "https://api.github.com/repos/");
        String zipUrl = String.format("%s/zipball/%s", repoApiBase, branch); // Use zipball API endpoint
        System.out.println("Fetching ZIP from API: " + zipUrl);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS) // Aggressively follow redirects, GitHub API might use them
                .build();
        // GitHub API often requires a User-Agent
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(zipUrl))
                .header("User-Agent", "IntelliJ-SyncFiles-Plugin") // Be polite
                .header("Accept", "application/vnd.github.v3+json") // Standard API header, though zipball might ignore
                .build();

        indicator.setText2("Downloading repository ZIP for branch: " + branch);
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        System.out.println("HTTP Status: " + response.statusCode());

        if (response.statusCode() != 200) {
            // Try reading error body
            String errorBody = "";
            try (InputStream errorStream = response.body()) { // response.body() gives the stream even for errors sometimes
                if (errorStream != null) {
                    errorBody = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (IOException readError) { /* Ignore */ }
            throw new IOException("Failed to fetch ZIP from API. Status: " + response.statusCode() + "\nURL: " + zipUrl + "\nResponse: " + errorBody);
        }

        // Define temporary paths within the workspace or system temp dir
        Path tempDir = workspacePath != null ? Paths.get(workspacePath, ".syncfiles-temp") : Files.createTempDirectory("syncfiles-");
        Path zipPath = tempDir.resolve("repo-" + branch + ".zip");
        Path tempExtractPath = tempDir.resolve("extract");

        System.out.println("Saving ZIP to: " + zipPath);
        Files.createDirectories(zipPath.getParent());

        try (InputStream in = response.body();
             OutputStream fos = Files.newOutputStream(zipPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                if (indicator.isCanceled()) throw new IOException("Download cancelled.");
                fos.write(buffer, 0, bytesRead);
            }
        }

        if (!Files.exists(zipPath) || Files.size(zipPath) == 0) {
            throw new IOException("Downloaded ZIP file is empty or missing: " + zipPath);
        }

        System.out.println("Unzipping to: " + tempExtractPath);
        indicator.setText2("Extracting files...");
        // Clean extraction target first
        deleteDirectoryRecursively(tempExtractPath);
        unzip(zipPath, tempExtractPath, indicator);

        // Find the root directory *inside* the extracted zip (usually named user-repo-commitsha)
        Path extractedRootDir;
        try (Stream<Path> stream = Files.list(tempExtractPath)) {
            extractedRootDir = stream.filter(Files::isDirectory).findFirst()
                    .orElseThrow(() -> new IOException("Could not find root directory within the extracted ZIP contents at " + tempExtractPath));
            System.out.println("Detected extracted root directory: " + extractedRootDir);
        }

        Path sourceDir = extractedRootDir;
        if (!subPath.isEmpty()) {
            sourceDir = extractedRootDir.resolve(subPath);
            System.out.println("Resolved source subPath to: " + sourceDir);
            if (!Files.exists(sourceDir)) {
                // Log available directories for debugging
                try (Stream<Path> dirContents = Files.walk(extractedRootDir, 2)) { // Walk 2 levels deep
                    System.out.println("Available contents in " + extractedRootDir + ":");
                    dirContents.forEach(p -> System.out.println("  " + extractedRootDir.relativize(p)));
                } catch (IOException e) { /* Ignore logging error */ }
                throw new IOException("SubPath '" + subPath + "' does not exist within the downloaded repository branch.");
            }
        }

        System.out.println("Merging from: " + sourceDir + " to target: " + targetPath);
        indicator.setText2("Merging files into target directory...");
        mergeDirectory(sourceDir, targetPath, indicator);

        // Cleanup
        indicator.setText2("Cleaning up temporary files...");
        try {
            deleteDirectoryRecursively(tempDir);
            System.out.println("Cleaned up temporary directory: " + tempDir);
        } catch (IOException e) {
            System.err.println("Failed to clean up temporary directory: " + tempDir + " - Error: " + e.getMessage());
            // Mark for deletion on exit? Or just log the error.
        }
        indicator.setText2(""); // Clear detail text
        System.out.println("Directory synced to: " + targetPath);
    }

    private void unzip(Path zipPath, Path extractPath, ProgressIndicator indicator) throws IOException {
        System.out.println("Unzipping: " + zipPath + " to: " + extractPath);
        Files.createDirectories(extractPath); // Ensure target exists

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (indicator.isCanceled()) throw new IOException("Unzip cancelled.");

                // Sanitize entry name to prevent path traversal vulnerabilities ( যদিও github zip should be safe)
                Path entryPath = extractPath.resolve(entry.getName()).normalize();
                if (!entryPath.startsWith(extractPath.normalize())) {
                    throw new IOException("Invalid ZIP entry path (path traversal attempt): " + entry.getName());
                }
                // System.out.println("Processing ZIP entry: " + entry.getName() + " -> " + entryPath); // Verbose logging

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    // Ensure parent dir exists for the file
                    Files.createDirectories(entryPath.getParent());
                    try (OutputStream fos = Files.newOutputStream(entryPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            if (indicator.isCanceled()) throw new IOException("Unzip cancelled.");
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private void mergeDirectory(Path source, Path target, ProgressIndicator indicator) throws IOException {
        // System.out.println("Merging source: " + source + " -> target: " + target); // Verbose
        if (indicator.isCanceled()) throw new IOException("Merge cancelled.");

        if (Files.isDirectory(source)) {
            // Create target directory if it doesn't exist
            Files.createDirectories(target); // Safe to call even if exists

            // Recursively merge contents
            try (Stream<Path> stream = Files.list(source)) {
                List<Path> entries = stream.toList(); // Collect to avoid issues with stream closing during recursion
                for (Path entry : entries) {
                    mergeDirectory(entry, target.resolve(entry.getFileName()), indicator);
                }
            }
        } else if (Files.isRegularFile(source)) {
            // Handle file merge: copy if target doesn't exist or if content differs
            if (!Files.exists(target) || !filesAreIdentical(source, target)) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                // System.out.println("Copied/Updated file: " + target);
            } else {
                // System.out.println("Skipped identical file: " + target);
            }
        }
        // Ignore other file types like symlinks for simplicity, or handle explicitly if needed
    }


    private boolean filesAreIdentical(Path source, Path target) throws IOException {
        // Check size first for quick rejection - this is efficient.
        long sourceSize = Files.size(source);
        long targetSize = Files.size(target);
        if (sourceSize != targetSize) {
            System.out.println("Files differ in size: " + source + " (" + sourceSize + ") vs " + target + " (" + targetSize + ")");
            return false;
        }

        // If files are empty, they are identical.
        if (sourceSize == 0) {
            System.out.println("Files are both empty, considered identical: " + source);
            return true;
        }

        // Proceed with byte-by-byte comparison for non-empty files of the same size.
        try (InputStream sourceStream = Files.newInputStream(source);
             InputStream targetStream = Files.newInputStream(target)) {

            byte[] sourceBuffer = new byte[8192]; // Use a reasonably sized buffer
            byte[] targetBuffer = new byte[8192]; // Use a separate buffer for target stream

            while (true) {
                int sourceRead = sourceStream.read(sourceBuffer);
                int targetRead = targetStream.read(targetBuffer); // Read into the second buffer

                // Check if the number of bytes read differs.
                // This also catches the case where one stream ends (-1) while the other hasn't.
                if (sourceRead != targetRead) {
                    System.out.println("Files differ: read count mismatch (" + sourceRead + " vs " + targetRead + ") for " + source);
                    return false;
                }

                // Check if *both* streams reached the end simultaneously in this read cycle.
                // If sourceRead is -1, targetRead must also be -1 due to the check above.
                if (sourceRead == -1) {
                    System.out.println("Files identical (reached EOF simultaneously): " + source);
                    return true; // End of both streams reached, files are identical.
                }

                // If we are here, sourceRead == targetRead and both are >= 0.
                // Compare the actual bytes read in this chunk.
                // Pass the actual number of bytes read (sourceRead) as the length/toIndex.
                if (!Arrays.equals(sourceBuffer, 0, sourceRead, targetBuffer, 0, targetRead)) {
                    System.out.println("Files differ: content mismatch in chunk for " + source);
                    return false; // Content differs within the chunk.
                }

                // Chunk is identical, continue to the next iteration.
            }
        } catch (IOException e) {
            // Log the error and rethrow or return false, depending on desired behavior
            System.err.println("IOException during file comparison for " + source + " and " + target + ": " + e.getMessage());
            throw e; // Rethrowing is often appropriate
        }
    }

    // Helper for recursive directory deletion
    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder()) // Delete contents before directory
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException e) {
                                // Log or handle deletion errors, e.g., file lock
                                System.err.println("Failed to delete path during cleanup: " + p + " - Error: " + e.getMessage());
                            }
                        });
            }
        }
    }


    @Override
    public void update(@NotNull AnActionEvent e) {
        // Action is enabled if there is an open project
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);
    }
}