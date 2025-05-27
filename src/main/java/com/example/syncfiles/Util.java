package com.example.syncfiles;

import com.intellij.openapi.components.PathMacroManager;
import com.intellij.openapi.project.Project;
// No JDOMUtil needed if using SAXBuilder directly for Document
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
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


    /**
     * Refreshes the Virtual File System for the entire project.
     * Useful after external changes or downloads.
     */
    public static void refreshAllFiles(@NotNull Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            // 1. 尝试全局刷新 (异步)
            // 这会尝试刷新所有已知的VFS根，而不仅仅是当前项目
            System.out.println("Attempting to refresh all VFS roots asynchronously (global)...");
            VirtualFileManager.getInstance().refreshWithoutFileWatcher(false); // false for asynchronous
            System.out.println("Global VFS refresh initiated.");

            // 2. 刷新项目特定基路径 (异步)
            // 这可以确保项目目录被特别关注，即使全局刷新已经包含它，
            // 这样做通常是无害的，有时可能更可靠地触发特定于该路径的更新。
            String basePath = project.getBasePath();
            if (basePath != null) {
                VirtualFile baseDir = LocalFileSystem.getInstance().findFileByPath(basePath);
                if (baseDir != null && baseDir.exists() && baseDir.isDirectory()) {
                    System.out.println("[" + project.getName() + "] Refreshing VFS recursively from base path: " + basePath);
                    // Asynchronous refresh (false = async, true = recursive)
                    baseDir.refresh(false, true);
                    System.out.println("[" + project.getName() + "] Project-specific VFS refresh initiated for: " + basePath);
                } else {
                    System.err.println("[" + project.getName() + "] Failed to find base directory for project-specific VFS refresh: " + basePath +
                            (baseDir == null ? " (baseDir is null)" : " (exists: " + baseDir.exists() + ", isDirectory: " + baseDir.isDirectory() + ")"));
                    // LOG.warn("[" + project.getName() + "] Failed to find base directory for VFS refresh: " + basePath);
                }
            } else {
                System.err.println("[" + project.getName() + "] Project base path is null, cannot perform project-specific VFS refresh.");
                // LOG.warn("[" + project.getName() + "] Project base path is null, cannot refresh VFS.");
            }
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
    @Nullable
    public static String ensureAbsolutePath(@NotNull Project project, @Nullable String pathString) {
        // 1. 检查输入的路径字符串是否为空或null
        if (StringUtil.isEmptyOrSpaces(pathString)) {
            // LOG.debug("输入的路径字符串为 null 或空。");
            return null;
        }

        try {
            // 2. 尝试将字符串转换为 Path 对象。
            //    Paths.get() 可以处理当前操作系统的路径格式 (包括 \ 和 /)
            Path path = Paths.get(pathString);

            String absolutePathString;

            // 3. 判断路径是否已经是绝对路径
            if (path.isAbsolute()) {
                // 如果已经是绝对路径，直接对其进行规范化。
                // path.normalize() 会处理 "." 和 ".." 等。
                // FileUtil.toSystemIndependentName() 会确保所有分隔符是 '/'。
                absolutePathString = FileUtil.toSystemIndependentName(path.normalize().toString());
            } else {
                // 4. 如果是相对路径，获取项目的基路径
                String projectBasePath = project.getBasePath();
                if (projectBasePath == null) {
                    // LOG.warn("项目基路径为 null，无法解析相对路径: " + pathString);
                    return null; // 没有基路径，无法将相对路径转为绝对路径
                }

                // 5. 将项目基路径和相对路径结合起来
                Path basePath = Paths.get(projectBasePath);
                Path resolvedPath = basePath.resolve(path); // resolve 方法会正确处理路径拼接

                // 6. 对拼接后的路径进行规范化，并确保分隔符为 '/'
                absolutePathString = FileUtil.toSystemIndependentName(resolvedPath.normalize().toString());
            }
            return absolutePathString;

        } catch (InvalidPathException e) {
            // LOG.warn("无效的路径字符串: " + pathString, e);
            return null; // 如果路径字符串格式不正确 (例如包含非法字符)
        }
    }

    public static  @NotNull String toWindowsPath(@NotNull String path) {
        if (path.isEmpty()) {
            return "";
        }
        return path.replace('/', '\\');
    }

    /**
     * Converts a file path to use Unix-style forward slash separators.
     * All backslashes ('\') will be replaced with forward slashes ('/').
     *
     * @param path The input file path string. Can be null.
     * @return The path string with Unix-style separators, or null if the input was null.
     *         Returns an empty string if the input was an empty string.
     */
    public static @NotNull String toUnixPath(@NotNull String path) {
        if (path.isEmpty()) {
            return "";
        }
        return path.replace('\\', '/');
    }
}