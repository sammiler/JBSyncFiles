package com.example.syncfiles;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.ui.table.JBTable;
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
        panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        // 映射表
        tableModel = new DefaultTableModel(new Object[]{"源 URL", "目标路径"}, 0);
        mappingsTable = new JBTable(tableModel);
        mappingsTable.setPreferredScrollableViewportSize(new java.awt.Dimension(500, 200));
        panel.add(new JScrollPane(mappingsTable));

        // 按钮
        JPanel buttonPanel = new JPanel();
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
        panel.add(buttonPanel);

        // 刷新时间设置
        JPanel refreshPanel = new JPanel();
        refreshPanel.add(new JLabel("触发 IDE 刷新时间 (ms):"));
        refreshIntervalField = new JTextField(10);
        refreshPanel.add(refreshIntervalField);
        panel.add(refreshPanel);

        // 快捷键设置
        JPanel shortcutPanel = new JPanel();
        shortcutPanel.add(new JLabel("IDE 刷新快捷键:"));
        shortcutLabel = new JLabel(currentShortcut);
        shortcutButton = new JButton("设置快捷键");
        shortcutButton.addActionListener(e -> startShortcutCapture());
        shortcutPanel.add(shortcutLabel);
        shortcutPanel.add(shortcutButton);
        panel.add(shortcutPanel);

        reset();
        return panel;
    }
    private void startShortcutCapture() {
        // 弹出对话框
        JDialog dialog = new JDialog((Frame) SwingUtilities.getAncestorOfClass(Frame.class, panel), "设置快捷键", true);
        dialog.setLayout(new BorderLayout());
        dialog.setSize(300, 150);
        dialog.setLocationRelativeTo(panel);

        // 文本框显示实时快捷键
        JTextField shortcutField = new JTextField(20);
        shortcutField.setEditable(false); // 禁止手动编辑，仅显示捕获结果
        dialog.add(new JLabel("按下快捷键:"), BorderLayout.NORTH);
        dialog.add(shortcutField, BorderLayout.CENTER);

        // 按钮面板
        JPanel buttonPanel = new JPanel();
        JButton okButton = new JButton("确认");
        JButton cancelButton = new JButton("取消");
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        // 按键状态
        StringBuilder shortcutText = new StringBuilder();
        Set<Integer> pressedKeys = new HashSet<>();

        // 按键监听
        KeyListener keyListener = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int keyCode = e.getKeyCode();
                int modifiers = e.getModifiersEx();
                pressedKeys.add(keyCode);

                // 重置显示
                shortcutText.setLength(0);

                // 添加修饰键
                if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) {
                    shortcutText.append("Ctrl+");
                }
                if ((modifiers & InputEvent.ALT_DOWN_MASK) != 0) {
                    shortcutText.append("Alt+");
                }
                if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) {
                    shortcutText.append("Shift+");
                }

                // 添加主按键（最后一个非修饰键）
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
                        break; // 只取一个主按键
                    }
                }

                if (keyText != null) {
                    shortcutText.append(keyText);
                } else if (shortcutText.length() > 0) {
                    shortcutText.setLength(shortcutText.length() - 1); // 移除末尾 '+'
                }

                shortcutField.setText(shortcutText.toString());
                System.out.println("实时快捷键: " + shortcutText);
            }

            @Override
            public void keyReleased(KeyEvent e) {
                pressedKeys.remove(e.getKeyCode());
                // 可选：释放时更新显示（如果需要动态变化）
            }
        };

        shortcutField.addKeyListener(keyListener);

        // 确认按钮
        okButton.addActionListener(e -> {
            if (shortcutText.length() > 0) {
                currentShortcut = shortcutText.toString();
                shortcutLabel.setText(currentShortcut);
                System.out.println("确认快捷键: " + currentShortcut);
            }
            dialog.dispose();
        });

        // 取消按钮
        cancelButton.addActionListener(e -> dialog.dispose());

        // 对话框关闭时清理
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
        List<SyncAction.Mapping> currentMappings = getMappingsFromTable();
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        List<SyncAction.Mapping> savedMappings = config.getMappings();
        try {
            int currentInterval = Integer.parseInt(refreshIntervalField.getText());
            return !currentMappings.equals(savedMappings) ||
                    currentInterval != config.getRefreshInterval() ||
                    !currentShortcut.equals(config.getShortcutKey());
        } catch (NumberFormatException e) {
            return true;
        }
    }

    @Override
    public void apply() throws ConfigurationException {
        List<SyncAction.Mapping> mappings = getMappingsFromTable();
        for (SyncAction.Mapping mapping : mappings) {
            if (mapping.sourceUrl.isEmpty() || mapping.targetPath.isEmpty()) {
                throw new ConfigurationException("源 URL 和目标路径不能为空。");
            }
        }

        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        config.setMappings(mappings);

        try {
            int interval = Integer.parseInt(refreshIntervalField.getText());
            if (interval < 0) {
                throw new ConfigurationException("刷新时间间隔不能为负数。");
            }
            config.setRefreshInterval(interval);
        } catch (NumberFormatException e) {
            throw new ConfigurationException("无效的刷新时间格式。");
        }

        if (currentShortcut == null || currentShortcut.trim().isEmpty()) {
            throw new ConfigurationException("快捷键不能为空。");
        }
        config.setShortcutKey(currentShortcut);
    }

    @Override
    public void reset() {
        tableModel.setRowCount(0);
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        List<SyncAction.Mapping> mappings = config.getMappings();
        for (SyncAction.Mapping mapping : mappings) {
            tableModel.addRow(new Object[]{mapping.sourceUrl, mapping.targetPath});
        }
        refreshIntervalField.setText(String.valueOf(config.getRefreshInterval()));
        currentShortcut = config.getShortcutKey();
        shortcutLabel.setText(currentShortcut);
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        mappingsTable = null;
        tableModel = null;
        refreshIntervalField = null;
        shortcutButton = null;
        shortcutLabel = null;
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