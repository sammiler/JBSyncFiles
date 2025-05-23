package com.example.syncfiles;

import com.intellij.openapi.diagnostic.Logger; // 使用 Logger
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity; // 或 StartupActivity，取决于您的 IDEA 版本和声明方式
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class WatcherInitializerActivity implements ProjectActivity { // 或者 com.intellij.openapi.startup.StartupActivity

    private static final Logger LOG = Logger.getInstance(WatcherInitializerActivity.class);

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        String projectName = project.getName();
        LOG.info("WatcherInitializerActivity executing for project: " + projectName);

        // 1. 初始化 ProjectDirectoryWatcherService (监控 Python 脚本目录)
        ProjectDirectoryWatcherService pyScriptDirWatcher = project.getService(ProjectDirectoryWatcherService.class);
        if (pyScriptDirWatcher != null) {
            LOG.info("[" + projectName + "] Initializing ProjectDirectoryWatcherService...");
            pyScriptDirWatcher.updateWatchedDirectories();
            LOG.info("[" + projectName + "] ProjectDirectoryWatcherService initialized.");
        } else {
            LOG.error("[" + projectName + "] Failed to get ProjectDirectoryWatcherService instance during project startup.");
        }

        // 2. 初始化 FileChangeEventWatcherService (监控用户定义的 WatchEntry)
        FileChangeEventWatcherService fileChangeEventWatcher = project.getService(FileChangeEventWatcherService.class);
        if (fileChangeEventWatcher != null) {
            LOG.info("[" + projectName + "] Initializing FileChangeEventWatcherService...");
            fileChangeEventWatcher.addWatcherPath(Util.configFilePath(project));
            fileChangeEventWatcher.updateWatchersFromConfig(); // ★★★ 在这里调用 ★★★
            LOG.info("[" + projectName + "] FileChangeEventWatcherService initialized.");
        } else {
            LOG.error("[" + projectName + "] Failed to get FileChangeEventWatcherService instance during project startup.");
        }

        return Unit.INSTANCE; // 对于 ProjectActivity (Kotlin suspend function)
        // 如果是旧的 StartupActivity (Java interface), 方法是 public void runActivity(@NotNull Project project)
    }

}