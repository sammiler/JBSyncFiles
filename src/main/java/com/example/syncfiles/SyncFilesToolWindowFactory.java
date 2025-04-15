package com.example.syncfiles;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.components.JBScrollPane;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Stream;

public class SyncFilesToolWindowFactory implements com.intellij.openapi.wm.ToolWindowFactory {
    private JPanel scriptButtonPanel;
    private Project project;
    private DirectoryWatcher directoryWatcher ;
    @Override
    public void createToolWindowContent(Project project, ToolWindow toolWindow) {
        this.project = project;
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 同步按钮
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton syncButton = new JButton("开始同步");
        syncButton.addActionListener(e -> {
            try {
                SyncFilesConfig config = SyncFilesConfig.getInstance(project);
                new SyncAction().syncFiles(project);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(null, "同步失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        });
        topPanel.add(syncButton);

        // 刷新按钮
        JButton refreshButton = new JButton("刷新脚本");
        refreshButton.addActionListener(e -> refreshScriptButtons(project));
        topPanel.add(refreshButton);

        mainPanel.add(topPanel, BorderLayout.NORTH);

        // 脚本按钮面板
        scriptButtonPanel = new JPanel();
        scriptButtonPanel.setLayout(new BoxLayout(scriptButtonPanel, BoxLayout.Y_AXIS));
        JScrollPane scrollPane = new JBScrollPane(scriptButtonPanel);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // 初始化时加载按钮
        refreshScriptButtons(project);
        if (SyncAction.directoryWatcher == null)
        {
            try {
                SyncAction.directoryWatcher = new DirectoryWatcher(project);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        }
        SyncAction.directoryWatcher.setSyncFilesToolWindowFactory(this);
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        String scriptPath = config.getPythonScriptPath();
        if (scriptPath != null)
        {
            try {
                SyncAction.directoryWatcher.watchDirectory(Paths.get(scriptPath));
                SyncAction.directoryWatcher.startWatching();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        toolWindow.getComponent().add(mainPanel);
    }



    public void refreshScriptButtons(Project project,String scriptPath,String pythonExe) {
        scriptButtonPanel.removeAll();
        if (scriptPath == null)
        {
            SyncFilesConfig config = SyncFilesConfig.getInstance(project);
            scriptPath = config.getPythonScriptPath();
        }
        if (pythonExe == null)
        {
            SyncFilesConfig config = SyncFilesConfig.getInstance(project);
            pythonExe = config.getPythonExecutablePath();
        }
        if (scriptPath != null && !scriptPath.isEmpty()) {
            try (Stream<Path> paths = Files.walk(Paths.get(scriptPath))) {
                String finalPythonExe = pythonExe;
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".py"))
                        .forEach(path -> {
                            File file = path.toFile();
                            JButton button = new JButton(file.getName());
                            button.setAlignmentX(Component.LEFT_ALIGNMENT);
                            button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
                            button.addActionListener(e -> executePythonScript(finalPythonExe, file.getAbsolutePath()));
                            scriptButtonPanel.add(button);
                            scriptButtonPanel.add(Box.createVerticalStrut(5));
                        });
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "加载脚本失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
        scriptButtonPanel.revalidate();
        scriptButtonPanel.repaint();

    }

    public void refreshScriptButtons(Project project) {
        refreshScriptButtons(project,null,null);

    }

    private void executePythonScript(String pythonExe, String scriptPath) {
        if (pythonExe == null || pythonExe.isEmpty()) {
            JOptionPane.showMessageDialog(null, "未配置Python可执行文件路径", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(pythonExe, scriptPath);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 读取输出
            StringBuilder output = new StringBuilder();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                JOptionPane.showMessageDialog(null, "脚本执行成功!\n输出:\n" + output, "成功", JOptionPane.INFORMATION_MESSAGE);

            } else {
                JOptionPane.showMessageDialog(null, "脚本执行失败!\n输出:\n" + output, "错误", JOptionPane.ERROR_MESSAGE);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "执行脚本失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }
}