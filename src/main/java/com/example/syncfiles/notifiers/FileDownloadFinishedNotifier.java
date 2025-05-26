// File: FileDownloadFinishedNotifier.java
package com.example.syncfiles.notifiers; // 或者你选择的包名

import com.intellij.util.messages.Topic;
import java.util.EventListener;

public interface FileDownloadFinishedNotifier extends EventListener {
    /**
     * Topic for file download finished events.
     */
    Topic<FileDownloadFinishedNotifier> TOPIC = Topic.create("File Download Finished", FileDownloadFinishedNotifier.class);

    /**
     * Called when a file download process (typically initiated by SyncAction) has finished.
     * This signal implies that local target files may have been updated.
     */
    void downloadFinished(); // ★ 不需要参数 ★
}