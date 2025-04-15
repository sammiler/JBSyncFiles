package com.example.syncfiles;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.jetbrains.rd.generator.nova.INullable;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import static java.nio.file.StandardWatchEventKinds.*;

public class DirectoryWatcher {
    private final WatchService watchService;
    private final Map<WatchKey, Path> keys = new HashMap<>();
    private final Project project;
    private SyncFilesToolWindowFactory syncFilesToolWindowFactory;
    private Boolean running = false;

    public DirectoryWatcher(Project project) throws IOException {
        this.project = project;
        this.watchService = FileSystems.getDefault().newWatchService();
    }

    public void watchDirectory(Path dir) throws IOException {
        Files.walk(dir)
                .filter(Files::isDirectory)
                .forEach(subDir -> {
                    try {
                        WatchKey key = subDir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
                        keys.put(key, subDir);
                        System.out.println("监控目录: " + subDir);
                    } catch (IOException e) {
                        System.err.println("无法注册目录: " + subDir);
                    }
                });
    }
    public void refreshWindow(String scriptPath,String pythonPath) throws IOException
    {
        syncFilesToolWindowFactory.refreshScriptButtons(project,scriptPath,pythonPath);
    }
    public void startWatching() {
        if (running) {
            return;
        }
        running = true;
        new Thread(() -> {
            while (true) {
                try {
                    WatchKey key = watchService.take();
                    Path dir = keys.get(key);
                    if (dir == null) {
                        continue;
                    }
                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        Path fileName = ((WatchEvent<Path>) event).context();
                        Path changedPath = dir.resolve(fileName);
                        System.out.println(kind.name() + ": " + changedPath);
                        if (changedPath.toString().endsWith(".py")) {
                            ApplicationManager.getApplication().invokeLater(() -> {
                                syncFilesToolWindowFactory.refreshScriptButtons(project);
                            });
                        }
                        // 触发 IntelliJ 目录刷新
                        ApplicationManager.getApplication().invokeLater(() -> {
                            project.getBaseDir().refresh(false, true);
                        });

                    }
                    boolean valid = key.reset();
                    if (!valid) {
                        keys.remove(key);
                        if (keys.isEmpty()) {
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "DirectoryWatcher").start();
    }

    public void stop() throws IOException {
        watchService.close();
    }

    public void setSyncFilesToolWindowFactory(SyncFilesToolWindowFactory syncFilesToolWindowFactory) {
        this.syncFilesToolWindowFactory = syncFilesToolWindowFactory;
    }
}