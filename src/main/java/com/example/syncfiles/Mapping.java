package com.example.syncfiles;

import com.intellij.util.xmlb.annotations.Attribute;
import com.intellij.util.xmlb.annotations.Tag;

@Tag("Mapping")
public class Mapping {
    @Attribute("sourceUrl")
    public String sourceUrl = "";

    @Attribute("targetPath")
    public String targetPath = "";

    public Mapping() {
    }

    public Mapping(String sourceUrl, String targetPath) {
        this.sourceUrl = sourceUrl != null ? sourceUrl : "";
        this.targetPath = targetPath != null ? targetPath : "";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Mapping mapping = (Mapping) o;
        return sourceUrl.equals(mapping.sourceUrl) && targetPath.equals(mapping.targetPath);
    }

    @Override
    public int hashCode() {
        return sourceUrl.hashCode() + targetPath.hashCode();
    }

    @Override
    public String toString() {
        return "Mapping{" +
                "sourceUrl='" + sourceUrl + '\'' +
                ", targetPath='" + targetPath + '\'' +
                '}';
    }
}