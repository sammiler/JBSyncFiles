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
import com.intellij.util.EnvironmentUtil;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class FileChangeEventWatcherService implements Disposable {
    private static final Logger LOG = Logger.getInstance(FileChangeEventWatcherService.class);
    private final Project project;
    private MessageBusConnection connection;
    private boolean isRunning = false;
    private final List<ActiveWatch> activeWatchers = new ArrayList<>();
    private final ExecutorService scriptExecutorService = Executors.newCachedThreadPool(); // 用于后台执行脚本

    public FileChangeEventWatcherService(Project project) {
        this.project = project;
        LOG.info("FileChangeEventWatcherService created for project: " + project.getName());
    }

    public synchronized void updateWatchersFromConfig() {
        LOG.info("[" + project.getName() + "] Updating watchers from config.");
        stopWatching(); // 停止当前的监听
        activeWatchers.clear();

        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        String pythonExecutable = config.getPythonExecutablePath();
        String pythonScriptBaseDir = config.getPythonScriptPath();

        if (pythonExecutable == null || pythonExecutable.trim().isEmpty()) {
            LOG.warn("[" + project.getName() + "] Python executable is not configured. File event watching will be disabled.");
            return;
        }
        if (!Files.isRegularFile(Paths.get(pythonExecutable))) {
            LOG.warn("[" + project.getName() + "] Python executable path is invalid: " + pythonExecutable + ". File event watching will be disabled.");
            return;
        }


        List<WatchEntry> configuredEntries = config.getWatchEntries();
        if (configuredEntries.isEmpty()) {
            LOG.info("[" + project.getName() + "] No watch entries configured.");
            return;
        }

        for (WatchEntry entry : configuredEntries) {
            String watchedPathStr = entry.watchedPath;
            String scriptToRunRelative = entry.onEventScript;

            if (watchedPathStr == null || watchedPathStr.trim().isEmpty() ||
                    scriptToRunRelative == null || scriptToRunRelative.trim().isEmpty()) {
                LOG.warn("[" + project.getName() + "] Skipping invalid watch entry: " + entry);
                continue;
            }

            Path fullScriptPath;
            if (Paths.get(scriptToRunRelative).isAbsolute()) {
                fullScriptPath = Paths.get(scriptToRunRelative);
            } else if (pythonScriptBaseDir != null && !pythonScriptBaseDir.trim().isEmpty()) {
                fullScriptPath = Paths.get(pythonScriptBaseDir, scriptToRunRelative);
            } else {
                LOG.warn("[" + project.getName() + "] Cannot resolve relative script path '" + scriptToRunRelative + "' because Python script base directory is not set. Skipping watch entry.");
                continue;
            }

            if (!Files.isRegularFile(fullScriptPath)) {
                LOG.warn("[" + project.getName() + "] Script for watch entry does not exist or is not a file: " + fullScriptPath + ". Skipping.");
                continue;
            }

            // 规范化被监控路径
            String normalizedWatchedPath = Paths.get(watchedPathStr.replace('\\','/')).normalize().toString();
            activeWatchers.add(new ActiveWatch(normalizedWatchedPath, fullScriptPath.toString().replace('\\', '/')));
            LOG.info("[" + project.getName() + "] Added watch for: " + normalizedWatchedPath + " -> " + fullScriptPath);
        }

        if (!activeWatchers.isEmpty()) {
            startWatching();
        }
    }

    private synchronized void startWatching() {
        if (isRunning || activeWatchers.isEmpty()) {
            if (isRunning) LOG.info("[" + project.getName() + "] Watcher already running.");
            return;
        }

        LOG.info("[" + project.getName() + "] Starting FileChangeEventWatcherService...");
        connection = project.getMessageBus().connect(this); // 关联到 service 的生命周期
        isRunning = true;

        connection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                if (!isRunning) return;

                for (VFileEvent event : events) {
                    processVfsEvent(event);
                }
            }
        });
        LOG.info("[" + project.getName() + "] FileChangeEventWatcherService started. Watching " + activeWatchers.size() + " configurations.");
    }

    private void processVfsEvent(@NotNull VFileEvent event) {
        String eventType = null;
        VirtualFile affectedVFile = null;
        String affectedPath = null; // 事件影响的绝对路径

        // 从事件中提取类型和路径
        if (event instanceof VFileCreateEvent) {
            eventType = "Change New";
            affectedVFile = ((VFileCreateEvent) event).getFile(); // 新创建的文件/目录
            if (affectedVFile != null) {
                affectedPath = affectedVFile.getPath();
            } else { // 有时 getFile() 为空，尝试从 path 构建
                affectedPath = ((VFileCreateEvent) event).getPath();
            }
        } else if (event instanceof VFileContentChangeEvent) {
            eventType = "Change Mod";
            affectedVFile = event.getFile();
            if (affectedVFile != null) affectedPath = affectedVFile.getPath();
        } else if (event instanceof VFileDeleteEvent) {
            eventType = "Change Del";
            affectedVFile = event.getFile(); // 文件可能已无效，但路径仍有用
            if (affectedVFile != null) affectedPath = affectedVFile.getPath();
        } else if (event instanceof VFileMoveEvent) {
            // 移动事件比较复杂，可能同时触发旧路径的删除和新路径的创建
            // 这里简单处理为修改事件，并使用移动后的路径
            eventType = "Change Mod"; // 或根据需要处理为 Delete + New
            affectedVFile = event.getFile(); // 移动后的文件
            if (affectedVFile != null) affectedPath = affectedVFile.getPath();
        } else if (event instanceof VFileCopyEvent) {
            eventType = "Change New"; // 复制等同于创建新文件
            affectedVFile = ((VFileCopyEvent) event).findCreatedFile();
            if (affectedVFile != null) affectedPath = affectedVFile.getPath();
        } else if (event instanceof VFilePropertyChangeEvent) {
            if (VirtualFile.PROP_NAME.equals(((VFilePropertyChangeEvent) event).getPropertyName())) {
                eventType = "Change Mod"; // 重命名视为修改 (或者 Delete old, New new)
                // (VFilePropertyChangeEvent)event).getOldValue() 和 .getNewValue() 可以获取旧名和新名
                affectedVFile = event.getFile(); // 重命名后的文件
                if (affectedVFile != null) affectedPath = affectedVFile.getPath();
            }
        }

        if (eventType == null || affectedPath == null) {
            return; // 不处理此事件
        }

        final String finalAffectedPath = Paths.get(affectedPath.replace('\\','/')).normalize().toString();
        final String finalEventType = eventType;

        // 检查是否与任何 activeWatchers 匹配
        List<ActiveWatch> matchedWatchers = activeWatchers.stream()
                .filter(watch -> finalAffectedPath.equals(watch.watchedPath) || // 精确匹配文件/目录本身
                        finalAffectedPath.startsWith(watch.watchedPath + "/") || // 匹配子文件/目录
                        (Paths.get(finalAffectedPath).getParent() != null && // 匹配父目录（例如在被监控目录下创建/删除文件）
                                Paths.get(finalAffectedPath).getParent().normalize().toString().equals(watch.watchedPath))
                )
                .collect(Collectors.toList());

        if (!matchedWatchers.isEmpty()) {
            LOG.info("[" + project.getName() + "] Relevant VFS Event: " + event.getClass().getSimpleName() +
                    " | Type: " + finalEventType + " | Path: " + finalAffectedPath);

            for (ActiveWatch watcher : matchedWatchers) {
                LOG.info("[" + project.getName() + "] Matched watch: " + watcher.watchedPath + " -> " + watcher.scriptToRun);
                executeWatchedScript(watcher.scriptToRun, finalEventType, finalAffectedPath);
            }
        }
    }

    private void executeWatchedScript(String scriptPath, String eventType, String affectedFilePath) {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        String pythonExecutable = config.getPythonExecutablePath();

        if (pythonExecutable == null || pythonExecutable.trim().isEmpty()) {
            LOG.error("[" + project.getName() + "] Cannot execute script " + scriptPath + " because Python executable is not set.");
            return;
        }
        if (!Files.isRegularFile(Paths.get(pythonExecutable))) {
            LOG.error("[" + project.getName() + "] Cannot execute script " + scriptPath + " because Python executable is invalid: "+ pythonExecutable);
            return;
        }


        scriptExecutorService.submit(() -> {
            LOG.info("[" + project.getName() + "] Executing script for file event: " + scriptPath +
                    " with args: [" + eventType + ", " + affectedFilePath + "]");
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        pythonExecutable,
                        scriptPath,
                        eventType,
                        affectedFilePath.replace('\\','/') // 确保路径分隔符为 /
                );

                // 设置环境变量
                Map<String, String> envVars = new HashMap<>(EnvironmentUtil.getEnvironmentMap()); // 继承当前环境
                envVars.putAll(config.getEnvVariables()); // 添加配置的环境变量
                envVars.put("PYTHONIOENCODING", "UTF-8");
                if (project.getBasePath() != null) {
                    envVars.put("PROJECT_DIR", project.getBasePath().replace('\\','/'));
                }
                pb.environment().clear();
                pb.environment().putAll(envVars);


                // 重定向工作目录到项目根目录（如果需要）
                // if (project.getBasePath() != null) {
                //     pb.directory(new File(project.getBasePath()));
                // }


                Process process = pb.start();

                StringBuilder output = new StringBuilder();
                StringBuilder errorOutput = new StringBuilder();

                // 读取标准输出
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append(System.lineSeparator());
                    }
                }
                // 读取错误输出
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errorOutput.append(line).append(System.lineSeparator());
                    }
                }

                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    LOG.info("[" + project.getName() + "] Script " + Paths.get(scriptPath).getFileName() + " executed successfully for event " + eventType + " on " + affectedFilePath +
                            (output.length() > 0 ? ". Output:\n" + output : ""));
                } else {
                    LOG.warn("[" + project.getName() + "] Script " + Paths.get(scriptPath).getFileName() + " execution failed for event " + eventType + " on " + affectedFilePath +
                            " (Exit Code: " + exitCode + ")" +
                            (output.length() > 0 ? ". Output:\n" + output : "") +
                            (errorOutput.length() > 0 ? ". Error:\n" + errorOutput : ""));
                }

                // 脚本执行后刷新VFS，确保IDE能看到脚本可能产生的文件变化
                ApplicationManager.getApplication().invokeLater(() -> {
                    VirtualFile changedFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(affectedFilePath);
                    if (changedFile != null) {
                        changedFile.refresh(false, true); // 异步，递归刷新
                    } else {
                        // 如果文件被删除，尝试刷新其父目录
                        Path parentPath = Paths.get(affectedFilePath).getParent();
                        if (parentPath != null) {
                            VirtualFile parentDir = LocalFileSystem.getInstance().refreshAndFindFileByPath(parentPath.toString());
                            if (parentDir != null) {
                                parentDir.refresh(false, true);
                            }
                        }
                    }
                    // 也可以考虑刷新整个项目 Util.refreshAllFiles(project);
                });


            } catch (IOException | InterruptedException e) {
                LOG.error("[" + project.getName() + "] Error executing watched script " + scriptPath + ": " + e.getMessage(), e);
            }
        });
    }


    private synchronized void stopWatching() {
        if (!isRunning) return;
        LOG.info("[" + project.getName() + "] Stopping FileChangeEventWatcherService...");
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
        LOG.info("[" + project.getName() + "] FileChangeEventWatcherService stopped.");
    }

    @Override
    public void dispose() {
        LOG.info("Disposing FileChangeEventWatcherService for project: " + project.getName());
        stopWatching();
        activeWatchers.clear();
        scriptExecutorService.shutdownNow(); // 强制关闭线程池
    }

    // 内部类，用于存储活跃的监控配置
    private static class ActiveWatch {
        final String watchedPath; // 规范化的被监控路径
        final String scriptToRun; // 规范化的脚本完整路径

        ActiveWatch(String watchedPath, String scriptToRun) {
            this.watchedPath = watchedPath;
            this.scriptToRun = scriptToRun;
        }
        // equals and hashCode if added to Set
    }
}