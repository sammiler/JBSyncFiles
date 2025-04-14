package com.example.syncfiles;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import javax.swing.*;
import java.awt.*;

public class SyncFilesToolWindowFactory implements com.intellij.openapi.wm.ToolWindowFactory {
    @Override
    public void createToolWindowContent(Project project, ToolWindow toolWindow) {
        JPanel panel = new JPanel(new BorderLayout());
        JButton syncButton = new JButton("开始同步");
        syncButton.addActionListener(e -> {
            System.out.println("生产环境中点击了开始同步按钮");
            try {
                SyncFilesConfig config = SyncFilesConfig.getInstance(project);
                System.out.println("加载的映射: " + config.getMappings());
                new SyncAction().syncFiles(project);
            } catch (Exception ex) {
                System.err.println("同步失败: " + ex.getMessage());
                ex.printStackTrace();
                JOptionPane.showMessageDialog(null, "同步失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        });
        panel.add(syncButton, BorderLayout.NORTH);
        toolWindow.getComponent().add(panel);
    }
}