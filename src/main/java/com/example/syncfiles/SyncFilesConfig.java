package com.example.syncfiles;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil; // 使用 XmlSerializerUtil 进行状态复制
import com.intellij.util.xmlb.annotations.MapAnnotation;
import com.intellij.util.xmlb.annotations.OptionTag;
import com.intellij.util.xmlb.annotations.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable; // 引入 Nullable

import java.util.*;
import java.util.concurrent.ConcurrentHashMap; // 使用 ConcurrentHashMap

@State(
        name = "SyncFilesConfig",
        storages = {@Storage("syncFilesConfig.xml")}
)
@Service(Service.Level.PROJECT)
// PersistentStateComponent<SyncFilesConfig.State> 推荐使用内部静态类来表示状态
public final class SyncFilesConfig implements PersistentStateComponent<SyncFilesConfig.State> {

    // 内部静态类表示状态，便于序列化和状态管理
    public static class State {
        @Tag("mappings")
        public List<Mapping> mappings = new ArrayList<>();

        @MapAnnotation(surroundWithTag = true, keyAttributeName = "name", valueAttributeName = "value")
        public Map<String, String> envVariables = new ConcurrentHashMap<>(); // 改用 ConcurrentHashMap

        @OptionTag("pythonScriptPath")
        public String pythonScriptPath = "";

        @OptionTag("pythonExecutablePath")
        public String pythonExecutablePath = "";
    }

    private State myState = new State(); // 持有状态对象

    public static SyncFilesConfig getInstance(Project project) {
        return project.getService(SyncFilesConfig.class);
    }

    @Nullable
    @Override
    public State getState() {
        // 返回内部状态对象的副本或直接返回（取决于是否需要保护内部状态不被外部修改）
        // 为简单起见，这里直接返回，但要注意 myState 内部集合的并发访问
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        // 使用 XmlSerializerUtil.copyBean 来安全地复制状态
        // 这通常能处理好集合和字段的复制
        XmlSerializerUtil.copyBean(state, myState);
        // 确保 envVariables 仍然是 ConcurrentHashMap (如果 copyBean 创建了普通 HashMap)
        if (!(myState.envVariables instanceof ConcurrentHashMap)) {
            myState.envVariables = new ConcurrentHashMap<>(myState.envVariables);
        }
        // 同样可以检查 mappings 是否需要转为线程安全的 List (如 CopyOnWriteArrayList) 如果需要频繁修改的话
        if (!(myState.mappings instanceof ArrayList)) { // 或者使用 CopyOnWriteArrayList
            myState.mappings = new ArrayList<>(myState.mappings);
        }
    }

    // --- 提供线程安全的访问方法 ---

    public List<Mapping> getMappings() {
        // 返回副本以防止外部修改，读取本身受益于 ConcurrentHashMap (如果适用) 或需要同步
        // 如果 mappings 不是线程安全的，这里需要同步块
        synchronized (myState) { // 同步访问 myState
            return new ArrayList<>(myState.mappings);
        }
    }

    public void setMappings(List<Mapping> mappings) {
        synchronized (myState) { // 同步访问 myState
            // 创建副本以存储
            this.myState.mappings = new ArrayList<>(mappings);
        }
    }

    public Map<String, String> getEnvVariables() {
        // ConcurrentHashMap 的读取操作通常是线程安全的，但返回副本更安全
        // 无需外部同步即可访问 ConcurrentHashMap 的读取方法
        return new HashMap<>(myState.envVariables); // 返回普通 HashMap 副本
        // 或者如果调用者也需要线程安全，可以返回 new ConcurrentHashMap<>(myState.envVariables)
    }

    public void setEnvVariables(Map<String, String> envVariables) {
        // ConcurrentHashMap 的写入操作是线程安全的
        // 如果要完全替换，可以 clear + putAll，或者创建一个新的 ConcurrentHashMap
        myState.envVariables.clear();
        myState.envVariables.putAll(envVariables);
        // 或者: this.myState.envVariables = new ConcurrentHashMap<>(envVariables);
    }

    public String getPythonScriptPath() {
        synchronized (myState) { // String 读取理论上原子，但同步更保险
            return myState.pythonScriptPath;
        }
    }

    public void setPythonScriptPath(String pythonScriptPath) {
        synchronized (myState) {
            myState.pythonScriptPath = pythonScriptPath != null ? pythonScriptPath : "";
        }
    }

    public String getPythonExecutablePath() {
        synchronized (myState) {
            return myState.pythonExecutablePath;
        }
    }

    public void setPythonExecutablePath(String pythonExecutablePath) {
        synchronized (myState) {
            myState.pythonExecutablePath = pythonExecutablePath != null ? pythonExecutablePath : "";
        }
    }
}