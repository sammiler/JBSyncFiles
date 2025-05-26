package com.example.syncfiles.config.smartworkflow;// File: SmartPlatformConfig.java
// package com.example.syncfiles.config.smartworkflow;

import java.util.List;
import java.util.Map;

public class SmartPlatformConfig {
    private String sourceUrl;
    private String targetDir; // 对应你 Mapping 中的 targetPath
    private Map<String, String> envVariables;
    private String pythonScriptPath; // 这是脚本的根目录
    private String pythonExecutablePath;
    private List<SmartWatchEntry> watchEntries;

    // getters and setters
    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getTargetDir() {
        return targetDir;
    }

    public void setTargetDir(String targetDir) {
        this.targetDir = targetDir;
    }

    public Map<String, String> getEnvVariables() {
        return envVariables;
    }

    public void setEnvVariables(Map<String, String> envVariables) {
        this.envVariables = envVariables;
    }

    public String getPythonScriptPath() {
        return pythonScriptPath;
    }

    public void setPythonScriptPath(String pythonScriptPath) {
        this.pythonScriptPath = pythonScriptPath;
    }

    public String getPythonExecutablePath() {
        return pythonExecutablePath;
    }

    public void setPythonExecutablePath(String pythonExecutablePath) {
        this.pythonExecutablePath = pythonExecutablePath;
    }

    public List<SmartWatchEntry> getWatchEntries() {
        return watchEntries;
    }

    public void setWatchEntries(List<SmartWatchEntry> watchEntries) {
        this.watchEntries = watchEntries;
    }
}