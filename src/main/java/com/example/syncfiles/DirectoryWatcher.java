package com.example.syncfiles;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DirectoryWatcher {
    private final Project project;
    private SyncFilesToolWindowFactory syncFilesToolWindowFactory;
    private final Set<String> watchedDirectories = ConcurrentHashMap.newKeySet(); // 保存绝对路径字符串
    private MessageBusConnection connection;
    private boolean running = false;

    public DirectoryWatcher(Project project) {
        this.project = project;
    }

    public void watchDirectory(Path dir) {
        if (dir == null || dir.toString().isEmpty()) return;

        String pathStr = dir.toAbsolutePath().toString().replace('\\', '/');
        if (watchedDirectories.contains(pathStr)) return;

        watchedDirectories.add(pathStr);
        System.out.println("监控目录: " + pathStr);
    }

    public void refreshButtons(String scriptPath, String pythonPath) throws IOException {
        syncFilesToolWindowFactory.refreshScriptButtons(project, scriptPath, pythonPath, false, false);
    }

    public void startWatching() {
        if (running) return;
        running = true;

        connection = ApplicationManager.getApplication().getMessageBus().connect();
        connection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                for (VFileEvent event : events) {
                    VirtualFile file = null;

                    if (event instanceof VFileContentChangeEvent) {
                        file = ((VFileContentChangeEvent) event).getFile();
                    } else if (event instanceof VFileCreateEvent) {
                        file = ((VFileCreateEvent) event).getFile();
                    } else if (event instanceof VFileDeleteEvent) {
                        file = ((VFileDeleteEvent) event).getFile();
                    }
                    if (file == null) continue;

                    String filePath = file.getPath().replace('\\', '/');
                    boolean match = watchedDirectories.stream().anyMatch(filePath::startsWith);
                    System.out.println("filePath: " + filePath);
                    if (!match && !file.isDirectory()) continue;

                    System.out.println("EventClass " + event.getClass().getSimpleName() + ": " + filePath);

                    if (file.getName().endsWith(".py") || file.isDirectory()) {
                        System.out.println("Python文件变动: " + filePath);
                            ApplicationManager.getApplication().invokeLater(() -> {
                                    System.out.println("ApplicationManager.getApplication().invokeLater");
                                    syncFilesToolWindowFactory.refreshScriptButtons(project, false, false);
                                }
                            );
                    }


                    Util.refreshAllFiles(project);
                }
            }
        });

        System.out.println("DirectoryWatcher 启动完成（使用 IntelliJ VirtualFileManager）");
    }

    public void stop() {
        if (connection != null) {
            connection.disconnect();
        }
        watchedDirectories.clear();
        running = false;
        System.out.println("DirectoryWatcher 停止监听");
    }

    public void setSyncFilesToolWindowFactory(SyncFilesToolWindowFactory factory) {
        this.syncFilesToolWindowFactory = factory;
    }

    public boolean isRunning() {
        return running;
    }
}
