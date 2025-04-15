package com.example.syncfiles;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.ui.table.JBTable;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SyncFilesSettingsConfigurable implements Configurable {
    private JPanel panel;
    private JBTable mappingsTable;
    private JBTable envVarsTable;
    private DefaultTableModel mappingsTableModel;
    private DefaultTableModel envVarsTableModel;
    private final Project project;
    private JTextField pythonScriptPathField;
    private JTextField pythonExecutablePathField;

    public SyncFilesSettingsConfigurable(Project project) {
        this.project = project;
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

        // 文件映射表格
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.weighty = 0.3; // 降低映射表格高度
        mainPanel.add(new JBLabel("文件映射:"), gbc);

        gbc.gridy = 1;
        mappingsTableModel = new DefaultTableModel(new Object[]{"源 URL", "目标路径"}, 0);
        mappingsTable = new JBTable(mappingsTableModel);
        mappingsTable.setPreferredScrollableViewportSize(new Dimension(500, 100)); // 减小高度
        mainPanel.add(new JScrollPane(mappingsTable), gbc);

        JPanel mappingsButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addMappingButton = new JButton("添加映射");
        addMappingButton.addActionListener(e -> mappingsTableModel.addRow(new Object[]{"", ""}));
        JButton removeMappingButton = new JButton("移除映射");
        removeMappingButton.addActionListener(e -> {
            int selectedRow = mappingsTable.getSelectedRow();
            if (selectedRow >= 0) {
                mappingsTableModel.removeRow(selectedRow);
            }
        });
        mappingsButtonPanel.add(addMappingButton);
        mappingsButtonPanel.add(removeMappingButton);
        gbc.gridy = 2;
        gbc.weighty = 0;
        mainPanel.add(mappingsButtonPanel, gbc);

        // 环境变量表格
        gbc.gridy = 3;
        mainPanel.add(new JBLabel("环境变量:"), gbc);

        gbc.gridy = 4;
        envVarsTableModel = new DefaultTableModel(new Object[]{"变量名", "变量值"}, 0);
        envVarsTable = new JBTable(envVarsTableModel);
        envVarsTable.setPreferredScrollableViewportSize(new Dimension(500, 100));
        mainPanel.add(new JScrollPane(envVarsTable), gbc);

        JPanel envVarsButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addEnvVarButton = new JButton("添加环境变量");
        addEnvVarButton.addActionListener(e -> envVarsTableModel.addRow(new Object[]{"", ""}));
        JButton removeEnvVarButton = new JButton("移除环境变量");
        removeEnvVarButton.addActionListener(e -> {
            int selectedRow = envVarsTable.getSelectedRow();
            if (selectedRow >= 0) {
                envVarsTableModel.removeRow(selectedRow);
            }
        });
        envVarsButtonPanel.add(addEnvVarButton);
        envVarsButtonPanel.add(removeEnvVarButton);
        gbc.gridy = 5;
        mainPanel.add(envVarsButtonPanel, gbc);

        // Python 路径输入框
        JPanel pythonPathPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JBLabel pythonScriptLabel = new JBLabel("Python脚本路径:");
        pythonScriptLabel.setPreferredSize(new Dimension(120, pythonScriptLabel.getPreferredSize().height));
        pythonPathPanel.add(pythonScriptLabel);
        pythonScriptPathField = new JTextField(30);
        pythonPathPanel.add(pythonScriptPathField);
        gbc.gridy = 6;
        mainPanel.add(pythonPathPanel, gbc);

        // Python 可执行路径输入框
        JPanel pythonExePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JBLabel pythonExeLabel = new JBLabel("Python可执行地址:");
        pythonExeLabel.setPreferredSize(new Dimension(120, pythonExeLabel.getPreferredSize().height));
        pythonExePanel.add(pythonExeLabel);
        pythonExecutablePathField = new JTextField(30);
        pythonExePanel.add(pythonExecutablePathField);
        gbc.gridy = 7;
        mainPanel.add(pythonExePanel, gbc);

        panel.add(mainPanel, BorderLayout.NORTH);
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);

        // 检查文件映射
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

        // 检查环境变量
        Map<String, String> currentEnvVars = getEnvVarsFromTable();
        Map<String, String> savedEnvVars = config.getEnvVariables();
        if (!currentEnvVars.equals(savedEnvVars)) {
            return true;
        }

        // 检查 Python 路径
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

        // 保存文件映射
        List<Mapping> mappings = getMappingsFromTable();
        for (Mapping mapping : mappings) {
            if (mapping.sourceUrl.isEmpty() || mapping.targetPath.isEmpty()) {
                throw new ConfigurationException("源 URL 和目标路径不能为空。");
            }
        }
        config.setMappings(mappings);

        // 保存环境变量
        Map<String, String> envVars = getEnvVarsFromTable();
        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            if (entry.getKey().isEmpty()) {
                throw new ConfigurationException("环境变量名不能为空。");
            }
        }
        config.setEnvVariables(envVars);

        // 保存 Python 路径
        config.setPythonScriptPath(pythonScriptPathField.getText().trim());
        config.setPythonExecutablePath(pythonExecutablePathField.getText().trim());

        try {
            Util.refreshAndSetWatchDir(project, null, null);
            Util.refreshAllFiles(project);
        } catch (IOException e) {
            throw new ConfigurationException("刷新目录失败: " + e.getMessage());
        }
    }

    @Override
    public void reset() {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);

        // 重置文件映射
        mappingsTableModel.setRowCount(0);
        List<Mapping> mappings = config.getMappings();
        for (Mapping mapping : mappings) {
            mappingsTableModel.addRow(new Object[]{mapping.sourceUrl, mapping.targetPath});
        }

        // 重置环境变量
        envVarsTableModel.setRowCount(0);
        Map<String, String> envVars = config.getEnvVariables();
        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            envVarsTableModel.addRow(new Object[]{entry.getKey(), entry.getValue()});
        }

        // 重置 Python 路径
        pythonScriptPathField.setText(config.getPythonScriptPath());
        pythonExecutablePathField.setText(config.getPythonExecutablePath());
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        mappingsTable = null;
        envVarsTable = null;
        mappingsTableModel = null;
        envVarsTableModel = null;
        pythonScriptPathField = null;
        pythonExecutablePathField = null;
    }

    private List<Mapping> getMappingsFromTable() {
        List<Mapping> mappings = new ArrayList<>();
        for (int i = 0; i < mappingsTableModel.getRowCount(); i++) {
            String sourceUrl = (String) mappingsTableModel.getValueAt(i, 0);
            String targetPath = (String) mappingsTableModel.getValueAt(i, 1);
            if (sourceUrl != null && !sourceUrl.trim().isEmpty() && targetPath != null && !targetPath.trim().isEmpty()) {
                mappings.add(new Mapping(sourceUrl.trim(), targetPath.trim()));
            }
        }
        return mappings;
    }

    private Map<String, String> getEnvVarsFromTable() {
        Map<String, String> envVars = new HashMap<>();
        for (int i = 0; i < envVarsTableModel.getRowCount(); i++) {
            String key = (String) envVarsTableModel.getValueAt(i, 0);
            String value = (String) envVarsTableModel.getValueAt(i, 1);
            if (key != null && !key.trim().isEmpty()) {
                envVars.put(key.trim(), value != null ? value.trim() : "");
            }
        }
        return envVars;
    }
}