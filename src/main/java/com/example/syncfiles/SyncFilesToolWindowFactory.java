package com.example.syncfiles;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.treeStructure.Tree;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class SyncFilesToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(Project project, ToolWindow toolWindow) {
        SimpleToolWindowPanel panel = new SimpleToolWindowPanel(true, true);
        JPanel content = new JPanel(new BorderLayout());

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("SyncFiles");
        DefaultMutableTreeNode syncNode = new DefaultMutableTreeNode("Start Sync");
        root.add(syncNode);
        Tree tree = new Tree(new DefaultTreeModel(root));
        tree.setRootVisible(false);
        tree.setShowsRootHandles(false);

        tree.setEnabled(true);
        tree.setFocusable(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);

        MouseAdapter mouseAdapter = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                System.out.println("Mouse clicked: count=" + e.getClickCount() + ", selected=" + tree.getLastSelectedPathComponent());
                if (e.getClickCount() == 1 && tree.getLastSelectedPathComponent() == syncNode) {
                    System.out.println("Triggering sync action for project: " + project.getName());
                    new SyncAction().syncFiles(project);
                }
            }
        };
        tree.addMouseListener(mouseAdapter);

        System.out.println("Mouse listener added: " + mouseAdapter);

        content.add(new JScrollPane(tree), BorderLayout.CENTER);
        panel.setContent(content);
        toolWindow.getComponent().getParent().add(panel);
    }
}