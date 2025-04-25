package com.example.syncfiles;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull; // Correct NotNull import

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Factory responsible for creating the SyncFiles tool window content.
 * IMPORTANT: The factory instance itself might be shared across multiple windows
 * of the same project. UI update logic must operate on window-specific components.
 */
public class SyncFilesToolWindowFactory implements com.intellij.openapi.wm.ToolWindowFactory {

    // REMOVED: Do not store UI components as fields if the factory instance can be shared.
    // private JPanel scriptButtonPanel;

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        String projectName = project.getName();
        int factoryHashCode = System.identityHashCode(this);
        System.out.println("[" + projectName + "][Factory@" + factoryHashCode + "] Creating tool window content for Project [Project@" + System.identityHashCode(project) + "], ToolWindow ID: " + toolWindow.getId());

        // --- Create UI Components specific to *this* window instance ---
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(JBUI.Borders.empty(10));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        // Use a local variable for the script panel of this specific window.
        JPanel windowScriptButtonPanel = new JPanel();
        windowScriptButtonPanel.setLayout(new BoxLayout(windowScriptButtonPanel, BoxLayout.Y_AXIS));
        windowScriptButtonPanel.setBorder(JBUI.Borders.empty(5));
        int panelHashCode = System.identityHashCode(windowScriptButtonPanel);
        System.out.println("[" + projectName + "][Factory@" + factoryHashCode + "] Created new script panel [Panel@" + panelHashCode + "]");

        // Sync Button
        JButton syncButton = new JButton("Sync GitHub Files");
        syncButton.setToolTipText("Download files/directories based on mappings in Settings.");
        // Action listener uses the correct project context captured here.
        syncButton.addActionListener(e -> new SyncAction().syncFiles(project));
        topPanel.add(syncButton);

        // Refresh Button
        JButton refreshButton = new JButton("Refresh Scripts");
        refreshButton.setToolTipText("Reload Python scripts from the configured directory.");
        // Capture references needed by the ActionListener lambda.
        final Project capturedProject = project;
        final JPanel capturedPanel = windowScriptButtonPanel; // Capture *this window's* panel
        refreshButton.addActionListener(e -> {
            System.out.println("[" + capturedProject.getName() + "][Factory@" + factoryHashCode + "][ActionListener] Refresh clicked. Updating panel [Panel@" + System.identityHashCode(capturedPanel) + "]");
            // Call the update method, passing the specific panel for this window.
            updateScriptButtonsPanel(capturedProject, capturedPanel, true);
        });
        topPanel.add(refreshButton);

        mainPanel.add(topPanel, BorderLayout.NORTH);

        // Scroll Pane containing this window's script panel
        JBScrollPane scrollPane = new JBScrollPane(windowScriptButtonPanel);
        scrollPane.setVerticalScrollBarPolicy(JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // --- Subscribe to Message Bus ---
        // Capture references needed by the Message Bus listener lambda.
        final Project capturedProjectForEvents = project;
        final JPanel capturedPanelForEvents = windowScriptButtonPanel; // Capture *this window's* panel
        project.getMessageBus().connect(toolWindow.getDisposable()).subscribe(SyncFilesNotifier.TOPIC, new SyncFilesNotifier() {
            @Override
            public void configurationChanged() {
                int currentPanelHashCode = System.identityHashCode(capturedPanelForEvents);
                System.out.println("[" + capturedProjectForEvents.getName() + "][Factory@" + factoryHashCode + "][MsgListener] Config changed. Updating panel [Panel@" + currentPanelHashCode + "]");
                ApplicationManager.getApplication().invokeLater(() -> {
                    // Call the update method, passing the specific panel captured by this listener.
                    updateScriptButtonsPanel(capturedProjectForEvents, capturedPanelForEvents, false);
                });
            }

            @Override
            public void scriptsChanged() {
                int currentPanelHashCode = System.identityHashCode(capturedPanelForEvents);
                System.out.println("[" + capturedProjectForEvents.getName() + "][Factory@" + factoryHashCode + "][MsgListener] Scripts changed. Updating panel [Panel@" + currentPanelHashCode + "]");
                ApplicationManager.getApplication().invokeLater(() -> {
                    // Call the update method, passing the specific panel captured by this listener.
                    updateScriptButtonsPanel(capturedProjectForEvents, capturedPanelForEvents, false);
                });
            }
        });

        // --- Initial UI Population ---
        // Call the update method for the first time for this window's panel.
        updateScriptButtonsPanel(project, windowScriptButtonPanel, true);

        // Add the main panel (containing this window's specific components) to the tool window.
        toolWindow.getComponent().add(mainPanel);
        System.out.println("[" + projectName + "][Factory@" + factoryHashCode + "] Finished createToolWindowContent for ToolWindow ID: " + toolWindow.getId());
    }

    /**
     * Updates the script buttons displayed within a specific target JPanel.
     * This method reads the current configuration and populates the provided panel.
     *
     * @param project         The current project context.
     * @param targetPanel     The specific JPanel instance to update for a given tool window.
     * @param isInitialCall   True if this is the first population during window creation.
     */
    private void updateScriptButtonsPanel(@NotNull Project project, @NotNull JPanel targetPanel, boolean isInitialCall) {
        String projectName = project.getName();
        int panelHashCode = System.identityHashCode(targetPanel);
        System.out.println("[" + projectName + "][Updater] Updating script buttons for panel [Panel@" + panelHashCode + "]. Initial call: " + isInitialCall);

        // Ensure UI updates happen on the EDT
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            System.out.println("[" + projectName + "][Updater] Not on EDT, scheduling update for panel [Panel@" + panelHashCode + "]");
            ApplicationManager.getApplication().invokeLater(() -> updateScriptButtonsPanel(project, targetPanel, isInitialCall));
            return;
        }

        // 1. Clear the target panel
        targetPanel.removeAll();

        // 2. Get Configuration
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        if (config == null) {
            System.err.println("[" + projectName + "][Updater] Failed to get SyncFilesConfig for panel [Panel@" + panelHashCode + "]");
            targetPanel.add(new JBLabel("Error: Configuration service unavailable."));
            targetPanel.revalidate();
            targetPanel.repaint();
            return;
        }
        String scriptPathStr = config.getPythonScriptPath();
        String pythonExeStr = config.getPythonExecutablePath();
        System.out.println("[" + projectName + "][Updater] Config for panel [Panel@" + panelHashCode + "]: ScriptPath='" + scriptPathStr + "', ExePath='" + pythonExeStr + "'");


        // 3. Validate Paths
        Path scriptPath = null;
        boolean scriptPathValid = false;

        if (scriptPathStr != null && !scriptPathStr.isEmpty()) {
            try {
                Path tempPath = Paths.get(scriptPathStr).normalize();
                if (Files.isDirectory(tempPath)) {
                    scriptPath = tempPath;
                    scriptPathValid = true;
                } else {
                    // Log error but don't show dialog unless triggered by user action (handled elsewhere)
                    System.err.println("[" + projectName + "][Updater] Configured script path is not a directory: " + scriptPathStr);
                }
            } catch (InvalidPathException e) {
                System.err.println("[" + projectName + "][Updater] Invalid script path format: " + scriptPathStr);
            }
        } else {
            System.out.println("[" + projectName + "][Updater] Script path is empty for panel [Panel@" + panelHashCode + "]");
        }

        Path pythonExePath = null;
        boolean exePathValid = false;
        if (pythonExeStr != null && !pythonExeStr.isEmpty()) {
            try {
                Path tempPath = Paths.get(pythonExeStr).normalize();
                if (Files.isRegularFile(tempPath)) {
                    pythonExePath = tempPath;
                    exePathValid = true;
                } else {
                    System.err.println("[" + projectName + "][Updater] Configured python executable is not a file: " + pythonExeStr);
                }
            } catch (InvalidPathException e) {
                System.err.println("[" + projectName + "][Updater] Invalid python executable path format: " + pythonExeStr);
            }
        } else {
            System.out.println("[" + projectName + "][Updater] Python executable path is empty for panel [Panel@" + panelHashCode + "]");
        }


        // 4. Find Python Script Files
        List<Path> pythonFiles = new ArrayList<>();
        if (scriptPathValid) {
            try (Stream<Path> paths = Files.list(scriptPath)) {
                pythonFiles = paths
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".py"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                        .collect(Collectors.toList());
                System.out.println("[" + projectName + "][Updater] Found " + pythonFiles.size() + " python files in " + scriptPath + " for panel [Panel@" + panelHashCode + "]");
            } catch (IOException e) {
                System.err.println("[" + projectName + "][Updater] Error reading script directory " + scriptPath + " for panel [Panel@" + panelHashCode + "]: " + e.getMessage());
                targetPanel.add(new JBLabel("Error reading script directory."));
                // Show limited error in UI, full error logged
            }
        }

        // 5. Populate the target panel
        if (!scriptPathValid) {
            if (isInitialCall || !scriptPathStr.isEmpty()) { // Show message if path was configured but invalid, or on first load
                targetPanel.add(new JBLabel("Configure a valid Python script directory in Settings."));
            }
        } else if (pythonFiles.isEmpty()) {
            // Valid directory, but no scripts found
            targetPanel.add(new JBLabel("No *.py scripts found in " + scriptPath.getFileName()));
        } else {
            // Valid directory with scripts
            final Path finalPythonExePath = pythonExePath; // Final for lambda
            boolean canExecute = exePathValid;

            for (Path pyFilePath : pythonFiles) {
                String buttonText = pyFilePath.getFileName().toString().replaceFirst("(?i)\\.py$", "");
                JButton button = new JButton(buttonText);
                button.setToolTipText("Run " + pyFilePath.getFileName() + (canExecute ? "" : " (Configure Python executable path first!)"));
                button.setAlignmentX(Component.LEFT_ALIGNMENT);
                button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
                button.setEnabled(canExecute);

                // Capture necessary variables for the script execution action listener
                final String scriptToExecute = pyFilePath.toAbsolutePath().toString();
                final String exeToUse = canExecute ? finalPythonExePath.toString() : null;

                button.addActionListener(e -> {
                    if (exeToUse == null) {
                        // Re-check just in case config changed without UI refresh (unlikely but safe)
                        SyncFilesConfig currentConfig = SyncFilesConfig.getInstance(project);
                        String currentExe = currentConfig.getPythonExecutablePath();
                        if (currentExe == null || currentExe.isEmpty() || !Files.isRegularFile(Paths.get(currentExe))) {
                            Messages.showErrorDialog(project, "Python executable path is not configured or invalid.", "Execution Error");
                            return;
                        }
                        // If valid now, proceed (though button should ideally be enabled)
                        executePythonScript(project, currentExe, scriptToExecute);
                    } else {
                        executePythonScript(project, exeToUse, scriptToExecute);
                    }
                });
                targetPanel.add(button); // Add button to the target panel
                targetPanel.add(Box.createVerticalStrut(5));
            }
        }

        // 6. Redraw the target panel
        targetPanel.revalidate();
        targetPanel.repaint();
        System.out.println("[" + projectName + "][Updater] Finished updating panel [Panel@" + panelHashCode + "]");
    }


    /**
     * Executes the specified Python script in a background task.
     * Reads configuration for environment variables.
     *
     * @param project          The current project context.
     * @param pythonExecutable The absolute path to the Python executable.
     * @param scriptPath       The absolute path to the Python script to execute.
     */
    private void executePythonScript(@NotNull Project project, @NotNull String pythonExecutable, @NotNull String scriptPath) {
        String projectName = project.getName();
        System.out.println("[" + projectName + "] Preparing to execute Python script: " + scriptPath);
        System.out.println("[" + projectName + "] Using Python executable: " + pythonExecutable);

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Running Python Script: " + Paths.get(scriptPath).getFileName(), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Executing " + Paths.get(scriptPath).getFileName());

                try {
                    // Ensure the script file is synced to disk before execution
                    Util.forceRefreshVFS(scriptPath);

                    ProcessBuilder pb = new ProcessBuilder(pythonExecutable, scriptPath);

                    // Set Environment Variables from Project Config
                    SyncFilesConfig config = SyncFilesConfig.getInstance(project);
                    if (config == null) {
                        System.err.println("[" + projectName + "] Failed to get SyncFilesConfig for environment variables.");
                        // Decide: proceed without config env vars or throw error?
                        // Let's proceed for now, logging the error.
                    } else {
                        Map<String, String> envVars = config.getEnvVariables(); // Reads a copy
                        if (!envVars.isEmpty()) {
                            Map<String, String> processEnv = pb.environment();
                            processEnv.putAll(envVars);
                            System.out.println("[" + projectName + "] Added environment variables from config: " + envVars.keySet());
                        } else {
                            System.out.println("[" + projectName + "] No environment variables configured in settings.");
                        }
                    }
                    // REMOVED problematic static fallback:
                    // if (envVars.isEmpty()) { envVars = SyncFilesSettingsConfigurable.applyEnvVars; }

                    // Force UTF-8 for Python I/O
                    pb.environment().put("PYTHONIOENCODING", "UTF-8");

                    // Start the process
                    Process process = pb.start();

                    // Capture output streams using UTF-8 (unchanged logic)
                    StringBuilder output = new StringBuilder();
                    StringBuilder errorOutput = new StringBuilder();
                    Thread outputReaderThread = new Thread(() -> { /* ... stream reading ... */
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                            String line; while ((line = reader.readLine()) != null) output.append(line).append("\n");
                        } catch (IOException e) { System.err.println("[" + projectName + "] Error reading script stdout: " + e.getMessage());}
                    });
                    Thread errorReaderThread = new Thread(() -> { /* ... stream reading ... */
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                            String line; while ((line = reader.readLine()) != null) errorOutput.append(line).append("\n");
                        } catch (IOException e) { System.err.println("[" + projectName + "] Error reading script stderr: " + e.getMessage());}
                    });

                    outputReaderThread.start();
                    errorReaderThread.start();

                    // Wait for process completion
                    int exitCode = process.waitFor();

                    // Wait for reader threads to finish
                    outputReaderThread.join();
                    errorReaderThread.join();

                    final String finalOutput = output.toString().trim();
                    final String finalError = errorOutput.toString().trim();

                    // Show result dialog on EDT (unchanged logic)
                    ApplicationManager.getApplication().invokeLater(() -> { /* ... show success/error dialog ... */
                        if (exitCode == 0) {
                            String message = "Script executed successfully!\n\nOutput:\n" + (finalOutput.isEmpty() ? "<No Output>" : finalOutput);
                            if (!finalError.isEmpty()) message += "\n\nStandard Error:\n" + finalError;
                            Messages.showInfoMessage(project, message, "Script Success: " + Paths.get(scriptPath).getFileName());
                        } else {
                            String message = "Script execution failed! (Exit Code: " + exitCode + ")\n\n";
                            if (!finalOutput.isEmpty()) message += "Standard Output:\n" + finalOutput + "\n\n";
                            if (!finalError.isEmpty()) message += "Standard Error:\n" + finalError;
                            else message += "Standard Error: <No Error Output>";
                            Messages.showErrorDialog(project, message, "Script Failure: " + Paths.get(scriptPath).getFileName());
                        }
                        Util.refreshAllFiles(project); // Refresh VFS after execution
                    });

                } catch (IOException | InterruptedException e) {
                    System.err.println("[" + projectName + "] Failed to execute script '" + scriptPath + "': " + e.getMessage());
                    // Show error dialog on EDT
                    ApplicationManager.getApplication().invokeLater(() ->
                            Messages.showErrorDialog(project, "Failed to start script: " + Paths.get(scriptPath).getFileName() + "\nError: " + e.getMessage(), "Execution Error")
                    );
                }
            }
        });
    }
}