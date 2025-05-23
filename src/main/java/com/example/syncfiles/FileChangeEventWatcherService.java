package com.example.syncfiles;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil; // 引入 StringUtil
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
import java.io.File; // 引入 File
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException; // 引入 InvalidPathException
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.example.syncfiles.Util.normalizePath;

public class FileChangeEventWatcherService implements Disposable {
    private static final Logger LOG = Logger.getInstance(FileChangeEventWatcherService.class);
    private final Project project;
    private MessageBusConnection connection;
    private boolean isRunning = false;
    private final List<ActiveWatch> activeWatchers = new ArrayList<>();
    private final Set<String> watcherPath = new LinkedHashSet<>();
    private final ExecutorService scriptExecutorService = Executors.newCachedThreadPool();

    public FileChangeEventWatcherService(Project project) {
        this.project = project;
        LOG.info("FileChangeEventWatcherService created for project: " + project.getName());
    }


    public synchronized void addWatcherPath(String path) {
        if (normalizePath(project, path) != null) {
            watcherPath.add(normalizePath(project, path));
        }
        if (!watcherPath.isEmpty()) {
            if (!isRunning)
                startWatching();
        }
    }

    public synchronized void removeWatcherPath(String path) {
        if (normalizePath(project, path) != null) {
            watcherPath.add(normalizePath(project, path));
        }
    }

    public synchronized void updateWatchersFromConfig() {
        String projectName = project.getName(); // 用于日志，避免重复调用 project.getName()
        LOG.info("[" + projectName + "] Updating watchers from config.");
        activeWatchers.clear();

        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        String pythonExecutable = config.getPythonExecutablePath();

        // 检查 Python 执行文件路径
        if (StringUtil.isEmptyOrSpaces(pythonExecutable)) {
            LOG.warn("[" + projectName + "] Python executable is not configured. File event watching will be disabled.");
            return;
        }
        try {
            if (!Files.isRegularFile(Paths.get(pythonExecutable))) {
                LOG.warn("[" + projectName + "] Python executable path is invalid or not a file: '" + pythonExecutable + "'. File event watching will be disabled.");
                return;
            }
        } catch (InvalidPathException e) {
            LOG.warn("[" + projectName + "] Python executable path format is invalid: '" + pythonExecutable + "'. Error: " + e.getMessage() + ". File event watching will be disabled.");
            return;
        }

        List<WatchEntry> configuredEntries = config.getWatchEntries();
        if (configuredEntries.isEmpty()) {
            LOG.info("[" + projectName + "] No watch entries configured.");
            return;
        }

        String projectBasePath = project.getBasePath(); // 获取项目根路径，用于解析相对路径

        for (WatchEntry entry : configuredEntries) {
            String watchedPathInput = entry.watchedPath; // 用户在UI上输入的路径
            String scriptToRunPathInput = entry.onEventScript; // 用户输入的脚本路径 (可能是相对或绝对)

            if (StringUtil.isEmptyOrSpaces(watchedPathInput) ||
                    StringUtil.isEmptyOrSpaces(scriptToRunPathInput)) {
                LOG.warn("[" + projectName + "] Skipping invalid watch entry due to empty paths. Watched: '" + watchedPathInput + "', Script: '" + scriptToRunPathInput + "'");
                continue;
            }

            // --- 1. 解析和规范化 scriptToRunPathInput (要执行的脚本路径) ---
            Path fullScriptPathToExecute;
            try {
                Path tempScriptPath = Paths.get(scriptToRunPathInput.replace('\\', '/'));
                if (tempScriptPath.isAbsolute()) {
                    fullScriptPathToExecute = tempScriptPath.normalize();
                } else if (!StringUtil.isEmptyOrSpaces(projectBasePath)) {
                    // 脚本路径是相对的，且全局脚本目录已配置
                    fullScriptPathToExecute = Paths.get(projectBasePath.replace('\\', '/'), scriptToRunPathInput.replace('\\', '/')).normalize();
                } else {
                    LOG.warn("[" + projectName + "] Cannot resolve relative 'Python Script on Modify': '" + scriptToRunPathInput +
                            "' for 'Path to Watch': '" + watchedPathInput +
                            "' because 'Python Scripts Directory' is not set. Skipping this watch entry.");
                    continue;
                }

                if (!Files.isRegularFile(fullScriptPathToExecute)) {
                    LOG.warn("[" + projectName + "] Script for 'Path to Watch' '" + watchedPathInput +
                            "' does not exist or is not a file: '" + fullScriptPathToExecute + "'. Skipping this watch entry.");
                    continue;
                }
            } catch (InvalidPathException e) {
                LOG.warn("[" + projectName + "] Invalid format for 'Python Script on Modify': '" + scriptToRunPathInput +
                        "' for 'Path to Watch': '" + watchedPathInput + "'. Error: " + e.getMessage() + ". Skipping this watch entry.");
                continue;
            }
            // --- scriptToRunPathInput 解析结束 ---


            // --- 2. 解析和规范化 watchedPathInput (要监控的路径) ---
            Path absoluteWatchedPathObject;
            try {
                Path tempWatchedPath = Paths.get(watchedPathInput.replace('\\', '/'));
                if (tempWatchedPath.isAbsolute()) {
                    absoluteWatchedPathObject = tempWatchedPath.normalize();
                } else {
                    if (!StringUtil.isEmptyOrSpaces(projectBasePath)) {
                        absoluteWatchedPathObject = Paths.get(projectBasePath.replace('\\', '/'), watchedPathInput.replace('\\', '/')).normalize();
                    } else {
                        LOG.warn("[" + projectName + "] Cannot resolve relative 'Path to Watch': '" + watchedPathInput +
                                "' because project base path is unavailable. Skipping this watch entry.");
                        continue;
                    }
                }
            } catch (InvalidPathException e) {
                LOG.warn("[" + projectName + "] Invalid format for 'Path to Watch': '" + watchedPathInput +
                        "'. Error: " + e.getMessage() + ". Skipping this watch entry.");
                continue;
            }

            String finalNormalizedAbsWatchedPath = absoluteWatchedPathObject.toString().replace('\\', '/'); // 确保最终是 / 分隔符
            // --- watchedPathInput 解析结束 ---


            // 检查解析后的被监控路径是否存在以及是否为目录
            boolean isDirectory = false;
            boolean pathExists = Files.exists(absoluteWatchedPathObject);
            if (pathExists) {
                isDirectory = Files.isDirectory(absoluteWatchedPathObject);
            } else {
                LOG.warn("[" + projectName + "] 'Path to Watch': '" + finalNormalizedAbsWatchedPath +
                        "' currently does not exist. Watch will be added, but may only trigger correctly if it's created as a file, or if its parent is watched and it's created within.");
            }

            activeWatchers.add(new ActiveWatch(
                    finalNormalizedAbsWatchedPath,
                    fullScriptPathToExecute.toString().replace('\\', '/'),
                    isDirectory
            ));

            String typeMsg = pathExists ? (isDirectory ? " (Directory)" : " (File)") : " (Path currently non-existent)";
            LOG.info("[" + projectName + "] Added watch for: '" + finalNormalizedAbsWatchedPath + "'" + typeMsg +
                    " -> executes '" + fullScriptPathToExecute.toString().replace('\\', '/') + "'");
        }

        if (!activeWatchers.isEmpty() || !isRunning) {
            startWatching();
        } else {
            LOG.info("[" + projectName + "] No active watchers were configured after processing entries.");
        }
    }

    private synchronized void startWatching() {
        String projectName = project.getName();
        if (isRunning || (activeWatchers.isEmpty() && watcherPath.isEmpty())) {
            if (isRunning) LOG.info("[" + projectName + "] Watcher already running.");
            if (activeWatchers.isEmpty() && !isRunning) LOG.info("[" + projectName + "] No active watchers to start.");
            return;
        }

        LOG.info("[" + projectName + "] Starting FileChangeEventWatcherService...");
        connection = project.getMessageBus().connect(this);
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
        LOG.info("[" + projectName + "] FileChangeEventWatcherService started. Watching " + activeWatchers.size() + " configurations.");
    }

    private void processVfsEvent(@NotNull VFileEvent event) {
        String projectName = project.getName();
        String eventType = null;
        String affectedPath = null; // VFS 事件原始路径

        // 从事件中提取类型和路径
        if (event instanceof VFileCreateEvent) {
            eventType = "Change New";
            affectedPath = ((VFileCreateEvent) event).getPath(); // 获取路径字符串
        } else if (event instanceof VFileContentChangeEvent) {
            eventType = "Change Mod";
            VirtualFile vf = event.getFile();
            if (vf != null) affectedPath = vf.getPath();
        } else if (event instanceof VFileDeleteEvent) {
            eventType = "Change Del";
            VirtualFile vf = event.getFile(); // 文件可能已无效，但路径仍有用
            if (vf != null) affectedPath = vf.getPath();
        } else if (event instanceof VFileMoveEvent) {
            eventType = "Change Mod"; // 简单处理为修改，使用移动后的路径
            VirtualFile vf = event.getFile();
            if (vf != null) affectedPath = vf.getPath();
        } else if (event instanceof VFileCopyEvent) {
            eventType = "Change New";
            VirtualFile vf = ((VFileCopyEvent) event).findCreatedFile();
            if (vf != null) affectedPath = vf.getPath();
        } else if (event instanceof VFilePropertyChangeEvent) {
            if (VirtualFile.PROP_NAME.equals(((VFilePropertyChangeEvent) event).getPropertyName())) {
                eventType = "Change Mod"; // 重命名视为修改
                VirtualFile vf = event.getFile();
                if (vf != null) affectedPath = vf.getPath();
            }
        }

        // 在 FileChangeEventWatcherService.processVfsEvent()

// ... (从 VFileEvent 获取 affectedPath 字符串) ...
        if (eventType == null || affectedPath == null) {
            return;
        }

        final String finalAffectedPath;
        try {
            // 1. 确保原始 affectedPath 中的反斜杠被替换为正斜杠
            String pathWithForwardSlashes = affectedPath.replace('\\', '/');
            // 2. 使用 Paths.get().normalize() 来处理 ".." 和 "." 等，并获得规范路径对象
            Path normalizedPathObject = Paths.get(pathWithForwardSlashes).normalize();
            // 3. 将规范化的 Path 对象转换为字符串，并再次确保是正斜杠
            finalAffectedPath = normalizedPathObject.toString().replace('\\', '/');
        } catch (InvalidPathException e) {
            LOG.warn("[" + projectName + "] Invalid path from VFS event: '" + affectedPath + "'. Error: " + e.getMessage() + ". Skipping event.");
            return;
        }
        final String finalEventType = eventType;

// ... 后续的 filter 逻辑 ...
// 在 filter 的 lambda 表达式中，watch.watchedPath 也应该是使用 '/' 的绝对规范路径

        List<ActiveWatch> matchedWatchers = activeWatchers.stream()
                .filter(watch -> {
                    if (finalAffectedPath.equals(watch.watchedPath)) {
                        return true;
                    }
                    if (watch.isDirectory) {
                        Path watchedDirObj = Paths.get(watch.watchedPath);
                        Path affectedPathObj = Paths.get(finalAffectedPath);
                        if (affectedPathObj.getParent() != null && affectedPathObj.getParent().equals(watchedDirObj)) {
                            return true;
                        }
                        if (finalAffectedPath.startsWith(watch.watchedPath + "/")) {
                            return true;
                        }
                    }
                    return false;
                })
                .collect(Collectors.toList());

        if (!matchedWatchers.isEmpty()) {
            for (ActiveWatch watch : matchedWatchers)
            {
                Util.forceRefreshVFS(watch.watchedPath);
            }
            LOG.info("[" + projectName + "] Relevant VFS Event: " + event.getClass().getSimpleName() +
                    " | Type: " + finalEventType + " | Path: " + finalAffectedPath +
                    " | Matched " + matchedWatchers.size() + " watchers.");

            for (ActiveWatch watcher : matchedWatchers) {
                LOG.info("[" + projectName + "] Matched watch: '" + watcher.watchedPath + "' -> executes '" + watcher.scriptToRun + "'");
                executeWatchedScript(watcher.scriptToRun, finalEventType, finalAffectedPath);
            }
        }
        Set<String> exactMatches = watcherPath.stream()
                .filter(pathInSet -> pathInSet.equals(finalAffectedPath))
                .collect(Collectors.toSet());
        if (!exactMatches.isEmpty()) {
            for (String match : exactMatches) {
                Util.forceRefreshVFS(match);
            }

            Util.reloadSyncFilesConfigFromDisk(project);
            project.getMessageBus().syncPublisher(SyncFilesNotifier.TOPIC).configurationChanged();
        }
    }


    private void executeWatchedScript(String scriptPathToExecute, String eventType, String affectedFilePath) {
        String projectName = project.getName();
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        String pythonExecutable = config.getPythonExecutablePath(); // 已在 updateWatchersFromConfig 验证过
        pythonExecutable = Util.isDirectoryAfterMacroExpansion(project,pythonExecutable);

        String finalPythonExecutable = pythonExecutable;
        scriptPathToExecute = Util.isDirectoryAfterMacroExpansion(project,scriptPathToExecute);
        String finalScriptPathToExecute = scriptPathToExecute;
        scriptExecutorService.submit(() -> {
            LOG.info("[" + projectName + "] Executing script for file event: '" + finalScriptPathToExecute +
                    "' with args: [" + eventType + ", " + affectedFilePath + "]");
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        finalPythonExecutable,
                        finalScriptPathToExecute,
                        eventType,
                        affectedFilePath // affectedFilePath 已经是 / 分隔的绝对规范路径
                );

                Map<String, String> envVars = new HashMap<>(EnvironmentUtil.getEnvironmentMap());
                envVars.putAll(config.getEnvVariables());
                envVars.put("PYTHONIOENCODING", "UTF-8");
                if (project.getBasePath() != null) {
                    envVars.put("PROJECT_DIR", project.getBasePath().replace('\\', '/'));
                }
                pb.environment().clear();
                pb.environment().putAll(envVars);

                if (project.getBasePath() != null) {
                    pb.directory(new File(project.getBasePath()));
                } else {
                    // Fallback CWD if project base path is null
                    Path scriptFile = Paths.get(finalScriptPathToExecute);
                    if (scriptFile.getParent() != null) {
                        pb.directory(scriptFile.getParent().toFile());
                    }
                }

                Process process = pb.start();
                StringBuilder output = new StringBuilder();
                StringBuilder errorOutput = new StringBuilder();

                try (BufferedReader outReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                     BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = outReader.readLine()) != null) output.append(line).append(System.lineSeparator());
                    while ((line = errReader.readLine()) != null)
                        errorOutput.append(line).append(System.lineSeparator());
                }
                int exitCode = process.waitFor();

                String scriptFileName = Paths.get(finalScriptPathToExecute).getFileName().toString();
                if (exitCode == 0) {
                    LOG.info("[" + projectName + "] Script '" + scriptFileName + "' executed successfully for event '" + eventType + "' on '" + affectedFilePath + "'" +
                            (output.length() > 0 ? ". Output:\n" + output.toString().trim() : ""));
                } else {
                    LOG.warn("[" + projectName + "] Script '" + scriptFileName + "' execution failed for event '" + eventType + "' on '" + affectedFilePath +
                            "' (Exit Code: " + exitCode + ")" +
                            (output.length() > 0 ? ". Output:\n" + output.toString().trim() : "") +
                            (errorOutput.length() > 0 ? ". Error:\n" + errorOutput.toString().trim() : ""));
                }
//                Util.refreshAllFiles(project);
                ApplicationManager.getApplication().invokeLater(() -> {
                    // Refresh the VFS for the affected path
                    // Util.refreshPath(affectedFilePath); // 假设有一个 Util 方法
                    VirtualFile changedFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(affectedFilePath);
                    if (changedFile != null) {
                        changedFile.refresh(false, true); // Async, recursive
                    } else {
                        Path parentOfAffected = Paths.get(affectedFilePath).getParent();
                        if (parentOfAffected != null) {
                            VirtualFile parentDir = LocalFileSystem.getInstance().refreshAndFindFileByPath(parentOfAffected.toString());
                            if (parentDir != null) parentDir.refresh(false, true);
                        }
                    }
                });

            } catch (IOException | InterruptedException e) {
                LOG.error("[" + projectName + "] Error executing watched script '" + finalScriptPathToExecute + "': " + e.getMessage(), e);
            }
        });
    }

    private synchronized void stopWatching() {
        String projectName = project.getName();
        if (!isRunning) return;
        LOG.info("[" + projectName + "] Stopping FileChangeEventWatcherService...");
        isRunning = false;
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Exception e) {
                LOG.error("[" + projectName + "] Error disconnecting MessageBus: " + e.getMessage(), e);
            } finally {
                connection = null;
            }
        }
        LOG.info("[" + projectName + "] FileChangeEventWatcherService stopped.");
    }

    @Override
    public void dispose() {
        LOG.info("Disposing FileChangeEventWatcherService for project: " + project.getName());
        stopWatching();
        activeWatchers.clear();
        if (!scriptExecutorService.isShutdown()) {
            scriptExecutorService.shutdownNow();
        }
    }

    private static class ActiveWatch {
        final String watchedPath;
        final String scriptToRun;
        final boolean isDirectory;

        ActiveWatch(String watchedPath, String scriptToRun, boolean isDirectory) {
            this.watchedPath = watchedPath;
            this.scriptToRun = scriptToRun;
            this.isDirectory = isDirectory;
        }
    }
}