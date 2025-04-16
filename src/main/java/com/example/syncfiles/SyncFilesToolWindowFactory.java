package com.example.syncfiles;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Stream;

public class SyncFilesToolWindowFactory implements com.intellij.openapi.wm.ToolWindowFactory {
    private JPanel scriptButtonPanel;
    private Project project;
    private DirectoryWatcher directoryWatcher;

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
        refreshButton.addActionListener(e -> refreshScriptButtons(project, true, false));
        topPanel.add(refreshButton);

        mainPanel.add(topPanel, BorderLayout.NORTH);

        // 脚本按钮面板
        scriptButtonPanel = new JPanel();
        scriptButtonPanel.setLayout(new BoxLayout(scriptButtonPanel, BoxLayout.Y_AXIS));
        JScrollPane scrollPane = new JBScrollPane(scriptButtonPanel);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        refreshScriptButtons(project, true, true);
        toolWindow.getComponent().add(mainPanel);
        try {
            Util.InitToolWindowFactory(project, this);
            Util.refreshAndSetWatchDir(project);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void refreshScriptButtons(Project project, String scriptPath, String pythonExe, boolean internel, boolean selfInit) {
        System.out.println("refreshScriptButtons");
        scriptButtonPanel.removeAll();
        scriptButtonPanel.revalidate();
        scriptButtonPanel.repaint();
        if (scriptPath == null) {
            SyncFilesConfig config = SyncFilesConfig.getInstance(project);
            scriptPath = config.getPythonScriptPath();
        }
        if (pythonExe == null) {
            SyncFilesConfig config = SyncFilesConfig.getInstance(project);
            pythonExe = config.getPythonExecutablePath();
        }

        if (scriptPath == null || scriptPath.isEmpty() || !Files.exists(Paths.get(scriptPath))) {
            if (!internel || selfInit)
                return;
            JOptionPane.showMessageDialog(null, "Python目录错误: ", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!pythonExe.contains("python") || !Files.exists(Paths.get(pythonExe))) {
            if (!internel || selfInit)
                return;
            JOptionPane.showMessageDialog(null, "Python可执行文件错误: ", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        scriptButtonPanel.removeAll();
        if (scriptPath != null && !scriptPath.isEmpty()) {
            try (Stream<Path> paths = Files.walk(Paths.get(scriptPath))) {
                String finalPythonExe = pythonExe;
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".py"))
                        .forEach(path -> {
                            File file = path.toFile();
                            JButton button = new JButton(file.getName().replaceFirst("\\.py$", ""));
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

    public void refreshScriptButtons(Project project, boolean internel, boolean selfInit) {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        String scriptPath = config.getPythonScriptPath();
        if (scriptPath.isEmpty() && internel && !selfInit) {
            Messages.showWarningDialog("没有配置Python脚本路径。请在 '设置 > syncFiles 设置' 中检查。", "警告");
            return;
        }
        String pythonExe = config.getPythonExecutablePath();
        if (pythonExe.isEmpty() && internel && !selfInit) {
            Messages.showWarningDialog("没有配置Python可执行路径。请在 '设置 > syncFiles 设置' 中检查。", "警告");
            return;
        }
        refreshScriptButtons(project, null, null, internel, selfInit);
    }

    private void executePythonScript(String pythonExe, String scriptPath) {
        if (pythonExe == null || pythonExe.isEmpty()) {
            JOptionPane.showMessageDialog(null, "未配置Python可执行文件路径", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            Util.forceRefreshVFS(scriptPath);

            ProcessBuilder pb = new ProcessBuilder(pythonExe, scriptPath);
            // 设置环境变量
            SyncFilesConfig config = SyncFilesConfig.getInstance(project);
            Map<String, String> envVars = config.getEnvVariables();
            if (!envVars.isEmpty()) {
                Map<String, String> env = pb.environment();
                env.putAll(envVars);
            }
            // 强制 Python 使用 UTF-8 编码
            pb.environment().put("PYTHONIOENCODING", "utf-8");

            // 打印环境变量，确认 P 和 AAA
            System.out.println("ENV:" + envVars);

            // 不合并错误流，单独处理 stdout 和 stderr
            pb.redirectErrorStream(false);

            Process process = pb.start();
            System.out.println("Process ENV:" + pb.environment());

            // 读取标准输出 (stdout)，使用 UTF-8 编码
            StringBuilder output = new StringBuilder();
            try (BufferedReader stdoutReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = stdoutReader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            // 读取标准错误 (stderr)，使用 UTF-8 编码
            StringBuilder errorOutput = new StringBuilder();
            try (BufferedReader stderrReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = stderrReader.readLine()) != null) {
                    errorOutput.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            String finalOutput = output.toString();
            String finalError = errorOutput.toString();

            if (exitCode == 0) {
                JOptionPane.showMessageDialog(null,
                        "脚本执行成功!\n输出:\n" + (finalOutput.isEmpty() ? "无输出" : finalOutput),
                        "成功", JOptionPane.INFORMATION_MESSAGE);
            } else {
                String message = "脚本执行失败!\n" +
                        "标准输出:\n" + (finalOutput.isEmpty() ? "无" : finalOutput) +
                        "错误输出:\n" + (finalError.isEmpty() ? "无" : finalError);
                JOptionPane.showMessageDialog(null, message, "错误", JOptionPane.ERROR_MESSAGE);
            }
            Util.refreshAllFiles(project);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "执行脚本失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }
}