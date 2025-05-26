// File: SmartWorkflowService.java
package com.example.syncfiles.logic;

import com.example.syncfiles.*;
import com.example.syncfiles.config.smartworkflow.SmartPlatformConfig;
import com.example.syncfiles.config.smartworkflow.SmartWatchEntry;
import com.example.syncfiles.config.smartworkflow.SmartWorkflowRootConfig;
import com.example.syncfiles.notifiers.FileDownloadFinishedNotifier; // 你的下载完成通知接口
import com.example.syncfiles.notifiers.SyncFilesNotifier;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SmartWorkflowService implements Disposable { // 实现 Disposable
    public static final Logger LOG = Logger.getInstance(SmartWorkflowService.class);
    private final Project project;
    private MessageBusConnection connection; // MessageBus 连接

    // ★ 用于存储当前正在处理的 workflow 的平台配置 ★
    private SmartPlatformConfig pendingPlatformConfig;

    // 构造函数 (ProjectService 会由 IntelliJ 容器实例化)
    public SmartWorkflowService(@NotNull Project project) {
        this.project = project;
        // 订阅下载完成事件
        connection = project.getMessageBus().connect(this); // 使用 this (Disposable) 来自动管理连接生命周期
        connection.subscribe(FileDownloadFinishedNotifier.TOPIC, new FileDownloadFinishedNotifier() {
            @Override
            public void downloadFinished() {
                LOG.info("FileDownloadFinishedNotifier event received by SmartWorkflowService.");
                // 在EDT或后台线程中执行后续操作，取决于finalizeWorkflowConfigurationAfterDownload的耗时
                ApplicationManager.getApplication().invokeLater(() -> { // 或者 runReadActionInSmartMode / runWriteAction
                    finalizeWorkflowConfigurationAfterDownload();
                });
            }
        });
        LOG.info("SmartWorkflowService initialized and subscribed to FileDownloadFinishedNotifier for project: " + project.getName());
    }

    /**
     * 阶段 1: 由 Action 调用。解析YAML，存储平台配置，更新 Mappings，触发 GitHub 文件同步。
     */
    public void prepareWorkflowFromYaml(@NotNull String yamlContent) {
        LOG.info("SmartWorkflow Phase 1: Preparing workflow from YAML content.");
        this.pendingPlatformConfig = null; // 重置状态，以防上次未清理

        SmartWorkflowRootConfig rootConfig = parseYamlToPojos(yamlContent);
        if (rootConfig == null || rootConfig.getPlatforms() == null) {
            String msg = "Failed to parse YAML or no 'platforms' section found.";
            LOG.error(msg + " Aborting Smart Workflow.");
            showErrorDialogInEdt("YAML Parsing Error", msg);
            return;
        }

        String platformKey = getCurrentPlatformKey();
        SmartPlatformConfig parsedPlatformConfig = rootConfig.getPlatforms().get(platformKey);

        if (parsedPlatformConfig == null) {
            String msg = "No configuration found for current platform: '" + platformKey + "' in YAML. Aborting Smart Workflow.";
            LOG.warn(msg);
            showInfoDialogInEdt("Missing Platform Configuration", msg);
            return;
        }

        // ★ 将解析得到的平台配置存储为成员变量，供下载完成后使用 ★
        this.pendingPlatformConfig = parsedPlatformConfig;
        LOG.info("Successfully parsed YAML and stored configuration for platform: " + platformKey);

        SyncFilesConfig pluginConfig = SyncFilesConfig.getInstance(project);
        if (pluginConfig == null) {
            LOG.error("Failed to get SyncFilesConfig instance. Aborting workflow.");
            this.pendingPlatformConfig = null; // 清理状态
            showErrorDialogInEdt("Configuration Error", "Could not access plugin settings.");
            return;
        }

        // 更新 Mappings
        String rawSourceUrl = pendingPlatformConfig.getSourceUrl();
        String rawTargetDir = pendingPlatformConfig.getTargetDir();

        if (StringUtil.isEmptyOrSpaces(rawSourceUrl) || StringUtil.isEmptyOrSpaces(rawTargetDir)) {
            String msg = "YAML for platform '" + platformKey + "' is missing 'sourceUrl' or 'targetDir'.";
            LOG.warn(msg + " Sync will not be triggered based on these fields.");
            showInfoDialogInEdt("Incomplete YAML Configuration", msg + "\nFile synchronization for these specific paths will be skipped.");
            // 即使 mapping 不完整，也可能仍要继续（如果将来 workflow 还做其他事），但下载不会发生
            // 或者，你可以在这里决定是否完全中止
            // this.pendingPlatformConfig = null; // 清理状态，因为关键信息缺失
            // return;
        } else {
            String resolvedSourceUrl = resolveValue(rawSourceUrl);
            String resolvedTargetDir = resolveValue(rawTargetDir);

            if (StringUtil.isEmptyOrSpaces(resolvedTargetDir) || StringUtil.isEmptyOrSpaces(resolvedSourceUrl)) {
                String msg = "Could not resolve 'sourceUrl' or 'targetDir' from YAML. Mappings not updated.";
                LOG.warn(msg + " Raw Source: " + rawSourceUrl + ", Raw Target: " + rawTargetDir);
                showErrorDialogInEdt("Configuration Error", msg);
                this.pendingPlatformConfig = null; // 清理状态
                return;
            }

            List<Mapping> newMappings = new ArrayList<>();
            newMappings.add(new Mapping(resolvedSourceUrl, resolvedTargetDir));
            pluginConfig.setMappings(newMappings);
            LOG.info("SyncFilesConfig mappings updated from YAML: Source='" + resolvedSourceUrl + "', Target='" + resolvedTargetDir + "'");
        }

        // ★★★ 触发 GitHub 文件同步 ★★★
        LOG.info("Triggering SyncAction.syncFiles(project) with updated mappings.");
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                new SyncAction(true).syncFiles(project); // SyncAction 完成后会发布 FileDownloadFinishedNotifier 事件
                LOG.info("SyncAction.syncFiles(project) successfully invoked.");
                // 此时不直接显示成功消息，等待 downloadFinished 事件后的最终结果
            } catch (Exception e) {
                LOG.error("Error invoking SyncAction.syncFiles(project)", e);
                this.pendingPlatformConfig = null; // 清理状态，因为同步触发失败
                showErrorDialogInEdt("Synchronization Error", "Failed to initiate file synchronization: " + e.getMessage());
            }
        });
    }

    /**
     * 阶段 2: 由 FileDownloadFinishedNotifier 事件触发。
     * 使用存储的 pendingPlatformConfig 更新 python路径, env, 和 ScriptEntries.
     */
    private void finalizeWorkflowConfigurationAfterDownload() {
        if (this.pendingPlatformConfig == null) {
            LOG.info("finalizeWorkflowConfigurationAfterDownload called, but no pending platform config. Ignoring.");
            return; // 没有进行中的 workflow
        }

        LOG.info("SmartWorkflow Phase 2: Finalizing configuration after file download.");
        SmartPlatformConfig currentPlatformPojo = this.pendingPlatformConfig; // 使用存储的配置
        this.pendingPlatformConfig = null; // ★ 清理状态，表示此 workflow 的这个实例已处理 ★

        SyncFilesConfig pluginConfig = SyncFilesConfig.getInstance(project);
        if (pluginConfig == null) {
            LOG.error("Failed to get SyncFilesConfig instance in Phase 2. Cannot finalize.");
            return;
        }
        // ★★★ 增强的路径和环境变量处理 ★★★

        // --- 1. 处理 Python Executable Path ---
        String yamlPythonExecPath = resolveValue(currentPlatformPojo.getPythonExecutablePath());
        String finalPythonExecPath = null;

        if (!StringUtil.isEmptyOrSpaces(yamlPythonExecPath) && isValidFilePath(yamlPythonExecPath)) {
            finalPythonExecPath = yamlPythonExecPath;
            LOG.info("Using Python Executable from YAML (valid): " + finalPythonExecPath);
        } else {
            if (!StringUtil.isEmptyOrSpaces(yamlPythonExecPath)) {
                LOG.warn("Python Executable Path from YAML is invalid or file not found: '" + yamlPythonExecPath + "'. Attempting to find system Python.");
            } else {
                LOG.info("Python Executable Path not provided in YAML. Attempting to find system Python.");
            }
            // 尝试从系统环境智能查找 Python
            finalPythonExecPath = findSystemPythonExecutable();
            if (!StringUtil.isEmptyOrSpaces(finalPythonExecPath)) {
                LOG.info("Found system Python Executable: " + finalPythonExecPath);
            } else {
                LOG.warn("Could not find a system Python Executable. Python execution might fail.");
                // 如果最终还是没找到，可以决定是否回退到 YAML 的值（即使它无效），或者留空
                // 为了安全，如果 YAML 的值无效且系统也找不到，我们倾向于不设置一个无效的路径
                // finalPythonExecPath = yamlPythonExecPath; // (可选回退)
                showInfoDialogInEdt("Python Executable Not Found",
                        "Could not validate Python executable from YAML and failed to find a system Python." +
                                (StringUtil.isEmptyOrSpaces(yamlPythonExecPath) ? "" : "\nYAML path was: " + yamlPythonExecPath) +
                                "\nPlease configure it manually in settings if Python script execution is needed.");
            }
        }
        if (!StringUtil.isEmptyOrSpaces(finalPythonExecPath)) {
            pluginConfig.setPythonExecutablePath(finalPythonExecPath);
            LOG.info("Final Python Executable Path set to: " + finalPythonExecPath);
        }


        // --- 2. 处理 Python Script Path (脚本根目录) ---
        // 首先获取已下载/同步的 targetDir，因为脚本路径可能在其内部
        String resolvedTargetDir = resolveValue(currentPlatformPojo.getTargetDir()); // 这个应该在 Phase 1 解析并用于下载
        if (StringUtil.isEmptyOrSpaces(resolvedTargetDir) || !isValidDirectoryPath(resolvedTargetDir)) {
            LOG.error("Target directory '" + resolvedTargetDir + "' is not valid or not set. Cannot reliably determine script path.");
            showErrorDialogInEdt("Configuration Error", "The target directory for downloaded files is invalid. Script path cannot be determined.");
            // 如果 targetDir 无效，后续处理 ScriptEntry 可能会失败，这里可以提前中止或标记错误
        }

        String yamlPythonScriptPath = resolveValue(currentPlatformPojo.getPythonScriptPath());
        String finalPythonScriptPath = null;

        if (!StringUtil.isEmptyOrSpaces(yamlPythonScriptPath) && isValidDirectoryPath(yamlPythonScriptPath)) {
            finalPythonScriptPath = yamlPythonScriptPath;
            LOG.info("Using Python Script Path from YAML (valid): " + finalPythonScriptPath);
        } else {
            if (!StringUtil.isEmptyOrSpaces(yamlPythonScriptPath)) {
                LOG.warn("Python Script Path from YAML is invalid or not a directory: '" + yamlPythonScriptPath + "'. Attempting to find within targetDir.");
            } else {
                LOG.info("Python Script Path not provided in YAML. Attempting to find within targetDir: " + resolvedTargetDir);
            }
            // 智能查找：在 resolvedTargetDir (.clion) 内部递归查找
            if (!StringUtil.isEmptyOrSpaces(resolvedTargetDir) && isValidDirectoryPath(resolvedTargetDir)) {
                finalPythonScriptPath = findPythonScriptsRootInDirectory(Paths.get(resolvedTargetDir));
                if (!StringUtil.isEmptyOrSpaces(finalPythonScriptPath)) {
                    LOG.info("Found Python Script Path by searching in targetDir: " + finalPythonScriptPath);
                } else {
                    LOG.warn("Could not find a suitable Python script directory within " + resolvedTargetDir);
                    // 如果找不到，可以决定是否回退到 YAML 的值（即使它无效），或留空
                    // finalPythonScriptPath = yamlPythonScriptPath; // (可选回退)
                    showInfoDialogInEdt("Python Scripts Directory Not Found",
                            "Could not validate Python scripts directory from YAML and failed to find one within the downloaded target directory (" + resolvedTargetDir +")." +
                                    (StringUtil.isEmptyOrSpaces(yamlPythonScriptPath) ? "" : "\nYAML path was: " + yamlPythonScriptPath) +
                                    "\nScript-related features might not work correctly.");
                }
            }
        }
        if (!StringUtil.isEmptyOrSpaces(finalPythonScriptPath)) {
            pluginConfig.setPythonScriptPath(finalPythonScriptPath);
            LOG.info("Final Python Script Path set to: " + finalPythonScriptPath);
        }


        // --- 3. 处理 Environment Variables ---
        Map<String, String> yamlEnvVars = currentPlatformPojo.getEnvVariables();
        Map<String, String> effectiveEnvVars = new HashMap<>();

        // a. 添加 IDE/系统提供的常用变量 (这些变量的 *值* 应该是已经被 resolveValue 解析过的)
        // 这些通常是在执行脚本时，由 ProcessBuilder 或你的脚本执行逻辑动态注入的，
        // 而不是直接写入 SyncFilesConfig 的 envVariables 字段（除非这些是用户想持久化的特定值）。
        // SyncFilesConfig.envVariables 通常存储用户在UI或YAML中明确定义要持久化的环境变量。

        // 我们这里的目标是：YAML中定义的 envVariables + 一些自动添加的通用变量（如果它们不在YAML中）
        // 这里的 resolveValue 是为了解析 YAML 中可能存在的 "$PROJECT_DIR$" 等
        if (project != null && project.getBasePath() != null) {
            effectiveEnvVars.put("PROJECT_DIR", project.getBasePath()); // 实际路径
        }
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            effectiveEnvVars.put("USER_HOME", userHome); // 实际路径
        }
        effectiveEnvVars.put("SYSTEM_TYPE", getCurrentPlatformKey()); // windows, linux, macos

        // b. 合并来自 YAML 的环境变量，YAML 的具有更高优先级（覆盖上面自动添加的同名变量）
        if (yamlEnvVars != null) {
            for (Map.Entry<String, String> entry : yamlEnvVars.entrySet()) {
                effectiveEnvVars.put(entry.getKey(), resolveValue(entry.getValue())); // 解析 YAML 中的值
            }
        }
        pluginConfig.setEnvVariables(effectiveEnvVars);
        LOG.info("Final Environment Variables set: " + effectiveEnvVars);


        // --- 4. 处理 Watch Entries (这部分逻辑基本不变，但它现在依赖于上面确定的 finalPythonScriptPath) ---
        // String actualScriptBasePathStr = pluginConfig.getPythonScriptPath(); // 这现在就是 finalPythonScriptPath
        // ... (这部分代码参考上一条回复中 finalizeWorkflowConfigurationAfterDownload 的 watch entries 处理部分)
        // ... 确保它使用的是 pluginConfig 中刚刚被 smart logic 设置的 pythonScriptPath ...
        // ... (主要修改是：日志、错误处理，以及确保 scriptBasePath 来自 pluginConfig.getPythonScriptPath() 的最新值)

        String actualScriptBasePathStr = pluginConfig.getPythonScriptPath(); // 获取刚刚智能设置的路径
        if (StringUtil.isEmptyOrSpaces(actualScriptBasePathStr) || !isValidDirectoryPath(actualScriptBasePathStr)) {
            LOG.warn("Python Script Path (final) is not valid. Cannot process watch entries from YAML.");
        } else {
            // ... (之前处理 watch entries 的逻辑，使用 actualScriptBasePathStr 作为基准) ...
            // (这部分逻辑与上一版回复中的类似，确保它读取 pluginConfig.getPythonScriptPath() 的最新值)
            Path scriptBasePath = Paths.get(actualScriptBasePathStr);
            List<SmartWatchEntry> smartWatchEntries = currentPlatformPojo.getWatchEntries();
            if (smartWatchEntries != null && !smartWatchEntries.isEmpty()) {
                LOG.info("Processing " + smartWatchEntries.size() + " watch entries from YAML to update ScriptEntries.");
                List<ScriptGroup> scriptGroups = new ArrayList<>(pluginConfig.getScriptGroups());
                ScriptGroup defaultGroup = scriptGroups.stream()
                        .filter(g -> ScriptGroup.DEFAULT_GROUP_ID.equals(g.id))
                        .findFirst().orElseGet(() -> {
                            ScriptGroup dg = new ScriptGroup(ScriptGroup.DEFAULT_GROUP_ID, ScriptGroup.DEFAULT_GROUP_NAME);
                            scriptGroups.add(0, dg);
                            return dg;
                        });

                boolean scriptConfigChanged = false;
                for (SmartWatchEntry smartEntry : smartWatchEntries) {
                    String rawOnEventScript = smartEntry.getOnEventScript();
                    if (StringUtil.isEmptyOrSpaces(rawOnEventScript)) continue;

                    String tempResolvedScript = resolveValue(rawOnEventScript);
                    String relativeScriptPath;

                    try {
                        Path fullScriptPathCandidate = Paths.get(tempResolvedScript);
                        if (scriptBasePath.isAbsolute() && fullScriptPathCandidate.startsWith(scriptBasePath)) { // 只有当 scriptBasePath 是绝对路径时，startsWith才有意义
                            relativeScriptPath = scriptBasePath.relativize(fullScriptPathCandidate).toString().replace('\\', '/');
                        } else if (!fullScriptPathCandidate.isAbsolute()) { // 如果是相对路径，则直接使用
                            relativeScriptPath = tempResolvedScript.replace('\\', '/');
                        } else { // 绝对路径，但不在 scriptBasePath 下
                            LOG.warn("onEventScript '" + tempResolvedScript + "' is absolute and not within the Python Script Path '" + actualScriptBasePathStr + "'. Skipping.");
                            continue;
                        }

                        Path finalScriptInBasePath = scriptBasePath.resolve(relativeScriptPath).normalize();
                        if (!Files.isRegularFile(finalScriptInBasePath)) {
                            LOG.warn("Script file for onEventScript '" + relativeScriptPath + "' (resolved to: " + finalScriptInBasePath + ") not found. Skipping.");
                            continue;
                        }
                        // 使用规范化后的相对路径
                        relativeScriptPath = scriptBasePath.relativize(finalScriptInBasePath).toString().replace('\\','/');

                    } catch (IllegalArgumentException e) { // IllegalArgumentException for relativize
                        LOG.warn("Invalid path for onEventScript: '" + tempResolvedScript + "'. Skipping.", e);
                        continue;
                    }

                    final String finalRelativePath = relativeScriptPath;
                    Optional<ScriptEntry> existingEntryOpt = scriptGroups.stream()
                            .flatMap(g -> g.scripts.stream())
                            .filter(s -> finalRelativePath.equalsIgnoreCase(s.path))
                            .findFirst();

                    if (existingEntryOpt.isPresent()) {
                        // ... (更新逻辑，如果 SmartWatchEntry 将来有更多元数据) ...
                    } else {
                        ScriptEntry newScript = new ScriptEntry(finalRelativePath);
                        defaultGroup.scripts.add(newScript);
                        scriptConfigChanged = true;
                    }
                }
                if (scriptConfigChanged) {
                    defaultGroup.scripts.sort(Comparator.comparing(s -> s.getDisplayName().toLowerCase()));
                    pluginConfig.setScriptGroups(scriptGroups);
                    LOG.info("ScriptGroups updated in SyncFilesConfig due to YAML watchEntries.");
                }
            } else {
                LOG.info("No watch entries in YAML to process for ScriptEntries for current platform.");
            }
        }


        // ★★★ 通知UI刷新 ★★★
        // ToolWindowFactory 监听这个事件来调用 updateScriptTree(true)
        ApplicationManager.getApplication().invokeLater(() -> {
            project.getMessageBus().syncPublisher(SyncFilesNotifier.TOPIC).configurationChanged();
            LOG.info("Published configurationChanged event to refresh UI after Smart Workflow finalization.");
            showInfoDialogInEdt("Smart Workflow Completed", "Smart Workflow successfully applied all configurations.");
        });
    }


    // --- 私有辅助方法 (parseYamlToPojos, getCurrentPlatformKey, resolveValue, isValidFilePath, isValidDirectoryPath, showErrorDialogInEdt, showInfoDialogInEdt) 保持不变 ---
    private SmartWorkflowRootConfig parseYamlToPojos(String yamlContent) {
        if (yamlContent == null || yamlContent.trim().isEmpty()) {
            LOG.error("YAML content is empty or null for parsing.");
            return null;
        }
        try {
            // 创建 LoaderOptions 并配置
            LoaderOptions loaderOptions = new LoaderOptions();
            loaderOptions.setAllowRecursiveKeys(true);
            loaderOptions.setAllowDuplicateKeys(true);

// 使用 Class + LoaderOptions 构建 Constructor（SnakeYAML 2.x 推荐方式）
            Constructor constructor = new Constructor(SmartWorkflowRootConfig.class, loaderOptions);
            Yaml yaml = new Yaml(constructor);
            return yaml.loadAs(yamlContent, SmartWorkflowRootConfig.class);
        } catch (YAMLException e) {
            LOG.error("SnakeYAML parsing error: " + e.getMessage(), e);
            return null;
        } catch (Exception e) { // 更广泛的捕获
            LOG.error("Unexpected error during YAML parsing or POJO construction: " + e.getMessage(), e);
            return null;
        }
    }

    private String getCurrentPlatformKey() {
        if (SystemInfo.isWindows) return "windows";
        if (SystemInfo.isMac) return "macos";
        if (SystemInfo.isLinux) return "linux";
        LOG.warn("Could not determine current OS platform. Defaulting to 'unknown'.");
        return "unknown";
    }

    private String resolveValue(String value) {
        if (value == null) return null;
        String resolvedValue = value;

        if (project != null && project.getBasePath() != null) {
            resolvedValue = resolvedValue.replace("$PROJECT_DIR$", project.getBasePath());
        } else if (resolvedValue.contains("$PROJECT_DIR$")) {
            LOG.warn("'$PROJECT_DIR$' used in YAML but no project is open or project path is unavailable. Value: '" + value + "'");
        }

        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            resolvedValue = resolvedValue.replace("$USER_HOME$", userHome);
        } else if (resolvedValue.contains("$USER_HOME$")) {
            LOG.warn("'$USER_HOME$' used in YAML but 'user.home' system property is null. Value: '" + value + "'");
        }
        if (resolvedValue.matches(".*\\$[^$ ]+\\$.*")) {
            LOG.warn("Potential unresolved variable pattern in value: '" + resolvedValue + "' (original: '" + value + "')");
        }
        return resolvedValue;
    }
    private boolean isValidFilePath(String pathStr) {
        if (pathStr == null || pathStr.trim().isEmpty()) return false;
        try {
            Path path = Paths.get(pathStr); // 可能抛出 InvalidPathException
            return Files.isRegularFile(path); // 检查文件是否存在且是普通文件
        } catch (InvalidPathException e) {
            LOG.warn("Path validation (file): Invalid path string '" + pathStr + "'", e);
            return false;
        } catch (SecurityException se) {
            LOG.warn("Path validation (file): Security exception for path '" + pathStr + "'", se);
            return false;
        }
    }

    private boolean isValidDirectoryPath(String pathStr) {
        if (pathStr == null || pathStr.trim().isEmpty()) return false;
        try {
            Path path = Paths.get(pathStr);
            return Files.isDirectory(path); // 检查路径是否存在且是目录
        } catch (InvalidPathException e) {
            LOG.warn("Path validation (directory): Invalid path string '" + pathStr + "'", e);
            return false;
        } catch (SecurityException se) {
            LOG.warn("Path validation (directory): Security exception for path '" + pathStr + "'", se);
            return false;
        }
    }
    private void showErrorDialogInEdt(String title, String message) {
        ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(project, message, title));
    }
    private void showInfoDialogInEdt(String title, String message) {
        ApplicationManager.getApplication().invokeLater(() -> Messages.showInfoMessage(project, message, title));
    }

    // 在 SmartWorkflowService.java 中

    /**
     * 尝试在系统中查找 Python 可执行文件。
     * 可以扩展这个方法以包含更复杂的查找逻辑 (e.g., anaconda, pyenv, virtualenv)。
     * @return Python可执行文件的路径，如果找到；否则返回null。
     */
    private String findSystemPythonExecutable() {
        // 1. 检查 PATH 环境变量
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String[] paths = pathEnv.split(File.pathSeparator);
            String[] commonPythonNames = SystemInfo.isWindows ?
                    new String[]{"python.exe", "python3.exe"} :
                    new String[]{"python3", "python"};

            for (String dir : paths) {
                for (String pyName : commonPythonNames) {
                    Path pyPath = Paths.get(dir, pyName);
                    if (isValidFilePath(pyPath.toString())) {
                        return pyPath.toString();
                    }
                }
            }
        }

        // 2. 检查常见的安装位置 (非常基础的示例)
        List<String> commonLocations = new ArrayList<>();
        if (SystemInfo.isWindows) {
            // String programFiles = System.getenv("ProgramFiles");
            // String localAppData = System.getenv("LOCALAPPDATA");
            // if (programFiles != null) commonLocations.add(programFiles + "\\PythonXX\\python.exe"); (XX需要版本)
            // if (localAppData != null) commonLocations.add(localAppData + "\\Programs\\Python\\PythonXX\\python.exe");
            // 更复杂的Windows查找可以使用 `where python` 命令或检查注册表，但那超出了简单范围
        } else if (SystemInfo.isMac) {
            commonLocations.add("/usr/local/bin/python3");
            commonLocations.add("/usr/bin/python3");
        } else if (SystemInfo.isLinux) {
            commonLocations.add("/usr/bin/python3");
            commonLocations.add("/usr/local/bin/python3");
        }
        for (String loc : commonLocations) {
            if (isValidFilePath(loc)) {
                return loc;
            }
        }

        // 3. 尝试特定环境管理器（简化）
        // PYENV
        String pyenvRoot = System.getenv("PYENV_ROOT");
        if (pyenvRoot != null) {
            // 可以尝试在 $PYENV_ROOT/versions/*/bin/python 中查找
            // 但这需要列出版本，比较复杂
        }
        // VIRTUAL_ENV
        String virtualEnv = System.getenv("VIRTUAL_ENV");
        if (virtualEnv != null) {
            Path venvPy = Paths.get(virtualEnv, "bin", SystemInfo.isWindows ? "python.exe" : "python");
            if (isValidFilePath(venvPy.toString())) {
                return venvPy.toString();
            }
        }


        LOG.info("No system Python executable found through basic checks.");
        return null;
    }

    /**
     * 在指定目录下递归查找一个合适的 Python 脚本根目录。
     * 策略：
     * 1. 如果目录下直接有 .py 文件，则返回该目录。
     * 2. 如果有名为 "scripts", "py-script", "src", "python" 的子目录且它们包含 .py 文件，返回那个子目录。
     * 3. 递归查找，如果找到任何包含 .py 文件的子目录，返回第一个找到的。
     * @param directoryToSearch 起始搜索目录 (例如，从YAML解析并下载后的 targetDir)
     * @return 合适的脚本根目录路径，如果找到；否则返回null。
     */
    private String findPythonScriptsRootInDirectory(Path directoryToSearch) {
        if (!Files.isDirectory(directoryToSearch)) {
            return null;
        }

        // 优先查找的子目录名
        List<String> preferredSubDirs = Arrays.asList("scripts", "py-script", "python", "src", "lib");

        // 检查当前目录是否直接包含 .py 文件
        try (Stream<Path> stream = Files.list(directoryToSearch)) {
            if (stream.anyMatch(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".py"))) {
                return directoryToSearch.toString();
            }
        } catch (IOException e) {
            LOG.warn("Error listing files in " + directoryToSearch, e);
        }

        // 检查优先子目录
        for (String subDirName : preferredSubDirs) {
            Path preferredPath = directoryToSearch.resolve(subDirName);
            if (Files.isDirectory(preferredPath)) {
                try (Stream<Path> stream = Files.list(preferredPath)) {
                    if (stream.anyMatch(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".py"))) {
                        return preferredPath.toString();
                    }
                } catch (IOException e) {
                    LOG.warn("Error listing files in preferred subdir " + preferredPath, e);
                }
            }
        }

        // 如果上面都没找到，进行更广泛的递归查找 (限制深度以避免性能问题)
        // 这里为了简单，我们只查找第一层子目录。如果需要更深，需要一个真正的递归函数。
        // 暂时不实现深度递归，如果需要可以后续添加。
        // 你可以扩展这里的逻辑来查找第一个包含 .py 文件的子目录。

        LOG.info("No specific Python script root found by preferred names in " + directoryToSearch);
        return null; // 或者如果 directoryToSearch 本身就是要用的，即使里面没直接的.py但watchEntry指向里面，也可以返回它
    }
    // 实现 Disposable 接口
    @Override
    public void dispose() {
        LOG.info("SmartWorkflowService disposing for project: " + (project != null ? project.getName() : "null"));
        if (connection != null) {
            // connection.disconnect(); // MessageBusConnection 自身没有 disconnect()。
            // connect(this) 会在 this 被 dispose 时自动处理。
            // 如果是手动 connect() 没有 Disposable，则需要手动 disconnect。
            // Disposer.dispose(connection) 也不对，因为它不是 Disposable。
            // 正确的做法是，如果 connection 是成员变量，并且是在 connect(this) 中创建的，
            // 那么当这个 Service (this) 被 dispose 时，连接会自动断开。
            // 如果是用 Disposer.newDisposable() 创建的临时 disposable，则需要 Disposer.dispose(thatDisposable)。
        }
        this.pendingPlatformConfig = null; // 清理状态
    }
}