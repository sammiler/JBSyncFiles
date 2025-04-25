package com.example.syncfiles;

import com.intellij.openapi.application.ApplicationManager; // Needed for invokeLater
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable; // Import Nullable

// import java.io.IOException; // No longer needed here
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class Util {




    // REMOVED: Watcher logic is now entirely within ProjectDirectoryWatcherService
    // public static void refreshAndSetWatchDir(Project project) throws IOException { ... }

    /**
     * Refreshes the Virtual File System for the entire project.
     * Useful after external changes or downloads.
     */
    public static void refreshAllFiles(@NotNull Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            ApplicationManager.getApplication().runWriteAction(() -> { // VFS refresh might need Write Action or just Read Action/EDT
                String basePath = project.getBasePath();
                if (basePath != null) {
                    VirtualFile baseDir = LocalFileSystem.getInstance().findFileByPath(basePath);
                    if (baseDir != null && baseDir.exists() && baseDir.isDirectory()) {
                        System.out.println("[" + project.getName() + "] Refreshing VFS recursively from base path: " + basePath);
                        // Asynchronous refresh (false = async, true = recursive)
                        baseDir.refresh(false, true);
                    } else {
                        System.err.println("[" + project.getName() + "] Failed to find base directory for VFS refresh: " + basePath);
                    }
                } else {
                    System.err.println("[" + project.getName() + "] Project base path is null, cannot refresh VFS.");
                }
            });
        });
    }

    /**
     * Saves all documents and attempts to refresh a specific file path in the VFS.
     * Useful before executing a script to ensure the latest version is used.
     * @param filePath Absolute path to the file to refresh.
     */
    public static void forceRefreshVFS(@NotNull String filePath) {
        ApplicationManager.getApplication().invokeAndWait(() -> { // Ensure save happens before refresh attempt
            System.out.println("Saving all documents...");
            FileDocumentManager.getInstance().saveAllDocuments();

            System.out.println("Requesting VFS refresh for path: " + filePath);
            // Synchronous refresh for a single file (null = modality state)
            VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath);
            if (virtualFile != null) {
                // Optionally force refresh again if needed
                // virtualFile.refresh(false, false);
                System.out.println("VFS refresh successful for: " + virtualFile.getPath());
            } else {
                System.err.println("VFS could not find file after refresh: " + filePath);
            }
        });
    }
}