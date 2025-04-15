package com.example.syncfiles;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;



import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

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
        try (Stream<Path> pathStream = Files.walk(dir)) {
            pathStream
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
    }

    public void refreshButtons(String scriptPath,String pythonPath) throws IOException
    {
        syncFilesToolWindowFactory.refreshScriptButtons(project,scriptPath,pythonPath,false,false);
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
                        if (kind == StandardWatchEventKinds.OVERFLOW) {
                            continue; // 跳过无效事件
                        }

                        @SuppressWarnings("unchecked")
                        WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;

                        Path fileName = pathEvent.context();
                        Path changedPath = dir.resolve(fileName);

                        System.out.println(kind.name() + ": " + changedPath);
                        if (fileName.toString().endsWith(".py")) {
                            ApplicationManager.getApplication().invokeLater(() ->
                                    syncFilesToolWindowFactory.refreshScriptButtons(project,false,false)
                            );
                        }

                        Util.refreshAllFiles(project);
                    }
                    boolean valid = key.reset();
                    if (!valid) {
                        keys.remove(key);
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