package com.example.syncfiles;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.intellij.util.xmlb.annotations.XCollection;
import com.intellij.util.xmlb.annotations.MapAnnotation;
import com.intellij.util.xmlb.annotations.OptionTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
// import java.util.stream.Collectors; // Potentially for deep list comparison if elements don't have equals

@State(
        name = "SyncFilesConfig",
        storages = {@Storage("syncFilesConfig.xml")}
)
@Service(Service.Level.PROJECT)
public final class SyncFilesConfig implements PersistentStateComponent<SyncFilesConfig.State> {

    // Static inner class for state
    public static class State {
        @XCollection(style = XCollection.Style.v2)
        public List<Mapping> mappings = new ArrayList<>();

        @MapAnnotation(surroundWithTag = true, keyAttributeName = "name", valueAttributeName = "value")
        public Map<String, String> envVariables = new ConcurrentHashMap<>(); // Or HashMap if only accessed via synchronized methods

        @OptionTag("pythonScriptPath")
        public String pythonScriptPath = "";

        @OptionTag("pythonExecutablePath")
        public String pythonExecutablePath = "";

        @XCollection(style = XCollection.Style.v2, elementTypes = WatchEntry.class)
        public List<WatchEntry> watchEntries = new ArrayList<>();

        @XCollection(style = XCollection.Style.v2, elementTypes = ScriptGroup.class)
        public List<ScriptGroup> scriptGroups = new ArrayList<>();

        // --- equals and hashCode ---
        // IMPORTANT: This assumes that Mapping, WatchEntry, and ScriptGroup
        // (and ScriptEntry if ScriptGroup.equals depends on it)
        // have correctly implemented their own equals() and hashCode() methods
        // based on ALL relevant persistent fields.

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            State state = (State) o;

            // Compare all fields. For collections, their .equals() method will be used,
            // which in turn relies on the .equals() method of their elements.
            return Objects.equals(mappings, state.mappings) &&
                    Objects.equals(envVariables, state.envVariables) && // Map.equals compares entries
                    Objects.equals(pythonScriptPath, state.pythonScriptPath) &&
                    Objects.equals(pythonExecutablePath, state.pythonExecutablePath) &&
                    Objects.equals(watchEntries, state.watchEntries) &&
                    Objects.equals(scriptGroups, state.scriptGroups);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mappings, envVariables, pythonScriptPath, pythonExecutablePath, watchEntries, scriptGroups);
        }
    }

    private State myState = new State();

    public static SyncFilesConfig getInstance(@NotNull Project project) {
        SyncFilesConfig service = project.getService(SyncFilesConfig.class);
        if (service == null) {
            // This should not happen if plugin.xml is correct and service is registered
            throw new IllegalStateException("SyncFilesConfig service not found. Check plugin.xml registration.");
        }
        return service;
    }

    @Nullable
    @Override
    public State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        XmlSerializerUtil.copyBean(state, myState);

        // Defensive copying to ensure mutable collections and non-null state
        // XmlSerializerUtil.copyBean should handle this well, but being explicit can be safer
        // depending on the exact nature of how copyBean works with specific collection types.
        myState.mappings = new ArrayList<>(myState.mappings != null ? myState.mappings : Collections.emptyList());
        myState.envVariables = new ConcurrentHashMap<>(myState.envVariables != null ? myState.envVariables : Collections.emptyMap());
        myState.watchEntries = new ArrayList<>(myState.watchEntries != null ? myState.watchEntries : Collections.emptyList());
        myState.scriptGroups = new ArrayList<>(myState.scriptGroups != null ? myState.scriptGroups : Collections.emptyList());


        // Ensure "Default" group logic is applied after loading.
        // Accessing through the getter will enforce this.
        // Make sure getScriptGroups() itself does not create excessive copies if called frequently internally.
        // For loadState, it's fine.
        List<ScriptGroup> loadedGroups = getScriptGroups(); // This will initialize default if needed and return a copy
        // If getScriptGroups modifies myState.scriptGroups directly (which it does for adding default),
        // then just calling it is enough. No need to re-assign.
    }

    // --- Accessors with thread-safety considerations and defensive copies ---

    public List<Mapping> getMappings() {
        synchronized (myState) {
            return new ArrayList<>(myState.mappings); // Return a copy
        }
    }

    public void setMappings(List<Mapping> mappings) {
        synchronized (myState) {
            this.myState.mappings = new ArrayList<>(mappings != null ? mappings : Collections.emptyList());
        }
    }

    public Map<String, String> getEnvVariables() {
        synchronized (myState) { // Even for ConcurrentHashMap, if you want a consistent snapshot
            return new HashMap<>(myState.envVariables); // Return a copy
        }
    }

    public void setEnvVariables(Map<String, String> envVariables) {
        synchronized (myState) {
            if (envVariables != null) {
                this.myState.envVariables = new ConcurrentHashMap<>(envVariables);
            } else {
                this.myState.envVariables = new ConcurrentHashMap<>();
            }
        }
    }

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

    public List<WatchEntry> getWatchEntries() {
        synchronized (myState) {
            return new ArrayList<>(myState.watchEntries);
        }
    }

    public void setWatchEntries(List<WatchEntry> watchEntries) {
        synchronized (myState) {
            myState.watchEntries = (watchEntries != null) ? new ArrayList<>(watchEntries) : new ArrayList<>();
        }
    }

    /**
     * Gets the script groups. Ensures the "Default" group exists.
     * Returns a defensive copy.
     */
    public List<ScriptGroup> getScriptGroups() {
        synchronized (myState) {
            if (myState.scriptGroups == null) { // Should be initialized by constructor or loadState
                myState.scriptGroups = new ArrayList<>();
            }
            // Ensure "Default" group exists in the internal list
            if (myState.scriptGroups.stream().noneMatch(g -> ScriptGroup.DEFAULT_GROUP_ID.equals(g.id))) {
                ScriptGroup defaultGroup = new ScriptGroup(ScriptGroup.DEFAULT_GROUP_ID, ScriptGroup.DEFAULT_GROUP_NAME);
                // Add to the beginning of the internal list
                myState.scriptGroups.add(0, defaultGroup);
            }
            return new ArrayList<>(myState.scriptGroups); // Return a copy
        }
    }

    /**
     * Sets the script groups. Ensures the "Default" group exists after setting.
     */
    public void setScriptGroups(List<ScriptGroup> scriptGroups) {
        synchronized (myState) {
            myState.scriptGroups = (scriptGroups != null) ? new ArrayList<>(scriptGroups) : new ArrayList<>();
            // Ensure "Default" group exists in the internal list after external list is set
            if (myState.scriptGroups.stream().noneMatch(g -> ScriptGroup.DEFAULT_GROUP_ID.equals(g.id))) {
                ScriptGroup defaultGroup = new ScriptGroup(ScriptGroup.DEFAULT_GROUP_ID, ScriptGroup.DEFAULT_GROUP_NAME);
                myState.scriptGroups.add(0, defaultGroup);
            }
        }
    }

    // Inside SyncFilesConfig.java

// ... (existing code) ...

    /**
     * Removes a specific WatchEntry from the configuration.
     * This method relies on the WatchEntry class having a correct
     * implementation of equals() and hashCode().
     *
     * @param entryToRemove The WatchEntry object to remove.
     * @return {@code true} if a matching entry was found and removed, {@code false} otherwise.
     */
    public boolean removeWatchEntry(@NotNull WatchEntry entryToRemove) {
        synchronized (myState) {
            if (myState.watchEntries == null) {
                return false; // Should not happen if initialized properly
            }
            boolean removed = myState.watchEntries.remove(entryToRemove);
            if (removed) {
                setWatchEntries(myState.watchEntries);
            }
            return removed;
        }
    }

    // If you need to remove based on specific criteria rather than an identical object:
    /**
     * Removes WatchEntry items that match the given watched path and script path.
     *
     * @param watchedPathToRemove The watched path of the entry to remove.
     * @param scriptPathToRemove The script path of the entry to remove.
     * @return {@code true} if at least one matching entry was found and removed, {@code false} otherwise.
     */
    public boolean removeWatchEntryByPaths(@Nullable String watchedPathToRemove, @Nullable String scriptPathToRemove) {
        synchronized (myState) {
            if (myState.watchEntries == null || myState.watchEntries.isEmpty()) {
                return false;
            }
            // We use removeIf which iterates and removes.
            // It's important that the comparison logic here matches how you define uniqueness.
            boolean PENTING = myState.watchEntries.removeIf(entry ->
                    Objects.equals(entry.watchedPath, watchedPathToRemove) &&
                            Objects.equals(entry.onEventScript, scriptPathToRemove)
            );
//            if (PENTING) {
//                 System.out.println("SyncFilesConfig: Removed watch entry by paths - Watched='" + watchedPathToRemove + "', Script='" + scriptPathToRemove + "'");
//            }
            return PENTING;
        }
    }


// ... (rest of SyncFilesConfig.java) ...

}