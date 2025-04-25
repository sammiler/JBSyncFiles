package com.example.syncfiles;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.intellij.util.xmlb.annotations.XCollection; // More explicit collection annotation
import com.intellij.util.xmlb.annotations.MapAnnotation;
import com.intellij.util.xmlb.annotations.OptionTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap; // Good choice
import java.util.concurrent.CopyOnWriteArrayList; // Alternative for list if needed

@State(
        name = "SyncFilesConfig",
        storages = {@Storage("syncFilesConfig.xml")} // Stored per project
)
@Service(Service.Level.PROJECT) // Correctly defined as Project Level Service
public final class SyncFilesConfig implements PersistentStateComponent<SyncFilesConfig.State> {

    // Static inner class for state is recommended
    public static class State {
        // Use @XCollection for better control over list serialization
        @XCollection(style = XCollection.Style.v2)
        public List<Mapping> mappings = new ArrayList<>(); // ArrayList is fine if only modified on EDT (Settings)

        @MapAnnotation(surroundWithTag = true, keyAttributeName = "name", valueAttributeName = "value")
        public Map<String, String> envVariables = new ConcurrentHashMap<>(); // ConcurrentHashMap is good

        @OptionTag("pythonScriptPath")
        public String pythonScriptPath = "";

        @OptionTag("pythonExecutablePath")
        public String pythonExecutablePath = "";
    }

    private State myState = new State(); // Holds the actual state

    public static SyncFilesConfig getInstance(@NotNull Project project) {
        return project.getService(SyncFilesConfig.class);
    }

    @Nullable
    @Override
    public State getState() {
        return myState; // Return the state object for serialization
    }

    @Override
    public void loadState(@NotNull State state) {
        XmlSerializerUtil.copyBean(state, myState); // Copy loaded state into current state object
        // Ensure collections are of the expected type after copyBean, if necessary
        if (!(myState.envVariables instanceof ConcurrentHashMap)) {
            myState.envVariables = new ConcurrentHashMap<>(myState.envVariables);
        }
        // Consider CopyOnWriteArrayList if mappings could be modified by background threads,
        // otherwise ArrayList synchronized externally (like below) is okay.
        // if (!(myState.mappings instanceof CopyOnWriteArrayList)) {
        //    myState.mappings = new CopyOnWriteArrayList<>(myState.mappings);
        // }
    }

    // --- Accessors with thread-safety considerations ---

    // Return immutable copy or use thread-safe list internally if needed
    public List<Mapping> getMappings() {
        synchronized (myState) {
            // Return a copy to prevent external modification of internal state
            return new ArrayList<>(myState.mappings);
        }
    }

    public void setMappings(List<Mapping> mappings) {
        synchronized (myState) {
            // Store a copy
            this.myState.mappings = new ArrayList<>(mappings != null ? mappings : Collections.emptyList());
        }
    }

    // ConcurrentHashMap is mostly thread-safe for reads/writes, returning a copy is safest for callers
    public Map<String, String> getEnvVariables() {
        // No sync needed for read if using ConcurrentHashMap, but return copy
        return new HashMap<>(myState.envVariables);
        // Or return ConcurrentHashMap copy if caller needs thread-safety too
        // return new ConcurrentHashMap<>(myState.envVariables);
    }

    public void setEnvVariables(Map<String, String> envVariables) {
        // No sync needed for replacing/clearing ConcurrentHashMap itself
        if (envVariables != null) {
            // Replace internal map safely
            this.myState.envVariables = new ConcurrentHashMap<>(envVariables);
        } else {
            this.myState.envVariables = new ConcurrentHashMap<>();
        }
        // Or use clear() + putAll() if preferred
        // myState.envVariables.clear();
        // if (envVariables != null) {
        //     myState.envVariables.putAll(envVariables);
        // }
    }

    // Primitives/Strings: Synchronization is good practice for visibility across threads
    public String getPythonScriptPath() {
        synchronized (myState) {
            return myState.pythonScriptPath;
        }
    }

    public void setPythonScriptPath(String pythonScriptPath) {
        synchronized (myState) {
            this.myState.pythonScriptPath = pythonScriptPath != null ? pythonScriptPath.trim() : "";
        }
    }

    public String getPythonExecutablePath() {
        synchronized (myState) {
            return myState.pythonExecutablePath;
        }
    }

    public void setPythonExecutablePath(String pythonExecutablePath) {
        synchronized (myState) {
            this.myState.pythonExecutablePath = pythonExecutablePath != null ? pythonExecutablePath.trim() : "";
        }
    }
}