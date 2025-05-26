// File: SmartWorkflowConfigAppliedNotifier.java
package com.example.syncfiles.notifiers; // 或者你选择的包名

import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;
import java.util.EventListener;
// 引入你的 SmartPlatformConfig POJO
import com.example.syncfiles.config.smartworkflow.SmartPlatformConfig;


public interface SmartWorkflowConfigAppliedNotifier extends EventListener {
    /**
     * 定义一个唯一的 Topic，用于发布和订阅此事件。
     * 通常使用当前类作为 Topic 的 display name。
     */
    Topic<SmartWorkflowConfigAppliedNotifier> TOPIC = Topic.create("Smart Workflow Config Applied", SmartWorkflowConfigAppliedNotifier.class);

    /**
     * 当 Smart Workflow 的配置从 YAML 应用到主设置，并且文件同步被触发后调用。
     *
     * @param project The current project context (can be null if project-agnostic).
     * @param platformConfigPojo The platform-specific configuration POJO that was processed.
     *                           This is useful for the next phase (processing watch entries).
     */
    void configurationAppliedAndSyncInitiated(Project project, SmartPlatformConfig platformConfigPojo);
}