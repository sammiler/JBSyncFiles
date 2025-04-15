package com.example.syncfiles;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.table.JBTable;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;

public class SyncFilesSettingsConfigurable implements Configurable {
    private JPanel panel;
    private JBTable mappingsTable;
    private DefaultTableModel tableModel;
    private final Project project;
    private JTextField pythonScriptPathField;
    private JTextField pythonExecutablePathField;

    public SyncFilesSettingsConfigurable(Project project) {
        this.project = project;
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
    }

    @Override
    public String getDisplayName() {
        return "SyncFiles 设置";
    }

    @Override
    public JComponent createComponent() {
        panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(JBUI.Borders.empty(10));

        JPanel mainPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(5);

        tableModel = new DefaultTableModel(new Object[]{"源 URL", "目标路径"}, 0);
        mappingsTable = new JBTable(tableModel);
        mappingsTable.setPreferredScrollableViewportSize(new Dimension(500, 200));
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.weighty = 1.0;
        mainPanel.add(new JScrollPane(mappingsTable), gbc);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addButton = new JButton("添加映射");
        addButton.addActionListener(e -> tableModel.addRow(new Object[]{"", ""}));
        JButton removeButton = new JButton("移除映射");
        removeButton.addActionListener(e -> {
            int selectedRow = mappingsTable.getSelectedRow();
            if (selectedRow >= 0) {
                tableModel.removeRow(selectedRow);
            }
        });
        buttonPanel.add(addButton);
        buttonPanel.add(removeButton);
        gbc.gridy = 1;
        gbc.weighty = 0;
        mainPanel.add(buttonPanel, gbc);


        gbc.gridy = 2;

        JPanel pythonPathPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        pythonPathPanel.add(new JBLabel("Python脚本路径:"));
        pythonScriptPathField = new JTextField(30);
        pythonPathPanel.add(pythonScriptPathField);
        gbc.gridy = 3;
        mainPanel.add(pythonPathPanel, gbc);

        JPanel pythonExePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        pythonExePanel.add(new JBLabel("Python可执行地址:"));
        pythonExecutablePathField = new JTextField(30);
        pythonExePanel.add(pythonExecutablePathField);
        gbc.gridy = 4;
        mainPanel.add(pythonExePanel, gbc);


        gbc.gridy = 5;

        panel.add(mainPanel, BorderLayout.NORTH);
        reset();
        return panel;
    }


    @Override
    public boolean isModified() {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);

        List<Mapping> currentMappings = getMappingsFromTable();
        List<Mapping> savedMappings = config.getMappings();
        if (currentMappings.size() != savedMappings.size()) {
            return true;
        }
        for (int i = 0; i < currentMappings.size(); i++) {
            if (!currentMappings.get(i).equals(savedMappings.get(i))) {
                return true;
            }
        }


        String currentPythonPath = pythonScriptPathField.getText().trim();
        String savedPythonPath = config.getPythonScriptPath() != null ? config.getPythonScriptPath() : "";
        if (!currentPythonPath.equals(savedPythonPath)) {
            return true;
        }

        String currentPythonExe = pythonExecutablePathField.getText().trim();
        String savedPythonExe = config.getPythonExecutablePath() != null ? config.getPythonExecutablePath() : "";
        return !currentPythonExe.equals(savedPythonExe);
    }

    @Override
    public void apply() throws ConfigurationException {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);

        List<Mapping> mappings = getMappingsFromTable();
        for (Mapping mapping : mappings) {
            if (mapping.sourceUrl.isEmpty() || mapping.targetPath.isEmpty()) {
                throw new ConfigurationException("源 URL 和目标路径不能为空。");
            }
        }
        config.setMappings(mappings);


        Path rootDir = Paths.get(Objects.requireNonNull(project.getBasePath()));

        config.setPythonScriptPath(pythonScriptPathField.getText().trim());
        config.setPythonExecutablePath(pythonExecutablePathField.getText().trim());
        try {
            SyncAction.directoryWatcher.watchDirectory(Paths.get(pythonScriptPathField.getText().trim()));
            SyncAction.directoryWatcher.watchDirectory(rootDir);
            SyncAction.directoryWatcher.startWatching();
            SyncAction.directoryWatcher.refreshWindow(pythonScriptPathField.getText().trim(),pythonExecutablePathField.getText().trim());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public void reset() {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        tableModel.setRowCount(0);
        List<Mapping> mappings = config.getMappings();
        for (Mapping mapping : mappings) {
            tableModel.addRow(new Object[]{mapping.sourceUrl, mapping.targetPath});
        }
        pythonScriptPathField.setText(config.getPythonScriptPath());
        pythonExecutablePathField.setText(config.getPythonExecutablePath());
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        mappingsTable = null;
        tableModel = null;
        pythonScriptPathField = null;
        pythonExecutablePathField = null;
    }

    private List<Mapping> getMappingsFromTable() {
        List<Mapping> mappings = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            String sourceUrl = (String) tableModel.getValueAt(i, 0);
            String targetPath = (String) tableModel.getValueAt(i, 1);
            if (sourceUrl != null && !sourceUrl.trim().isEmpty() && targetPath != null && !targetPath.trim().isEmpty()) {
                mappings.add(new Mapping(sourceUrl.trim(), targetPath.trim()));
            }
        }
        return mappings;
    }
}