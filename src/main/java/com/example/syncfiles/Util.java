package com.example.syncfiles;

import com.intellij.openapi.components.PathMacroManager;
import com.intellij.openapi.project.Project;
// No JDOMUtil needed if using SAXBuilder directly for Document
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.xmlb.XmlSerializer;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.input.SAXBuilder; // Import SAXBuilder
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import com.intellij.openapi.application.ApplicationManager; // Needed for invokeLater
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.Nullable;
// Import Nullable

// import java.io.IOException; // No longer needed here
import java.nio.file.InvalidPathException;

import static com.intellij.codeInspection.InspectionApplicationBase.LOG;

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
     *
     * @param filePath Absolute path to the file to refresh.
     */
    public static void forceRefreshVFS(@NotNull String filePath) {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            System.out.println("Saving all documents...");
            FileDocumentManager.getInstance().saveAllDocuments();

            System.out.println("Requesting VFS refresh for path: " + filePath);
            VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath);

            if (virtualFile != null) {
                virtualFile.refresh(false, false); // Async refresh
                System.out.println("VFS refresh successful for: " + virtualFile.getPath());

            } else {
                System.err.println("VFS could not find file after refresh: " + filePath);
            }
        });
    }

    public static String normalizePath(@NotNull Project project, String path) {
        Path absoluteWatchedPathObject;
        String projectName = project.getName();
        String normalizedPath = null;
        String projectBasePath = project.getBasePath(); // 获取项目根路径，用于解析相对路径
        try {
            Path tempWatchedPath = Paths.get(path.replace('\\', '/'));
            if (tempWatchedPath.isAbsolute()) {
                absoluteWatchedPathObject = tempWatchedPath.normalize();
            } else {
                if (!StringUtil.isEmptyOrSpaces(projectBasePath)) {
                    absoluteWatchedPathObject = Paths.get(projectBasePath.replace('\\', '/'), path.replace('\\', '/')).normalize();
                } else {
                    LOG.warn("[" + projectName + "] Cannot resolve relative 'Path to Watch': '" + path +
                            "' because project base path is unavailable. Skipping this watch entry.");
                    return null;
                }
            }
            normalizedPath = absoluteWatchedPathObject.toString().replace('\\', '/');
        } catch (InvalidPathException e) {
            LOG.warn("[" + projectName + "] Invalid format for 'Path to Watch': '" + path +
                    "'. Error: " + e.getMessage() + ". Skipping this watch entry.");
        }
        return normalizedPath;
    }

    public static String configFilePath(@NotNull Project project) {
        String projectBasePath = project.getBasePath(); // 获取项目的根路径
        Path configFilePath;
        if (projectBasePath != null) {
            // 使用 Paths API 构造路径 (更推荐)
            configFilePath = Paths.get(projectBasePath, ".idea", "syncFilesConfig.xml");
            System.out.println("Config file path (NIO): " + configFilePath.toString());

        }
        else
        {
            return null;
        }
        return configFilePath.toString();
    }

    public static boolean reloadSyncFilesConfigFromDisk(@NotNull Project project) {
        String configFilePathStr = configFilePath(project);
        if (configFilePathStr == null) {
            return false;
        }

        SyncFilesConfig configService = SyncFilesConfig.getInstance(project);
        if (configService == null) {
            LOG.warn("[" + project.getName() + "] SyncFilesConfig service instance is null. Cannot reload.");
            return false;
        }

        VirtualFile configFile = LocalFileSystem.getInstance().findFileByPath(configFilePathStr);

        if (configFile == null || !configFile.isValid() || !configFile.exists()) {
            LOG.warn("[" + project.getName() + "] Config file not found or not accessible for reload: " + configFilePathStr);
            return false;
        }

        LOG.info("[" + project.getName() + "] Attempting to reload SyncFilesConfig from: " + configFile.getPath());
        try (InputStream inputStream = configFile.getInputStream()) {
            // 1. Parse the XML using JDOMUtil.load, which returns the root Element
            Element projectElement = JDOMUtil.load(inputStream);

            if (projectElement == null) {
                LOG.warn("[" + project.getName() + "] Failed to load root element from config file: " + configFilePathStr);
                return false;
            }

            // 2. Find the <component name="SyncFilesConfig"> element
            Element componentElement = null;
            if ("project".equals(projectElement.getName())) { // Check if the root element is <project>
                for (Element child : projectElement.getChildren("component")) {
                    if ("SyncFilesConfig".equals(child.getAttributeValue("name"))) {
                        componentElement = child;
                        break;
                    }
                }
            } else {
                // This case handles if the root element itself might be the component,
                // or if the structure is flatter than expected.
                LOG.warn("[" + project.getName() + "] Config file root is not <project>. Root is: '" +
                        projectElement.getName() + "'. Attempting to find <component name='SyncFilesConfig'> or use root if applicable.");
                if ("component".equals(projectElement.getName()) &&
                        "SyncFilesConfig".equals(projectElement.getAttributeValue("name"))) {
                    componentElement = projectElement; // The root element is the component itself
                }
                // If your file could *only* contain the <SyncFilesConfig> tag as root (less likely for .idea files)
                // else if ("SyncFilesConfig".equals(projectElement.getName())) {
                //    componentElement = projectElement;
                // }
            }

            if (componentElement != null) {
                SyncFilesConfig.State newState = XmlSerializer.deserialize(componentElement, SyncFilesConfig.State.class);
                if (newState != null) {
                    configService.loadState(newState);
                    LOG.info("[" + project.getName() + "] SyncFilesConfig reloaded successfully using JDOMUtil.load.");
                    return true;
                } else {
                    LOG.warn("[" + project.getName() + "] Failed to deserialize new state from <component name='SyncFilesConfig'> JDOM Element. New state is null. File: " + configFilePathStr);
                    return false;
                }
            } else {
                LOG.warn("[" + project.getName() + "] Could not find the <component name='SyncFilesConfig'> element within the XML file: " + configFilePathStr);
                return false;
            }

        } catch (org.jdom.JDOMException e) { // JDOMUtil.load can throw JDOMException
            LOG.error("[" + project.getName() + "] JDOM parsing error for config file " + configFilePathStr + ": " + e.getMessage(), e);
            return false;
        } catch (java.io.IOException e) { // InputStream operations or JDOMUtil.load can throw IOException
            LOG.error("[" + project.getName() + "] IOException while reading/parsing config file " + configFilePathStr + ": " + e.getMessage(), e);
            return false;
        } catch (Exception e) { // Catch-all for other exceptions like XmlSerializationException
            LOG.error("[" + project.getName() + "] General error parsing or reloading config from " + configFilePathStr + ": " + e.getMessage(), e);
            return false;
        }
    }

    public static String isDirectoryAfterMacroExpansion(@NotNull Project project, @Nullable String pathWithMaybeMacros) {
        if (pathWithMaybeMacros == null || pathWithMaybeMacros.trim().isEmpty()) {
            System.err.println("Input path is null or empty.");
            return pathWithMaybeMacros;
        }

        String pathForSystemInteraction;
        String expandedPath = null;
        // Check if the path string likely contains a macro.
        // Common macros start with '$' and end with '$' or are like '$PROJECT_DIR$'.
        // A simple check for '$' is often sufficient as a heuristic.
        if (pathWithMaybeMacros.contains("$")) {
            PathMacroManager macroManager = PathMacroManager.getInstance(project);
            expandedPath = macroManager.expandPath(pathWithMaybeMacros);

            if (expandedPath == null || expandedPath.trim().isEmpty()) {
                System.err.println("Path '" + pathWithMaybeMacros + "' could not be expanded or resulted in an empty path.");
                return pathWithMaybeMacros; // Macro expansion failed or resulted in nothing usable
            }
            return expandedPath;
        }
        return  pathWithMaybeMacros;
    }
}