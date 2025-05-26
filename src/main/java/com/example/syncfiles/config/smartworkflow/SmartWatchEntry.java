package com.example.syncfiles.config.smartworkflow;// File: SmartWatchEntry.java
// package com.example.syncfiles.config.smartworkflow;

public class SmartWatchEntry {
    private String watchedPath;
    private String onEventScript; // 这是相对于 pythonScriptPath 的脚本路径，或完整路径

    // getters and setters
    public String getWatchedPath() {
        return watchedPath;
    }

    public void setWatchedPath(String watchedPath) {
        this.watchedPath = watchedPath;
    }

    public String getOnEventScript() {
        return onEventScript;
    }

    public void setOnEventScript(String onEventScript) {
        this.onEventScript = onEventScript;
    }

    // public String getAlias() { return alias; }
    // public void setAlias(String alias) { this.alias = alias; }
    // ... etc.
}