// com/example/syncfiles/ScriptGroup.java
package com.example.syncfiles;

import com.intellij.util.xmlb.annotations.Attribute;
import com.intellij.util.xmlb.annotations.Tag;
import com.intellij.util.xmlb.annotations.XCollection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Tag("ScriptGroup")
public class ScriptGroup {
    public static final String DEFAULT_GROUP_ID = "syncfiles-default-group-id";
    public static final String DEFAULT_GROUP_NAME = "Default";

    @Attribute("id")
    public String id = UUID.randomUUID().toString();

    @Attribute("name")
    public String name = "";

    @XCollection(style = XCollection.Style.v2, elementTypes = ScriptEntry.class)
    public List<ScriptEntry> scripts = new ArrayList<>();

    public ScriptGroup() {}

    public ScriptGroup(String id, String name) {
        this.id = id;
        this.name = name;
    }

    // equals and hashCode based on ID
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScriptGroup that = (ScriptGroup) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ScriptGroup{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", scriptCount=" + (scripts != null ? scripts.size() : 0) +
                '}';
    }
}