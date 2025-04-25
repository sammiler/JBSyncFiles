package com.example.syncfiles;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// Runs once when a project is opened
public class WatcherInitializerActivity implements ProjectActivity {

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        System.out.println("WatcherInitializerActivity executing for project: " + project.getName());
        // Get the project-specific service instance
        ProjectDirectoryWatcherService watcherService = project.getService(ProjectDirectoryWatcherService.class);
        if (watcherService != null) {
            // Initialize watches based on the current project configuration
            // Run on pooled thread to avoid blocking startup? VFS access might require EDT/ReadAction later though.
            // For simplicity, run directly for now, but consider background if config loading is slow.
            watcherService.updateWatchedDirectories();
        } else {
            System.err.println("Failed to get ProjectDirectoryWatcherService for project: " + project.getName());
        }
        return Unit.INSTANCE; // Required for Kotlin suspend function compatibility
    }
}