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
import java.awt.event.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SyncFilesSettingsConfigurable implements Configurable {
    private JPanel panel;
    private JBTable mappingsTable;
    private DefaultTableModel tableModel;
    private final Project project;
    private JTextField refreshIntervalField;
    private JButton shortcutButton;
    private JLabel shortcutLabel;
    private String currentShortcut;
    private volatile boolean isCapturing;
    private JTextField pythonScriptPathField;
    private JTextField pythonExecutablePathField;

    public SyncFilesSettingsConfigurable(Project project) {
        this.project = project;
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        this.currentShortcut = config.getShortcutKey() != null ? config.getShortcutKey() : "Ctrl+Shift+S";
        this.isCapturing = false;
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

        JPanel refreshPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        refreshPanel.add(new JBLabel("触发重载刷新时间 (ms):"));
        refreshIntervalField = new JTextField(10);
        refreshPanel.add(refreshIntervalField);
        gbc.gridy = 2;
        mainPanel.add(refreshPanel, gbc);

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

        JPanel shortcutPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        shortcutPanel.add(new JBLabel("重载硬盘快捷键:"));
        shortcutLabel = new JLabel(currentShortcut);
        shortcutButton = new JButton("设置快捷键");
        shortcutButton.addActionListener(e -> startShortcutCapture());
        shortcutPanel.add(shortcutLabel);
        shortcutPanel.add(shortcutButton);
        gbc.gridy = 5;
        mainPanel.add(shortcutPanel, gbc);

        panel.add(mainPanel, BorderLayout.NORTH);
        reset();
        return panel;
    }

    private void startShortcutCapture() {
        JDialog dialog = new JDialog((Frame) SwingUtilities.getAncestorOfClass(Frame.class, panel), "设置快捷键", true);
        dialog.setLayout(new BorderLayout());
        dialog.setSize(300, 150);
        dialog.setLocationRelativeTo(panel);

        JTextField shortcutField = new JTextField(20);
        shortcutField.setEditable(false);
        dialog.add(new JLabel("按下快捷键:"), BorderLayout.NORTH);
        dialog.add(shortcutField, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel();
        JButton okButton = new JButton("确认");
        JButton cancelButton = new JButton("取消");
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        StringBuilder shortcutText = new StringBuilder();
        Set<Integer> pressedKeys = new HashSet<>();

        KeyListener keyListener = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int keyCode = e.getKeyCode();
                int modifiers = e.getModifiersEx();
                pressedKeys.add(keyCode);

                shortcutText.setLength(0);
                if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) shortcutText.append("Ctrl+");
                if ((modifiers & InputEvent.ALT_DOWN_MASK) != 0) shortcutText.append("Alt+");
                if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) shortcutText.append("Shift+");

                String keyText = null;
                for (int code : pressedKeys) {
                    if (code != KeyEvent.VK_CONTROL && code != KeyEvent.VK_ALT && code != KeyEvent.VK_SHIFT) {
                        if (code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) {
                            keyText = String.valueOf((char) code);
                        } else if (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9) {
                            keyText = String.valueOf((char) code);
                        } else {
                            keyText = switch (code) {
                                case KeyEvent.VK_SPACE -> "Space";
                                case KeyEvent.VK_ENTER -> "Enter";
                                case KeyEvent.VK_TAB -> "Tab";
                                case KeyEvent.VK_F1 -> "F1";
                                case KeyEvent.VK_F2 -> "F2";
                                case KeyEvent.VK_F3 -> "F3";
                                case KeyEvent.VK_F4 -> "F4";
                                case KeyEvent.VK_F5 -> "F5";
                                case KeyEvent.VK_F6 -> "F6";
                                case KeyEvent.VK_F7 -> "F7";
                                case KeyEvent.VK_F8 -> "F8";
                                case KeyEvent.VK_F9 -> "F9";
                                case KeyEvent.VK_F10 -> "F10";
                                case KeyEvent.VK_F11 -> "F11";
                                case KeyEvent.VK_F12 -> "F12";
                                default -> KeyEvent.getKeyText(code);
                            };
                        }
                        break;
                    }
                }

                if (keyText != null) {
                    shortcutText.append(keyText);
                } else if (shortcutText.length() > 0) {
                    shortcutText.setLength(shortcutText.length() - 1);
                }

                shortcutField.setText(shortcutText.toString());
            }

            @Override
            public void keyReleased(KeyEvent e) {
                pressedKeys.remove(e.getKeyCode());
            }
        };

        shortcutField.addKeyListener(keyListener);

        okButton.addActionListener(e -> {
            if (shortcutText.length() > 0) {
                currentShortcut = shortcutText.toString();
                shortcutLabel.setText(currentShortcut);
            }
            dialog.dispose();
        });

        cancelButton.addActionListener(e -> dialog.dispose());

        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shortcutField.removeKeyListener(keyListener);
            }
        });

        dialog.setVisible(true);
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

        try {
            int currentInterval = Integer.parseInt(refreshIntervalField.getText());
            if (currentInterval != config.getRefreshInterval()) {
                return true;
            }
        } catch (NumberFormatException e) {
            return true;
        }

        if (!currentShortcut.equals(config.getShortcutKey())) {
            return true;
        }

        String currentPythonPath = pythonScriptPathField.getText().trim();
        String savedPythonPath = config.getPythonScriptPath() != null ? config.getPythonScriptPath() : "";
        if (!currentPythonPath.equals(savedPythonPath)) {
            return true;
        }

        String currentPythonExe = pythonExecutablePathField.getText().trim();
        String savedPythonExe = config.getPythonExecutablePath() != null ? config.getPythonExecutablePath() : "";
        if (!currentPythonExe.equals(savedPythonExe)) {
            return true;
        }

        return false;
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

        int interval;
        try {
            interval = Integer.parseInt(refreshIntervalField.getText());
            if (interval < 0) {
                throw new ConfigurationException("刷新时间间隔不能为负数。");
            }
        } catch (NumberFormatException e) {
            throw new ConfigurationException("无效的刷新时间格式。");
        }
        config.setRefreshInterval(interval);

        if (currentShortcut == null || currentShortcut.trim().isEmpty()) {
            throw new ConfigurationException("快捷键不能为空。");
        }
        config.setShortcutKey(currentShortcut);

        config.setPythonScriptPath(pythonScriptPathField.getText().trim());
        config.setPythonExecutablePath(pythonExecutablePathField.getText().trim());
    }

    @Override
    public void reset() {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        tableModel.setRowCount(0);
        List<Mapping> mappings = config.getMappings();
        for (Mapping mapping : mappings) {
            tableModel.addRow(new Object[]{mapping.sourceUrl, mapping.targetPath});
        }
        refreshIntervalField.setText(String.valueOf(config.getRefreshInterval()));
        currentShortcut = config.getShortcutKey();
        shortcutLabel.setText(currentShortcut);
        pythonScriptPathField.setText(config.getPythonScriptPath());
        pythonExecutablePathField.setText(config.getPythonExecutablePath());
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        mappingsTable = null;
        tableModel = null;
        refreshIntervalField = null;
        shortcutButton = null;
        shortcutLabel = null;
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