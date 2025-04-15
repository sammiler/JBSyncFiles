package com.example.syncfiles;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.annotations.OptionTag;
import com.intellij.util.xmlb.annotations.Tag;
import org.jetbrains.annotations.NotNull;


import java.util.ArrayList;
import java.util.List;

@State(
        name = "SyncFilesConfig",
        storages = {@Storage("syncFilesConfig.xml")}
)
@Service(Service.Level.PROJECT)
public final class SyncFilesConfig implements PersistentStateComponent<SyncFilesConfig> {


    @Tag("mappings")
    public List<Mapping> mappings = new ArrayList<>(); // 更新为独立 Mapping 类


    @OptionTag("pythonScriptPath")
    public String pythonScriptPath = "";

    @OptionTag("pythonExecutablePath")
    public String pythonExecutablePath = "";



    public SyncFilesConfig() {
    }

    public static SyncFilesConfig getInstance(Project project) {
        return project.getService(SyncFilesConfig.class);
    }


    @Override
    public @NotNull SyncFilesConfig getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull SyncFilesConfig state) {
        mappings.clear();
        mappings.addAll(state.mappings);
        pythonScriptPath = state.pythonScriptPath != null ? state.pythonScriptPath : "";
        pythonExecutablePath = state.pythonExecutablePath != null ? state.pythonExecutablePath : "";
    }

    public List<Mapping> getMappings() {
        return new ArrayList<>(mappings);
    }

    public void setMappings(List<Mapping> mappings) {
        this.mappings = new ArrayList<>(mappings);
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