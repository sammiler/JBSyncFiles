package com.example.syncfiles;

import com.example.syncfiles.notifiers.SyncFilesNotifier;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
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
import java.util.*;
import java.util.stream.Collectors;

public class ProjectDirectoryWatcherService implements Disposable {
    private static final Logger LOG = Logger.getInstance(ProjectDirectoryWatcherService.class);
    private final Project project;
    private MessageBusConnection connection;
    private boolean isRunning = false;
    // 只监控 pythonScriptPath 目录
    private String watchedPythonScriptDir = null; // 存储规范化的绝对路径

    public ProjectDirectoryWatcherService(Project project) {
        this.project = project;
        LOG.info("ProjectDirectoryWatcherService created for project: " + project.getName());
    }

    public synchronized void updateWatchedDirectories() {
        LOG.info("[" + project.getName() + "] Updating watched Python script directory.");
        stopWatching(); // 停止旧的监听

        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        String scriptPathStr = config.getPythonScriptPath();

        if (scriptPathStr == null || scriptPathStr.trim().isEmpty()) {
            LOG.info("[" + project.getName() + "] Python script path is empty, no directory will be watched by ProjectDirectoryWatcherService.");
            this.watchedPythonScriptDir = null;
            return;
        }

        Path scriptPathObj = Paths.get(scriptPathStr);
        if (!Files.isDirectory(scriptPathObj)) {
            LOG.warn("[" + project.getName() + "] Python script path is not a valid directory: " + scriptPathStr + ". No directory will be watched.");
            this.watchedPythonScriptDir = null;
            return;
        }

        this.watchedPythonScriptDir = scriptPathObj.toAbsolutePath().normalize().toString().replace('\\', '/');
        LOG.info("[" + project.getName() + "] Will watch Python script directory: " + this.watchedPythonScriptDir);
        startWatching();
    }

    private synchronized void startWatching() {
        if (isRunning || this.watchedPythonScriptDir == null) {
            if (isRunning) LOG.info("[" + project.getName() + "] ProjectDirectoryWatcherService already running for script dir.");
            return;
        }

        LOG.info("[" + project.getName() + "] Starting ProjectDirectoryWatcherService for script dir: " + this.watchedPythonScriptDir);
        connection = project.getMessageBus().connect(this);
        isRunning = true;

        connection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            // ... 在 ProjectDirectoryWatcherService.java 的 BulkFileListener.after() 方法内 ...
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                if (!isRunning || watchedPythonScriptDir == null) return;

                boolean scriptsStructureChanged = false; // 用于判断是否需要发 scriptsChanged 通知
                List<String> newPyFilesRelativeToScriptDir = new ArrayList<>(); // 存储新发现的py文件相对路径

                for (VFileEvent event : events) {
                    VirtualFile file = extractFileFromEvent(event);
                    if (file == null) continue;

                    String filePath = file.getPath().replace('\\', '/');
                    String parentPath = file.getParent() != null ? file.getParent().getPath().replace('\\', '/') : null;

                    boolean isRelevant = filePath.equals(watchedPythonScriptDir) ||
                            (parentPath != null && parentPath.equals(watchedPythonScriptDir));
                    if (!isRelevant && filePath.startsWith(watchedPythonScriptDir + "/")) {
                        isRelevant = true;
                    }

                    if (isRelevant) {
                        String changedFileName = file.getName().toLowerCase();
                        if (event instanceof VFileCreateEvent && changedFileName.endsWith(".py") && !file.isDirectory()) {
                            // 新建了 .py 文件
                            Path scriptBasePath = Paths.get(watchedPythonScriptDir);
                            Path newFilePath = Paths.get(filePath);
                            String relativePath = scriptBasePath.relativize(newFilePath).toString().replace('\\', '/');
                            newPyFilesRelativeToScriptDir.add(relativePath);
                            scriptsStructureChanged = true; // 结构发生变化
                            LOG.info("[" + project.getName() + "] New .py file detected: " + relativePath);
                        } else if (event instanceof VFileDeleteEvent || // 文件删除或重命名也会触发
                                (event instanceof VFilePropertyChangeEvent && VirtualFile.PROP_NAME.equals(((VFilePropertyChangeEvent) event).getPropertyName())) ||
                                event instanceof VFileMoveEvent) {
                            if (changedFileName.endsWith(".py") || file.isDirectory()) {
                                scriptsStructureChanged = true;
                            }
                        }
                    }
                }

                if (!newPyFilesRelativeToScriptDir.isEmpty()) {
                    // 在后台线程或 invokeLater 中处理配置修改，避免阻塞VFS事件处理
                    ApplicationManager.getApplication().invokeLater(() -> {
                        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
                        List<ScriptGroup> currentGroups = new ArrayList<>(config.getScriptGroups());
                        ScriptGroup defaultGroup = currentGroups.stream()
                                .filter(g -> ScriptGroup.DEFAULT_GROUP_ID.equals(g.id))
                                .findFirst().orElse(null);
                        if (defaultGroup == null) { /* ... 处理 ... */ return; }

                        boolean configChanged = false;
                        Set<String> allConfiguredScriptPaths = currentGroups.stream()
                                .flatMap(g -> g.scripts.stream())
                                .map(s -> s.path.toLowerCase())
                                .collect(Collectors.toSet());

                        for (String newRelativePath : newPyFilesRelativeToScriptDir) {
                            if (!allConfiguredScriptPaths.contains(newRelativePath.toLowerCase()) &&
                                    defaultGroup.scripts.stream().noneMatch(s -> s.path.equalsIgnoreCase(newRelativePath))) {
                                ScriptEntry newEntry = new ScriptEntry(newRelativePath);
                                newEntry.description = "Auto-added by watcher " + new java.text.SimpleDateFormat("yyyy/MM/dd").format(new Date());
                                defaultGroup.scripts.add(newEntry);
                                configChanged = true;
                                LOG.info("[" + project.getName() + "] Watcher: Added new script '" + newRelativePath + "' to Default group.");
                            }
                        }

                        if (configChanged) {
                            defaultGroup.scripts.sort(Comparator.comparing(s -> s.getDisplayName().toLowerCase()));
                            config.setScriptGroups(currentGroups);
                            // scriptsChanged 通知会由下面的逻辑发出，如果 configChanged 为 true，
                            // tool window 的 updateScriptTree(true) 会扫描并正确显示。
                            // 或者，如果只想更新而不强制扫描，可以发 configurationChanged，
                            // 但 যেহেতু文件系统变了，scriptsChanged 更合适。
                        }
                    });
                }

                if (scriptsStructureChanged) { // 只要结构变了（增、删、改名），就通知刷新
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!isRunning) return;
                        LOG.info("[" + project.getName() + "] Publishing scriptsChanged notification due to VFS events.");
                        SyncFilesNotifier publisher = project.getMessageBus().syncPublisher(SyncFilesNotifier.TOPIC);
                        publisher.scriptsChanged();
                    });
                }
            }
        });
        LOG.info("[" + project.getName() + "] ProjectDirectoryWatcherService started watching: " + this.watchedPythonScriptDir);
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

    @Nullable
    private VirtualFile extractFileFromEvent(VFileEvent event) {
        // 简化版，您可以根据需要扩展
        if (event instanceof VFileContentChangeEvent ||
                event instanceof VFileDeleteEvent ||
                event instanceof VFileMoveEvent) {
            return event.getFile();
        } else if (event instanceof VFileCreateEvent) {
            return ((VFileCreateEvent) event).getFile(); // 注意：有时可能是 null
        } else if (event instanceof VFileCopyEvent) {
            return ((VFileCopyEvent) event).findCreatedFile();
        } else if (event instanceof VFilePropertyChangeEvent) {
            return event.getFile();
        }
        return null;
    }

    @Override
    public void dispose() {
        LOG.info("Disposing ProjectDirectoryWatcherService for project: " + project.getName());
        stopWatching();
        this.watchedPythonScriptDir = null;
    }
}