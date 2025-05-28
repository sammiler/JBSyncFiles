package com.example.syncfiles;

import com.example.syncfiles.notifiers.FilesChangeNotifier;
import com.example.syncfiles.notifiers.SyncFilesNotifier;
import com.example.syncfiles.util.Util;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.EnvironmentUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.example.syncfiles.util.Util.normalizePath; // 假设 Util.normalizePath 存在且工作正常

public class FileChangeEventWatcherService implements Disposable {
    private static final Logger LOG = Logger.getInstance(FileChangeEventWatcherService.class);
    private final Project project;
    private volatile boolean isRunning = false;
    private volatile boolean isConfigLoaded = false; // 新增：标记配置是否已加载

    // 使用 ConcurrentHashMap 保证线程安全
    private final Map<WatchKey, Path> watchKeyToDirPathMap = new ConcurrentHashMap<>();
    private final List<ActiveWatch> activeWatchers = new ArrayList<>(); // 受 synchronized 方法保护
    private final Set<String> watcherPath = new HashSet<>(); // 受 synchronized 方法保护

    private final ExecutorService scriptExecutorService = Executors.newCachedThreadPool();
    private WatchService nativeWatchService;
    private Thread watcherThread;

    public FileChangeEventWatcherService(Project project) {
        this.project = project;
        LOG.info("FileChangeEventWatcherService created for project: " + project.getName());
        project.getMessageBus().connect(this).subscribe(FilesChangeNotifier.TOPIC, new FilesChangeNotifier() {

            @Override
            public void watchFileChanged(String scriptPathToExecute, String eventType, String affectedFilePath) {
                executeWatchedScript(scriptPathToExecute,eventType,affectedFilePath);
            }
        });
    }

    public synchronized void addWatcherPath(String path) {
        String normalized = normalizePath(project, path); // 确保 normalizePath 返回绝对、规范的路径
        if (normalized == null) {
            LOG.warn("[" + project.getName() + "] addWatcherPath: Normalization failed for path: " + path);
            return;
        }

        boolean newlyAdded = watcherPath.add(normalized);
        LOG.info("[" + project.getName() + "] Processed addWatcherPath for: '" + normalized +
                "'. Newly added: " + newlyAdded +
                ". isConfigLoaded: " + isConfigLoaded +
                ". isRunning: " + isRunning);

        if (newlyAdded && isConfigLoaded) {
            LOG.info("[" + project.getName() + "] Path '" + normalized + "' newly added and config is loaded. Restarting watcher.");
            if (isRunning) {
                stopWatching();
            }
            if (!activeWatchers.isEmpty() || !watcherPath.isEmpty()) {
                startWatching();
            } else {
                LOG.info("[" + project.getName() + "] No paths to watch after adding '" + normalized + "'. Watcher remains stopped.");
            }
        } else if (newlyAdded && !isConfigLoaded) {
            LOG.info("[" + project.getName() + "] Path '" + normalized + "' newly added. Config not yet loaded. Watcher will be started by updateWatchersFromConfig.");
            // 初始化时，不在此处启动 watcher，等待 updateWatchersFromConfig
        } else if (!newlyAdded) {
            LOG.debug("[" + project.getName() + "] Path '" + normalized + "' was already in watcherPath. No action needed by addWatcherPath.");
        }
    }

    public synchronized void removeWatcherPath(String path) {
        String normalized = normalizePath(project, path);
        if (normalized == null) {
            LOG.warn("[" + project.getName() + "] removeWatcherPath: Normalization failed for path: " + path);
            return;
        }

        boolean removed = watcherPath.remove(normalized);
        LOG.info("[" + project.getName() + "] Processed removeWatcherPath for: '" + normalized + "'. Actually removed: " + removed);

        if (removed && isRunning) { // 只有在运行时且确实移除了路径才重启
            LOG.info("[" + project.getName() + "] Path '" + normalized + "' removed and watcher was running. Restarting watcher.");
            stopWatching();
            if (!activeWatchers.isEmpty() || !watcherPath.isEmpty()) {
                startWatching();
            } else {
                LOG.info("[" + project.getName() + "] No paths left to watch after removal. Watcher stopped.");
            }
        }
    }

    public synchronized void updateWatchersFromConfig() {
        String projectName = project.getName();
        LOG.info("[" + projectName + "] Updating watchers from config.");

        if (isRunning) {
            LOG.info("[" + projectName + "] Watcher is running, stopping it before updating config.");
            stopWatching();
        } else {
            LOG.info("[" + projectName + "] Watcher is not running. Proceeding with config update.");
        }

        // 清理旧的 activeWatchers
        // activeWatchers 的访问已通过 synchronized(this) 保护，但 clear() 本身是安全的
        // 为清晰起见，可以显式同步，但由于整个方法已同步，此处不需要额外同步块。
        activeWatchers.clear();
        LOG.debug("[" + projectName + "] Cleared activeWatchers list.");

        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        String pythonExecutable = config.getPythonExecutablePath();

        if (StringUtil.isEmptyOrSpaces(pythonExecutable)) {
            LOG.warn("[" + projectName + "] Python executable is not configured. File event watching will be disabled.");
            isConfigLoaded = true; // 标记配置已尝试加载
            return; // 不启动 watcher
        }
        try {
            Path pythonPath = Paths.get(pythonExecutable);
            if (!Files.isRegularFile(pythonPath)) {
                LOG.warn("[" + projectName + "] Python executable path is invalid or not a file: '" + pythonExecutable + "'. File event watching will be disabled.");
                isConfigLoaded = true;
                return;
            }
        } catch (InvalidPathException e) {
            LOG.warn("[" + projectName + "] Python executable path format is invalid: '" + pythonExecutable + "'. Error: " + e.getMessage() + ". File event watching will be disabled.");
            isConfigLoaded = true;
            return;
        }

        List<WatchEntry> configuredEntries = config.getWatchEntries();
        if (configuredEntries.isEmpty()) {
            LOG.info("[" + projectName + "] No watch entries configured in SyncFilesConfig.");
        } else {
            LOG.info("[" + projectName + "] Found " + configuredEntries.size() + " watch entries in SyncFilesConfig.");
        }


        String projectBasePath = project.getBasePath();

        for (WatchEntry entry : configuredEntries) {
            String watchedPathInput = Util.isDirectoryAfterMacroExpansion(project, entry.watchedPath);
            String scriptToRunPathInput = Util.isDirectoryAfterMacroExpansion(project, entry.onEventScript);

            if (StringUtil.isEmptyOrSpaces(watchedPathInput) || StringUtil.isEmptyOrSpaces(scriptToRunPathInput)) {
                LOG.warn("[" + projectName + "] Skipping invalid watch entry due to empty paths. Watched: '" + watchedPathInput + "', Script: '" + scriptToRunPathInput + "'");
                continue;
            }

            Path fullScriptPathToExecute;
            try {
                Path tempScriptPath = Paths.get(scriptToRunPathInput.replace('\\', '/'));
                if (tempScriptPath.isAbsolute()) {
                    fullScriptPathToExecute = tempScriptPath.normalize();
                } else if (!StringUtil.isEmptyOrSpaces(projectBasePath)) {
                    fullScriptPathToExecute = Paths.get(projectBasePath.replace('\\', '/'), scriptToRunPathInput.replace('\\', '/')).normalize();
                } else {
                    LOG.warn("[" + projectName + "] Cannot resolve relative 'Python Script on Modify': '" + scriptToRunPathInput +
                            "' for 'Path to Watch': '" + watchedPathInput +
                            "' because project base path is not set. Skipping this watch entry.");
                    continue;
                }
                if (!Files.isRegularFile(fullScriptPathToExecute)) {
                    LOG.warn("[" + projectName + "] Script for 'Path to Watch' '" + watchedPathInput +
                            "' does not exist or is not a file: '" + fullScriptPathToExecute + "'. Skipping this watch entry.");
                    continue;
                }
            } catch (InvalidPathException e) {
                LOG.warn("[" + projectName + "] Invalid format for 'Python Script on Modify': '" + scriptToRunPathInput +
                        "' for 'Path to Watch': '" + watchedPathInput + "'. Error: " + e.getMessage() + ". Skipping this watch entry.");
                continue;
            }

            Path absoluteWatchedPathObject;
            try {
                Path tempWatchedPath = Paths.get(watchedPathInput.replace('\\', '/'));
                if (tempWatchedPath.isAbsolute()) {
                    absoluteWatchedPathObject = tempWatchedPath.normalize();
                } else {
                    if (!StringUtil.isEmptyOrSpaces(projectBasePath)) {
                        absoluteWatchedPathObject = Paths.get(projectBasePath.replace('\\', '/'), watchedPathInput.replace('\\', '/')).normalize();
                    } else {
                        LOG.warn("[" + projectName + "] Cannot resolve relative 'Path to Watch': '" + watchedPathInput +
                                "' because project base path is unavailable. Skipping this watch entry.");
                        continue;
                    }
                }
            } catch (InvalidPathException e) {
                LOG.warn("[" + projectName + "] Invalid format for 'Path to Watch': '" + watchedPathInput +
                        "'. Error: " + e.getMessage() + ". Skipping this watch entry.");
                continue;
            }

            String finalNormalizedAbsWatchedPath = absoluteWatchedPathObject.toString().replace('\\', '/');
            boolean pathExists = Files.exists(absoluteWatchedPathObject);
            // isDirectoryPretended: true if user configured this path as a directory (even if it doesn't exist yet, or if it exists as a file)
            // For native WatchService, we watch a dir. If path is a file, we watch its parent.
            // This flag primarily helps in logging and deciding what *kind* of path the user *intended* to watch.
            // The actual registration logic will determine the directory to register based on existence and type.
            boolean isDirectoryPretended = Files.isDirectory(absoluteWatchedPathObject); // More accurately, if it exists AND is a dir.
            // If it doesn't exist, this is false. User's UI might imply directory.
            // For simplicity, let's rely on Files.isDirectory for now.
            // If needed, add a field in WatchEntry from UI: "isDirIntent"

            if (!pathExists) {
                LOG.warn("[" + projectName + "] Configured 'Path to Watch': '" + finalNormalizedAbsWatchedPath +
                        "' currently does not exist. Watch will be added. If it's a file, its parent directory will be watched. " +
                        "If it's intended as a directory, it cannot be watched directly until created.");
            } else if (!isDirectoryPretended && !Files.isRegularFile(absoluteWatchedPathObject)){
                LOG.warn("[" + projectName + "] Configured 'Path to Watch': '" + finalNormalizedAbsWatchedPath +
                        "' exists but is neither a directory nor a regular file. Skipping this watch entry.");
                continue;
            }

            activeWatchers.add(new ActiveWatch(
                    finalNormalizedAbsWatchedPath,
                    fullScriptPathToExecute.toString().replace('\\', '/'),
                    isDirectoryPretended, // True if the configured path itself resolved to an existing directory
                    pathExists
            ));

            String typeMsg = pathExists ? (isDirectoryPretended ? " (Existing Directory)" : " (Existing File)") : " (Path currently non-existent)";
            LOG.info("[" + projectName + "] Adding to activeWatchers: '" + finalNormalizedAbsWatchedPath + "'" + typeMsg +
                    " -> executes '" + fullScriptPathToExecute.toString().replace('\\', '/') + "'");
        }

        isConfigLoaded = true; // Mark that configuration has been loaded (or attempted)

        // Start watching only if there are active watchers or paths in watcherPath
        if (!activeWatchers.isEmpty() || !watcherPath.isEmpty()) {
            LOG.info("[" + projectName + "] Configuration processed. Attempting to start watcher as there are active/watcherPath entries.");
            startWatching();
        } else {
            LOG.info("[" + projectName + "] No active watchers or watcherPath entries to start watching after processing config.");
        }
    }


    private synchronized void startWatching() {
        String projectName = project.getName();
        if (isRunning) {
            LOG.info("[" + projectName + "] Watcher already running. startWatching call ignored.");
            return;
        }
        if (activeWatchers.isEmpty() && watcherPath.isEmpty()) {
            LOG.info("[" + projectName + "] No paths configured to watch. startWatching call ignored.");
            return;
        }

        LOG.info("[" + projectName + "] Attempting to start Native FileChangeEventWatcherService...");

        try {
            if (nativeWatchService != null) {
                LOG.warn("[" + projectName + "] Existing nativeWatchService (identity: " + System.identityHashCode(nativeWatchService) + ") found before creating new one. Closing it first.");
                try {
                    nativeWatchService.close();
                } catch (IOException e) {
                    LOG.error("[" + projectName + "] Error closing pre-existing nativeWatchService: " + e.getMessage(), e);
                }
                nativeWatchService = null;
            }
            nativeWatchService = FileSystems.getDefault().newWatchService();
            LOG.info("[" + projectName + "] Native WatchService created (identity: " + System.identityHashCode(nativeWatchService) + ").");
        } catch (IOException e) {
            LOG.error("[" + projectName + "] Failed to create native WatchService: " + e.getMessage(), e);
            nativeWatchService = null;
            return;
        }

        // isRunning is set to true *after* WatchService creation and before thread start
        // watchKeyToDirPathMap is cleared here to ensure it's fresh for this new WatchService instance
        watchKeyToDirPathMap.clear();
        LOG.info("[" + projectName + "] watchKeyToDirPathMap cleared at the beginning of startWatching. Size: " + watchKeyToDirPathMap.size());

        Set<Path> registeredDirectoriesInThisCycle = new HashSet<>(); // Tracks dirs registered in *this* startWatching call

        // --- 1. Register paths from activeWatchers ---
        LOG.debug("[" + projectName + "] Processing activeWatchers for registration. Count: " + activeWatchers.size());
        for (ActiveWatch watch : activeWatchers) {
            Path dirToRegister = null;
            try {
                Path pathObj = Paths.get(watch.watchedPath);

                if (watch.pathExists && watch.isDirectoryPretended) {
                    dirToRegister = pathObj;
                } else if (watch.pathExists && !watch.isDirectoryPretended) {
                    dirToRegister = pathObj.getParent();
                } else if (!watch.pathExists && watch.isDirectoryPretended) {
                    LOG.warn("[" + projectName + "] ActiveWatch path '" + watch.watchedPath + "' (intended as dir) does not exist. Cannot watch non-existent directory directly.");
                    continue;
                } else { // Not exists, file intent
                    dirToRegister = pathObj.getParent();
                }

                if (dirToRegister == null) {
                    LOG.warn("[" + projectName + "] Cannot determine directory for active watch '" + watch.watchedPath + "' (parent is null or path issue). Skipping.");
                    continue;
                }
                if (!Files.exists(dirToRegister) || !Files.isDirectory(dirToRegister)) {
                    LOG.warn("[" + projectName + "] Directory to register '" + dirToRegister + "' (for active watch '" + watch.watchedPath + "') does not exist or is not a directory. Skipping.");
                    continue;
                }

                if (registeredDirectoriesInThisCycle.contains(dirToRegister)) {
                    LOG.info("[" + projectName + "] Directory '" + dirToRegister + "' (for active watch '" + watch.watchedPath + "') already processed in this startWatching cycle. Skipping duplicate registration.");
                    continue;
                }

                WatchKey key = dirToRegister.register(nativeWatchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
                registeredDirectoriesInThisCycle.add(dirToRegister);
                int keyIdentityHashCode = System.identityHashCode(key);

                LOG.info("[" + projectName + "] Path (from activeWatch) to be stored in map for Key (identity: " + keyIdentityHashCode + "): " +
                        "toString: '" + dirToRegister.toString() + "', " +
                        "hashCode: " + dirToRegister.hashCode() + ", " +
                        "isAbsolute: " + dirToRegister.isAbsolute() + ", " +
                        "FileSystem: " + dirToRegister.getFileSystem().toString());
                try {
                    LOG.info("[" + projectName + "] Path (from activeWatch) to be stored - toRealPath(): '" + dirToRegister.toRealPath().toString() + "'");
                } catch (IOException e) {
                    LOG.warn("[" + projectName + "] Path (from activeWatch) to be stored - toRealPath() failed for '" + dirToRegister + "': " + e.getMessage());
                }

                Path previousPathForKey = watchKeyToDirPathMap.put(key, dirToRegister);
                if (previousPathForKey != null) {
                    LOG.warn("[" + projectName + "] CRITICAL: Replaced existing WatchKey mapping during active watch reg! " +
                            "Key (identity: " + keyIdentityHashCode + ", valid: " + key.isValid() + ") was for Path: '" + previousPathForKey +
                            "', now for Path: '" + dirToRegister + "'. Map size: " + watchKeyToDirPathMap.size());
                } else {
                    LOG.info("[" + projectName + "] Registered new WatchKey for active watch (identity: " + keyIdentityHashCode +
                            ", valid: " + key.isValid() + ") for Directory: '" + dirToRegister +
                            "' (derived from configured path: '" + watch.watchedPath + "'). Map size: " + watchKeyToDirPathMap.size());
                }
            } catch (Exception e) {
                LOG.error("[" + projectName + "] Exception registering active watch '" + watch.watchedPath + "': " + e.getMessage(), e);
            }
        }

        // --- 2. Register paths from watcherPath (e.g., config files) ---
        LOG.debug("[" + projectName + "] Processing watcherPath for registration. Count: " + watcherPath.size());
        Set<String> currentWatcherPathCopy = new HashSet<>(watcherPath); // Use copy
        for (String confPathStr : currentWatcherPathCopy) {
            Path dirToRegisterForConfig = null;
            try {
                Path confPathObj = Paths.get(confPathStr);
                if (Files.exists(confPathObj) && Files.isDirectory(confPathObj)) {
                    dirToRegisterForConfig = confPathObj;
                } else {
                    dirToRegisterForConfig = confPathObj.getParent();
                }

                if (dirToRegisterForConfig == null) {
                    LOG.warn("[" + projectName + "] Cannot determine directory for watcherPath entry '" + confPathStr + "'. Skipping.");
                    continue;
                }
                if (!Files.exists(dirToRegisterForConfig) || !Files.isDirectory(dirToRegisterForConfig)) {
                    LOG.warn("[" + projectName + "] Directory to register '" + dirToRegisterForConfig + "' (for watcherPath '" + confPathStr + "') does not exist or is not a directory. Skipping.");
                    continue;
                }

                if (registeredDirectoriesInThisCycle.contains(dirToRegisterForConfig)) {
                    LOG.info("[" + projectName + "] Directory '" + dirToRegisterForConfig + "' (for watcherPath '" + confPathStr + "') already processed in this startWatching cycle. Skipping.");
                    continue;
                }

                WatchKey key = dirToRegisterForConfig.register(nativeWatchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
                registeredDirectoriesInThisCycle.add(dirToRegisterForConfig);
                int keyIdentityHashCode = System.identityHashCode(key);

                LOG.info("[" + projectName + "] Path (from watcherPath) to be stored in map for Key (identity: " + keyIdentityHashCode + "): " +
                        "toString: '" + dirToRegisterForConfig.toString() + "', " +
                        "hashCode: " + dirToRegisterForConfig.hashCode() + ", " +
                        "isAbsolute: " + dirToRegisterForConfig.isAbsolute() + ", " +
                        "FileSystem: " + dirToRegisterForConfig.getFileSystem().toString());
                try {
                    LOG.info("[" + projectName + "] Path (from watcherPath) to be stored - toRealPath(): '" + dirToRegisterForConfig.toRealPath().toString() + "'");
                } catch (IOException e) {
                    LOG.warn("[" + projectName + "] Path (from watcherPath) to be stored - toRealPath() failed for '" + dirToRegisterForConfig + "': " + e.getMessage());
                }

                Path previousPathForKey = watchKeyToDirPathMap.put(key, dirToRegisterForConfig);
                if (previousPathForKey != null) {
                    LOG.warn("[" + projectName + "] CRITICAL: Replaced existing WatchKey mapping during watcherPath reg! " +
                            "Key (identity: " + keyIdentityHashCode + ", valid: " + key.isValid() + ") was for Path: '" + previousPathForKey +
                            "', now for Path: '" + dirToRegisterForConfig + "'. Map size: " + watchKeyToDirPathMap.size());
                } else {
                    LOG.info("[" + projectName + "] Registered new WatchKey for watcherPath entry (identity: " + keyIdentityHashCode +
                            ", valid: " + key.isValid() + ") for Directory: '" + dirToRegisterForConfig +
                            "' (derived from configured path: '" + confPathStr + "'). Map size: " + watchKeyToDirPathMap.size());
                }
            } catch (Exception e) {
                LOG.error("[" + projectName + "] Exception registering watcherPath entry '" + confPathStr + "': " + e.getMessage(), e);
            }
        }

        isRunning = true; // Set isRunning true *before* starting the thread.

        if (watchKeyToDirPathMap.isEmpty()) {
            LOG.warn("[" + projectName + "] No valid paths could be registered. Watcher will not be started.");
            isRunning = false; // Reset as nothing is watched
            try {
                if (nativeWatchService != null) {
                    nativeWatchService.close();
                    LOG.info("[" + projectName + "] Closed unused WatchService instance (identity: " + System.identityHashCode(nativeWatchService) + ").");
                }
            } catch (IOException e) {
                LOG.warn("["+projectName+"] Error closing unused WatchService: " + e.getMessage(), e);
            }
            nativeWatchService = null;
            return;
        }

        if (watcherThread != null && watcherThread.isAlive()) {
            LOG.warn("[" + projectName + "] Previous watcherThread (" + watcherThread.getName() + ", identity: " + System.identityHashCode(watcherThread) +
                    ") is still alive. Attempting interrupt and join before starting new one.");
            watcherThread.interrupt();
            try {
                watcherThread.join(1000); // Wait a bit
                if(watcherThread.isAlive()){
                    LOG.error("[" + projectName + "] Previous watcherThread did not terminate after interrupt and join!");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("[" + projectName + "] Interrupted while joining previous watcher thread.");
            }
        }

        watcherThread = new Thread(new WatcherRunnable(nativeWatchService, project.getName()), "NativeFileWatcherThread-" + project.getName() + "-" + System.currentTimeMillis());
        watcherThread.setDaemon(true);
        LOG.info("[" + projectName + "] Starting new WatcherThread (identity: " + System.identityHashCode(watcherThread) +
                ") for WatchService (identity: " + System.identityHashCode(nativeWatchService) + ").");
        watcherThread.start();

        LOG.info("[" + projectName + "] Native FileChangeEventWatcherService started successfully. Watching " +
                watchKeyToDirPathMap.size() + " WatchKeys for " + registeredDirectoriesInThisCycle.size() +
                " unique directories. Monitored WatchService (identity: " + System.identityHashCode(nativeWatchService) + ").");
    }


    private class WatcherRunnable implements Runnable {
        private final WatchService serviceInstance; // Store the specific WatchService instance this runnable is for
        private final String projectName;

        WatcherRunnable(WatchService service, String projectName) {
            this.serviceInstance = service;
            this.projectName = projectName;
            LOG.info("[" + projectName + "] WatcherRunnable created for WatchService (identity: " + System.identityHashCode(serviceInstance) + ")");
        }

        private String safeGetWatchablePath(WatchKey key) {
            try {
                if (!key.isValid()) return "N/A (key invalid on getWatchablePath entry)";
                Watchable watchable = key.watchable();
                if (watchable instanceof Path) {
                    return ((Path) watchable).toString();
                }
                return watchable != null ? watchable.getClass().getName() + "@" + System.identityHashCode(watchable) : "null watchable";
            } catch (Exception e) {
                // This can happen if the key becomes invalid between isValid() and watchable() or during watchable()
                return "Error getting watchable path: " + e.getMessage();
            }
        }

        @Override
        public void run() {
            long currentThreadId = Thread.currentThread().threadId();
            int currentThreadIdentity = System.identityHashCode(Thread.currentThread());

            LOG.info("[" + projectName + "] Native watcher thread (Java Thread ID: " + currentThreadId + ", identity: " + currentThreadIdentity + ") started for WatchService (identity: " + System.identityHashCode(serviceInstance) + "). isRunning: " + FileChangeEventWatcherService.this.isRunning);
            try {
                while (!Thread.currentThread().isInterrupted() && FileChangeEventWatcherService.this.isRunning) {
                    if (serviceInstance != FileChangeEventWatcherService.this.nativeWatchService) {
                        LOG.warn("[" + projectName + "] WatcherThread (Java Thread ID: " + currentThreadId + ", for WS identity: " + System.identityHashCode(serviceInstance) +
                                ") detected its WatchService is no longer active (active is: " +
                                (FileChangeEventWatcherService.this.nativeWatchService == null ? "null" : System.identityHashCode(FileChangeEventWatcherService.this.nativeWatchService)) +
                                "). Terminating.");
                        break;
                    }

                    WatchKey keyFromTake;
                    try {
                        keyFromTake = serviceInstance.take();
                    } catch (InterruptedException e) {
                        LOG.info("[" + projectName + "] WatcherThread (Java Thread ID: " + currentThreadId + ", for WS identity: " + System.identityHashCode(serviceInstance) + ") interrupted (during take). Terminating.");
                        Thread.currentThread().interrupt();
                        break;
                    } catch (ClosedWatchServiceException e) {
                        LOG.info("[" + projectName + "] WatcherThread (Java Thread ID: " + currentThreadId + ", for WS identity: " + System.identityHashCode(serviceInstance) + ") detected ClosedWatchServiceException. Terminating.");
                        break;
                    } catch (Exception e) {
                        LOG.error("[" + projectName + "] WatcherThread (Java Thread ID: " + currentThreadId + ", for WS identity: " + System.identityHashCode(serviceInstance) + ") error in take(): " + e.getMessage(), e);
                        break;
                    }

                    int keyFromTakeIdentity = System.identityHashCode(keyFromTake);
                    boolean keyFromTakeIsValidAtReceive = keyFromTake.isValid();
                    String watchablePathStringAtReceive = safeGetWatchablePath(keyFromTake);

                    LOG.debug("[" + projectName + "] WatchService.take() returned WatchKey (identity: " + keyFromTakeIdentity +
                            ", isValidAtReceive: " + keyFromTakeIsValidAtReceive +
                            ", watchablePathStringAtReceive: '" + watchablePathStringAtReceive +
                            "', from WS identity: " + System.identityHashCode(serviceInstance) + ")");

                    Path actualWatchedDirForEvent = null;
                    boolean isKeyDirectlyInMap = false;
                    Path pathFromMapViaKeyIdentity = watchKeyToDirPathMap.get(keyFromTake);

                    if (pathFromMapViaKeyIdentity != null) {
                        actualWatchedDirForEvent = pathFromMapViaKeyIdentity;
                        isKeyDirectlyInMap = true;
                        LOG.debug("[" + projectName + "] Key (identity: " + keyFromTakeIdentity + ") found directly in map, points to: '" + actualWatchedDirForEvent + "'");
                    } else {
                        LOG.warn("[" + projectName + "] Key (identity: " + keyFromTakeIdentity + ", isValidAtReceive: " + keyFromTakeIsValidAtReceive + ") NOT in map by ID.");
                        if (keyFromTakeIsValidAtReceive) {
                            Watchable watchable = keyFromTake.watchable();
                            if (watchable instanceof Path) {
                                Path pathFromWatchableKey = (Path) watchable;
                                LOG.info("[" + projectName + "] Path from key.watchable() (Key ID: " + keyFromTakeIdentity + "): " +
                                        "toString: '" + pathFromWatchableKey.toString() + "', hc: " + pathFromWatchableKey.hashCode() +
                                        ", fs: " + pathFromWatchableKey.getFileSystem().toString());
                                try {
                                    LOG.info("[" + projectName + "] Path from key.watchable() - toRealPath(): '" + pathFromWatchableKey.toRealPath().toString() + "'");
                                } catch (IOException e) {
                                    LOG.warn("[" + projectName + "] Path from key.watchable() - toRealPath() failed for '" + pathFromWatchableKey + "': " + e.getMessage());
                                }

                                if (!Files.exists(pathFromWatchableKey)) {
                                    LOG.warn("[" + projectName + "] Key (ID: " + keyFromTakeIdentity + ") is valid, but its watchable Path '" + pathFromWatchableKey +
                                            "' does NOT exist. Treating as effectively invalid for matching.");
                                } else {
                                    final Path finalPathFromWatchable = pathFromWatchableKey;
                                    Optional<Map.Entry<WatchKey, Path>> matchingEntry = watchKeyToDirPathMap.entrySet().stream()
                                            .filter(entry -> {
                                                Path registeredPath = entry.getValue();
                                                try {
                                                    if (!Files.exists(registeredPath)) {
                                                        LOG.warn("[" + projectName + "] Registered path '" + registeredPath + "' in map no longer exists. Cannot compare with watchable '" + finalPathFromWatchable + "'. Non-match.");
                                                        return false;
                                                    }
                                                    String realPathFromWatchableStr = finalPathFromWatchable.toRealPath().toString();
                                                    String realRegisteredPathStr = registeredPath.toRealPath().toString();
                                                    return realPathFromWatchableStr.equals(realRegisteredPathStr);
                                                } catch (NoSuchFileException nsfe) {
                                                    LOG.warn("[" + projectName + "] NoSuchFileException during toRealPath() comparison. Registered: '" + registeredPath + "' or Watchable: '" + finalPathFromWatchable + "'. Error: " + nsfe.getMessage() + ". Non-match.");
                                                    return false;
                                                } catch (IOException e) {
                                                    LOG.warn("[" + projectName + "] IOException during toRealPath() comparison. Registered: '" + registeredPath + "' or Watchable: '" + finalPathFromWatchable + "'. Error: " + e.getMessage() + ". Non-match.");
                                                    return false;
                                                }
                                            })
                                            .findFirst();

                                    if (matchingEntry.isPresent()) {
                                        actualWatchedDirForEvent = matchingEntry.get().getValue();
                                        LOG.info("[" + projectName + "] Key (ID: " + keyFromTakeIdentity + ") (not in map by ID) had watchable Path '" + pathFromWatchableKey +
                                                "' MATCH (via toRealPath) with registered map Path: '" + actualWatchedDirForEvent + "'. Processing event.");
                                    } else {
                                        // If matchingEntry is not present, check again if pathFromWatchableKey still exists
                                        boolean pathStillExists = Files.exists(pathFromWatchableKey);
                                        if (pathStillExists) {
                                            LOG.error(new Throwable("[" + projectName + "] CRITICAL (Path Still Exists): Key (ID: " + keyFromTakeIdentity +
                                                    ") not in map by ID. Its watchable Path '" + pathFromWatchableKey +
                                                    "' (hc:" + pathFromWatchableKey.hashCode() + ", fs:" + pathFromWatchableKey.getFileSystem() +
                                                    ", exists:true" +
                                                    ") does NOT match (via toRealPath().toString()) any Path in map.values(). Rogue key or Path issue. Ignoring."));
                                        } else {
                                            LOG.warn("[" + projectName + "] Key (ID: " + keyFromTakeIdentity +
                                                    ") not in map by ID. Its watchable Path '" + pathFromWatchableKey +
                                                    "' NO LONGER EXISTS (checked after failed match). toRealPath() comparison naturally failed or was skipped. Ignoring event for this key.");
                                        }
                                        // actualWatchedDirForEvent remains null
                                    }
                                }
                            } else {
                                LOG.error("[" + projectName + "] Key (ID: " + keyFromTakeIdentity + ") not in map, isValid, but watchable not a Path: " + (watchable != null ? watchable.getClass().getName() : "null") + ". Ignoring.");
                            }
                        } else {
                            LOG.warn("[" + projectName + "] Key (ID: " + keyFromTakeIdentity + ") not in map AND was already invalid when received. Ignoring.");
                        }
                    }

                    if (actualWatchedDirForEvent == null) {
                        if (!keyFromTake.reset()) {
                            LOG.warn("[" + projectName + "] Ignored/Unresolved Key (ID: " + keyFromTakeIdentity + ", isValidAtReset: " + keyFromTake.isValid() + ") no longer valid after reset.");
                        }
                        continue;
                    }

                    List<? extends WatchEvent<?>> events = keyFromTake.pollEvents();
                    LOG.debug("[" + projectName + "] Polled " + events.size() + " events for Key (ID: " + keyFromTakeIdentity + ") for dir: " + actualWatchedDirForEvent);

                    for (WatchEvent<?> event : events) {
                        WatchEvent.Kind<?> kind = event.kind();
                        if (kind == StandardWatchEventKinds.OVERFLOW) {
                            LOG.warn("[" + projectName + "] OVERFLOW for dir: " + actualWatchedDirForEvent);
                            final Path dirToRefresh = actualWatchedDirForEvent;
                            ApplicationManager.getApplication().invokeLater(() -> Util.forceRefreshVFS(dirToRefresh.toString()));
                            continue;
                        }
                        if (!(event.context() instanceof Path)) {
                            LOG.warn("[" + projectName + "] Event context not Path. Kind: " + kind + ". Context: " + event.context() + ". Skipping.");
                            continue;
                        }

                        @SuppressWarnings("unchecked")
                        WatchEvent<Path> ev = (WatchEvent<Path>) event;
                        Path relativeFileName = ev.context();
                        Path absoluteAffectedPath = actualWatchedDirForEvent.resolve(relativeFileName).normalize();
                        String affectedPathStr = absoluteAffectedPath.toString().replace('\\', '/');
                        String eventType = null;
                        if (kind == StandardWatchEventKinds.ENTRY_CREATE) eventType = "Change New";
                        else if (kind == StandardWatchEventKinds.ENTRY_DELETE) eventType = "Change Del";
                        else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) eventType = "Change Mod";

                        if (eventType != null) {
                            LOG.debug("[" + projectName + "] Native Event: " + eventType + " | Rel: " + relativeFileName + " | Abs: " + affectedPathStr + " | Dir: " + actualWatchedDirForEvent);
                            processNativeWatchEvent(eventType, affectedPathStr, absoluteAffectedPath);
                        }
                    }

                    boolean keyStillValidAfterReset = keyFromTake.reset();
                    if (!keyStillValidAfterReset) {
                        LOG.info("[" + projectName + "] Key (ID: " + keyFromTakeIdentity + ", for path: '" + actualWatchedDirForEvent + "') no longer valid after reset.");
                        if (isKeyDirectlyInMap) {
                            Path removedPath = watchKeyToDirPathMap.remove(keyFromTake);
                            if (removedPath != null) {
                                LOG.info("[" + projectName + "] Removed invalid Key (ID: " + keyFromTakeIdentity + ") for path: '" + removedPath + "' from map.");
                            } else {
                                LOG.warn("[" + projectName + "] Tried to remove invalid Key (ID: " + keyFromTakeIdentity + ") (was directly mapped), but already gone.");
                            }
                        } else {
                            LOG.info("[" + projectName + "] Key (ID: " + keyFromTakeIdentity + ") (resolved via watchable()) became invalid. Not in map by ID, no removal needed.");
                        }
                        if (isKeyDirectlyInMap && watchKeyToDirPathMap.isEmpty() && FileChangeEventWatcherService.this.isRunning) {
                            LOG.info("[" + projectName + "] All directly mapped keys invalid/removed. Watcher thread may stop if no new registrations.");
                        }
                    }
                }
            } finally {
                LOG.info("[" + projectName + "] Native watcher thread (Java Thread ID: " + currentThreadId + ", identity: " + currentThreadIdentity + ") finished for WatchService (identity: " + System.identityHashCode(serviceInstance) + ").");
            }
        } // end run()
    }

    private void processNativeWatchEvent(String eventType, String affectedPathStr, Path absoluteAffectedPath) {
        final String projectName = project.getName();
        boolean configChangedByWatcherPath = false; // Flag to indicate if a config file in watcherPath changed

        List<ActiveWatch> matchedActiveWatchers = new ArrayList<>();

        // --- 1. Match against activeWatchers (user-defined script executions) ---
        synchronized (this) { // Synchronize to protect activeWatchers list during iteration
            for (ActiveWatch watch : activeWatchers) {
                // Log the comparison for debugging, can be reduced later
                LOG.debug("[" + projectName + "] processNativeWatchEvent: Comparing affectedPath='" + affectedPathStr +
                        "' with ActiveWatch.watchedPath='" + watch.watchedPath + "' (isDirPretended=" + watch.isDirectoryPretended + ")");

                // Condition 1: Exact match for a configured file path
                if (watch.watchedPath.equals(affectedPathStr)) {
                    LOG.info("[" + projectName + "] Matched ActiveWatch (exact file): '" + watch.watchedPath + "' for affectedPath: '" + affectedPathStr + "'");
                    matchedActiveWatchers.add(watch);
                    continue; // Found a match, no need to check other conditions for THIS ActiveWatch
                }

                // Condition 2: Configured path is a directory, and affected path is its direct child
                // This requires that 'watch.watchedPath' was indeed an existing directory when config was loaded,
                // or if it's a general directory watch.
                if (watch.isDirectoryPretended) { // True if the user configured this as a directory watch
                    Path configuredWatchedDirObj = null;
                    boolean configuredDirExistsAndIsDir = false;
                    try {
                        configuredWatchedDirObj = Paths.get(watch.watchedPath);
                        configuredDirExistsAndIsDir = Files.isDirectory(configuredWatchedDirObj); // Check current state
                    } catch (InvalidPathException e) {
                        LOG.warn("[" + projectName + "] Invalid configured watchedPath in ActiveWatch: '" + watch.watchedPath + "'", e);
                        continue; // Skip this ActiveWatch if path is invalid
                    }

                    if (configuredDirExistsAndIsDir) { // Only proceed if the configured watched path is currently a directory
                        Path parentOfAffected = absoluteAffectedPath.getParent();
                        if (parentOfAffected != null && parentOfAffected.equals(configuredWatchedDirObj)) {
                            LOG.info("[" + projectName + "] Matched ActiveWatch (direct child): affectedPath '" + affectedPathStr +
                                    "' is child of watched directory '" + watch.watchedPath + "'");
                            matchedActiveWatchers.add(watch);
                            // continue; // A single event path could potentially match multiple directory watches if nested/overlapping,
                            // though usually one ActiveWatch corresponds to one user entry.
                            // For simplicity, let's assume one event path matches at most relevant directory watch for now.
                            // If multiple ActiveWatch entries could legitimately both be parents, this logic might need refinement
                            // or the list could become a Set to avoid duplicate script executions if the same ActiveWatch object was added twice.
                        }
                    }
                }
            }
        } // end synchronized(this) for activeWatchers

        if (!matchedActiveWatchers.isEmpty()) {
            LOG.info("[" + projectName + "] Relevant Native Event: " + eventType + " | Path: " + affectedPathStr +
                    " | Matched " + matchedActiveWatchers.size() + " active watchers.");
            for (ActiveWatch watch : matchedActiveWatchers) {
                final String pathForVFS = watch.watchedPath; // Capture for lambda
                final String scriptToRun = watch.scriptToRun; // Capture for lambda
                final String finalEventType = eventType; // Capture for lambda
                final String finalAffectedPathStr = affectedPathStr; // Capture for lambda

                LOG.info("[" + projectName + "] Matched active watch: '" + pathForVFS + "' -> executes '" + scriptToRun + "' for event type '" + finalEventType + "' on path '" + finalAffectedPathStr + "'");

                // Refresh VFS for the *configured* watched path (could be parent dir or specific file)
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;
                    Util.forceRefreshVFS(pathForVFS);
                });

                executeWatchedScript(scriptToRun, finalEventType, finalAffectedPathStr);
            }
        }

        // --- 2. Match against watcherPath (typically for config file changes) ---
        Set<String> currentWatcherPathsSnapshot;
        synchronized (this) { // Protect watcherPath set during iteration by copying
            currentWatcherPathsSnapshot = new HashSet<>(watcherPath);
        }

        for (String singleWatchedFileInSet : currentWatcherPathsSnapshot) {
            if (singleWatchedFileInSet.equals(affectedPathStr)) {
                LOG.info("[" + projectName + "] Native Event on a path in watcherPath set (e.g. config file): " +
                        "eventType='" + eventType + "', affectedPathStr='" + affectedPathStr + "'");

                final String configPathForVFS = affectedPathStr; // Capture for lambda
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;
                    Util.forceRefreshVFS(configPathForVFS); // Refresh VFS for the config file itself
                });
                configChangedByWatcherPath = true; // Mark that a config file change was detected
                break; // Assume only one config file path will match, or first match is sufficient
            }
        }

        // --- 3. Handle config reload if a path in watcherPath changed ---
        if (configChangedByWatcherPath) {
            LOG.info("[" + projectName + "] Change detected on a path in watcherPath set. Scheduling configuration reload on EDT.");
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) {
                    LOG.info("[" + projectName + "] Project disposed, skipping config reload for path: " + affectedPathStr);
                    return;
                }
                LOG.info("[" + projectName + "] Executing config reload on EDT due to change in: " + affectedPathStr);
                Util.reloadSyncFilesConfigFromDisk(project); // This reloads the SyncFilesConfig service's state
                project.getMessageBus().syncPublisher(SyncFilesNotifier.TOPIC).configurationChanged(); // This notifies listeners (e.g., UI)
                LOG.info("[" + projectName + "] Configuration reloaded from disk and change notification sent.");
            });
        }
    }

    private void executeWatchedScript(String scriptPathToExecute, String eventType, String affectedFilePath) {
        String projectName = project.getName();
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        String pythonExecutableRaw = config.getPythonExecutablePath();

        if (StringUtil.isEmptyOrSpaces(pythonExecutableRaw)) {
            LOG.warn("[" + projectName + "] Python executable not configured. Cannot execute script: " + scriptPathToExecute);
            return;
        }
        String pythonExecutable = Util.isDirectoryAfterMacroExpansion(project, pythonExecutableRaw);
        if (StringUtil.isEmptyOrSpaces(pythonExecutable) || !Files.isRegularFile(Paths.get(pythonExecutable))) {
            LOG.warn("[" + projectName + "] Python executable path invalid or not a file: '" + pythonExecutable + "'. Cannot execute script: " + scriptPathToExecute);
            return;
        }

        String finalScriptPathToExecute = Util.isDirectoryAfterMacroExpansion(project, scriptPathToExecute);
        if (StringUtil.isEmptyOrSpaces(finalScriptPathToExecute) || !Files.isRegularFile(Paths.get(finalScriptPathToExecute))) {
            LOG.warn("[" + projectName + "] Script path to execute is invalid or not a file: '" + finalScriptPathToExecute + "'.");
            return;
        }

        scriptExecutorService.submit(() -> {
            LOG.info("[" + projectName + "] Executing script for file event: '" + finalScriptPathToExecute +
                    "' with args: [" + eventType + ", " + affectedFilePath + "]");
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        pythonExecutable,
                        finalScriptPathToExecute,
                        eventType,
                        affectedFilePath
                );

                Map<String, String> envVars = new HashMap<>(EnvironmentUtil.getEnvironmentMap());
                envVars.putAll(config.getEnvVariables());
                envVars.put("PYTHONIOENCODING", "UTF-8");
                if (project.getBasePath() != null) {
                    envVars.put("PROJECT_DIR", project.getBasePath().replace('\\', '/'));
                }
                pb.environment().clear();
                pb.environment().putAll(envVars);

                if (project.getBasePath() != null) {
                    pb.directory(new File(project.getBasePath()));
                } else {
                    Path scriptFile = Paths.get(finalScriptPathToExecute);
                    if (scriptFile.getParent() != null) {
                        pb.directory(scriptFile.getParent().toFile());
                    } else {
                        LOG.warn("[" + projectName + "] Script has no parent directory and project base path is null. Using default CWD for script: " + finalScriptPathToExecute);
                    }
                }

                Process process = pb.start();
                StringBuilder output = new StringBuilder();
                StringBuilder errorOutput = new StringBuilder();

                try (BufferedReader outReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                     BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = outReader.readLine()) != null) output.append(line).append(System.lineSeparator());
                    while ((line = errReader.readLine()) != null) errorOutput.append(line).append(System.lineSeparator());
                }
                int exitCode = process.waitFor();
                String scriptFileName = Paths.get(finalScriptPathToExecute).getFileName().toString();

                if (exitCode == 0) {
                    LOG.info("[" + projectName + "] Script '" + scriptFileName + "' executed successfully. Event: '" + eventType + "' on '" + affectedFilePath + "'" +
                            (output.length() > 0 ? ". Output:\n" + output.toString().trim() : ""));
                } else {
                    LOG.warn("[" + projectName + "] Script '" + scriptFileName + "' execution failed. Event: '" + eventType + "' on '" + affectedFilePath +
                            "' (Exit Code: " + exitCode + ")" +
                            (output.length() > 0 ? ". Output:\n" + output.toString().trim() : "") +
                            (errorOutput.length() > 0 ? ". Error:\n" + errorOutput.toString().trim() : ""));
                }

                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;
                    Path nioAffectedPath = null;
                    try {
                        nioAffectedPath = Paths.get(affectedFilePath);
                    } catch (InvalidPathException e) {
                        LOG.warn("[" + projectName + "] VFS Refresh: Invalid affectedFilePath: " + affectedFilePath, e);
                        return;
                    }
                    VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(affectedFilePath); // Uses String path
                    if (virtualFile == null) {
                        Path parentNioPath = nioAffectedPath.getParent();
                        if (parentNioPath != null) {
                            LOG.debug("[" + projectName + "] VFS Refresh: Affected path '" + affectedFilePath + "' not found. Refreshing parent: " + parentNioPath);
                            LocalFileSystem.getInstance().refreshAndFindFileByPath(parentNioPath.toString());
                        } else {
                            LOG.debug("[" + projectName + "] VFS Refresh: Affected path '" + affectedFilePath + "' not found and has no parent.");
                        }
                    } else {
                        LOG.debug("[" + projectName + "] VFS refreshed (or found already up-to-date) for: " + virtualFile.getPath());
                    }
                });

            } catch (IOException | InterruptedException e) {
                LOG.error("[" + projectName + "] Error executing watched script '" + finalScriptPathToExecute + "': " + e.getMessage(), e);
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        });
    }

    public synchronized void stopWatching() {
        String projectName = project.getName();
        LOG.info("[" + projectName + "] Attempting to stop Native FileChangeEventWatcherService... Current isRunning: " + isRunning);
        if (!isRunning) {
            LOG.info("[" + projectName + "] Watcher not running or already in process of stopping. Stop request ignored.");
            return;
        }
        isRunning = false; // Signal all loops to stop

        WatchService serviceToClose = this.nativeWatchService; // Capture current instance
        Thread threadToJoin = this.watcherThread; // Capture current instance

        if (threadToJoin != null && threadToJoin.isAlive()) {
            LOG.info("[" + projectName + "] Interrupting watcher thread (Java Thread ID: " + threadToJoin.threadId() + ", identity: " + System.identityHashCode(threadToJoin) + ")");
            threadToJoin.interrupt();
        } else {
            LOG.info("[" + projectName + "] Watcher thread was null or not alive at stop time.");
        }

        if (serviceToClose != null) {
            LOG.info("[" + projectName + "] Closing native WatchService instance (identity: " + System.identityHashCode(serviceToClose) + ")");
            try {
                serviceToClose.close(); // This should cause take() to throw ClosedWatchServiceException
                LOG.info("[" + projectName + "] Native WatchService (identity: " + System.identityHashCode(serviceToClose) + ") closed successfully.");
            } catch (IOException e) {
                LOG.error("[" + projectName + "] Error closing native WatchService (identity: " + System.identityHashCode(serviceToClose) + "): " + e.getMessage(), e);
            }
        } else {
            LOG.info("[" + projectName + "] nativeWatchService was already null at stop time.");
        }

        this.nativeWatchService = null; // Null out the reference
        this.watcherThread = null; // Null out the reference

        if (threadToJoin != null) {
            try {
                LOG.info("[" + projectName + "] Waiting for watcher thread (Java Thread ID: " + threadToJoin.getName() + ", identity: " + System.identityHashCode(threadToJoin) + ") to join...");
                threadToJoin.join(5000); // Wait up to 5 seconds
                if (threadToJoin.isAlive()) {
                    LOG.warn("[" + projectName + "] Watcher thread (identity: " + System.identityHashCode(threadToJoin) + ") did not terminate in time after interrupt and service close.");
                } else {
                    LOG.info("[" + projectName + "] Watcher thread (identity: " + System.identityHashCode(threadToJoin) + ") terminated.");
                }
            } catch (InterruptedException e) {
                LOG.warn("[" + projectName + "] Interrupted while waiting for watcher thread (identity: " + System.identityHashCode(threadToJoin) + ") to terminate.");
                Thread.currentThread().interrupt();
            }
        }

        // Clear map after service and thread are handled
        // This map is already ConcurrentHashMap, but clear() is fine.
        int mapSizeBeforeClear = watchKeyToDirPathMap.size();
        List<Integer> keysIdentitiesBeforeClear = watchKeyToDirPathMap.keySet().stream().map(System::identityHashCode).collect(Collectors.toList());
        watchKeyToDirPathMap.clear();
        LOG.info("[" + projectName + "] watchKeyToDirPathMap cleared by stopWatching. Size before: " + mapSizeBeforeClear +
                (mapSizeBeforeClear > 0 ? ", Keys (identities): " + keysIdentitiesBeforeClear : "") +
                ". Size after: " + watchKeyToDirPathMap.size());
        LOG.info("[" + projectName + "] Native FileChangeEventWatcherService stopped logic completed.");
    }

    @Override
    public void dispose() {
        String projectName = project.isDisposed() ? "DisposedProject" : project.getName();
        LOG.info("Disposing FileChangeEventWatcherService for project: " + projectName);
        stopWatching(); // Ensure watcher is stopped

        // Clear collections, though synchronized methods should handle this if project is closing.
        // No need for explicit sync here if stopWatching is robust.
        activeWatchers.clear();
        watcherPath.clear();

        if (!scriptExecutorService.isShutdown()) {
            LOG.info("[" + projectName + "] Shutting down scriptExecutorService.");
            scriptExecutorService.shutdown();
            try {
                if (!scriptExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOG.warn("[" + projectName + "] scriptExecutorService did not terminate in 5s, forcing shutdownNow().");
                    scriptExecutorService.shutdownNow();
                } else {
                    LOG.info("[" + projectName + "] scriptExecutorService terminated gracefully.");
                }
            } catch (InterruptedException e) {
                LOG.warn("[" + projectName + "] Interrupted during scriptExecutorService shutdown, forcing shutdownNow().");
                scriptExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        LOG.info("FileChangeEventWatcherService disposed for project: " + projectName);
    }

    // ActiveWatch class (ensure it's defined as discussed previously)
    private static class ActiveWatch {
        final String watchedPath;       // Configured path (absolute, normalized with /)
        final String scriptToRun;       // Script to run (absolute, normalized with /)
        final boolean isDirectoryPretended; // True if the user configured this path *as if* it's a directory
        final boolean pathExists;       // True if the watchedPath existed at the time of config parsing

        ActiveWatch(String watchedPath, String scriptToRun, boolean isDirectoryPretended, boolean pathExists) {
            this.watchedPath = watchedPath; // Should be absolute & normalized
            this.scriptToRun = scriptToRun;   // Should be absolute & normalized
            this.isDirectoryPretended = isDirectoryPretended;
            this.pathExists = pathExists;
        }

        @Override
        public String toString() {
            return "ActiveWatch{" +
                    "watchedPath='" + watchedPath + '\'' +
                    ", scriptToRun='" + scriptToRun + '\'' +
                    ", isDirectoryPretended=" + isDirectoryPretended +
                    ", pathExists=" + pathExists +
                    '}';
        }
    }
}