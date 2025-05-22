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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                if (!isRunning || watchedPythonScriptDir == null) return;

                boolean scriptsNeedRefresh = false;
                for (VFileEvent event : events) {
                    VirtualFile file = extractFileFromEvent(event);
                    if (file == null) continue;

                    String filePath = file.getPath().replace('\\', '/');
                    String parentPath = file.getParent() != null ? file.getParent().getPath().replace('\\', '/') : null;

                    // 检查事件是否发生在被监控的 pythonScriptPath 目录下，或者目录本身发生变化
                    boolean isRelevant = filePath.equals(watchedPythonScriptDir) || // 目录本身 (例如重命名)
                            (parentPath != null && parentPath.equals(watchedPythonScriptDir)); // 目录下的文件

                    if (!isRelevant && filePath.startsWith(watchedPythonScriptDir + "/")) { // 检查是否是子目录中的文件
                        isRelevant = true;
                    }


                    if (isRelevant) {
                        // 只关心 .py 文件的新增、删除、重命名，或目录结构变化
                        if (event instanceof VFileCreateEvent ||
                                event instanceof VFileDeleteEvent ||
                                (event instanceof VFilePropertyChangeEvent && VirtualFile.PROP_NAME.equals(((VFilePropertyChangeEvent) event).getPropertyName())) ||
                                event instanceof VFileMoveEvent ||
                                event instanceof VFileCopyEvent) { // VFileCopyEvent 也会创建新文件

                            // 进一步检查是否影响 .py 文件或目录本身
                            String changedFileName = file.getName().toLowerCase();
                            if (changedFileName.endsWith(".py") || file.isDirectory()) {
                                LOG.info("[" + project.getName() + "] Relevant event in script dir: " +
                                        event.getClass().getSimpleName() + " on " + filePath + ". Triggering scripts refresh.");
                                scriptsNeedRefresh = true;
                                break; // 只要有一个相关事件就足够了
                            }
                        }
                    }
                }

                if (scriptsNeedRefresh) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!isRunning) return;
                        LOG.info("[" + project.getName() + "] Publishing scriptsChanged notification.");
                        SyncFilesNotifier publisher = project.getMessageBus().syncPublisher(SyncFilesNotifier.TOPIC);
                        publisher.scriptsChanged(); // 通知工具窗口更新脚本列表
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