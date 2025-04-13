package com.example.syncfiles;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.ui.table.JBTable;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.util.ArrayList;
import java.util.List;

public class SyncFilesSettingsConfigurable implements Configurable {
    private JPanel panel;
    private JBTable mappingsTable;
    private DefaultTableModel tableModel;
    private final Project project;

    public SyncFilesSettingsConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public String getDisplayName() {
        return "SyncFiles Settings";
    }

    @Override
    public JComponent createComponent() {
        panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        // 创建表格
        tableModel = new DefaultTableModel(new Object[]{"Source URL", "Target Path"}, 0);
        mappingsTable = new JBTable(tableModel);
        mappingsTable.setPreferredScrollableViewportSize(new java.awt.Dimension(500, 200));
        panel.add(new JScrollPane(mappingsTable));

        // 添加按钮
        JPanel buttonPanel = new JPanel();
        JButton addButton = new JButton("Add Mapping");
        addButton.addActionListener(e -> tableModel.addRow(new Object[]{"", ""}));
        JButton removeButton = new JButton("Remove Mapping");
        removeButton.addActionListener(e -> {
            int selectedRow = mappingsTable.getSelectedRow();
            if (selectedRow >= 0) {
                tableModel.removeRow(selectedRow);
            }
        });
        buttonPanel.add(addButton);
        buttonPanel.add(removeButton);
        panel.add(buttonPanel);

        // 加载现有配置
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        List<SyncAction.Mapping> currentMappings = getMappingsFromTable();
        List<SyncAction.Mapping> savedMappings = SyncFilesConfig.getInstance(project).getMappings();
        return !currentMappings.equals(savedMappings);
    }

    @Override
    public void apply() throws ConfigurationException {
        List<SyncAction.Mapping> mappings = getMappingsFromTable();
        for (SyncAction.Mapping mapping : mappings) {
            if (mapping.sourceUrl.isEmpty() || mapping.targetPath.isEmpty()) {
                throw new ConfigurationException("Source URL and Target Path cannot be empty.");
            }
        }
        SyncFilesConfig.getInstance(project).setMappings(mappings);
    }

    @Override
    public void reset() {
        tableModel.setRowCount(0);
        List<SyncAction.Mapping> mappings = SyncFilesConfig.getInstance(project).getMappings();
        for (SyncAction.Mapping mapping : mappings) {
            tableModel.addRow(new Object[]{mapping.sourceUrl, mapping.targetPath});
        }
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        mappingsTable = null;
        tableModel = null;
    }

    private List<SyncAction.Mapping> getMappingsFromTable() {
        List<SyncAction.Mapping> mappings = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            String sourceUrl = (String) tableModel.getValueAt(i, 0);
            String targetPath = (String) tableModel.getValueAt(i, 1);
            if (sourceUrl != null && !sourceUrl.trim().isEmpty() && targetPath != null && !targetPath.trim().isEmpty()) {
                mappings.add(new SyncAction.Mapping(sourceUrl.trim(), targetPath.trim()));
            }
        }
        return mappings;
    }
}