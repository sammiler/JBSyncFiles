package com.example.syncfiles.notifiers;

import com.intellij.util.messages.Topic;

public interface SyncFilesNotifier {
    // 定义一个 Topic，每个项目一个实例
    Topic<SyncFilesNotifier> TOPIC = Topic.create("SyncFiles Update Notification", SyncFilesNotifier.class, Topic.BroadcastDirection.TO_CHILDREN);

    // 定义通知方法
    void configurationChanged(); // 例如，当配置改变时
    void scriptsChanged();      // 例如，当监控的脚本目录变化时
}