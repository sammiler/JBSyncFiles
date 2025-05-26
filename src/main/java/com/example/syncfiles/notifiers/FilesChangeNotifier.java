package com.example.syncfiles.notifiers;

import com.intellij.util.messages.Topic;

public interface FilesChangeNotifier {
    // 定义一个 Topic，每个项目一个实例
    Topic<FilesChangeNotifier> TOPIC = Topic.create("Files Change Notification", FilesChangeNotifier.class, Topic.BroadcastDirection.TO_CHILDREN);

    // 定义通知方法
    void watchFileChanged(String scriptPathToExecute, String eventType, String affectedFilePath);
}