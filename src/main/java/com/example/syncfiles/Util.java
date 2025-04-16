package com.example.syncfiles;

import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class Util {


    // 存储每个项目对应的 ToolWindow 工厂实例
    private static final ConcurrentHashMap<Project, SyncFilesToolWindowFactory> factoryMap = new ConcurrentHashMap<>();

    /**
     * 在 ToolWindowFactory.createToolWindowContent 中调用注册
     */
    public static void initToolWindowFactory(Project project, SyncFilesToolWindowFactory factory) {
        factoryMap.put(project, factory);
    }

    /**
     * 获取对应项目的工厂实例，如果未初始化则尝试触发初始化。
     */
    public static SyncFilesToolWindowFactory getOrInitFactory(Project project) {
        SyncFilesToolWindowFactory factory = factoryMap.get(project);
        if (factory == null) {
            // 尝试初始化 ToolWindow（懒加载机制）
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("SyncFiles");
            if (toolWindow != null) {
                // 触发 ToolWindow 的 UI 初始化
                toolWindow.getContentManager(); // 会触发 createToolWindowContent
                // 再次尝试获取
                factory = factoryMap.get(project);
            }
        }
        return factory;
    }

    /**
     * 可选：清理（如果你在项目关闭时清理）
     */
    public static void removeFactory(Project project) {
        factoryMap.remove(project);
    }

    public static void refreshAndSetWatchDir(Project project) throws IOException {
        if (SyncAction.directoryWatcher == null)
        {
            SyncAction.directoryWatcher = new DirectoryWatcher(project);
        }
//        Path rootDir = Paths.get(Objects.requireNonNull(project.getBasePath()));
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);

//        for (Mapping mapping : config.getMappings()) {
//            String relativePath = mapping.targetPath;
//            try {
//                // 拼接 rootDir 和相对路径，跨平台兼容
//                Path dir = rootDir.resolve(relativePath);
//                SyncAction.directoryWatcher.watchDirectory(dir);
//                System.out.println("添加监控目录: " + dir);
//            } catch (IOException e) {
//                System.err.println("无法监控目录: " + relativePath + ", 错误: " + e.getMessage());
//            }
//        }

        SyncAction.directoryWatcher.watchDirectory(Paths.get(config.getPythonScriptPath()));
        SyncAction.directoryWatcher.startWatching();
        SyncAction.directoryWatcher.refreshButtons(config.getPythonScriptPath(),config.getPythonExecutablePath());

    }

    public static void refreshAllFiles(Project project)
    {
        // 触发 IntelliJ 目录刷新
        String basePath = project.getBasePath();
        VirtualFile baseDir = LocalFileSystem.getInstance().findFileByPath(Objects.requireNonNull(basePath));
        if (baseDir != null) {
            baseDir.refresh(false, true);
        }
    }

    public static void forceRefreshVFS(String scriptPath)
    {
        // 保存所有文件，确保脚本写入磁盘
        FileDocumentManager.getInstance().saveAllDocuments();

        // 刷新 VFS，确保 scriptPath 是最新
        LocalFileSystem.getInstance().refreshAndFindFileByPath(scriptPath);
        // 或者更强的刷新
        // LocalFileSystem.getInstance().refreshFiles(Collections.singletonList(new File(scriptPath)), true, false, null);
    }
}
