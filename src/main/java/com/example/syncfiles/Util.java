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

    // Store factory instances per project - needed to call refreshScriptButtons on the correct UI instance
    private static final ConcurrentHashMap<Project, SyncFilesToolWindowFactory> factoryMap = new ConcurrentHashMap<>();

    /**
     * Called by SyncFilesToolWindowFactory.createToolWindowContent to register itself.
     */
    public static void initToolWindowFactory(@NotNull Project project, @NotNull SyncFilesToolWindowFactory factory) {
        System.out.println("Registering ToolWindowFactory for project: " + project.getName());
        factoryMap.put(project, factory);
    }

    /**
     * Gets the ToolWindowFactory instance for a specific project.
     * This allows other components (like the settings apply) to trigger UI updates.
     * Returns null if the tool window hasn't been created/registered yet for that project.
     */
    @Nullable // Can return null
    public static SyncFilesToolWindowFactory getOrInitFactory(@NotNull Project project) {
        SyncFilesToolWindowFactory factory = factoryMap.get(project);
        // Removed the auto-initialization logic as it can be complex and might not always work.
        // Rely on the tool window being opened by the user or the framework first.
        // if (factory == null) {
        //    System.out.println("ToolWindowFactory not found for project " + project.getName() + ", attempting lazy init (might not work).");
        //    ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("SyncFiles"); // Use your ToolWindow ID
        //    if (toolWindow != null) {
        //        // Activating or getting content manager *might* trigger creation, but not guaranteed.
        //        // toolWindow.activate(null); // Might bring window to front
        //        toolWindow.getContentManager();
        //        factory = factoryMap.get(project); // Check again
        //    }
        // }
        if (factory == null) {
            System.out.println("ToolWindowFactory instance for project " + project.getName() + " not yet available.");
        }
        return factory;
    }

    /**
     * Called when a project is closed (e.g., via a ProjectManagerListener) to clean up the map.
     * You would need to implement a ProjectManagerListener to call this.
     */
    public static void removeFactory(@NotNull Project project) {
        System.out.println("Removing ToolWindowFactory for project: " + project.getName());
        factoryMap.remove(project);
    }

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