package com.example.syncfiles;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessHandlerFactory;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.InputValidator;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.plugins.terminal.TerminalView; // 核心 Terminal API
import org.jetbrains.plugins.terminal.ShellTerminalWidget;
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SideBorder;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.EnvironmentUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;
import org.jetbrains.plugins.terminal.TerminalView; // 核心 Terminal API

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SyncFilesToolWindowFactory implements com.intellij.openapi.wm.ToolWindowFactory, DumbAware {
    private static final Logger LOG = Logger.getInstance(SyncFilesToolWindowFactory.class);
    private Tree scriptTree;
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;
    private Project project; // 当前 Project

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        this.project = project;
        JPanel mainPanel = new JPanel(new BorderLayout());

        // 1. 工具栏 (ActionToolbar)
        ActionManager actionManager = ActionManager.getInstance();
        DefaultActionGroup actionGroup = new DefaultActionGroup();
        actionGroup.add(new AnAction("Refresh Scripts", "Reload scripts from disk and configuration", AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                updateScriptTree(true); // true 表示强制从磁盘扫描
            }
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() { // BGT = Background Thread
                return ActionUpdateThread.BGT;
            }
        });
        actionGroup.add(new AnAction("Add Group...", "Add a new script group", AllIcons.General.Add) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                addNewScriptGroup();
            }
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }
        });
        actionGroup.add(new Separator());
        actionGroup.add(new AnAction("Sync GitHub Files", "Download files/directories based on mappings", AllIcons.Actions.CheckOut) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                new SyncAction().syncFiles(project); // SyncAction 是您原有的类
            }
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }
        });


        ActionToolbar toolbar = actionManager.createActionToolbar("SyncFilesToolbar", actionGroup, true);
        toolbar.setTargetComponent(mainPanel);
        mainPanel.add(toolbar.getComponent(), BorderLayout.NORTH);

        // 2. 脚本树 (JBTree)
        rootNode = new DefaultMutableTreeNode("Scripts Root"); // 根节点不显示
        treeModel = new DefaultTreeModel(rootNode);
        scriptTree = new Tree(treeModel);
        scriptTree.setRootVisible(false);
        scriptTree.setShowsRootHandles(true); // 显示展开/折叠的句柄
        scriptTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        scriptTree.setCellRenderer(new ScriptTreeCellRenderer()); // 自定义渲染器

        // 双击事件
        scriptTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    TreePath path = scriptTree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) {
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                        if (node.getUserObject() instanceof ScriptEntry) {
                            handleDoubleClickScript((ScriptEntry) node.getUserObject());
                        }
                    }
                }
            }
        });

        // 右键菜单
        PopupHandler.installPopupMenu(scriptTree, createContextMenuActionGroup(), "SyncFilesTreePopup");


        JBScrollPane scrollPane = (JBScrollPane) ScrollPaneFactory.createScrollPane(scriptTree);
        scrollPane.setBorder(IdeBorderFactory.createBorder(SideBorder.TOP));
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        toolWindow.getComponent().add(mainPanel);

        // 订阅消息总线事件
        project.getMessageBus().connect(toolWindow.getDisposable()).subscribe(SyncFilesNotifier.TOPIC, new SyncFilesNotifier() {
            @Override
            public void configurationChanged() {
                // 配置变化后，从配置更新树 (不需要强制扫描磁盘，除非脚本路径也变了)
                ApplicationManager.getApplication().invokeLater(() -> updateScriptTree(false));
            }

            @Override
            public void scriptsChanged() {
                // pythonScriptPath 目录内容变化 (文件增删改)，需要扫描磁盘
                ApplicationManager.getApplication().invokeLater(() -> updateScriptTree(true));
            }
        });

        // 初始加载
        ApplicationManager.getApplication().invokeLater(() -> updateScriptTree(true));
    }

    private void handleDoubleClickScript(ScriptEntry scriptEntry) {
        if (scriptEntry.isMissing()) {
            Messages.showWarningDialog(project, "Script file '" + scriptEntry.path + "' not found. Please refresh or check configuration.", "Script Missing");
            return;
        }

        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        String pythonExecutable = config.getPythonExecutablePath();
        if (StringUtil.isEmptyOrSpaces(pythonExecutable)) {
            Messages.showErrorDialog(project, "Python executable path is not configured in SyncFiles Settings.", "Execution Error");
            return;
        }
        if (!Files.isRegularFile(Paths.get(pythonExecutable))) {
            Messages.showErrorDialog(project, "Configured Python executable path is invalid or not a file: " + pythonExecutable, "Execution Error");
            return;
        }

        Path scriptBasePath = Paths.get(config.getPythonScriptPath());
        Path fullScriptPath = scriptBasePath.resolve(scriptEntry.path).normalize();

        if (!Files.isRegularFile(fullScriptPath)) {
            Messages.showErrorDialog(project, "Script file not found: " + fullScriptPath, "Execution Error");
            scriptEntry.setMissing(true); // 标记为丢失
            treeModel.nodeChanged((TreeNode) findScriptNode(scriptEntry.id).orElse(null)); // 更新UI
            return;
        }


        if ("terminal".equalsIgnoreCase(scriptEntry.executionMode)) {
            executeScriptInTerminal(project, pythonExecutable, fullScriptPath.toString(), config.getEnvVariables());
        } else { // directApi 或其他 (默认为 directApi)
            executeScriptDirectly(project, pythonExecutable, fullScriptPath.toString(), config.getEnvVariables(), scriptEntry.getDisplayName());
        }
    }

    private ActionGroup createContextMenuActionGroup() {
        DefaultActionGroup group = new DefaultActionGroup();

        // --- 针对 ScriptEntry ---
        group.add(new AnAction("Run in Terminal") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                getSelectedScriptEntry().ifPresent(se -> {
                    se.executionMode = "terminal"; // 临时切换，不保存
                    handleDoubleClickScript(se);
                });
            }
            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(getSelectedScriptEntry().isPresent() && !getSelectedScriptEntry().get().isMissing());
            }
            @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        });
        group.add(new AnAction("Run with Direct API") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                getSelectedScriptEntry().ifPresent(se -> {
                    se.executionMode = "directApi"; // 临时切换
                    handleDoubleClickScript(se);
                });
            }
            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(getSelectedScriptEntry().isPresent() && !getSelectedScriptEntry().get().isMissing());
            }
            @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        });
        group.add(new Separator());
        group.add(new AnAction("Open Script File") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                getSelectedScriptEntry().ifPresent(se -> {
                    SyncFilesConfig config = SyncFilesConfig.getInstance(project);
                    Path scriptBasePath = Paths.get(config.getPythonScriptPath());
                    Path fullScriptPath = scriptBasePath.resolve(se.path);
                    VirtualFile vf = LocalFileSystem.getInstance().findFileByNioFile(fullScriptPath);
                    if (vf != null) {
                        FileEditorManager.getInstance(project).openFile(vf, true);
                    } else {
                        Messages.showErrorDialog(project, "Could not find script file: " + fullScriptPath, "Error");
                    }
                });
            }
            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(getSelectedScriptEntry().isPresent() && !getSelectedScriptEntry().get().isMissing());
            }
            @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        });
        group.add(new AnAction("Set Alias...") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                getSelectedScriptEntry().ifPresent(se -> {
                    String newAlias = Messages.showInputDialog(project, "Enter new alias for script '" + se.getDisplayName() + "':",
                            "Set Script Alias", null, se.alias, null);
                    if (newAlias != null) { // 用户可能取消
                        updateScriptEntryConfig(se.id, script -> script.alias = newAlias.trim());
                    }
                });
            }
            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(getSelectedScriptEntry().isPresent());
            }
            @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        });
        group.add(new AnAction("Set Execution Mode...") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                getSelectedScriptEntry().ifPresent(se -> {
                    ExecutionModeDialog dialog = new ExecutionModeDialog(project, se.executionMode);
                    if (dialog.showAndGet()) {
                        String newMode = dialog.getSelectedMode();
                        updateScriptEntryConfig(se.id, script -> script.executionMode = newMode);
                    }
                });
            }
            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(getSelectedScriptEntry().isPresent());
            }
            @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        });
        group.add(new AnAction("Set Description...") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                getSelectedScriptEntry().ifPresent(se -> {
                    String newDesc = Messages.showMultilineInputDialog(project, "Enter description for script '" + se.getDisplayName() + "':",
                            "Set Script Description", se.description, null, null);
                    if (newDesc != null) {
                        updateScriptEntryConfig(se.id, script -> script.description = newDesc.trim());
                    }
                });
            }
            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(getSelectedScriptEntry().isPresent());
            }
            @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        });

        // --- 移动脚本到组 ---
        DefaultActionGroup moveToGroup = new DefaultActionGroup("Move to Group", true);
        // 这个子菜单的构建需要在 update 中动态进行，或者有一个固定的“选择组”对话框
        // 为简单起见，这里可以弹出一个对话框让用户选择目标组
        AnAction moveToGroupAction = new AnAction("Move to Another Group...") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                getSelectedScriptEntry().ifPresent(scriptEntry -> {
                    Optional<ScriptGroup> currentGroupOpt = findGroupContainingScript(scriptEntry.id);
                    if (currentGroupOpt.isEmpty()) return; // 不应该发生

                    SyncFilesConfig config = SyncFilesConfig.getInstance(project);
                    List<ScriptGroup> allGroups = config.getScriptGroups();
                    List<ScriptGroup> targetGroups = allGroups.stream()
                            .filter(g -> !g.id.equals(currentGroupOpt.get().id)) // 排除当前组
                            .collect(Collectors.toList());

                    if (targetGroups.isEmpty()) {
                        Messages.showInfoMessage(project, "No other groups available to move the script to.", "Move Script");
                        return;
                    }

                    MoveScriptDialog dialog = new MoveScriptDialog(project, targetGroups, scriptEntry.getDisplayName());
                    if (dialog.showAndGet()) {
                        ScriptGroup targetGroup = dialog.getSelectedGroup();
                        if (targetGroup != null) {
                            moveScriptToGroup(scriptEntry, currentGroupOpt.get(), targetGroup);
                        }
                    }
                });
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(getSelectedScriptEntry().isPresent());
            }
            @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        };
        group.add(moveToGroupAction);


        group.add(new AnAction("Remove from Group") { // 从当前组移除
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                getSelectedScriptEntry().ifPresent(scriptEntry -> {
                    Optional<ScriptGroup> currentGroupOpt = findGroupContainingScript(scriptEntry.id);
                    currentGroupOpt.ifPresent(currentGroup -> {
                        if (ScriptGroup.DEFAULT_GROUP_ID.equals(currentGroup.id)) {
                            int choice = Messages.showYesNoDialog(project,
                                    "Removing from 'Default' group will untrack this script. It might be re-added on next refresh if still present in script directory.\n\nAre you sure you want to remove '" + scriptEntry.getDisplayName() + "'?",
                                    "Confirm Remove", Messages.getWarningIcon());
                            if (choice != Messages.YES) return;
                        }
                        removeScriptFromGroup(scriptEntry, currentGroup);
                    });
                });
            }
            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(getSelectedScriptEntry().isPresent());
            }
            @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        });
        group.add(new Separator());

        // --- 针对 ScriptGroup ---
        // ... 在 createContextMenuActionGroup() 方法内 ...
        group.add(new AnAction("Add Script to this Group...") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                getSelectedScriptGroup().ifPresent(selectedGroup -> {
                    // 直接调用实例方法
                    addScriptToGroupDialog(selectedGroup);
                });
            }
            // update 和 getActionUpdateThread 方法保持不变
            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(getSelectedScriptGroup().isPresent());
            }
            @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        });
// ...
        group.add(new AnAction("Rename Group...") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                getSelectedScriptGroup().ifPresent(sg -> {
                    if (ScriptGroup.DEFAULT_GROUP_ID.equals(sg.id)) return; // 不能重命名默认组
                    String newName = Messages.showInputDialog(project, "Enter new name for group '" + sg.name + "':",
                            "Rename Group", null, sg.name, null);
                    if (newName != null && !newName.trim().isEmpty() && !newName.trim().equals(sg.name)) {
                        updateScriptGroupConfig(sg.id, groupToUpdate -> groupToUpdate.name = newName.trim());
                    }
                });
            }
            @Override
            public void update(@NotNull AnActionEvent e) {
                Optional<ScriptGroup> sg = getSelectedScriptGroup();
                e.getPresentation().setEnabled(sg.isPresent() && !ScriptGroup.DEFAULT_GROUP_ID.equals(sg.get().id));
            }
            @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        });
        group.add(new AnAction("Delete Group") { // 删除组（非Default）
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                getSelectedScriptGroup().ifPresent(sg -> {
                    if (ScriptGroup.DEFAULT_GROUP_ID.equals(sg.id)) return;
                    int choice = Messages.showYesNoDialog(project,
                            "Are you sure you want to delete group '" + sg.name + "'?\nScripts within will be moved to the 'Default' group.",
                            "Confirm Delete Group", Messages.getWarningIcon());
                    if (choice == Messages.YES) {
                        deleteScriptGroup(sg);
                    }
                });
            }
            @Override
            public void update(@NotNull AnActionEvent e) {
                Optional<ScriptGroup> sg = getSelectedScriptGroup();
                e.getPresentation().setEnabled(sg.isPresent() && !ScriptGroup.DEFAULT_GROUP_ID.equals(sg.get().id));
            }
            @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        });


        return group;
    }

    private void updateScriptEntryConfig(String scriptId, java.util.function.Consumer<ScriptEntry> updater) {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        List<ScriptGroup> groups = config.getScriptGroups(); // 获取的是副本
        boolean changed = false;
        for (ScriptGroup group : groups) {
            for (ScriptEntry entry : group.scripts) {
                if (entry.id.equals(scriptId)) {
                    updater.accept(entry);
                    changed = true;
                    break;
                }
            }
            if (changed) break;
        }
        if (changed) {
            config.setScriptGroups(groups); // 保存修改后的副本
            project.getMessageBus().syncPublisher(SyncFilesNotifier.TOPIC).configurationChanged();
        }
    }
    private void updateScriptGroupConfig(String groupId, java.util.function.Consumer<ScriptGroup> updater) {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        List<ScriptGroup> groups = config.getScriptGroups(); // 获取副本
        boolean changed = false;
        for (ScriptGroup group : groups) {
            if (group.id.equals(groupId)) {
                updater.accept(group);
                changed = true;
                break;
            }
        }
        if (changed) {
            config.setScriptGroups(groups); // 保存修改后的副本
            project.getMessageBus().syncPublisher(SyncFilesNotifier.TOPIC).configurationChanged();
        }
    }


    private Optional<ScriptEntry> getSelectedScriptEntry() {
        TreePath path = scriptTree.getSelectionPath();
        if (path != null) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (node.getUserObject() instanceof ScriptEntry) {
                return Optional.of((ScriptEntry) node.getUserObject());
            }
        }
        return Optional.empty();
    }

    private Optional<ScriptGroup> getSelectedScriptGroup() {
        TreePath path = scriptTree.getSelectionPath();
        if (path != null) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (node.getUserObject() instanceof ScriptGroup) {
                return Optional.of((ScriptGroup) node.getUserObject());
            }
        }
        return Optional.empty();
    }

    private Optional<DefaultMutableTreeNode> findScriptNode(String scriptId) {
        Enumeration<?> depthFirst = rootNode.depthFirstEnumeration();
        while (depthFirst.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) depthFirst.nextElement();
            if (node.getUserObject() instanceof ScriptEntry) {
                if (((ScriptEntry) node.getUserObject()).id.equals(scriptId)) {
                    return Optional.of(node);
                }
            }
        }
        return Optional.empty();
    }
    private Optional<ScriptGroup> findGroupContainingScript(String scriptId) {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        for (ScriptGroup group : config.getScriptGroups()) {
            if (group.scripts.stream().anyMatch(s -> s.id.equals(scriptId))) {
                return Optional.of(group);
            }
        }
        return Optional.empty();
    }


    private void addNewScriptGroup() {
        String groupName = Messages.showInputDialog(project, "Enter new group name:", "Add Script Group", null);
        if (groupName != null && !groupName.trim().isEmpty()) {
            SyncFilesConfig config = SyncFilesConfig.getInstance(project);
            List<ScriptGroup> groups = config.getScriptGroups(); // 获取副本
            // 检查名称是否已存在
            if (groups.stream().anyMatch(g -> g.name.equalsIgnoreCase(groupName.trim()))) {
                Messages.showErrorDialog(project, "A group with this name already exists.", "Error Adding Group");
                return;
            }
            ScriptGroup newGroup = new ScriptGroup(UUID.randomUUID().toString(), groupName.trim());
            groups.add(newGroup);
            config.setScriptGroups(groups); // 保存
            project.getMessageBus().syncPublisher(SyncFilesNotifier.TOPIC).configurationChanged();
        }
    }
    private void addScriptToGroupDialog(ScriptGroup targetGroup) {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        String pythonScriptPathStr = config.getPythonScriptPath();
        if (StringUtil.isEmptyOrSpaces(pythonScriptPathStr)) {
            Messages.showErrorDialog(project, "Python Scripts Directory is not configured in settings.", "Cannot Add Script");
            return;
        }
        VirtualFile scriptDir = LocalFileSystem.getInstance().findFileByPath(pythonScriptPathStr);
        if (scriptDir == null || !scriptDir.isDirectory()) {
            Messages.showErrorDialog(project, "Configured Python Scripts Directory is invalid or not found:\n" + pythonScriptPathStr, "Cannot Add Script");
            return;
        }

        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("py")
                .withTitle("Select Python Script to Add to Group '" + targetGroup.name + "'")
                .withRoots(scriptDir) // 限制在脚本目录内选择
                .withHideIgnored(true);

        VirtualFile selectedFile = FileChooser.chooseFile(descriptor, project, null);
        if (selectedFile != null) {
            Path scriptBasePath = Paths.get(pythonScriptPathStr);
            Path selectedFilePath = Paths.get(selectedFile.getPath());
            if (!selectedFilePath.startsWith(scriptBasePath)) {
                Messages.showErrorDialog(project, "Selected script is not within the configured Python Scripts Directory.", "Error Adding Script");
                return;
            }
            String relativePath = scriptBasePath.relativize(selectedFilePath).toString().replace('\\', '/');

            // 检查脚本是否已在任何组中
            boolean alreadyExists = config.getScriptGroups().stream()
                    .flatMap(g -> g.scripts.stream())
                    .anyMatch(s -> s.path.equalsIgnoreCase(relativePath));
            if (alreadyExists) {
                Messages.showWarningDialog(project, "This script ('" + relativePath + "') is already in a group.", "Script Exists");
                return;
            }

            ScriptEntry newScriptEntry = new ScriptEntry(relativePath);
            newScriptEntry.description = "Added on " + new java.text.SimpleDateFormat("yyyy/MM/dd").format(new Date());

            List<ScriptGroup> groups = config.getScriptGroups(); // 副本
            groups.stream().filter(g -> g.id.equals(targetGroup.id)).findFirst().ifPresent(g -> {
                g.scripts.add(newScriptEntry);
                g.scripts.sort(Comparator.comparing(s -> s.getDisplayName().toLowerCase())); // 保持排序
            });
            config.setScriptGroups(groups);
            project.getMessageBus().syncPublisher(SyncFilesNotifier.TOPIC).configurationChanged();
        }
    }

    private void moveScriptToGroup(ScriptEntry scriptEntry, ScriptGroup currentGroup, ScriptGroup targetGroup) {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        List<ScriptGroup> groups = config.getScriptGroups(); // 副本

        // 从当前组移除
        groups.stream().filter(g -> g.id.equals(currentGroup.id)).findFirst()
                .ifPresent(cg -> cg.scripts.removeIf(s -> s.id.equals(scriptEntry.id)));

        // 添加到目标组
        groups.stream().filter(g -> g.id.equals(targetGroup.id)).findFirst()
                .ifPresent(tg -> {
                    tg.scripts.add(scriptEntry); // scriptEntry 对象本身被移动
                    tg.scripts.sort(Comparator.comparing(s -> s.getDisplayName().toLowerCase()));
                });

        config.setScriptGroups(groups);
        project.getMessageBus().syncPublisher(SyncFilesNotifier.TOPIC).configurationChanged();
    }

    private void removeScriptFromGroup(ScriptEntry scriptEntry, ScriptGroup currentGroup) {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        List<ScriptGroup> groups = config.getScriptGroups(); // 副本

        groups.stream().filter(g -> g.id.equals(currentGroup.id)).findFirst()
                .ifPresent(cg -> cg.scripts.removeIf(s -> s.id.equals(scriptEntry.id)));

        // 如果不是从Default组删除，且Default组存在，则移入Default组（除非用户选择彻底删除）
        // 这里简化为：如果从非Default组删除，脚本就“消失”了，下次扫描可能会重现。
        // 如果是从Default组删除，脚本也“消失”。

        config.setScriptGroups(groups);
        project.getMessageBus().syncPublisher(SyncFilesNotifier.TOPIC).configurationChanged();
    }
    private void deleteScriptGroup(ScriptGroup groupToDelete) {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        List<ScriptGroup> groups = config.getScriptGroups(); // 副本

        ScriptGroup defaultGroup = groups.stream()
                .filter(g -> ScriptGroup.DEFAULT_GROUP_ID.equals(g.id))
                .findFirst().orElse(null);
        if (defaultGroup == null) { // 不应该发生，防御性编程
            defaultGroup = new ScriptGroup(ScriptGroup.DEFAULT_GROUP_ID, ScriptGroup.DEFAULT_GROUP_NAME);
            groups.add(0, defaultGroup);
        }
        final ScriptGroup finalDefaultGroup = defaultGroup;

        // 将被删除组的脚本移到 Default 组
        groups.stream().filter(g -> g.id.equals(groupToDelete.id)).findFirst().ifPresent(deletedGroup -> {
            for (ScriptEntry script : deletedGroup.scripts) {
                // 避免重复添加
                if (finalDefaultGroup.scripts.stream().noneMatch(s -> s.id.equals(script.id))) {
                    finalDefaultGroup.scripts.add(script);
                }
            }
            finalDefaultGroup.scripts.sort(Comparator.comparing(s -> s.getDisplayName().toLowerCase()));
        });

        // 删除该组
        groups.removeIf(g -> g.id.equals(groupToDelete.id));

        config.setScriptGroups(groups);
        project.getMessageBus().syncPublisher(SyncFilesNotifier.TOPIC).configurationChanged();
    }



    private void updateScriptTree(boolean forceScanDisk) {
        LOG.info("Updating script tree. Force scan disk: " + forceScanDisk);
        rootNode.removeAllChildren();
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        String pythonScriptPathStr = config.getPythonScriptPath();

        List<ScriptGroup> configuredGroups = new ArrayList<>(config.getScriptGroups()); // 使用副本进行操作

        // 确保 Default 组存在
        ScriptGroup defaultGroup = configuredGroups.stream()
                .filter(g -> ScriptGroup.DEFAULT_GROUP_ID.equals(g.id))
                .findFirst().orElse(null);

        if (defaultGroup == null) {
            defaultGroup = new ScriptGroup(ScriptGroup.DEFAULT_GROUP_ID, ScriptGroup.DEFAULT_GROUP_NAME);
            configuredGroups.add(0, defaultGroup); // 添加到列表开头
        }


        // 如果设置了脚本目录且需要扫描磁盘，则进行扫描
        if (forceScanDisk && pythonScriptPathStr != null && !pythonScriptPathStr.isEmpty()) {
            Path scriptBasePath = Paths.get(pythonScriptPathStr);
            if (Files.isDirectory(scriptBasePath)) {
                // 1. 标记所有现有脚本为潜在丢失
                for (ScriptGroup group : configuredGroups) {
                    for (ScriptEntry entry : group.scripts) {
                        entry.setMissing(true);
                    }
                }

                // 2. 扫描磁盘上的 .py 文件
                Set<String> diskScriptRelativePaths = new HashSet<>();
                try (Stream<Path> stream = Files.walk(scriptBasePath, 1)) { // 只扫描顶层
                    diskScriptRelativePaths = stream
                            .filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".py"))
                            .map(p -> scriptBasePath.relativize(p).toString().replace('\\', '/'))
                            .collect(Collectors.toSet());
                } catch (IOException e) {
                    LOG.warn("Error scanning script directory: " + pythonScriptPathStr, e);
                    // 可以在UI上显示一个错误节点
                }

                // 3. 更新现有脚本状态，添加新发现的脚本到 Default 组
                Set<String> configuredScriptPaths = new HashSet<>();
                for (ScriptGroup group : configuredGroups) {
                    Iterator<ScriptEntry> iterator = group.scripts.iterator();
                    while (iterator.hasNext()) {
                        ScriptEntry entry = iterator.next();
                        Path fullPath = scriptBasePath.resolve(entry.path);
                        if (Files.isRegularFile(fullPath)) {
                            entry.setMissing(false); // 文件存在
                            configuredScriptPaths.add(entry.path.toLowerCase());
                        } else {
                            if (group.id.equals(ScriptGroup.DEFAULT_GROUP_ID)) {
                                // 如果在Default组且文件丢失，则直接移除
                                iterator.remove();
                            } else {
                                // 在其他组，标记为丢失，但不移除，让用户处理
                                entry.setMissing(true);
                            }
                        }
                    }
                }

                // 4. 将磁盘上存在但配置中没有的脚本添加到 Default 组
                final ScriptGroup finalDefaultGroup = defaultGroup; // lambda 需要
                for (String diskPath : diskScriptRelativePaths) {
                    if (configuredScriptPaths.stream().noneMatch(p -> p.equalsIgnoreCase(diskPath))) {
                        boolean alreadyInDefault = finalDefaultGroup.scripts.stream().anyMatch(s -> s.path.equalsIgnoreCase(diskPath));
                        if (!alreadyInDefault) {
                            ScriptEntry newEntry = new ScriptEntry(diskPath);
                            newEntry.description = "Auto-added " + new java.text.SimpleDateFormat("yyyy/MM/dd").format(new Date());
                            finalDefaultGroup.scripts.add(newEntry);
                            LOG.info("Auto-added new script to Default group: " + diskPath);
                        }
                    }
                }
                // 如果因为扫描而修改了配置，则保存回去
                if (forceScanDisk) { // 只有当 forceScanDisk 时，我们才修改了 configGroups 的内容
                    config.setScriptGroups(configuredGroups);
                    // 不需要再发 configurationChanged，因为 updateScriptTree 通常是其结果
                }
            } else {
                // 脚本目录无效，标记所有脚本为丢失
                configuredGroups.forEach(g -> g.scripts.forEach(s -> s.setMissing(true)));
                LOG.warn("Python script path is not a valid directory: " + pythonScriptPathStr);
            }
        } else if (pythonScriptPathStr == null || pythonScriptPathStr.isEmpty()) {
            // 没有配置脚本目录，标记所有脚本为丢失
            configuredGroups.forEach(g -> g.scripts.forEach(s -> s.setMissing(true)));
            LOG.info("Python script path is not configured. All scripts will be marked as missing if any are defined.");
        }


        // 排序组（Default 组优先，其他按名称）
        configuredGroups.sort((g1, g2) -> {
            if (ScriptGroup.DEFAULT_GROUP_ID.equals(g1.id)) return -1;
            if (ScriptGroup.DEFAULT_GROUP_ID.equals(g2.id)) return 1;
            return g1.name.compareToIgnoreCase(g2.name);
        });


        // 填充树模型
        for (ScriptGroup group : configuredGroups) {
            DefaultMutableTreeNode groupNode = new DefaultMutableTreeNode(group);
            // 脚本按名称排序
            group.scripts.sort(Comparator.comparing(s -> s.getDisplayName().toLowerCase()));
            for (ScriptEntry script : group.scripts) {
                DefaultMutableTreeNode scriptNode = new DefaultMutableTreeNode(script);
                groupNode.add(scriptNode);
            }
            rootNode.add(groupNode);
        }

        treeModel.reload();
        TreeUtil.expandAll(scriptTree); // 默认展开所有节点
        LOG.info("Script tree updated.");
    }


    // --- Script Execution Methods ---

    // 在 SyncFilesToolWindowFactory.java 的 executeScriptInTerminal 方法中

    private void executeScriptInTerminal(Project project, String pythonExecutable, String scriptPath, Map<String, String> envVars) {
        LOG.info("Executing in terminal: " + pythonExecutable + " " + scriptPath);
        Util.forceRefreshVFS(scriptPath); // 确保文件已同步

        // 获取 Terminal ToolWindow
        ToolWindow terminalToolWindow = ToolWindowManager.getInstance(project).getToolWindow(org.jetbrains.plugins.terminal.TerminalToolWindowFactory.TOOL_WINDOW_ID);
        if (terminalToolWindow == null) {
            Messages.showErrorDialog(project, "Terminal tool window is not available.", "Terminal Error");
            LOG.warn("Terminal tool window not found.");
            return;
        }

        // 准备命令 (这部分逻辑保持，但可能需要调整环境变量设置方式)
        String commandToExecute;
        // ... (构建 commandToExecute 的逻辑，如之前讨论的 PowerShell 和 Bash/Zsh 方式) ...
        // [之前的 commandToExecute 构建逻辑]
        String normPythonExec = pythonExecutable.replace('\\', '/');
        String normScriptPath = scriptPath.replace('\\', '/');
        boolean isLikelyPowerShell = SystemInfo.isWindows;

        if (isLikelyPowerShell) {
            StringBuilder psCommand = new StringBuilder();
            for (Map.Entry<String, String> entry : envVars.entrySet()) {
                String escapedValue = entry.getValue().replace("'", "''");
                psCommand.append("$env:").append(entry.getKey()).append(" = '").append(escapedValue).append("'; ");
            }
            psCommand.append("& '").append(normPythonExec).append("' '").append(normScriptPath).append("'");
            commandToExecute = psCommand.toString();
        } else {
            StringBuilder bashCommand = new StringBuilder();
            for (Map.Entry<String, String> entry : envVars.entrySet()) {
                String escapedValue = entry.getValue().replace("'", "'\\''");
                bashCommand.append("export ").append(entry.getKey()).append("='").append(escapedValue).append("'; ");
            }
            bashCommand.append("\"").append(normPythonExec).append("\" \"").append(normScriptPath).append("\"");
            commandToExecute = bashCommand.toString();
        }
        // --- 命令构建结束 ---

        // 显示 Terminal ToolWindow 并尝试执行命令
        final String finalCommandToExecute = commandToExecute; // for lambda
        ApplicationManager.getApplication().invokeLater(() -> {
            terminalToolWindow.show(() -> { // 回调会在 ToolWindow 显示后执行
                // 尝试从现有的 Content 中获取 ShellTerminalWidget，或者创建一个新的
                // 这是最可能需要根据新 API 调整的部分
                ShellTerminalWidget widgetToUse = findOrCreateTerminalWidget(project, terminalToolWindow, "SyncFiles Script");

                if (widgetToUse != null) {
                    try {
                        // 如何为 widgetToUse 设置初始环境变量是个挑战。
                        // 如果 widget 是新创建的，PtyProcessBuilder (如果通过它创建) 可能允许设置环境。
                        // 如果是复用已有的，命令行中嵌入环境变量设置是常用方法。
                        LOG.info("Sending command to terminal widget: " + finalCommandToExecute);
                        widgetToUse.executeCommand(finalCommandToExecute);
                    } catch (Exception e) {
                        LOG.error("Error sending command to terminal widget: " + finalCommandToExecute, e);
                        Messages.showErrorDialog(project, "Error sending command to terminal: " + e.getMessage() + "\nCommand: " + finalCommandToExecute, "Terminal Error");
                    }
                } else {
                    LOG.error("Failed to find or create a suitable terminal widget for command: " + finalCommandToExecute);
                    Messages.showErrorDialog(project, "Failed to open or reuse a terminal session for script execution.\nCommand: " + finalCommandToExecute, "Terminal Error");
                }
            });
        });
    }

    // 辅助方法，尝试查找或创建 Terminal Widget (需要根据最新的 Terminal API 进行调整)
    @Nullable
    private ShellTerminalWidget findOrCreateTerminalWidget(@NotNull Project project, @NotNull ToolWindow terminalToolWindow, @NotNull String tabName) {
        // 这是一个高度依赖具体 Terminal API 版本的实现
        // 以下是一种可能的思路，但您需要验证
        com.intellij.ui.content.ContentManager contentManager = terminalToolWindow.getContentManager();
        com.intellij.ui.content.Content selectedContent = contentManager.getSelectedContent();

        // 尝试复用当前选中的 Terminal Tab (如果它是 ShellTerminalWidget)
        if (selectedContent != null && selectedContent.getComponent() instanceof ShellTerminalWidget) {
            ShellTerminalWidget selectedWidget = (ShellTerminalWidget) selectedContent.getComponent();
            if (selectedWidget.isSessionRunning()) { // 或者其他判断是否可用的条件
                LOG.info("Reusing selected terminal widget.");
                return selectedWidget;
            }
        }

        // 尝试查找一个名为 tabName 的 Tab (如果之前创建过)
        for (com.intellij.ui.content.Content content : contentManager.getContents()) {
            if (tabName.equals(content.getDisplayName()) && content.getComponent() instanceof ShellTerminalWidget) {
                ShellTerminalWidget existingWidget = (ShellTerminalWidget) content.getComponent();
                contentManager.setSelectedContent(content); // 选中它
                LOG.info("Reusing existing terminal widget with tab name: " + tabName);
                return existingWidget;
            }
        }

        // 如果没有合适的，尝试创建一个新的 Terminal 会话/Widget
        // 这部分 API 是最容易发生变化的。
        // 旧的 TerminalView.createLocalShellWidget 可能有新的服务或工厂方法替代。
        // 例如，可能需要使用 TerminalRunner 或类似的类来启动一个新的PtyProcess，然后包装成 Widget。
        LOG.info("Attempting to create a new terminal widget with tab name: " + tabName);
        try {
            // 【关键：这里需要找到创建 ShellTerminalWidget 的新方法】
            // 这只是一个占位符，实际的 API 可能完全不同。
            // 您可能需要查找 TerminalProjectOptionsProvider, PtyProcessBuilder,
            // أو TerminalArrangementManager, TerminalStarter 等相关的类。

            // 这是一个非常粗略的猜测，基于以前的API模式：
            // TerminalSettings terminalSettings = TerminalSettings.getInstance(); // 获取设置
            // AbstractTerminalRunner<?> runner = new LocalTerminalDirectRunner(project); // 某种 Runner
            // PtyProcess ptyProcess = runner.createProcess(project.getBasePath(), null); // 创建进程
            // ShellTerminalWidget newWidget = new ShellTerminalWidget(project, terminalSettings, terminalToolWindow.getDisposable());
            // newWidget.setProcess(ptyProcess, true); // 关联进程

            // 另一种可能性是有一个服务：
            // SomeTerminalCreatorService service = project.getService(SomeTerminalCreatorService.class);
            // ShellTerminalWidget newWidget = service.createTerminalWidget(project.getBasePath(), tabName);

            // **由于无法确定 2025.1 的确切 API，这里暂时返回 null，表示创建失败**
            // **您需要替换以下行为实际的创建逻辑**
            LOG.warn("Placeholder: Actual creation of new ShellTerminalWidget is NOT IMPLEMENTED for the new API. Please update this section.");

            // --- BEGIN 临时创建（如果 TerminalView 的 create 仍然可用，仅用于测试） ---
            // 这是一个临时的回退尝试，如果 TerminalView 类仍然存在，但只有 getInstance() 过时
            try {
                Object terminalViewInstance = Class.forName("org.jetbrains.plugins.terminal.TerminalView")
                        .getMethod("getInstance", Project.class)
                        .invoke(null, project);
                if (terminalViewInstance != null) {
                    java.lang.reflect.Method createWidgetMethod = terminalViewInstance.getClass()
                            .getMethod("createLocalShellWidget", String.class, String.class, boolean.class);
                    // boolean attachToProcess = true; // 或者根据API调整
                    // ShellTerminalWidget tempWidget = (ShellTerminalWidget) createWidgetMethod.invoke(terminalViewInstance, project.getBasePath(), tabName, true);

                    // 更可能是这样：
                    java.lang.reflect.Method createWidgetMethodSimple = terminalViewInstance.getClass()
                            .getMethod("createLocalShellWidget", String.class, String.class);
                    ShellTerminalWidget tempWidget = (ShellTerminalWidget) createWidgetMethodSimple.invoke(terminalViewInstance, project.getBasePath(), tabName);


                    if (tempWidget != null) {
                        LOG.info("TEMPORARY: Created widget using reflection on (possibly deprecated) TerminalView methods.");
                        // 注意：通过反射创建的 Widget 可能不会自动添加到 ToolWindow 的 ContentManager。
                        // 但如果 createLocalShellWidget 内部处理了，那就可以了。
                        // 如果它不自动添加，那么这里还需要代码来创建 Content 并添加到 ContentManager。
                        return tempWidget;
                    }
                }
            } catch (Exception reflectionEx) {
                LOG.warn("Reflection attempt to use old TerminalView.createLocalShellWidget failed.", reflectionEx);
            }
            // --- END 临时创建 ---


            return null; // 表示创建失败，等待正确的API实现
        } catch (Exception e) {
            LOG.error("Error trying to create new ShellTerminalWidget.", e);
            return null;
        }
    }


    // 在 SyncFilesToolWindowFactory.java 的 executeScriptDirectly 方法中
    private void executeScriptDirectly(Project project, String pythonExecutable, String scriptPath, Map<String, String> envVars, String displayName) {
        LOG.info("Executing directly: " + pythonExecutable + " " + scriptPath);
        Util.forceRefreshVFS(scriptPath); // 确保文件已同步

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Running: " + displayName, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Executing " + Paths.get(scriptPath).getFileName().toString());
                StringBuilder output = new StringBuilder();
                StringBuilder errorOutput = new StringBuilder();
                int exitCode = -1;

                try {
                    ProcessBuilder pb = new ProcessBuilder(pythonExecutable, scriptPath);
                    Map<String, String> effectiveEnv = new HashMap<>(EnvironmentUtil.getEnvironmentMap());
                    effectiveEnv.putAll(envVars);
                    effectiveEnv.put("PYTHONIOENCODING", "UTF-8");
                    if (project.getBasePath() != null) {
                        effectiveEnv.put("PROJECT_DIR", project.getBasePath().replace('\\','/'));
                    }
                    pb.environment().clear();
                    pb.environment().putAll(effectiveEnv);
                    pb.directory(project.getBasePath() != null ? new File(project.getBasePath()) : Paths.get(scriptPath).getParent().toFile());

                    Process process = pb.start();

                    try (BufferedReader outReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                         BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = outReader.readLine()) != null) output.append(line).append("\n");
                        while ((line = errReader.readLine()) != null) errorOutput.append(line).append("\n");
                    }
                    exitCode = process.waitFor();

                } catch (IOException | InterruptedException e) {
                    LOG.error("Failed to execute script (direct API): " + scriptPath, e);
                    errorOutput.append("Execution failed: ").append(e.getMessage());
                    exitCode = -1; // Indicate failure
                }

                final String finalOutput = output.toString().trim();
                final String finalError = errorOutput.toString().trim();
                final int finalExitCode = exitCode;

                ApplicationManager.getApplication().invokeLater(() -> {
                    String scriptFileName = Paths.get(scriptPath).getFileName().toString();
                    if (finalExitCode == 0) {
                        String message = "Script '" + scriptFileName + "' executed successfully.\n\nOutput:\n" +
                                (finalOutput.isEmpty() ? "<No Output>" : finalOutput);
                        if (!finalError.isEmpty()) {
                            message += "\n\nStandard Error (stderr):\n" + finalError;
                        }
                        Messages.showInfoMessage(project, message, "Script Success: " + scriptFileName);
                    } else {
                        String message = "Script '" + scriptFileName + "' execution failed (Exit Code: " + finalExitCode + ").\n\n";
                        if (!finalOutput.isEmpty()) message += "Output:\n" + finalOutput + "\n\n";
                        if (!finalError.isEmpty()) message += "Error:\n" + finalError;
                        else if (finalExitCode != 0) message += "Error: <No specific error message, check logs>";
                        Messages.showErrorDialog(project, message, "Script Failure: " + scriptFileName);
                    }
                    Util.refreshAllFiles(project); // Refresh VFS
                });
            }
        });
    }

    // --- Helper Dialogs ---
    private static class ExecutionModeDialog extends DialogWrapper {
        private ComboBox<String> modeComboBox;
        private final String currentMode;

        protected ExecutionModeDialog(@Nullable Project project, String currentMode) {
            super(project);
            this.currentMode = currentMode;
            setTitle("Set Script Execution Mode");
            init();
        }

        @Nullable
        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(5)));
            panel.add(new JBLabel("Select execution mode:"), BorderLayout.NORTH);
            modeComboBox = new ComboBox<>(new String[]{"terminal", "directApi"});
            modeComboBox.setSelectedItem(currentMode);
            panel.add(modeComboBox, BorderLayout.CENTER);
            return panel;
        }

        public String getSelectedMode() {
            return (String) modeComboBox.getSelectedItem();
        }
    }
    private static class MoveScriptDialog extends DialogWrapper {
        private ComboBox<ScriptGroup> groupComboBox;
        private final List<ScriptGroup> targetGroups;
        private final String scriptName;

        protected MoveScriptDialog(@Nullable Project project, List<ScriptGroup> targetGroups, String scriptName) {
            super(project);
            this.targetGroups = targetGroups;
            this.scriptName = scriptName;
            setTitle("Move Script '" + scriptName + "'");
            init();
            // 设置 ComboBox 的渲染器，使其显示组名而不是对象引用
            groupComboBox.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    if (value instanceof ScriptGroup) {
                        setText(((ScriptGroup) value).name);
                    }
                    return this;
                }
            });
        }

        @Nullable
        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(5)));
            panel.add(new JBLabel("Move script '" + scriptName + "' to group:"), BorderLayout.NORTH);

            // 正确的 ComboBox 初始化方式
            if (targetGroups == null || targetGroups.isEmpty()) {
                // 处理 targetGroups 为空的情况，可以显示一个空 ComboBox 或者提示信息
                groupComboBox = new ComboBox<>(); // 创建一个空的 ComboBox
                groupComboBox.setEnabled(false); // 禁用它，因为没有选项
                // 或者 panel.add(new JBLabel("No target groups available."), BorderLayout.CENTER);
            } else {
                // 使用 DefaultComboBoxModel
                DefaultComboBoxModel<ScriptGroup> model = new DefaultComboBoxModel<>(new Vector<>(targetGroups)); // Vector 可以被 DefaultComboBoxModel 接受
                groupComboBox = new ComboBox<>(model);

                // 或者直接传递数组 (如果 targetGroups 是 List，可以转换为数组)
                // ScriptGroup[] groupArray = targetGroups.toArray(new ScriptGroup[0]);
                // groupComboBox = new ComboBox<>(groupArray);

                groupComboBox.setSelectedIndex(0); // 默认选中第一个
            }
            panel.add(groupComboBox, BorderLayout.CENTER);
            return panel;
        }
// ...

        public ScriptGroup getSelectedGroup() {
            return (ScriptGroup) groupComboBox.getSelectedItem();
        }
    }

}

// --- Tree Cell Renderer ---
class ScriptTreeCellRenderer extends DefaultTreeCellRenderer {
    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
        Object userObject = node.getUserObject();

        if (userObject instanceof ScriptGroup) {
            ScriptGroup group = (ScriptGroup) userObject;
            setText(group.name);
            setIcon(expanded ? AllIcons.Nodes.Folder : AllIcons.Nodes.Folder);
            setToolTipText("Group: " + group.name);
        } else if (userObject instanceof ScriptEntry) {
            ScriptEntry script = (ScriptEntry) userObject;
            setText(script.getDisplayName());
            if (script.isMissing()) {
                setIcon(AllIcons.General.WarningDecorator); // 或一个表示“丢失”的图标
                setText("<html><font color='red'>" + script.getDisplayName() + " (Missing)</font></html>");
            } else {
                setIcon(AllIcons.Language.Python); // Python 文件图标
            }
            // 构建 tooltip
            StringBuilder tooltip = new StringBuilder("<html>");
            tooltip.append("<b>").append(script.path).append("</b><br>");
            tooltip.append("Mode: ").append(script.executionMode).append("<br>");
            if (script.alias != null && !script.alias.isEmpty()) {
                tooltip.append("Alias: ").append(script.alias).append("<br>");
            }
            if (script.description != null && !script.description.isEmpty()) {
                tooltip.append("Desc: <i>").append(StringUtil.escapeXmlEntities(script.description).replace("\n", "<br>")).append("</i>");
            }
            tooltip.append("</html>");
            setToolTipText(tooltip.toString());
        }
        return this;
    }
}