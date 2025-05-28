package com.example.syncfiles.action;

import com.example.syncfiles.util.TerminalFontUtil;
import com.example.syncfiles.ui.FontSelectionDialog; // 你的对话框类
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.Font;

public class FontSelectorAction extends AnAction {

    public static final Icon ACTION_ICON = IconLoader.getIcon("/icons/font.svg", FontSelectorAction.class);
    public FontSelectorAction() {
        super("Select Terminal Font...", "Choose the font for the custom terminal", ACTION_ICON);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        // project 可能为null，如果Action在无项目上下文中被调用，对话框可能需要处理这种情况

        Font currentSettingsFont = TerminalFontUtil.getConfiguredFont(); // 获取当前字体和大小

        FontSelectionDialog dialog = new FontSelectionDialog(project, currentSettingsFont.getFamily(), currentSettingsFont.getSize());
        if (dialog.showAndGet()) {
            String selectedFamily = dialog.getSelectedFontFamily();
            int selectedSize = dialog.getSelectedFontSize();
            if (selectedFamily != null) {
                TerminalFontUtil.saveFontConfiguration(selectedFamily, selectedSize);
                // 可以考虑发送一个消息或事件，通知现有终端实例刷新它们的设置（如果它们在监听）
                // 或者依赖于 TerminalFontSettingsProvider 在下次被调用时获取新值
            }
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
    @Override
    public void update(@NotNull AnActionEvent e) {
        // 通常，字体选择不需要特定项目上下文，但如果你的对话框需要project，则保持检查
        e.getPresentation().setEnabledAndVisible(true); // 或者 e.getProject() != null
    }
}