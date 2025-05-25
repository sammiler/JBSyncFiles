// com/example/syncfiles/WatchEntry.java
package com.example.syncfiles;

import com.intellij.util.xmlb.annotations.Attribute;
import com.intellij.util.xmlb.annotations.Tag;
import java.util.Objects;

@Tag("WatchEntry")
public class WatchEntry {
    @Attribute("watchedPath")
    public String watchedPath = "";

    @Attribute("onEventScript") // Relative to project Path
    public String onEventScript = "";

    public WatchEntry() {}

    public WatchEntry(String watchedPath, String onEventScript) {
        this.watchedPath = watchedPath != null ? watchedPath.replace('\\', '/') : "";
        this.onEventScript = onEventScript != null ? onEventScript.replace('\\', '/') : "";
    }

    // equals and hashCode based on both fields
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WatchEntry that = (WatchEntry) o;
        return Objects.equals(watchedPath, that.watchedPath) && Objects.equals(onEventScript, that.onEventScript);
    }

    @Override
    public int hashCode() {
        return Objects.hash(watchedPath, onEventScript);
    }

    @Override
    public String toString() {
        return "WatchedPath: " + watchedPath + ",onEventScript: " + onEventScript +" .";
    }
}