package com.example.syncfiles;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@State(
        name = "SyncFilesConfig",
        storages = {@Storage("syncFilesConfig.xml")}
)
@Service(Service.Level.PROJECT)
public final class SyncFilesConfig implements PersistentStateComponent<SyncFilesConfig> {
    public List<SyncAction.Mapping> mappings;
    public int refreshInterval;
    public String shortcutKey;
    public String pythonScriptPath;  // 新增Python脚本路径
    public String pythonExecutablePath;  // 新增Python可执行文件路径

    public SyncFilesConfig() {
        mappings = new ArrayList<>();
        refreshInterval = 1000;
        shortcutKey = "Ctrl+Shift+S";
        pythonScriptPath = "";
        pythonExecutablePath = "";
    }

    public static SyncFilesConfig getInstance(Project project) {
        return project.getService(SyncFilesConfig.class);
    }

    @Nullable
    @Override
    public SyncFilesConfig getState() {
        if (mappings == null) {
            mappings = new ArrayList<>();
        }
        if (shortcutKey == null || shortcutKey.trim().isEmpty()) {
            shortcutKey = "Ctrl+Shift+S";
        }
        if (refreshInterval <= 0) {
            refreshInterval = 1000;
        }
        if (pythonScriptPath == null) {
            pythonScriptPath = "";
        }
        if (pythonExecutablePath == null) {
            pythonExecutablePath = "";
        }
        return this;
    }

    @Override
    public void loadState(@NotNull SyncFilesConfig state) {
        XmlSerializerUtil.copyBean(state, this);
        if (mappings == null) {
            mappings = new ArrayList<>();
        }
        if (shortcutKey == null || shortcutKey.trim().isEmpty()) {
            shortcutKey = "Ctrl+Shift+S";
        }
        if (refreshInterval <= 0) {
            refreshInterval = 1000;
        }
        if (pythonScriptPath == null) {
            pythonScriptPath = "";
        }
        if (pythonExecutablePath == null) {
            pythonExecutablePath = "";
        }
    }

    // getters and setters
    public List<SyncAction.Mapping> getMappings() {
        return new ArrayList<>(mappings);
    }

    public void setMappings(List<SyncAction.Mapping> mappings) {
        this.mappings = new ArrayList<>(mappings);
    }

    public int getRefreshInterval() {
        return refreshInterval;
    }

    public void setRefreshInterval(int refreshInterval) {
        this.refreshInterval = refreshInterval;
    }

    public String getShortcutKey() {
        return shortcutKey;
    }

    public void setShortcutKey(String shortcutKey) {
        this.shortcutKey = shortcutKey;
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
}