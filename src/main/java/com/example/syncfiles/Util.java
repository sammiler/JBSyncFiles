package com.example.syncfiles;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Objects;

public class Util {
    public static void refreshAndSetWatchDir(Project project, String scriptPath, String pythonExe) throws IOException {
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
        SyncAction.directoryWatcher.refreshButtons(scriptPath,pythonExe);

    }

    public static void refreshAllFiles(Project project)
    {
        // 触发 IntelliJ 目录刷新
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String basePath = project.getBasePath();
            VirtualFile baseDir = LocalFileSystem.getInstance().findFileByPath(Objects.requireNonNull(basePath));
            if (baseDir != null) {
                baseDir.refresh(false, true);
            }
        });
    }

    public static void InitToolWindowFactory(Project project,SyncFilesToolWindowFactory syncFilesToolWindowFactory)

    {
        if (SyncAction.directoryWatcher == null) {
            try {
                SyncAction.directoryWatcher = new DirectoryWatcher(project);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(null, "配置文件中目录不存在: ", "错误", JOptionPane.ERROR_MESSAGE);
                System.err.println(e.getMessage());
            }
        }
        SyncAction.directoryWatcher.setSyncFilesToolWindowFactory(syncFilesToolWindowFactory);
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
