package com.example.syncfiles;

import com.example.syncfiles.action.FontSelectorAction;
import com.example.syncfiles.font.LoggingTtyConnectorWrapper;
import com.example.syncfiles.font.TerminalFontSettingsProvider;
import com.example.syncfiles.util.TerminalFontUtil;
import com.example.syncfiles.notifiers.SyncFilesNotifier;
import com.example.syncfiles.ui.LoadSmartWorkflowAction;
import com.example.syncfiles.util.Util;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
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
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.terminal.pty.PtyProcessTtyConnector;
import com.intellij.ui.*;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalSession;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import org.jetbrains.plugins.terminal.*;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.EnvironmentUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
// 核心 Terminal API
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SyncFilesToolWindowFactory implements com.intellij.openapi.wm.ToolWindowFactory, DumbAware {
    private static final Logger LOG = Logger.getInstance(SyncFilesToolWindowFactory.class);
    private Tree scriptTree;
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;
    private Project project; // 当前 Project
    private  String TAB_NAME = "Sync Files";
    private final int INIT_COLUMNS = 120;
    private final int INIT_ROWS     = 40;
    private final int FONT_SIZE     = 40;
    public static final Icon ACTION_ADD = IconLoader.getIcon("/icons/add.svg", FontSelectorAction.class);
    public static final Icon ACTION_DOWNLOAD = IconLoader.getIcon("/icons/download.svg", FontSelectorAction.class);
    public static final Icon ACTION_REFRESH = IconLoader.getIcon("/icons/refresh.svg", FontSelectorAction.class);
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        this.project = project;
        JPanel mainPanel = new JPanel(new BorderLayout());

        // 1. 工具栏 (ActionToolbar)
        ActionManager actionManager = ActionManager.getInstance();
        DefaultActionGroup actionGroup = new DefaultActionGroup();
        actionGroup.add(new AnAction("Refresh Scripts And Work Directory", "Reload scripts from disk and configuration", ACTION_REFRESH) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                updateScriptTree(true); // true 表示强制从磁盘扫描
                Util.refreshAllFiles(project);
            }
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() { // BGT = Background Thread
                return ActionUpdateThread.BGT;
            }
        });
        actionGroup.add(new AnAction("Add Group...", "Add a new script group", ACTION_ADD) {
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
        actionGroup.add(new AnAction("Sync GitHub Files", "Download files/directories based on mappings", ACTION_DOWNLOAD) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                new SyncAction().syncFiles(project); // SyncAction 是您原有的类
            }
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }
        });
        actionGroup.add(new Separator());
        actionGroup.add(new LoadSmartWorkflowAction());
        actionGroup.add(new FontSelectorAction());

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
                ApplicationManager.getApplication().invokeLater(() -> updateScriptTree(true));
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
        pythonExecutable = Util.isDirectoryAfterMacroExpansion(project,pythonExecutable);
        if (StringUtil.isEmptyOrSpaces(pythonExecutable)) {
            Messages.showErrorDialog(project, "Python executable path is not configured in SyncFiles Settings.", "Execution Error");
            return;
        }
        if (!Files.isRegularFile(Paths.get(pythonExecutable))) {
            Messages.showErrorDialog(project, "Configured Python executable path is invalid or not a file: " + pythonExecutable, "Execution Error");
            return;
        }

        Path scriptBasePath = Paths.get(Util.isDirectoryAfterMacroExpansion(project,config.getPythonScriptPath()));
        Path fullScriptPath = scriptBasePath.resolve(scriptEntry.path).normalize();
        if (!Files.isRegularFile(fullScriptPath)) {
            Messages.showErrorDialog(project, "Script file not found: " + fullScriptPath, "Execution Error");
            scriptEntry.setMissing(true); // 标记为丢失
            treeModel.nodeChanged((TreeNode) findScriptNode(scriptEntry.id).orElse(null)); // 更新UI
            return;
        }


        if ("terminal".equalsIgnoreCase(scriptEntry.executionMode)) {
//            executeScriptInTerminal(project, pythonExecutable, fullScriptPath.toString(), config.getEnvVariables());
            executeScriptInDedicatedPtyTab(project,pythonExecutable,fullScriptPath.toString(),new ArrayList<>(),config.getEnvVariables(), TAB_NAME);
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
                    Path scriptBasePath = Paths.get(Util.isDirectoryAfterMacroExpansion(project,config.getPythonScriptPath()));
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
        group.add(new AnAction("Add Script to This Group...") {
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
        pythonScriptPathStr = Util.isDirectoryAfterMacroExpansion(project,pythonScriptPathStr);
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
        pythonScriptPathStr = Util.isDirectoryAfterMacroExpansion(project,pythonScriptPathStr);
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



        /**
         * 使用直接的 PTY 进程在新的终端标签页中执行 Python 脚本。
         *
         * @param project          当前项目
         * @param pythonExecutable Python 解释器路径
         * @param scriptPath       要执行的 Python 脚本路径
         * @param scriptArguments  传递给 Python 脚本的参数列表
         * @param customEnvVars    需要为脚本设置的自定义环境变量
         * @param tabNamePrefix    新终端标签页的名称前缀 (会附加UUID以保证唯一性)
         */
        public void executeScriptInDedicatedPtyTab(
                @NotNull Project project,
                @NotNull String pythonExecutable,
                @NotNull String scriptPath,
                @NotNull List<String> scriptArguments, // 明确脚本参数
                @NotNull Map<String, String> customEnvVars,
                @NotNull String tabNamePrefix) {

            LOG.info("Attempting to execute in a new PTY tab: " + pythonExecutable + " " + scriptPath);
            Util.forceRefreshVFS(scriptPath); // 确保文件已同步 (如果需要)

            ApplicationManager.getApplication().invokeLater(() -> {
                String uniqueTabName = TAB_NAME;
                try {
                    // 1. 准备 PTY 进程的环境变量
                    Map<String, String> effectiveEnv = new HashMap<>(System.getenv());
                    effectiveEnv.putAll(customEnvVars);
                    effectiveEnv.put("TERM", "xterm-256color"); // 对 ANSI 颜色至关重要
                    effectiveEnv.put("PYTHONUNBUFFERED", "1");    // 建议 Python 实时输出
                    effectiveEnv.put("PYTHONIOENCODING", "UTF-8"); // 确保 UTF-8 I/O
                    effectiveEnv.put("LANG", "en_US.UTF-8");
                    effectiveEnv.put("LC_ALL", "en_US.UTF-8");
                    // 2. 准备 PTY 进程的命令
                    List<String> commandList = new ArrayList<>();
                    commandList.add(pythonExecutable);
                    commandList.add(scriptPath);
                    commandList.addAll(scriptArguments); // 添加脚本参数
                    String[] command = commandList.toArray(new String[0]);

                    // 3. 创建 PtyProcess
                    // 确保工作目录是项目根目录或脚本所在目录，根据需求调整
                    String workDir = project.getBasePath() != null ? project.getBasePath() : new java.io.File(scriptPath).getParent();
                    PtyProcessBuilder ptyProcessBuilder = new PtyProcessBuilder(command)
                            .setEnvironment(effectiveEnv)
                            .setDirectory(workDir)
                            .setInitialColumns(INIT_COLUMNS)
                            .setInitialRows(INIT_ROWS)
                            .setWindowsAnsiColorEnabled(true)
                            .setUseWinConPty(true)
                            ;

                    LOG.info("Starting PTY process with command: " + String.join(" ", commandList) +
                            ", environment keys: " + effectiveEnv.keySet());

                    PtyProcess ptyProcess = ptyProcessBuilder.start();

                    // 4. 创建 TtyConnector
                    TtyConnector ttyConnector = new PtyProcessTtyConnector(ptyProcess, StandardCharsets.UTF_8);
                    TtyConnector loggingTtyConnector = new LoggingTtyConnectorWrapper(ttyConnector);
                    // 5. 创建 JediTermWidget (UI 组件)
                    Disposable contentDisposable = Disposer.newDisposable("JediTermContentDisposable_" + uniqueTabName);
                    SettingsProvider myFontProvider = new TerminalFontSettingsProvider(TerminalFontUtil.getConfiguredFont().getFontName(), Font.PLAIN, FONT_SIZE);
                    JediTermWidget jediTermWidget = new JediTermWidget(INIT_COLUMNS,INIT_ROWS, myFontProvider);


                    // 6. 创建终端会话并启动
                    TerminalSession session = jediTermWidget.createTerminalSession(ttyConnector);
                    if (session instanceof com.jediterm.terminal.ui.TerminalSession && session == jediTermWidget) {
                        LOG.info("Confirmed: session object is indeed the jediTermWidget instance and implements TerminalSession.");
                    } else {
                        LOG.error("Error: session object is NOT the jediTermWidget or does not implement TerminalSession as expected!");
                        // 如果这里出错，说明我对你提供的 createTerminalSession 的理解或你的代码有出入
                        return; // 无法继续
                    }
                    final com.jediterm.terminal.Terminal emulatorTerminal = session.getTerminal(); // 获取 Terminal 接口实例

                    if (emulatorTerminal != null) {
                        ApplicationManager.getApplication().invokeLater(() -> { // 确保在 UI 线程操作，如果 setEnabled 会触发UI
                            try {
                                LOG.info("Attempting to explicitly disable AlternateBuffer mode via Terminal interface.");
                                // 调用 TerminalMode 的 setEnabled，传入获取到的 Terminal 实例
                                com.jediterm.terminal.TerminalMode.AlternateBuffer.setEnabled(emulatorTerminal, false);
                                LOG.info("AlternateBuffer mode explicitly set to false.");

                                // （可选）同时确保光标可见，以防万一
                                // com.jediterm.terminal.TerminalMode.CursorVisible.setEnabled(emulatorTerminal, true);

                            } catch (Exception e) {
                                LOG.error("Exception while trying to set AlternateBuffer mode.", e);
                            }
                        });
                    } else {
                        LOG.warn("Could not get Terminal interface from session (jediTermWidget) to set mode.");
                    }
                    session.start();
                    final ToolWindow terminalToolWindow = ToolWindowManager.getInstance(project).getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID);
                    if (terminalToolWindow == null) {
                        LOG.error("Terminal tool window not found for PTY tab.");
                        Messages.showErrorDialog(project, "Terminal tool window is not available.", "Terminal Error");
                        Disposer.dispose(contentDisposable); // 清理
                        if (ptyProcess.isAlive()) ptyProcess.destroy(); // 确保进程被杀死
                        return;
                    }

                    ContentFactory contentFactory = ContentFactory.getInstance();
                    Content content = contentFactory.createContent(jediTermWidget.getComponent(), uniqueTabName, true);
                    content.setDisposer(contentDisposable); // 当标签页关闭时，jediTermWidget 和 PTY 进程会被清理

                    terminalToolWindow.getContentManager().addContent(content);
                    terminalToolWindow.getContentManager().setSelectedContent(content);
                    terminalToolWindow.show(); // 确保工具窗口可见

                    LOG.info("Dedicated PTY session '" + uniqueTabName + "' started for script: " + scriptPath);

                } catch (IOException e) { // PtyProcessBuilder.start() 可能抛出 ExecutionException
                    LOG.error("Failed to start PTY process for script: " + scriptPath);
                    Messages.showErrorDialog(project, "Failed to start PTY process: " + e.getMessage(), "Terminal Error");
                } catch (Exception e) { // 捕获其他意外错误
                    LOG.error("An unexpected error occurred while setting up PTY for script: " + scriptPath, e);
                    Messages.showErrorDialog(project, "Unexpected error setting up PTY: " + e.getMessage(), "Terminal Error");
                }
            });
        }

    // 在 SyncFilesToolWindowFactory.java 的 executeScriptDirectly 方法中
    private void executeScriptDirectly(Project project, String pythonExecutable, String scriptPath, Map<String, String> envVars, String displayName) {
        LOG.info("Executing directly: " + pythonExecutable + " " + scriptPath);
        Util.forceRefreshVFS(scriptPath); // 确保文件已同步
        scriptPath = Util.isDirectoryAfterMacroExpansion(project,scriptPath);
        String finalScriptPath = scriptPath;
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Running: " + displayName, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Executing " + Paths.get(finalScriptPath).getFileName().toString());
                StringBuilder output = new StringBuilder();
                StringBuilder errorOutput = new StringBuilder();
                int exitCode = -1;

                try {
                    ProcessBuilder pb = new ProcessBuilder(pythonExecutable, finalScriptPath);
                    Map<String, String> effectiveEnv = new HashMap<>(EnvironmentUtil.getEnvironmentMap());
                    effectiveEnv.putAll(envVars);
                    effectiveEnv.put("PYTHONIOENCODING", "UTF-8");
                    pb.environment().clear();
                    pb.environment().putAll(effectiveEnv);
                    pb.directory(project.getBasePath() != null ? new File(project.getBasePath()) : Paths.get(finalScriptPath).getParent().toFile());

                    Process process = pb.start();

                    try (BufferedReader outReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                         BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = outReader.readLine()) != null) output.append(line).append("\n");
                        while ((line = errReader.readLine()) != null) errorOutput.append(line).append("\n");
                    }
                    exitCode = process.waitFor();

                } catch (IOException | InterruptedException e) {
                    LOG.error("Failed to execute script (direct API): " + finalScriptPath, e);
                    errorOutput.append("Execution failed: ").append(e.getMessage());
                    exitCode = -1; // Indicate failure
                }

                final String finalOutput = output.toString().trim();
                final String finalError = errorOutput.toString().trim();
                final int finalExitCode = exitCode;

                ApplicationManager.getApplication().invokeLater(() -> {
                    String scriptFileName = Paths.get(finalScriptPath).getFileName().toString();
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
//                    Util.refreshAllFiles(project); // Refresh VFS
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