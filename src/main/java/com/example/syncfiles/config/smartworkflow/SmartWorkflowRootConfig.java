package com.example.syncfiles.config.smartworkflow;// File: SmartWorkflowRootConfig.java
// package com.example.syncfiles.config.smartworkflow; // 建议放在单独的包

import java.util.Map;

// 注意：为了让 SnakeYAML 正确地将 YAML 键映射到 Java 字段，
// 字段名应与 YAML 中的键名匹配（SnakeYAML 通常能处理驼峰到连字符的转换，但最好保持一致性或明确配置）。
// 如果不匹配，你可能需要为 SnakeYAML 配置属性命名策略，或者如果用 Jackson，则使用 @JsonProperty。
// 这里我们假设字段名能直接映射。

public class SmartWorkflowRootConfig {
    private Map<String, SmartPlatformConfig> platforms;
    public SmartWorkflowRootConfig(){}
    // getters and setters
    public Map<String, SmartPlatformConfig> getPlatforms() {
        return platforms;
    }

    public void setPlatforms(Map<String, SmartPlatformConfig> platforms) {
        this.platforms = platforms;
    }
}