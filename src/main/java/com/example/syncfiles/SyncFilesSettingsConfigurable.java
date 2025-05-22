package com.example.syncfiles;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.intellij.codeInspection.InspectionApplicationBase.LOG;

public class SyncFilesSettingsConfigurable implements Configurable {
    private JPanel mainPanel;
    private JBTable mappingsTable;
    private DefaultTableModel mappingsTableModel;
    private JBTable envVarsTable;
    private DefaultTableModel envVarsTableModel;
    private final Project project;
    private TextFieldWithBrowseButton pythonScriptPathField;
    private TextFieldWithBrowseButton pythonExecutablePathField;

    // 新增：用于监控项的表格
    private JBTable watchEntriesTable;
    private WatchEntriesTableModel watchEntriesTableModel;


    // 用于比较状态是否修改
    private List<Mapping> originalMappings;
    private Map<String, String> originalEnvVars;
    private String originalScriptPath;
    private String originalExePath;
    private List<WatchEntry> originalWatchEntries;


    public SyncFilesSettingsConfigurable(Project project) {
        this.project = project;
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "SyncFiles Settings";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        // --- Mappings Table Setup ---
        mappingsTableModel = new DefaultTableModel(new Object[]{"Source URL", "Target Path"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return true;
            }
        };
        mappingsTable = new JBTable(mappingsTableModel);
        JPanel mappingsPanel = ToolbarDecorator.createDecorator(mappingsTable)
                .setAddAction(button -> mappingsTableModel.addRow(new String[]{"", ""}))
                .setRemoveAction(button -> removeSelectedRows(mappingsTable, mappingsTableModel))
                .setPreferredSize(new Dimension(500, 100))
                .createPanel();

        // --- Environment Variables Table Setup ---
        envVarsTableModel = new DefaultTableModel(new Object[]{"Variable Name", "Value"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return true;
            }
        };
        envVarsTable = new JBTable(envVarsTableModel);
        JPanel envVarsPanel = ToolbarDecorator.createDecorator(envVarsTable)
                .setAddAction(button -> envVarsTableModel.addRow(new String[]{"", ""}))
                .setRemoveAction(button -> removeSelectedRows(envVarsTable, envVarsTableModel))
                .setPreferredSize(new Dimension(500, 100))
                .createPanel();

        // --- Python Script Path Setup ---
        // ... 在 createComponent() 方法内 ...

// --- Python Script Path Setup (MODIFIED) ---
        pythonScriptPathField = new TextFieldWithBrowseButton();
// 1. 创建 FileChooserDescriptor
        final FileChooserDescriptor scriptFolderDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
        scriptFolderDescriptor.setTitle("Select Python Scripts Directory");
        scriptFolderDescriptor.setDescription("Select the directory containing your Python utility scripts.");
// 2. 为浏览按钮添加 ActionListener
        pythonScriptPathField.addActionListener(e -> {
            // 记住用户上次选择的目录 (可选, 但能提升用户体验)
            // String currentPath = pythonScriptPathField.getText();
            // VirtualFile initialFileToSelect = null;
            // if (!currentPath.isEmpty()) {
            //     initialFileToSelect = LocalFileSystem.getInstance().findFileByPath(currentPath);
            // }

            VirtualFile selectedFolder = FileChooser.chooseFile(
                    scriptFolderDescriptor,
                    project,
                    null // 或者 initialFileToSelect, 如果你想预选上次的目录
            );

            if (selectedFolder != null && selectedFolder.isDirectory()) {
                pythonScriptPathField.setText(selectedFolder.getPath().replace('\\', '/')); // 规范化路径
            }
        });

// --- Python Executable Path Setup (您已有的正确实现) ---
        pythonExecutablePathField = new TextFieldWithBrowseButton();
        final FileChooserDescriptor exeFileDescriptor = FileChooserDescriptorFactory.createSingleFileOrExecutableAppDescriptor();
        exeFileDescriptor.setTitle("Select Python Executable");
        exeFileDescriptor.setDescription("Select the python.exe or python binary");
        pythonExecutablePathField.addActionListener(e -> {
            VirtualFile selectedFile = FileChooser.chooseFile(exeFileDescriptor, project, null);
            if (selectedFile != null) {
                pythonExecutablePathField.setText(selectedFile.getPath().replace('\\', '/')); // 规范化路径
            }
        });

// ... 布局代码等 ...


        // --- Watch Entries Table Setup (新增) ---
        watchEntriesTableModel = new WatchEntriesTableModel(new ArrayList<>(), project, pythonScriptPathField);
        watchEntriesTable = new JBTable(watchEntriesTableModel);
        // 设置列编辑器和渲染器 (如果需要自定义浏览按钮)
        // watchEntriesTable.getColumnModel().getColumn(0).setCellEditor(new TextFieldWithBrowseButtonCellEditor(true, project));
        // watchEntriesTable.getColumnModel().getColumn(1).setCellEditor(new TextFieldWithBrowseButtonCellEditor(false, project, pythonScriptPathField));

        JPanel watchEntriesPanel = ToolbarDecorator.createDecorator(watchEntriesTable)
                .setAddAction(button -> watchEntriesTableModel.addRow(new WatchEntry("", "")))
                .setRemoveAction(button -> {
                    int[] selectedRows = watchEntriesTable.getSelectedRows();
                    if (selectedRows.length == 0) return;
                    Arrays.sort(selectedRows);
                    for (int i = selectedRows.length - 1; i >= 0; i--) {
                        int modelRow = watchEntriesTable.convertRowIndexToModel(selectedRows[i]);
                        watchEntriesTableModel.removeRow(modelRow);
                    }
                })
                .setPreferredSize(new Dimension(500, 100))
                .createPanel();


        // --- Layout ---
        mainPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = GridBagConstraints.RELATIVE;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = JBUI.insets(5, 0);

        mainPanel.add(new JBLabel("File Mappings (GitHub URL to Local Path):"), gbc);
        gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.insets = JBUI.insets(0, 0, 10, 0);
        mainPanel.add(mappingsPanel, gbc);

        gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE; gbc.insets = JBUI.insets(5, 0);
        mainPanel.add(new JBLabel("Environment Variables for Python Scripts:"), gbc);
        gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.insets = JBUI.insets(0, 0, 10, 0);
        mainPanel.add(envVarsPanel, gbc);

        gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE; gbc.insets = JBUI.insets(5, 0);
        mainPanel.add(new JBLabel("Path to Watch Settings:"), gbc); // 新增 Watch Entries 标题
        gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.insets = JBUI.insets(0, 0, 10, 0);
        mainPanel.add(watchEntriesPanel, gbc); // 新增 Watch Entries 面板

        gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE; gbc.insets = JBUI.insets(5, 0);
        mainPanel.add(new JBLabel("Python Scripts Directory:"), gbc);
        gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.insets = JBUI.insets(0, 0, 5, 0);
        mainPanel.add(pythonScriptPathField, gbc);

        gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE; gbc.insets = JBUI.insets(5, 0);
        mainPanel.add(new JBLabel("Python Executable Path:"), gbc);
        gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.insets = JBUI.insets(0, 0, 10, 0);
        mainPanel.add(pythonExecutablePathField, gbc);

        gbc.weighty = 1.0; // Filler
        mainPanel.add(new JPanel(), gbc);

        return JBUI.Panels.simplePanel().addToTop(mainPanel).withBorder(JBUI.Borders.empty(10));
    }

    private void removeSelectedRows(JBTable table, DefaultTableModel model) {
        int[] selectedRows = table.getSelectedRows();
        if (selectedRows.length == 0) return;
        Arrays.sort(selectedRows);
        for (int i = selectedRows.length - 1; i >= 0; i--) {
            int modelRow = table.convertRowIndexToModel(selectedRows[i]);
            model.removeRow(modelRow);
        }
    }


    @Override
    public boolean isModified() {

        boolean mappingsChanged = !Comparing.equal(getMappingsFromTable(), originalMappings);
        boolean envVarsChanged = !Comparing.equal(getEnvVarsFromTable(), originalEnvVars);
        // ★★★ 修改这两行 ★★★
        boolean scriptPathChanged = !Objects.equals(pythonScriptPathField.getText().trim(), originalScriptPath);
        boolean exePathChanged = !Objects.equals(pythonExecutablePathField.getText().trim(), originalExePath);
        // ★★★ 修改结束 ★★★
        boolean watchEntriesChanged = !Comparing.equal(watchEntriesTableModel.getEntries(), originalWatchEntries);

        return mappingsChanged || envVarsChanged || scriptPathChanged || exePathChanged || watchEntriesChanged;
    }

    // ... 在 SyncFilesSettingsConfigurable.java 的 apply() 方法中 ...
    @Override
    public void apply() throws ConfigurationException {
        // ... (停止编辑和获取其他配置的代码保持不变) ...
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        // ... (设置 mappings, envVars, pythonExecutablePath 的代码保持不变) ...

        String scriptPathText = pythonScriptPathField.getText().trim();
        // ... (scriptPathText 的校验逻辑保持不变) ...
        config.setPythonScriptPath(scriptPathText); // 先保存路径

        // --- 核心逻辑：扫描脚本并更新组 ---
        List<ScriptGroup> currentGroups = new ArrayList<>(config.getScriptGroups()); // 获取副本进行操作
        ScriptGroup defaultGroup = currentGroups.stream()
                .filter(g -> ScriptGroup.DEFAULT_GROUP_ID.equals(g.id))
                .findFirst().orElse(null);

        if (defaultGroup == null) { // 理论上 config 的 getter/setter 会保证，但再次检查
            defaultGroup = new ScriptGroup(ScriptGroup.DEFAULT_GROUP_ID, ScriptGroup.DEFAULT_GROUP_NAME);
            currentGroups.add(0, defaultGroup);
        }

        if (!scriptPathText.isEmpty() && Files.isDirectory(Paths.get(scriptPathText))) {
            Path scriptBasePath = Paths.get(scriptPathText);
            Set<String> allConfiguredScriptPaths = currentGroups.stream()
                    .flatMap(g -> g.scripts.stream())
                    .map(s -> s.path.toLowerCase())
                    .collect(Collectors.toSet());

            try (Stream<Path> stream = Files.walk(scriptBasePath, 1)) { // 只扫描顶层
                final ScriptGroup finalDefaultGroup = defaultGroup; // for lambda
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".py"))
                        .map(p -> scriptBasePath.relativize(p).toString().replace('\\', '/'))
                        .forEach(relativePath -> {
                            if (!allConfiguredScriptPaths.contains(relativePath.toLowerCase())) {
                                // 检查 Default 组是否已包含此路径（以防万一）
                                boolean alreadyInDefault = finalDefaultGroup.scripts.stream()
                                        .anyMatch(s -> s.path.equalsIgnoreCase(relativePath));
                                if (!alreadyInDefault) {
                                    ScriptEntry newEntry = new ScriptEntry(relativePath);
                                    newEntry.description = "Auto-added on settings save " + new java.text.SimpleDateFormat("yyyy/MM/dd").format(new Date());
                                    finalDefaultGroup.scripts.add(newEntry);
                                    LOG.info("Settings Apply: Added new script '" + relativePath + "' to Default group.");
                                }
                            }
                        });
                defaultGroup.scripts.sort(Comparator.comparing(s -> s.getDisplayName().toLowerCase()));
            } catch (IOException e) {
                LOG.warn("Error scanning script directory during settings apply: " + scriptPathText, e);
                // 可以考虑抛出 ConfigurationException 或者仅记录日志
            }
        } else { // scriptPathText 为空或无效
            LOG.info("Settings Apply: Python script path is empty or invalid. Clearing all scripts from groups.");
            for (ScriptGroup group : currentGroups) {
                group.scripts.clear(); // 清空每个组的脚本
            }
            // 如果希望只保留空的 Default 组，可以进一步处理 currentGroups
            // 例如：currentGroups.removeIf(g -> !ScriptGroup.DEFAULT_GROUP_ID.equals(g.id));
            // 但通常清空所有组的脚本，让 config 的 getter/setter 确保 Default 组存在就够了。
        }
        config.setScriptGroups(currentGroups); // 保存更新后的组和脚本列表
        // --- 核心逻辑结束 ---


        // ... (设置 watchEntries 的代码保持不变) ...
        // ★★★ 4. 处理 Watch Entries (这是您需要添加的部分) ★★★
        List<WatchEntry> watchEntriesFromUI = watchEntriesTableModel.getEntries(); // 从 TableModel 获取数据
        // 对 watchEntriesFromUI 进行校验 (非常重要)
        for (int i = 0; i < watchEntriesFromUI.size(); i++) {
            WatchEntry entry = watchEntriesFromUI.get(i);
            if (StringUtil.isEmptyOrSpaces(entry.watchedPath)) {
                throw new ConfigurationException("Watch Entry #" + (i + 1) + ": 'Path to Watch' cannot be empty.");
            }
            if (StringUtil.isEmptyOrSpaces(entry.onEventScript)) {
                throw new ConfigurationException("Watch Entry #" + (i + 1) + ": 'Python Script on Modify' cannot be empty.");
            }
            // 校验路径格式 (可选但推荐)
            try {
                Paths.get(entry.watchedPath.replace('\\', '/'));
            } catch (InvalidPathException e) {
                throw new ConfigurationException("Watch Entry #" + (i + 1) + ": Invalid 'Path to Watch' format: " + e.getMessage());
            }
            // 校验脚本路径 (可选，但如果脚本目录已设置，可以检查相对路径的有效性)
            if (!scriptPathText.isEmpty() && !Paths.get(entry.onEventScript).isAbsolute()) {
                try {
                    Path fullScriptForWatch = Paths.get(scriptPathText, entry.onEventScript.replace('\\', '/'));
                    // 注意：这里不强制要求脚本文件必须存在于apply时，因为用户可能稍后创建它。
                    // 但至少路径结构应该是合法的。
                    // if (!Files.isRegularFile(fullScriptForWatch)) {
                    //     LOG.warn("Script for watch entry does not exist (yet): " + fullScriptForWatch);
                    // }
                } catch (InvalidPathException e) {
                    throw new ConfigurationException("Watch Entry #" + (i + 1) + ": Invalid 'Python Script on Modify' path structure relative to script directory: " + e.getMessage());
                }
            } else if (scriptPathText.isEmpty() && !Paths.get(entry.onEventScript).isAbsolute()){
                throw new ConfigurationException("Watch Entry #" + (i + 1) + ": 'Python Script on Modify' must be an absolute path if 'Python Scripts Directory' is not set.");
            }
        }
        config.setWatchEntries(watchEntriesFromUI); // ★★★ 将获取到的数据设置到配置中 ★★★
        // ★★★ 处理 Watch Entries 结束 ★★★
        // 通知配置已更改
        project.getMessageBus().syncPublisher(SyncFilesNotifier.TOPIC).configurationChanged();

        // 更新 watcher service
        ProjectDirectoryWatcherService pyScriptDirWatcher = project.getService(ProjectDirectoryWatcherService.class);
        if (pyScriptDirWatcher != null) {
            pyScriptDirWatcher.updateWatchedDirectories(); // 这个 watcher 会监听脚本目录变化
        }
        FileChangeEventWatcherService fileChangeEventWatcher = project.getService(FileChangeEventWatcherService.class);
        if (fileChangeEventWatcher != null) {
            fileChangeEventWatcher.updateWatchersFromConfig();
        }

        updateOriginalState(); // 保存后更新原始状态
    }

    @Override
    public void reset() {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        // 从配置加载当前值以填充UI
        originalMappings = new ArrayList<>(config.getMappings());
        originalEnvVars = new HashMap<>(config.getEnvVariables());
        originalScriptPath = config.getPythonScriptPath() != null ? config.getPythonScriptPath() : "";
        originalExePath = config.getPythonExecutablePath() != null ? config.getPythonExecutablePath() : "";
        originalWatchEntries = new ArrayList<>(config.getWatchEntries());


        mappingsTableModel.setRowCount(0);
        for (Mapping mapping : originalMappings) {
            mappingsTableModel.addRow(new Object[]{mapping.sourceUrl, mapping.targetPath});
        }

        envVarsTableModel.setRowCount(0);
        for (Map.Entry<String, String> entry : originalEnvVars.entrySet()) {
            envVarsTableModel.addRow(new Object[]{entry.getKey(), entry.getValue()});
        }

        pythonScriptPathField.setText(originalScriptPath);
        pythonExecutablePathField.setText(originalExePath);

        watchEntriesTableModel.setEntries(new ArrayList<>(originalWatchEntries));
    }

    private void updateOriginalState() {
        // UI应用到配置后，用当前UI的值更新originalXX变量
        originalMappings = getMappingsFromTable();
        originalEnvVars = getEnvVarsFromTable();
        originalScriptPath = pythonScriptPathField.getText().trim();
        originalExePath = pythonExecutablePathField.getText().trim();
        originalWatchEntries = new ArrayList<>(watchEntriesTableModel.getEntries());
    }


    @Override
    public void disposeUIResources() {
        mainPanel = null;
        mappingsTable = null;
        envVarsTable = null;
        watchEntriesTable = null;
        mappingsTableModel = null;
        envVarsTableModel = null;
        watchEntriesTableModel = null;
        pythonScriptPathField = null;
        pythonExecutablePathField = null;
        originalMappings = null;
        originalEnvVars = null;
        originalScriptPath = null;
        originalExePath = null;
        originalWatchEntries = null;
    }

    private List<Mapping> getMappingsFromTable() {
        List<Mapping> mappings = new ArrayList<>();
        for (int i = 0; i < mappingsTableModel.getRowCount(); i++) {
            String sourceUrl = ((String) mappingsTableModel.getValueAt(i, 0)).trim();
            String targetPath = ((String) mappingsTableModel.getValueAt(i, 1)).trim();
            if (!sourceUrl.isEmpty() || !targetPath.isEmpty()) { // 允许部分为空，apply时校验
                mappings.add(new Mapping(sourceUrl, targetPath));
            }
        }
        return mappings;
    }

    private Map<String, String> getEnvVarsFromTable() {
        Map<String, String> envVars = new HashMap<>();
        for (int i = 0; i < envVarsTableModel.getRowCount(); i++) {
            String key = ((String) envVarsTableModel.getValueAt(i, 0)).trim();
            String value = (String) envVarsTableModel.getValueAt(i, 1); // Value 可以为空字符串
            if (!key.isEmpty()) {
                envVars.put(key, value != null ? value : "");
            }
        }
        return envVars;
    }

    // WatchEntriesTableModel 的实现
    private static class WatchEntriesTableModel extends AbstractTableModel {
        private final List<String> columnNames = Arrays.asList("Path to Watch (File or Directory)", "Python Script on Modify");
        private List<WatchEntry> entries;
        private final Project project;
        private final TextFieldWithBrowseButton pythonScriptPathGlobalField; // 用于获取全局Python脚本目录

        public WatchEntriesTableModel(List<WatchEntry> entries, Project project, TextFieldWithBrowseButton pythonScriptPathGlobalField) {
            this.entries = entries;
            this.project = project;
            this.pythonScriptPathGlobalField = pythonScriptPathGlobalField;
        }

        public List<WatchEntry> getEntries() {
            // 返回副本以避免外部修改
            return entries.stream()
                    .map(entry -> new WatchEntry(entry.watchedPath, entry.onEventScript))
                    .collect(Collectors.toList());
        }

        public void setEntries(List<WatchEntry> newEntries) {
            this.entries = new ArrayList<>(newEntries); // 存储副本
            fireTableDataChanged();
        }

        public void addRow(WatchEntry entry) {
            entries.add(entry);
            fireTableRowsInserted(entries.size() - 1, entries.size() - 1);
        }

        public void removeRow(int rowIndex) {
            if (rowIndex >= 0 && rowIndex < entries.size()) {
                entries.remove(rowIndex);
                fireTableRowsDeleted(rowIndex, rowIndex);
            }
        }

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return columnNames.size();
        }

        @Override
        public String getColumnName(int column) {
            return columnNames.get(column);
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            WatchEntry entry = entries.get(rowIndex);
            switch (columnIndex) {
                case 0: return entry.watchedPath;
                case 1: return entry.onEventScript;
                default: return null;
            }
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            WatchEntry entry = entries.get(rowIndex);
            String valueStr = (aValue == null) ? "" : aValue.toString().replace('\\', '/');
            switch (columnIndex) {
                case 0:
                    entry.watchedPath = valueStr;
                    break;
                case 1:
                    entry.onEventScript = valueStr;
                    break;
            }
            fireTableCellUpdated(rowIndex, columnIndex);
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return true; // 所有单元格都可直接编辑，或通过自定义编辑器
        }
    }
    // 如果需要带浏览按钮的单元格编辑器，你需要一个更复杂的实现，例如:
    // private static class TextFieldWithBrowseButtonCellEditor extends AbstractCellEditor implements TableCellEditor {
    //     private TextFieldWithBrowseButton component;
    //     // ... 构造函数和 getCellEditorValue, getTableCellEditorComponent 方法
    // }
}