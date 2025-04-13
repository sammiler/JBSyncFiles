package com.example.syncfiles;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import javax.swing.*;
import java.awt.*;

public class SyncFilesToolWindowFactory implements com.intellij.openapi.wm.ToolWindowFactory {
    @Override
    public void createToolWindowContent(Project project, ToolWindow toolWindow) {
        JPanel panel = new JPanel(new BorderLayout());
        JButton syncButton = new JButton("Start Sync");
        syncButton.addActionListener(e -> {
            System.out.println("Start Sync button clicked in production");
            try {
                SyncFilesConfig config = SyncFilesConfig.getInstance(project);
                System.out.println("Mappings loaded: " + config.getMappings());
                new SyncAction().syncFiles(project);
            } catch (Exception ex) {
                System.err.println("Sync failed: " + ex.getMessage());
                ex.printStackTrace();
                JOptionPane.showMessageDialog(null, "Sync failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        panel.add(syncButton, BorderLayout.NORTH);
        toolWindow.getComponent().add(panel);
    }
}