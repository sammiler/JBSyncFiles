package com.example.syncfiles;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction; // Use ReadAction for VFS access if needed
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages; // Keep using Messages
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.openapi.vfs.VirtualFile; // For VFS operations
import com.intellij.util.ui.JBUI;
import groovyjarjarantlr4.v4.runtime.misc.NotNull;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
// import java.io.File; // Less reliant on File, use Path/VirtualFile
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

// Implement ToolWindowFactory directly
public class SyncFilesToolWindowFactory implements com.intellij.openapi.wm.ToolWindowFactory {
    // No Project field needed if we pass it around correctly
    // No watcher field needed
    private JPanel scriptButtonPanel; // Keep panel reference for updates


    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // Store this instance in the Util map so it can be found later for updates
        Util.initToolWindowFactory(project, this);

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Top Panel for Actions
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton syncButton = new JButton("Sync GitHub Files");
        syncButton.setToolTipText("Download files/directories based on mappings in Settings.");
        syncButton.addActionListener(e -> {
            // Directly call the SyncAction logic
            new SyncAction().syncFiles(project);
            // SyncAction shows progress, no need for extra dialog here
        });
        topPanel.add(syncButton);

        JButton refreshButton = new JButton("Refresh Scripts");
        refreshButton.setToolTipText("Reload Python scripts from the configured directory.");
        refreshButton.addActionListener(e -> refreshScriptButtons(project, true, false));
        topPanel.add(refreshButton);

        mainPanel.add(topPanel, BorderLayout.NORTH);

        // Script Buttons Panel (Scrollable)
        scriptButtonPanel = new JPanel();
        // Use BoxLayout for vertical stacking
        scriptButtonPanel.setLayout(new BoxLayout(scriptButtonPanel, BoxLayout.Y_AXIS));
        // Add some padding inside the scroll pane's content
        scriptButtonPanel.setBorder(JBUI.Borders.empty(5));

        JBScrollPane scrollPane = new JBScrollPane(scriptButtonPanel);
        scrollPane.setVerticalScrollBarPolicy(JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // Initial population of script buttons
        // Pass false for internal/selfInit if called during initial creation
        refreshScriptButtons(project, false, true);

        // Add the main panel to the tool window component
        toolWindow.getComponent().add(mainPanel);

        // No need to interact with watcher here - it's handled by startup activity / settings apply
        // try {
        //    Util.refreshAndSetWatchDir(project); // REMOVED
        // } catch (IOException e) {
        //    Messages.showErrorDialog(project, "Failed to initialize directory watcher: " + e.getMessage(), "Initialization Error");
        //}
    }

    /**
     * Refreshes the buttons based on *.py files in the configured script path.
     * @param project Current project
     * @param triggeredByUser True if triggered by button click, false if automatic/initial.
     * @param isInitialCall True if this is the very first call during tool window creation.
     */
    public void refreshScriptButtons(@NotNull Project project, boolean triggeredByUser, boolean isInitialCall) {
        System.out.println("[" + project.getName() + "] Refreshing script buttons requested. User action: " + triggeredByUser);

        // Always clear existing buttons first
        scriptButtonPanel.removeAll();

        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        String scriptPathStr = config.getPythonScriptPath();
        String pythonExeStr = config.getPythonExecutablePath();

        // Validate paths before proceeding
        Path scriptPath = null;
        if (scriptPathStr != null && !scriptPathStr.isEmpty()) {
            try {
                scriptPath = Paths.get(scriptPathStr).normalize();
                if (!Files.isDirectory(scriptPath) && triggeredByUser) {
                    String message = "Configured Python Scripts Path is not a valid directory: " + scriptPathStr;
                    System.err.println(message);
                    if (triggeredByUser) { // Only show error dialog if user explicitly clicked refresh
                        Messages.showErrorDialog(project, message, "Script Path Error");
                    } else if (!isInitialCall) { // Show warning on subsequent auto-refreshes if path becomes invalid
                        Messages.showWarningDialog(project, message, "Script Path Warning");
                    }
                    scriptPath = null; // Invalidate path
                }
            } catch (InvalidPathException e) {
                String message = "Invalid format for Python Scripts Path: " + scriptPathStr;
                System.err.println(message);
                if (triggeredByUser) Messages.showErrorDialog(project, message, "Script Path Error");
                scriptPath = null; // Invalidate path
            }
        } else {
            // Path is empty - this is valid, just means no scripts to show
            System.out.println("[" + project.getName() + "] Python script path is empty. No scripts will be listed.");
            if (triggeredByUser && !isInitialCall) { // Inform user on manual refresh if path is empty
                Messages.showInfoMessage(project, "Python Scripts Directory is not configured in settings.", "Info");
            }
        }

        Path pythonExePath = null;
        if (pythonExeStr != null && !pythonExeStr.isEmpty()) {
            try {
                pythonExePath = Paths.get(pythonExeStr).normalize();
                if (!Files.isRegularFile(pythonExePath) && triggeredByUser) {
                    String message = "Configured Python Executable Path is not a valid file: " + pythonExeStr;
                    System.err.println(message);
                    // This is more critical - warn always if invalid (except initial silent load)
                    if (triggeredByUser || !isInitialCall) Messages.showWarningDialog(project, message, "Python Executable Warning");
                    pythonExePath = null; // Invalidate
                }
            } catch (InvalidPathException e) {
                String message = "Invalid format for Python Executable Path: " + pythonExeStr;
                System.err.println(message);
                if (triggeredByUser || !isInitialCall) Messages.showWarningDialog(project, message, "Python Executable Error");
                pythonExePath = null; // Invalidate
            }
        } else {
            // Executable path is empty - scripts cannot be run
            System.out.println("[" + project.getName() + "] Python executable path is empty. Scripts cannot be executed.");
            if (triggeredByUser && !isInitialCall && scriptPath != null) { // Only warn if scripts *could* be shown but not run
                Messages.showWarningDialog(project, "Python Executable Path is not configured. Scripts cannot be run.", "Warning");
            }
        }

        // If script path is valid, find and add buttons
        if (scriptPath != null) {
            List<Path> pythonFiles = new ArrayList<>();
            try (Stream<Path> paths = Files.list(scriptPath)) { // Use Files.list for non-recursive listing
                pythonFiles = paths
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".py"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase())) // Sort alphabetically
                        .collect(Collectors.toList());
            } catch (IOException e) {
                String message = "Error reading Python scripts directory: " + scriptPathStr + "\n" + e.getMessage();
                System.err.println(message);
                if (triggeredByUser && !isInitialCall) { // Show error if not initial silent load
                    Messages.showErrorDialog(project, message, "Error Loading Scripts");
                }
            }

            if (pythonFiles.isEmpty()) {
                if (scriptPath != null) { // Only add label if path itself was valid
                    JBLabel noScriptsLabel = new JBLabel("No *.py scripts found in " + scriptPath.getFileName());
                    noScriptsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                    scriptButtonPanel.add(noScriptsLabel);
                }
            } else {
                final Path finalPythonExePath = pythonExePath; // Effectively final for lambda
                boolean canExecute = finalPythonExePath != null;

                for (Path pyFilePath : pythonFiles) {
                    String buttonText = pyFilePath.getFileName().toString().replaceFirst("(?i)\\.py$", ""); // Case-insensitive remove .py
                    JButton button = new JButton(buttonText);
                    button.setToolTipText("Run " + pyFilePath.getFileName() + (canExecute ? "" : " (Configure Python executable path first!)"));
                    button.setAlignmentX(Component.LEFT_ALIGNMENT);
                    // Ensure buttons don't stretch wider than the panel
                    button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
                    button.setEnabled(canExecute); // Disable button if Python exe is not set

                    button.addActionListener(e -> {
                        // Re-check executable path just before running
                        SyncFilesConfig currentConfig = SyncFilesConfig.getInstance(project);
                        String currentExePath = currentConfig.getPythonExecutablePath();
                        if (currentExePath == null || currentExePath.isEmpty() || !Files.isRegularFile(Paths.get(currentExePath))) {
                            Messages.showErrorDialog(project, "Python executable path is not configured or invalid. Cannot run script.", "Execution Error");
                            // Optionally refresh buttons again to disable them
                            refreshScriptButtons(project, true, false);
                            return;
                        }
                        executePythonScript(project, currentExePath, pyFilePath.toAbsolutePath().toString());
                    });
                    scriptButtonPanel.add(button);
                    scriptButtonPanel.add(Box.createVerticalStrut(5)); // Add spacing between buttons
                }
            }
        } else if (!triggeredByUser && isInitialCall) {
            // Initial call and path is invalid/empty, show placeholder
            JBLabel configureLabel = new JBLabel("Configure Python script/executable paths in Settings.");
            configureLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            scriptButtonPanel.add(configureLabel);
        }


        // Redraw the panel
        scriptButtonPanel.revalidate();
        scriptButtonPanel.repaint();
        System.out.println("[" + project.getName() + "] Script buttons refresh complete.");
    }


    // No longer needs scriptPath/pythonExe parameters, reads from config inside if needed
    public void refreshScriptButtons(@NotNull Project project, boolean triggeredByUser) {
        refreshScriptButtons(project, triggeredByUser, false); // Not initial call
    }


    // Renamed method for clarity
    private void executePythonScript(@NotNull Project project, @NotNull String pythonExecutable, @NotNull String scriptPath) {
        System.out.println("[" + project.getName() + "] Executing Python script: " + scriptPath);
        System.out.println("Using Python executable: " + pythonExecutable);

        // Refresh VFS for the specific script file just before execution? Maybe not needed if watcher works.
        // Util.forceRefreshVFS(scriptPath);

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Running Python Script: " + Paths.get(scriptPath).getFileName(), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Executing " + Paths.get(scriptPath).getFileName());

                try {
                    Util.forceRefreshVFS(scriptPath);
                    ProcessBuilder pb = new ProcessBuilder(pythonExecutable, scriptPath);

                    // Set working directory (optional, defaults to IDE process CWD)
                    // Path scriptDir = Paths.get(scriptPath).getParent();
                    // if (scriptDir != null) pb.directory(scriptDir.toFile());

                    // Get environment variables from config
                    SyncFilesConfig config = SyncFilesConfig.getInstance(project);
                    Map<String, String> envVars = config.getEnvVariables(); // Reads a copy
                    if (envVars.isEmpty())
                    {
                        envVars = SyncFilesSettingsConfigurable.applyEnvVars;
                    }
                    if (!envVars.isEmpty()) {
                        Map<String, String> processEnv = pb.environment();
                        // Add configured vars, potentially overriding system vars
                        processEnv.putAll(envVars);
                        System.out.println("[" + project.getName() + "] Added environment variables: " + envVars.keySet());
                    }
                    // Force UTF-8 for Python I/O
                    pb.environment().put("PYTHONIOENCODING", "UTF-8");

                    // Start the process
                    Process process = pb.start();

                    // Capture output streams using UTF-8
                    StringBuilder output = new StringBuilder();
                    StringBuilder errorOutput = new StringBuilder();
                    Thread outputReaderThread = new Thread(() -> {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                output.append(line).append("\n");
                                // Update progress indicator text incrementally?
                                // ApplicationManager.getApplication().invokeLater(() -> indicator.setText2(line));
                            }
                        } catch (IOException e) {
                            System.err.println("[" + project.getName() + "] Error reading script stdout: " + e.getMessage());
                        }
                    });
                    Thread errorReaderThread = new Thread(() -> {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                errorOutput.append(line).append("\n");
                            }
                        } catch (IOException e) {
                            System.err.println("[" + project.getName() + "] Error reading script stderr: " + e.getMessage());
                        }
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

                    // Show result dialog on EDT
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (exitCode == 0) {
                            String message = "Script executed successfully!\n\nOutput:\n" + (finalOutput.isEmpty() ? "<No Output>" : finalOutput);
                            // Optionally show stderr even on success if it's not empty
                            if (!finalError.isEmpty()) {
                                message += "\n\nStandard Error:\n" + finalError;
                            }
                            Messages.showInfoMessage(project, message, "Script Success: " + Paths.get(scriptPath).getFileName());
                        } else {
                            String message = "Script execution failed! (Exit Code: " + exitCode + ")\n\n";
                            if (!finalOutput.isEmpty()) {
                                message += "Standard Output:\n" + finalOutput + "\n\n";
                            }
                            if (!finalError.isEmpty()) {
                                message += "Standard Error:\n" + finalError;
                            } else {
                                message += "Standard Error: <No Error Output>";
                            }
                            Messages.showErrorDialog(project, message, "Script Failure: " + Paths.get(scriptPath).getFileName());
                        }
                        // Refresh VFS after execution in case the script modified files
                        Util.refreshAllFiles(project);
                    });

                } catch (IOException | InterruptedException e) {
                    System.err.println("[" + project.getName() + "] Failed to execute script: " + e.getMessage());
                    ApplicationManager.getApplication().invokeLater(() ->
                            Messages.showErrorDialog(project, "Failed to execute script: " + scriptPath + "\nError: " + e.getMessage(), "Execution Error")
                    );
                }
            }
        });
    }
}