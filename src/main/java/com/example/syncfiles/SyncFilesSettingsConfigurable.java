package com.example.syncfiles;

import com.intellij.openapi.fileChooser.FileChooser; // Import FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor; // Import FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.vfs.VirtualFile; // Import VirtualFile
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;

import org.jetbrains.annotations.NotNull; // Ensure correct NotNull import

public class SyncFilesSettingsConfigurable implements Configurable {
    private JPanel mainPanel;
    private JBTable mappingsTable;
    private JBTable envVarsTable;
    private DefaultTableModel mappingsTableModel;
    private DefaultTableModel envVarsTableModel;
    private final Project project;
    private TextFieldWithBrowseButton pythonScriptPathField;
    private TextFieldWithBrowseButton pythonExecutablePathField;
    public static String applyScriptPath;
    private List<Mapping> originalMappings;
    private Map<String, String> originalEnvVars;
    private String originalScriptPath;
    private String originalExePath;


    public SyncFilesSettingsConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public String getDisplayName() {
        return "SyncFiles Settings";
    }

    @Override
    public JComponent createComponent() {
        // --- Mappings Table Setup (unchanged) ---
        mappingsTableModel = new DefaultTableModel(new Object[]{"Source URL", "Target Path"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return true; // 允许编辑
            }
        };
        mappingsTable = new JBTable(mappingsTableModel);
        JPanel mappingsPanel = ToolbarDecorator.createDecorator(mappingsTable)
                .setAddAction(button -> mappingsTableModel.addRow(new String[]{"", ""}))
                .setRemoveAction(button -> {
                    // 获取选中的视图行索引
                    int[] selectedRows = mappingsTable.getSelectedRows();
                    if (selectedRows.length == 0) {
                        return; // 没有选中行，直接返回
                    }
                    // 对选中的行索引进行排序（升序），以便我们从后往前删除
                    Arrays.sort(selectedRows);
                    // 从后往前遍历选中的行索引
                    for (int i = selectedRows.length - 1; i >= 0; i--) {
                        // 将视图索引转换为模型索引 (重要！以防表格排序或过滤)
                        int modelRow = mappingsTable.convertRowIndexToModel(selectedRows[i]);
                        // 从 TableModel 中删除行
                        mappingsTableModel.removeRow(modelRow);
                    }
                })
                .setPreferredSize(new Dimension(500, 150))
                .createPanel();

        // --- Environment Variables Table Setup (unchanged) ---
        envVarsTableModel = new DefaultTableModel(new Object[]{"Variable Name", "Value"}, 0) {/* ... */};
        envVarsTable = new JBTable(envVarsTableModel);
        JPanel envVarsPanel = ToolbarDecorator.createDecorator(envVarsTable)
                .setAddAction(button -> envVarsTableModel.addRow(new String[]{"", ""}))
                .setRemoveAction(button -> {
                    // 获取选中的视图行索引
                    int[] selectedRows = envVarsTable.getSelectedRows();
                    if (selectedRows.length == 0) {
                        return; // 没有选中行，直接返回
                    }
                    // 对选中的行索引进行排序（升序），以便我们从后往前删除
                    Arrays.sort(selectedRows);
                    // 从后往前遍历选中的行索引
                    for (int i = selectedRows.length - 1; i >= 0; i--) {
                        // 将视图索引转换为模型索引 (重要！)
                        int modelRow = envVarsTable.convertRowIndexToModel(selectedRows[i]);
                        // 从 TableModel 中删除行
                        envVarsTableModel.removeRow(modelRow);
                    }
                })
                .setPreferredSize(new Dimension(500, 150))
                .createPanel();

        // --- Python Script Path Setup (MODIFIED) ---
        pythonScriptPathField = new TextFieldWithBrowseButton();
        // 1. Create the descriptor
        final FileChooserDescriptor scriptFolderDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
        scriptFolderDescriptor.setTitle("Select Python Scripts Directory");
        scriptFolderDescriptor.setDescription("Select the directory containing your Python utility scripts");
        // 2. Add ActionListener using the new pattern
        pythonScriptPathField.addActionListener(e -> {
            VirtualFile file = FileChooser.chooseFile(scriptFolderDescriptor, project, null);
            if (file != null) {
                pythonScriptPathField.setText(file.getPath());
            }
        });

        // --- Python Executable Path Setup (MODIFIED) ---
        pythonExecutablePathField = new TextFieldWithBrowseButton();
        // 1. Create the descriptor
        final FileChooserDescriptor exeFileDescriptor = FileChooserDescriptorFactory.createSingleFileOrExecutableAppDescriptor();
        exeFileDescriptor.setTitle("Select Python Executable");
        exeFileDescriptor.setDescription("Select the python.exe or python binary");
        // 2. Add ActionListener using the new pattern
        pythonExecutablePathField.addActionListener(e -> {
            VirtualFile file = FileChooser.chooseFile(exeFileDescriptor, project, null);
            if (file != null) {
                pythonExecutablePathField.setText(file.getPath());
            }
        });


        // --- Layout using GridBagLayout (unchanged) ---
        mainPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = GridBagConstraints.RELATIVE; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.WEST; gbc.insets = JBUI.insets(5, 0);
        mainPanel.add(new JBLabel("File Mappings (GitHub URL to Local Path):"), gbc);
        gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.insets = JBUI.insets(0, 0, 15, 0);
        mainPanel.add(mappingsPanel, gbc);
        gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE; gbc.insets = JBUI.insets(5, 0);
        mainPanel.add(new JBLabel("Environment Variables for Python Scripts:"), gbc);
        gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.insets = JBUI.insets(0, 0, 15, 0);
        mainPanel.add(envVarsPanel, gbc);
        gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE; gbc.insets = JBUI.insets(5, 0);
        mainPanel.add(new JBLabel("Python Scripts Directory:"), gbc);
        gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.insets = JBUI.insets(0, 0, 5, 0);
        mainPanel.add(pythonScriptPathField, gbc); // Add the field itself
        gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE; gbc.insets = JBUI.insets(5, 0);
        mainPanel.add(new JBLabel("Python Executable Path:"), gbc);
        gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.insets = JBUI.insets(0, 0, 15, 0);
        mainPanel.add(pythonExecutablePathField, gbc); // Add the field itself
        gbc.weighty = 1.0; mainPanel.add(new JPanel(), gbc); // Filler

        return JBUI.Panels.simplePanel().addToTop(mainPanel).withBorder(JBUI.Borders.empty(10));
    }

    // --- isModified(), apply(), reset(), disposeUIResources(), helpers (unchanged) ---
    // ... (Keep the rest of the methods as they were) ...

    @Override
    public boolean isModified() {
        boolean mappingsChanged = !getMappingsFromTable().equals(originalMappings);
        boolean envVarsChanged = !getEnvVarsFromTable().equals(originalEnvVars);
        // Use getText() to compare with original string values
        boolean scriptPathChanged = !pythonScriptPathField.getText().trim().equals(originalScriptPath);
        boolean exePathChanged = !pythonExecutablePathField.getText().trim().equals(originalExePath);
        return mappingsChanged || envVarsChanged || scriptPathChanged || exePathChanged;
    }

    @Override
    public void apply() throws ConfigurationException {

        // 确保停止单元格编辑，这样 getMappingsFromTable 能拿到最新的值
        if (mappingsTable.isEditing()) {
            mappingsTable.getCellEditor().stopCellEditing();
        }
        if (envVarsTable.isEditing()) {
            envVarsTable.getCellEditor().stopCellEditing();
        }
        String projectName = project.getName();
        System.out.println("[" + projectName + "][Settings] Applying settings...");
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);

        // Validate and Save Mappings
        List<Mapping> mappings = getMappingsFromTable();
        for (int i = 0; i < mappings.size(); i++) {
            Mapping mapping = mappings.get(i);
            if (mapping.sourceUrl.isEmpty()) throw new ConfigurationException("Mapping source URL cannot be empty (row " + (i + 1) + ").");
            if (mapping.targetPath.isEmpty()) throw new ConfigurationException("Mapping target path cannot be empty (row " + (i + 1) + ").");
            if (!mapping.sourceUrl.matches("^https?://.*")) throw new ConfigurationException("Invalid source URL format (row " + (i + 1) + "). Must start with http:// or https://.");
            try { Paths.get(mapping.targetPath); } catch (InvalidPathException e) { throw new ConfigurationException("Invalid target path format (row " + (i + 1) + "): " + e.getMessage());}
        }
        config.setMappings(mappings);

        // Validate and Save Environment Variables
        Map<String, String> envVars = getEnvVarsFromTable();
        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            if (entry.getKey().isEmpty()) throw new ConfigurationException("Environment variable name cannot be empty.");
        }
        config.setEnvVariables(envVars);

        // Validate and Save Python Paths
        String scriptPath = pythonScriptPathField.getText().trim(); // Get text from field
        Path scriptPathObj = null;
        if (!scriptPath.isEmpty()) {
            try {
                scriptPathObj = Paths.get(scriptPath);
                if (!Files.exists(scriptPathObj)) throw new ConfigurationException("Python Scripts Directory does not exist: " + scriptPath);
                if (!Files.isDirectory(scriptPathObj)) throw new ConfigurationException("Python Scripts Path must be a directory: " + scriptPath);
            } catch (InvalidPathException e) { throw new ConfigurationException("Invalid path format for Python Scripts Directory: " + e.getMessage());}
        }
        config.setPythonScriptPath(scriptPath); // Save the string path
        applyScriptPath = scriptPath;
        String exePath = pythonExecutablePathField.getText().trim(); // Get text from field
        Path exePathObj = null;
        if (!exePath.isEmpty()) {
            try {
                exePathObj = Paths.get(exePath);
                if (!Files.exists(exePathObj)) throw new ConfigurationException("Python Executable does not exist: " + exePath);
                if (!Files.isRegularFile(exePathObj)) throw new ConfigurationException("Python Executable Path must be a file: " + exePath);
                // Optional: Check filename contains 'python'
            } catch (InvalidPathException e) { throw new ConfigurationException("Invalid path format for Python Executable: " + e.getMessage());}
        }
        config.setPythonExecutablePath(exePath); // Save the string path
        System.out.println("[" + projectName + "][Settings] Configuration saved.");

        // Update Watcher Service
        ProjectDirectoryWatcherService watcherService = project.getService(ProjectDirectoryWatcherService.class);
        if (watcherService != null) {
            System.out.println("[" + projectName + "][Settings] Updating watcher service [Svc@" + watcherService.hashCode() + "]");
            watcherService.updateWatchedDirectories();
        } else {
            System.err.println("[" + projectName + "][Settings] Apply settings: Could not get ProjectDirectoryWatcherService instance.");
            throw new ConfigurationException("Failed to update directory watcher. Service unavailable.");
        }

        // Publish configuration changed notification
        System.out.println("[" + projectName + "][Settings] Publishing configurationChanged notification.");
        SyncFilesNotifier publisher = project.getMessageBus().syncPublisher(SyncFilesNotifier.TOPIC);
        publisher.configurationChanged();

        updateOriginalState();
        System.out.println("[" + projectName + "][Settings] Apply complete.");
    }

    @Override
    public void reset() {
        updateOriginalState(); // Capture current config before resetting UI

        mappingsTableModel.setRowCount(0);
        if (originalMappings != null) {
            for (Mapping mapping : originalMappings) {
                mappingsTableModel.addRow(new Object[]{mapping.sourceUrl, mapping.targetPath});
            }
        }

        envVarsTableModel.setRowCount(0);
        if (originalEnvVars != null) {
            for (Map.Entry<String, String> entry : originalEnvVars.entrySet()) {
                envVarsTableModel.addRow(new Object[]{entry.getKey(), entry.getValue()});
            }
        }
        // Set text field values from captured strings
        pythonScriptPathField.setText(originalScriptPath != null ? originalScriptPath : "");
        pythonExecutablePathField.setText(originalExePath != null ? originalExePath : "");
    }

    private void updateOriginalState() {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        originalMappings = config.getMappings(); // Gets a copy
        originalEnvVars = config.getEnvVariables(); // Gets a copy
        originalScriptPath = config.getPythonScriptPath();
        originalExePath = config.getPythonExecutablePath();
    }


    @Override
    public void disposeUIResources() {
        mainPanel = null;
        mappingsTable = null; envVarsTable = null;
        mappingsTableModel = null; envVarsTableModel = null;
        pythonScriptPathField = null; pythonExecutablePathField = null;
        originalMappings = null; originalEnvVars = null;
        originalScriptPath = null; originalExePath = null;
    }

    private List<Mapping> getMappingsFromTable() {
        List<Mapping> mappings = new ArrayList<>();
        for (int i = 0; i < mappingsTableModel.getRowCount(); i++) {
            String sourceUrl = ((String) mappingsTableModel.getValueAt(i, 0)).trim();
            String targetPath = ((String) mappingsTableModel.getValueAt(i, 1)).trim();
            if (!sourceUrl.isEmpty() && !targetPath.isEmpty()) {
                mappings.add(new Mapping(sourceUrl, targetPath));
            }
        }
        return mappings;
    }

    private Map<String, String> getEnvVarsFromTable() {
        Map<String, String> envVars = new HashMap<>();
        for (int i = 0; i < envVarsTableModel.getRowCount(); i++) {
            String key = ((String) envVarsTableModel.getValueAt(i, 0)).trim();
            String value = (String) envVarsTableModel.getValueAt(i, 1);
            if (!key.isEmpty()) {
                envVars.put(key, value != null ? value.trim() : "");
            }
        }
        return envVars;
    }
}