package com.example.syncfiles;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.*;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DirectoryWatcher {
    private final Project project;
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
        SyncFilesToolWindowFactory factory = Util.getOrInitFactory(project);
        if (factory == null) return;
        factory.refreshScriptButtons(project, scriptPath, pythonPath, false, false);
    }

    public void startWatching() {
        if (running) return;
        running = true;

        connection = ApplicationManager.getApplication().getMessageBus().connect();
        connection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                for (VFileEvent event : events) {
                    VirtualFile file = extractFileFromEvent(event);
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
                                    SyncFilesToolWindowFactory factory = Util.getOrInitFactory(project);
                                    if (factory != null)
                                            factory.refreshScriptButtons(project, false, false);
                                }
                            );
                    }


                    Util.refreshAllFiles(project);
                }
            }
        });

        System.out.println("DirectoryWatcher 启动完成（使用 IntelliJ VirtualFileManager）");
    }


    @Nullable
    private VirtualFile extractFileFromEvent(VFileEvent event) {
        if (event instanceof VFileContentChangeEvent) {
            return ((VFileContentChangeEvent) event).getFile();
        } else if (event instanceof VFileCreateEvent) {
            return ((VFileCreateEvent) event).getFile();
        } else if (event instanceof VFileDeleteEvent) {
            return ((VFileDeleteEvent) event).getFile();
        } else if (event instanceof VFileMoveEvent) {
            return ((VFileMoveEvent) event).getFile();
        } else if (event instanceof VFilePropertyChangeEvent) {
            VFilePropertyChangeEvent e = (VFilePropertyChangeEvent) event;
            // 这里只处理 name 属性的变化（重命名）
            if ("name".equals(e.getPropertyName())) {
                return e.getFile();
            }
        }
        return null;
    }

    public void stop() {
        if (connection != null) {
            connection.disconnect();
        }
        watchedDirectories.clear();
        running = false;
        System.out.println("DirectoryWatcher 停止监听");
    }



    public boolean isRunning() {
        return running;
    }
}
