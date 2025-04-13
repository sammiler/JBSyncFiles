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
    public List<SyncAction.Mapping> mappings = new ArrayList<>();

    public static SyncFilesConfig getInstance(Project project) {
        return project.getService(SyncFilesConfig.class);
    }

    @Nullable
    @Override
    public SyncFilesConfig getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull SyncFilesConfig state) {
        XmlSerializerUtil.copyBean(state, this);
    }

    public List<SyncAction.Mapping> getMappings() {
        return new ArrayList<>(mappings);
    }

    public void setMappings(List<SyncAction.Mapping> mappings) {
        this.mappings = new ArrayList<>(mappings);
    }
}