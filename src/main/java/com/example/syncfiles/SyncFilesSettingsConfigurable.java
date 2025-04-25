package com.example.syncfiles;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton; // Use for path selection
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory; // For file chooser
import com.intellij.ui.table.JBTable;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane; // Use JBScrollPane
import com.intellij.util.ui.JBUI;
import com.intellij.ui.ToolbarDecorator; // Nicer table controls
import com.intellij.ui.BooleanTableCellRenderer; // For potential boolean columns
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files; // Use Files for path validation
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SyncFilesSettingsConfigurable implements Configurable {
    private JPanel mainPanel; // Renamed from 'panel'
    private JBTable mappingsTable;
    private JBTable envVarsTable;
    private DefaultTableModel mappingsTableModel;
    private DefaultTableModel envVarsTableModel;
    private final Project project;
    // Use TextFieldWithBrowseButton for easier path selection
    private TextFieldWithBrowseButton pythonScriptPathField;
    private TextFieldWithBrowseButton pythonExecutablePathField;
    public   static String applyScriptPath;
    public   static Map<String,String> applyEnvVars;
    // Store original state for isModified check
    private List<Mapping> originalMappings;
    private Map<String, String> originalEnvVars;
    private String originalScriptPath;
    private String originalExePath;


    public SyncFilesSettingsConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public String getDisplayName() {
        return "SyncFiles Settings"; // Consistent naming
    }

    @Override
    public JComponent createComponent() {
        // Mappings Table Setup
        mappingsTableModel = new DefaultTableModel(new Object[]{"Source URL", "Target Path"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return true; // Allow editing directly in the table
            }
        };
        mappingsTable = new JBTable(mappingsTableModel);
        // Decorator adds Add/Remove/Edit buttons nicely
        JPanel mappingsPanel = ToolbarDecorator.createDecorator(mappingsTable)
                .setAddAction(button -> mappingsTableModel.addRow(new String[]{"", ""}))
                .setRemoveAction(button -> {
                    int selectedRow = mappingsTable.getSelectedRow();
                    if (selectedRow >= 0) {
                        // Optional confirmation dialog
                        mappingsTableModel.removeRow(mappingsTable.convertRowIndexToModel(selectedRow));
                    }
                })
                .setPreferredSize(new Dimension(500, 150)) // Adjusted size
                .createPanel();


        // Environment Variables Table Setup
        envVarsTableModel = new DefaultTableModel(new Object[]{"Variable Name", "Value"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return true;
            }
        };
        envVarsTable = new JBTable(envVarsTableModel);
        JPanel envVarsPanel = ToolbarDecorator.createDecorator(envVarsTable)
                .setAddAction(button -> envVarsTableModel.addRow(new String[]{"", ""}))
                .setRemoveAction(button -> {
                    int selectedRow = envVarsTable.getSelectedRow();
                    if (selectedRow >= 0) {
                        envVarsTableModel.removeRow(envVarsTable.convertRowIndexToModel(selectedRow));
                    }
                })
                .setPreferredSize(new Dimension(500, 150)) // Adjusted size
                .createPanel();

        // Python Script Path Setup
        pythonScriptPathField = new TextFieldWithBrowseButton();
        pythonScriptPathField.addBrowseFolderListener("Select Python Scripts Directory", "Select the directory containing your Python utility scripts",
                project, FileChooserDescriptorFactory.createSingleFolderDescriptor()); // Use folder descriptor

        // Python Executable Path Setup
        pythonExecutablePathField = new TextFieldWithBrowseButton();
        pythonExecutablePathField.addBrowseFolderListener("Select Python Executable", "Select the python.exe or python binary",
                project, FileChooserDescriptorFactory.createSingleFileOrExecutableAppDescriptor()); // Use file descriptor


        // Layout using GridBagLayout for better control
        mainPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = GridBagConstraints.RELATIVE; // Increment y automatically
        gbc.weightx = 0.0; // Labels don't expand horizontally
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = JBUI.insets(5, 0); // Vertical spacing

        mainPanel.add(new JBLabel("File Mappings (GitHub URL to Local Path):"), gbc);

        gbc.weightx = 1.0; // Table panel expands horizontally
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(0, 0, 15, 0); // Space after table
        mainPanel.add(mappingsPanel, gbc);

        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(5, 0);
        mainPanel.add(new JBLabel("Environment Variables for Python Scripts:"), gbc);

        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(0, 0, 15, 0);
        mainPanel.add(envVarsPanel, gbc);

        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(5, 0);
        mainPanel.add(new JBLabel("Python Scripts Directory:"), gbc);

        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(0, 0, 5, 0);
        mainPanel.add(pythonScriptPathField, gbc);

        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(5, 0);
        mainPanel.add(new JBLabel("Python Executable Path:"), gbc);

        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(0, 0, 15, 0);
        mainPanel.add(pythonExecutablePathField, gbc);

        // Add a filler component to push everything to the top
        gbc.weighty = 1.0;
        mainPanel.add(new JPanel(), gbc);


        // Load initial values in reset() which is called after createComponent normally
        // reset(); // Call reset here or rely on framework call

        return JBUI.Panels.simplePanel().addToTop(mainPanel).withBorder(JBUI.Borders.empty(10));
    }

    @Override
    public boolean isModified() {
        // Compare current UI values with the original values loaded in reset()
        boolean mappingsChanged = !getMappingsFromTable().equals(originalMappings);
        boolean envVarsChanged = !getEnvVarsFromTable().equals(originalEnvVars);
        boolean scriptPathChanged = !pythonScriptPathField.getText().trim().equals(originalScriptPath);
        boolean exePathChanged = !pythonExecutablePathField.getText().trim().equals(originalExePath);

        return mappingsChanged || envVarsChanged || scriptPathChanged || exePathChanged;
    }

    @Override
    public void apply() throws ConfigurationException {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);

        // Validate and Save Mappings
        List<Mapping> mappings = getMappingsFromTable();
        for (int i = 0; i < mappings.size(); i++) {
            Mapping mapping = mappings.get(i);
            if (mapping.sourceUrl.isEmpty()) {
                throw new ConfigurationException("Mapping source URL cannot be empty (row " + (i + 1) + ").");
            }
            if (mapping.targetPath.isEmpty()) {
                throw new ConfigurationException("Mapping target path cannot be empty (row " + (i + 1) + ").");
            }
            // Basic URL format check (optional but helpful)
            if (!mapping.sourceUrl.matches("^https?://.*")) {
                throw new ConfigurationException("Invalid source URL format (row " + (i + 1) + "). Must start with http:// or https://.");
            }
            // Basic path validation (optional) - check for invalid characters maybe
            try {
                Paths.get(mapping.targetPath); // Check if path string is syntactically valid
            } catch (InvalidPathException e) {
                throw new ConfigurationException("Invalid target path format (row " + (i + 1) + "): " + e.getMessage());
            }
        }
        config.setMappings(mappings);

        // Validate and Save Environment Variables
        Map<String, String> envVars = getEnvVarsFromTable();
        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            if (entry.getKey().isEmpty()) {
                throw new ConfigurationException("Environment variable name cannot be empty.");
            }
            // Maybe check for invalid characters in variable names if needed
        }
        config.setEnvVariables(envVars);
        applyEnvVars = envVars;
        // Validate and Save Python Paths
        String scriptPath = pythonScriptPathField.getText().trim();
        if (!scriptPath.isEmpty()) {
            try {
                Path path = Paths.get(scriptPath);
                // Check if exists and is a directory
                if (!Files.exists(path)) {
                    throw new ConfigurationException("Python Scripts Directory does not exist: " + scriptPath);
                }
                if (!Files.isDirectory(path)) {
                    throw new ConfigurationException("Python Scripts Path must be a directory: " + scriptPath);
                }
            } catch (InvalidPathException e) {
                throw new ConfigurationException("Invalid path format for Python Scripts Directory: " + e.getMessage());
            }
        } // Allow empty path
        config.setPythonScriptPath(scriptPath);
        applyScriptPath = scriptPath;

        String exePath = pythonExecutablePathField.getText().trim();
        if (!exePath.isEmpty()) {
            try {
                Path path = Paths.get(exePath);
                if (!Files.exists(path)) {
                    throw new ConfigurationException("Python Executable does not exist: " + exePath);
                }
                if (!Files.isRegularFile(path)) {
                    throw new ConfigurationException("Python Executable Path must be a file: " + exePath);
                }
                // Simple check if it looks like python (optional)
                if (!path.getFileName().toString().toLowerCase().contains("python")) {
                    System.out.println("Warning: Selected Python executable path doesn't contain 'python': " + exePath);
                    // Allow it but maybe warn? For now, just proceed.
                }
            } catch (InvalidPathException e) {
                throw new ConfigurationException("Invalid path format for Python Executable: " + e.getMessage());
            }
        } // Allow empty path
        config.setPythonExecutablePath(exePath);

        // IMPORTANT: Update the watcher service after saving config
        ProjectDirectoryWatcherService watcherService = project.getService(ProjectDirectoryWatcherService.class);
        if (watcherService != null) {
            watcherService.updateWatchedDirectories(); // Tell the service to re-read config and update watches
        } else {
            System.err.println("Apply settings: Could not get ProjectDirectoryWatcherService instance.");
            // Maybe throw ConfigurationException here? Or just log error.
            throw new ConfigurationException("Failed to update directory watcher. Service unavailable.");
        }

        // Also refresh the ToolWindow UI immediately to reflect changes

        System.out.println("[" + project.getName() + "][Settings] Publishing configurationChanged notification.");
        SyncFilesNotifier publisher = project.getMessageBus().syncPublisher(SyncFilesNotifier.TOPIC);
        publisher.configurationChanged();

        // ---- 不再需要下面的代码 ----
        // SyncFilesToolWindowFactory factory = Util.getOrInitFactory(project);
        // if (factory != null) {
        //    factory.refreshScriptButtons(project, false, false);
        // }

        updateOriginalState();
        System.out.println("[" + project.getName() + "][Settings] Apply complete.");

        // Update original state after successful apply
        updateOriginalState();
    }

    @Override
    public void reset() {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);

        // Store current config values as the 'original' state for comparison in isModified
        updateOriginalState();

        // Reset UI components to match the stored original state
        mappingsTableModel.setRowCount(0); // Clear table
        for (Mapping mapping : originalMappings) {
            mappingsTableModel.addRow(new Object[]{mapping.sourceUrl, mapping.targetPath});
        }

        envVarsTableModel.setRowCount(0); // Clear table
        for (Map.Entry<String, String> entry : originalEnvVars.entrySet()) {
            envVarsTableModel.addRow(new Object[]{entry.getKey(), entry.getValue()});
        }

        pythonScriptPathField.setText(originalScriptPath);
        pythonExecutablePathField.setText(originalExePath);
    }

    // Helper to capture the current config state
    private void updateOriginalState() {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        originalMappings = config.getMappings(); // Gets a copy
        originalEnvVars = config.getEnvVariables(); // Gets a copy
        originalScriptPath = config.getPythonScriptPath() != null ? config.getPythonScriptPath() : "";
        originalExePath = config.getPythonExecutablePath() != null ? config.getPythonExecutablePath() : "";
    }


    @Override
    public void disposeUIResources() {
        // Dispose Swing components if necessary, though typically handled by framework
        mainPanel = null;
        mappingsTable = null;
        envVarsTable = null;
        mappingsTableModel = null;
        envVarsTableModel = null;
        pythonScriptPathField = null;
        pythonExecutablePathField = null;
        // Clear original state references
        originalMappings = null;
        originalEnvVars = null;
        originalScriptPath = null;
        originalExePath = null;
    }

    // Helper to get current mappings from the UI table
    private List<Mapping> getMappingsFromTable() {
        List<Mapping> mappings = new ArrayList<>();
        for (int i = 0; i < mappingsTableModel.getRowCount(); i++) {
            String sourceUrl = ((String) mappingsTableModel.getValueAt(i, 0)).trim();
            String targetPath = ((String) mappingsTableModel.getValueAt(i, 1)).trim();
            // Only add if both fields are non-empty after trimming
            if (!sourceUrl.isEmpty() && !targetPath.isEmpty()) {
                mappings.add(new Mapping(sourceUrl, targetPath));
            }
        }
        return mappings;
    }

    // Helper to get current env vars from the UI table
    private Map<String, String> getEnvVarsFromTable() {
        Map<String, String> envVars = new HashMap<>();
        for (int i = 0; i < envVarsTableModel.getRowCount(); i++) {
            String key = ((String) envVarsTableModel.getValueAt(i, 0)).trim();
            String value = (String) envVarsTableModel.getValueAt(i, 1); // Value can be empty, trim later if needed
            // Only add if key is non-empty after trimming
            if (!key.isEmpty()) {
                envVars.put(key, value != null ? value.trim() : ""); // Trim value as well
            }
        }
        return envVars;
    }
}