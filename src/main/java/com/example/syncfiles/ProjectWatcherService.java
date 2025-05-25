package com.example.syncfiles;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.*;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.util.*;
import java.util.stream.Collectors;

public class ProjectWatcherService implements Disposable {
    private static final Logger LOG = Logger.getInstance(ProjectDirectoryWatcherService.class);
    private final Project project;
    private MessageBusConnection connection;
    private boolean isRunning = false;
    private List<WatchEntry> configuredEntries = new ArrayList<>();

    public enum EventType {
        CREATE,          // Corresponds to VFileCreateEvent
        REMOVE,          // Corresponds to VFileDeleteEvent
        MODIFY_CONTENT,  // Corresponds to VFileContentChangeEvent
        MODIFY_PROPERTY, // Corresponds to VFilePropertyChangeEvent
        MOVE,            // Corresponds to VFileMoveEvent
        COPY,            // Corresponds to VFileCopyEvent
        UNKNOWN          // For any other event types or if determination is not possible
    }

    public ProjectWatcherService(Project project) {
        this.project = project;
        LOG.info("ProjectDirectoryWatcherService created for project: " + project.getName());
    }

    public synchronized void updateWatchedDirectories() {
        LOG.info("[" + project.getName() + "] Updating watched Python script directory.");
        configuredEntries.clear();
        stopWatching(); // 停止旧的监听

        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        configuredEntries = config.getWatchEntries();
        if (!isRunning)
            startWatching();
    }

    private synchronized void startWatching() {
        if (isRunning)
            return;

        connection = project.getMessageBus().connect(this);
        isRunning = true;

        connection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            // ... 在 ProjectDirectoryWatcherService.java 的 BulkFileListener.after() 方法内 ...
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                if (!isRunning) return;


                for (VFileEvent event : events) {
                    VirtualFile file = event.getFile();
                    EventType type = mapEventToEventType(event);
                    if (file == null || type == EventType.UNKNOWN) continue;


                    String filePath = file.getPath().replace('\\', '/');
                    filePath = Util.ensureAbsolutePath(project,filePath);

                    String finalFilePath = filePath;
                    List<WatchEntry> watchEntries = configuredEntries.stream().filter(watchEntry ->
                            {
                                if (finalFilePath != null && finalFilePath.equals(Util.ensureAbsolutePath(project,watchEntry.watchedPath))) return true;
                                if (finalFilePath != null && finalFilePath.contains(Objects.requireNonNull(Util.ensureAbsolutePath(project, watchEntry.watchedPath))) && type == EventType.REMOVE)
                                    return true;
                                if (finalFilePath != null && Objects.requireNonNull(Util.ensureAbsolutePath(project, watchEntry.watchedPath)).contains(finalFilePath) && type == EventType.REMOVE) {
                                    return true;
                                }
                                return false;
                            }
                    ).toList();
                    if (!watchEntries.isEmpty()) {
                        String eventType;
                        if (type == EventType.CREATE) eventType = "Change New";
                        else if (type == EventType.REMOVE || type == EventType.MOVE) eventType = "Change Del";
                        else if (type == EventType.MODIFY_CONTENT) eventType = "Change Mod";
                        else
                            eventType = "UnKnow";
                        for (WatchEntry watchEntry : watchEntries) {
                            project.getMessageBus().syncPublisher(FilesChangeNotifier.TOPIC).watchFileChanged(watchEntry.onEventScript, eventType, watchEntry.watchedPath);
                        }

                    }

                }
            }
        });
    }

    private synchronized void stopWatching() {
        if (!isRunning) return;
        LOG.info("[" + project.getName() + "] Stopping ProjectDirectoryWatcherService for script dir.");
        isRunning = false;
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Exception e) {
                LOG.error("[" + project.getName() + "] Error disconnecting MessageBus: " + e.getMessage(), e);
            } finally {
                connection = null;
            }
        }
        // watchedPythonScriptDir 保留，updateWatchedDirectories 会更新它
        LOG.info("[" + project.getName() + "] ProjectDirectoryWatcherService for script dir stopped.");
    }

    @NotNull // Assuming UNKNOWN is returned for unhandled cases
    public EventType mapEventToEventType(VFileEvent event) {
        if (event instanceof VFileCreateEvent) {
            // The event itself signifies creation, regardless of whether getFile() is null.
            return EventType.CREATE;
        } else if (event instanceof VFileDeleteEvent) {
            return EventType.REMOVE;
        } else if (event instanceof VFileContentChangeEvent) {
            return EventType.MODIFY_CONTENT;
        } else if (event instanceof VFilePropertyChangeEvent) {
            // VFilePropertyChangeEvent can include renaming, permissions change, etc.
            return EventType.MODIFY_PROPERTY;
        } else if (event instanceof VFileMoveEvent) {
            return EventType.MOVE;
        } else if (event instanceof VFileCopyEvent) {
            // A copy results in a new file being created.
            return EventType.COPY;
        }
        // For any other VFileEvent subtypes not explicitly handled
        return EventType.UNKNOWN;
    }

    @Override
    public void dispose() {
        LOG.info("Disposing ProjectDirectoryWatcherService for project: " + project.getName());
        stopWatching();
    }
}