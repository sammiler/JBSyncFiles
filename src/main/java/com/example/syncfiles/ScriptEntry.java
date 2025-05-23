// com/example/syncfiles/ScriptEntry.java
package com.example.syncfiles;

import com.intellij.util.xmlb.annotations.Attribute;
import com.intellij.util.xmlb.annotations.Tag;

import java.nio.file.Paths;
import java.util.Objects;
import java.util.UUID;

@Tag("ScriptEntry")
public class ScriptEntry {
    @Attribute("id")
    public String id = UUID.randomUUID().toString();

    @Attribute("path") // Relative to pythonScriptPath
    public String path = "";

    @Attribute("alias")
    public String alias = "";

    @Attribute("executionMode") // "terminal" or "directApi"
    public String executionMode = "terminal";

    @Tag("description")
    public String description = "";

    // Transient field, not persisted, for UI to indicate if file is missing
    private transient boolean isMissing = false;

    public ScriptEntry() {
    }

    public ScriptEntry(String path) {
        this.path = path != null ? path.replace('\\', '/') : "";
    }

    public String getDisplayName() {
        if (alias != null && !alias.trim().isEmpty()) {
            return alias.trim();
        }
        if (path.isEmpty())
            return "Unnamed Script";
        String fileName = Paths.get(path).getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex != -1) {
            fileName = fileName.substring(0, dotIndex);
        }
        return fileName;
    }

    public boolean isMissing() {
        return isMissing;
    }

    public void setMissing(boolean missing) {
        isMissing = missing;
    }

    // equals and hashCode based on ID
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScriptEntry that = (ScriptEntry) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}