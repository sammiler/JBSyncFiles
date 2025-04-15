package com.example.syncfiles;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;
import java.nio.file.Path;
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
        SyncAction.directoryWatcher.watchDirectory(Paths.get(config.pythonScriptPath));

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
}
