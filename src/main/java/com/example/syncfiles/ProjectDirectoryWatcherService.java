package com.example.syncfiles;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.*;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Project Level Service - one instance per project
public class ProjectDirectoryWatcherService implements Disposable {
    private final Project project;
    private final Set<String> watchedDirectories = ConcurrentHashMap.newKeySet(); // Absolute paths
    private MessageBusConnection connection;
    private boolean running = false;
    private final ConcurrentHashMap<Path, Boolean> watchedDirExists = new ConcurrentHashMap<>();

    // Constructor called by IntelliJ framework
    public ProjectDirectoryWatcherService(Project project) {
        this.project = project;
        System.out.println("ProjectDirectoryWatcherService created for project: " + project.getName());
    }

    public void updateWatchedDirectories() {
        System.out.println("Updating watched directories for project: " + project.getName());
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        String scriptPathStr = config.getPythonScriptPath();
        if (scriptPathStr == null || scriptPathStr.isEmpty())
        {
            scriptPathStr = SyncFilesSettingsConfigurable.applyScriptPath;
        }
        // Clear existing watches before adding new ones
        // Note: Actual stop/start might be needed if VFS listener setup is complex,
        // but for simple path checking, updating the set might suffice.
        // Let's restart the listener for simplicity and robustness.
        stopWatching();
        watchedDirectories.clear(); // Clear the set

        if (scriptPathStr != null && !scriptPathStr.isEmpty())
        {
            Path scriptPath = Paths.get(scriptPathStr);
            addWatch(scriptPath);
            if (Files.exists(scriptPath)) // If it's a file, watch its parent directory
            {
                Path parentDir = scriptPath.getParent();
                if (parentDir != null)
                {
                    addWatch(parentDir);
                }
            }
            else
            {
                System.err.println("Python script path does not exist or is invalid: " + scriptPathStr);
            }
        }
        else
        {
            System.out.println("Python script path is empty, no directory will be watched.");
        }


        startWatching(); // Start with the updated set of directories
    }

    private void addWatch(Path dir) {
        if (dir == null) return;
        String pathStr = dir.toAbsolutePath().normalize().toString().replace('\\', '/');
        watchedDirExists.put(dir, Files.exists(dir));
        if (watchedDirectories.add(pathStr)) {
            System.out.println("[" + project.getName() + "] Added watch for directory: " + pathStr);
        }
    }

    private void startWatching() {
        if (running) {
            System.out.println("[" + project.getName() + "] Watcher already running.");
            return;
        }
        if (watchedDirectories.isEmpty()) {
            System.out.println("[" + project.getName() + "] No directories to watch.");
            return; // Don't start if there's nothing to watch
        }

        System.out.println("[" + project.getName() + "] Starting directory watcher...");
        // Connect to the project's message bus and associate with this service (Disposable)
        connection = project.getMessageBus().connect(this);
        running = true;

        connection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                // Prevent processing if watcher stopped concurrently
                if (!running) return;

                boolean refreshNeeded = false;
                for (VFileEvent event : events) {
                    VirtualFile file = extractFileFromEvent(event);
                    if (file == null) continue;

                    String filePath = file.getPath().replace('\\', '/');

                    // Check if the changed file is within any of the watched directories for THIS project
                    boolean isRelevant = watchedDirectories.stream().anyMatch(watchedDir -> filePath.startsWith(watchedDir + "/"));
                    // Also check if the changed file *is* one of the watched directories itself (e.g., directory rename)
                    isRelevant = isRelevant || watchedDirectories.contains(filePath);
                    // Also check if the changed item's *parent* is watched (for creates/deletes directly under watched folder)
                    if (!isRelevant && file.getParent() != null) {
                        String parentPath = file.getParent().getPath().replace('\\','/');
                        isRelevant = watchedDirectories.contains(parentPath) || watchedDirChanged();
                    }


//                     System.out.println("[" + project.getName() + "] VFS Event: " + event.getClass().getSimpleName() + " Path: " + filePath + " Relevant: " + isRelevant);


                    if (!isRelevant) continue;

                    // Relevant event detected
                    System.out.println("[" + project.getName() + "] Relevant VFS Event: " + event.getClass().getSimpleName() + " Path: " + filePath);


                    // Check if it's a Python file change or a directory change within the watched roots
                    if (file.getName().toLowerCase().endsWith(".py") || file.isDirectory()) {
                        System.out.println("[" + project.getName() + "] Python file or directory changed: " + filePath + ". Triggering refresh.");
                        refreshNeeded = true;
                        // Optimization: break if we already know a refresh is needed
                        break;
                    }
                }

                // ProjectDirectoryWatcherService.java - in the invokeLater block after refreshNeeded is true
                if (refreshNeeded) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!running) { /* ... */ return; }
                        // 使用 Service 的 final project 字段

                        // ★★★ 发布消息，而不是获取 Factory ★★★
                        SyncFilesNotifier publisher = project.getMessageBus().syncPublisher(SyncFilesNotifier.TOPIC);
                        publisher.scriptsChanged();

                        // ---- 不再需要下面的代码 ----
                        // SyncFilesToolWindowFactory factory = Util.getOrInitFactory(projectForRefresh);
                        // if (factory != null) {
                        //     factory.refreshScriptButtons(projectForRefresh, false, false);
                        // } else { ... }
                    });
                }
            }
        });

        System.out.println("[" + project.getName() + "] DirectoryWatcher started. Watching: " + watchedDirectories);
    }

    private void stopWatching() {
        if (!running) return;
        System.out.println("[" + project.getName() + "] Stopping directory watcher...");
        running = false; // Set flag first
        if (connection != null) {
            try {
                connection.disconnect();
                System.out.println("[" + project.getName() + "] MessageBus connection disconnected.");
            } catch (Exception e) {
                System.err.println("[" + project.getName() + "] Error disconnecting MessageBus: " + e.getMessage());
            } finally {
                connection = null;
            }
        }
        // Don't clear watchedDirectories here, they are managed by updateWatchedDirectories
        System.out.println("[" + project.getName() + "] DirectoryWatcher stopped.");
    }


    @Nullable
    private VirtualFile extractFileFromEvent(VFileEvent event) {
        // Simplified extraction logic
        if (event instanceof VFileContentChangeEvent) {
            return event.getFile();
        } else if (event instanceof VFileCreateEvent) {
            // For create, sometimes getFile() is null, try requestor or path
            VirtualFile file = event.getFile();
            if (file == null && event.getRequestor() instanceof VirtualFile) {
                file = (VirtualFile) event.getRequestor(); // Might be parent
            }
            if (file == null && event.getPath() != null) {
                file = LocalFileSystem.getInstance().findFileByPath(event.getPath());
            }
            return file; // Could still be null
        } else if (event instanceof VFileDeleteEvent) {
            return event.getFile(); // File might be invalid after delete, but path is useful
        } else if (event instanceof VFileMoveEvent) {
            return event.getFile(); // Represents the file *after* the move
        } else if (event instanceof VFileCopyEvent) {
            return ((VFileCopyEvent) event).getNewParent().findChild(((VFileCopyEvent) event).getNewChildName());
        } else if (event instanceof VFilePropertyChangeEvent) {
            // Handle renames specifically
            if (VirtualFile.PROP_NAME.equals(((VFilePropertyChangeEvent) event).getPropertyName())) {
                return event.getFile();
            }
        }
        return null; // Default return null if not handled or file is not extractable
    }


    public boolean isRunning() {
        return running;
    }
    private boolean watchedDirChanged()
    {
        boolean changed = false;
        for (ConcurrentHashMap.Entry<Path, Boolean> entry : watchedDirExists.entrySet()) {
            Path path = entry.getKey();
            Boolean exists = entry.getValue();
            boolean actualExists = Files.exists(path); // 检查路径实际是否存在
            if (actualExists != exists) {
                changed = true;
            }
            watchedDirExists.put(path, actualExists);
        }
        return changed;
    }
    // Called when the project is closed
    @Override
    public void dispose() {
        System.out.println("Disposing ProjectDirectoryWatcherService for project: " + project.getName());
        stopWatching();
        watchedDirectories.clear();
    }
}