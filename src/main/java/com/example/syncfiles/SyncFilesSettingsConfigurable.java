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
import java.text.SimpleDateFormat;
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
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(0, 0, 10, 0);
        mainPanel.add(mappingsPanel, gbc);

        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(5, 0);
        mainPanel.add(new JBLabel("Environment Variables for Python Scripts:"), gbc);
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(0, 0, 10, 0);
        mainPanel.add(envVarsPanel, gbc);

        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(5, 0);
        mainPanel.add(new JBLabel("Path to Watch Settings:"), gbc); // 新增 Watch Entries 标题
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(0, 0, 10, 0);
        mainPanel.add(watchEntriesPanel, gbc); // 新增 Watch Entries 面板

        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(5, 0);
        mainPanel.add(new JBLabel("Python Scripts Directory:"), gbc);
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(0, 0, 5, 0);
        mainPanel.add(pythonScriptPathField, gbc);

        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(5, 0);
        mainPanel.add(new JBLabel("Python Executable Path:"), gbc);
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(0, 0, 10, 0);
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
        String projectName = project.getName();
        LOG.info("[" + projectName + "][Settings] Attempting to apply settings...");

        // 确保在读取UI值之前，所有表格的编辑都已停止并提交到模型
        if (mappingsTable.isEditing() && mappingsTable.getCellEditor() != null)
            mappingsTable.getCellEditor().stopCellEditing();
        if (envVarsTable.isEditing() && envVarsTable.getCellEditor() != null)
            envVarsTable.getCellEditor().stopCellEditing();
        if (watchEntriesTable.isEditing() && watchEntriesTable.getCellEditor() != null)
            watchEntriesTable.getCellEditor().stopCellEditing();

        SyncFilesConfig config = SyncFilesConfig.getInstance(project);

        // 1. 处理 Mappings
        List<Mapping> mappingsFromUI = getMappingsFromTable();
        for (int i = 0; i < mappingsFromUI.size(); i++) {
            Mapping mapping = mappingsFromUI.get(i);
            if (StringUtil.isEmptyOrSpaces(mapping.sourceUrl)) {
                throw new ConfigurationException("Mapping Entry #" + (i + 1) + ": 'Source URL' cannot be empty.");
            }
            if (StringUtil.isEmptyOrSpaces(mapping.targetPath)) {
                throw new ConfigurationException("Mapping Entry #" + (i + 1) + ": 'Target Path' cannot be empty.");
            }
            if (!mapping.sourceUrl.matches("^https?://.*")) { // 简单URL格式校验
                throw new ConfigurationException("Mapping Entry #" + (i + 1) + ": Invalid 'Source URL' format. Must start with http:// or https://.");
            }
            try {
                Paths.get(mapping.targetPath.replace('\\', '/'));
            } // 简单路径格式校验
            catch (InvalidPathException e) {
                throw new ConfigurationException("Mapping Entry #" + (i + 1) + ": Invalid 'Target Path' format: " + e.getMessage());
            }
        }
        config.setMappings(mappingsFromUI);
        LOG.debug("[" + projectName + "][Settings] Mappings applied. Count: " + mappingsFromUI.size());

        // 2. 处理 Environment Variables
        Map<String, String> envVarsFromUI = getEnvVarsFromTable();
        for (Map.Entry<String, String> entry : envVarsFromUI.entrySet()) {
            if (StringUtil.isEmptyOrSpaces(entry.getKey())) {
                // 通常表格不会允许空键，但以防万一
                throw new ConfigurationException("Environment variable name cannot be empty.");
            }
        }
        config.setEnvVariables(envVarsFromUI);
        LOG.debug("[" + projectName + "][Settings] Environment variables applied. Count: " + envVarsFromUI.size());

        // 3. 处理 Python Paths
        String scriptPathText = pythonScriptPathField.getText().trim().replace('\\', '/');
        if (!scriptPathText.isEmpty()) {
            try {
                Path path = Paths.get(scriptPathText);
                if (!Files.exists(path) || !Files.isDirectory(path)) {
                    throw new ConfigurationException("Python Scripts Directory does not exist or is not a directory: " + scriptPathText);
                }
            } catch (InvalidPathException e) {
                throw new ConfigurationException("Invalid Python Scripts Directory path: " + e.getMessage());
            }
        }
        config.setPythonScriptPath(scriptPathText); // 先保存路径

        String exePathText = pythonExecutablePathField.getText().trim().replace('\\', '/');
        if (!exePathText.isEmpty()) {
            try {
                Path path = Paths.get(exePathText);
                if (!Files.exists(path) || !Files.isRegularFile(path)) {
                    throw new ConfigurationException("Python Executable does not exist or is not a file: " + exePathText);
                }
            } catch (InvalidPathException e) {
                throw new ConfigurationException("Invalid Python Executable path: " + e.getMessage());
            }
        }
        config.setPythonExecutablePath(exePathText);
        LOG.debug("[" + projectName + "][Settings] Python paths applied. ScriptDir: '" + scriptPathText + "', Executable: '" + exePathText + "'");


        // 4. 核心逻辑：扫描脚本并更新 ScriptGroups (基于新的 scriptPathText)
        List<ScriptGroup> currentGroups = new ArrayList<>(config.getScriptGroups()); // 获取副本
        ScriptGroup defaultGroup = currentGroups.stream()
                .filter(g -> ScriptGroup.DEFAULT_GROUP_ID.equals(g.id))
                .findFirst().orElse(null);

        if (defaultGroup == null) { // 防御性：确保 Default 组存在
            defaultGroup = new ScriptGroup(ScriptGroup.DEFAULT_GROUP_ID, ScriptGroup.DEFAULT_GROUP_NAME);
            currentGroups.add(0, defaultGroup);
        }

        if (!scriptPathText.isEmpty() && Files.isDirectory(Paths.get(scriptPathText))) {
            Path scriptBasePath = Paths.get(scriptPathText);
            Set<String> allConfiguredScriptPaths = currentGroups.stream()
                    .flatMap(g -> g.scripts.stream())
                    .map(s -> s.path.toLowerCase()) // 统一小写比较
                    .collect(Collectors.toSet());

            try (Stream<Path> stream = Files.walk(scriptBasePath, 1)) {
                final ScriptGroup finalDefaultGroup = defaultGroup;
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd");
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".py"))
                        .map(p -> scriptBasePath.relativize(p).toString().replace('\\', '/'))
                        .forEach(relativePath -> {
                            if (!allConfiguredScriptPaths.contains(relativePath.toLowerCase())) {
                                boolean alreadyInDefault = finalDefaultGroup.scripts.stream()
                                        .anyMatch(s -> s.path.equalsIgnoreCase(relativePath));
                                if (!alreadyInDefault) {
                                    ScriptEntry newEntry = new ScriptEntry(relativePath);
                                    newEntry.description = "Auto-added on settings save " + sdf.format(new Date());
                                    finalDefaultGroup.scripts.add(newEntry);
                                    LOG.info("[" + projectName + "][Settings Apply] Added new script '" + relativePath + "' to Default group.");
                                }
                            }
                        });
                defaultGroup.scripts.sort(Comparator.comparing(s -> s.getDisplayName().toLowerCase()));
            } catch (IOException e) {
                LOG.warn("[" + projectName + "][Settings Apply] Error scanning script directory '" + scriptPathText + "': " + e.getMessage(), e);
                // 不抛出 ConfigurationException，允许其他设置保存，但记录警告
            }
        } else {
            LOG.info("[" + projectName + "][Settings Apply] Python script path is empty or invalid. Clearing all scripts from groups.");
            for (ScriptGroup group : currentGroups) {
                group.scripts.clear();
            }
        }
        config.setScriptGroups(currentGroups);
        LOG.debug("[" + projectName + "][Settings] Script groups updated.");


        // 5. 处理 Watch Entries
        List<WatchEntry> watchEntriesFromUI = watchEntriesTableModel.getEntries();
        for (int i = 0; i < watchEntriesFromUI.size(); i++) {
            WatchEntry entry = watchEntriesFromUI.get(i);
            String entryWatchedPath = entry.watchedPath != null ? entry.watchedPath.trim().replace('\\', '/') : "";
            String entryOnEventScript = entry.onEventScript != null ? entry.onEventScript.trim().replace('\\', '/') : "";

            if (StringUtil.isEmptyOrSpaces(entryWatchedPath)) {
                throw new ConfigurationException("Watch Entry #" + (i + 1) + ": 'Path to Watch' cannot be empty.");
            }
            if (StringUtil.isEmptyOrSpaces(entryOnEventScript)) {
                throw new ConfigurationException("Watch Entry #" + (i + 1) + ": 'Python Script on Modify' cannot be empty.");
            }
            try {
                Paths.get(entryWatchedPath); // 校验被监控路径格式
            } catch (InvalidPathException e) {
                throw new ConfigurationException("Watch Entry #" + (i + 1) + ": Invalid 'Path to Watch' format: '" + entryWatchedPath + "'. " + e.getMessage());
            }

            // 校验脚本路径
            Path scriptForWatchPath = Paths.get(entryOnEventScript);
            if (!scriptForWatchPath.isAbsolute()) { // 如果脚本是相对路径
                if (scriptPathText.isEmpty()) {
                    throw new ConfigurationException("Watch Entry #" + (i + 1) + ": 'Python Script on Modify' ('" + entryOnEventScript +
                            "') must be an absolute path if 'Python Scripts Directory' is not set.");
                }

                // 这里可以尝试解析为绝对路径，但不强制检查文件是否存在，因为服务启动时会检查
                String projectPath = project.getBasePath();
                if (projectPath != null) {
                    Path realPath = Paths.get(projectPath, entryOnEventScript);
                    if (!Files.exists(realPath)) {
                        throw new ConfigurationException("Watch Entry #" + (i + 1) + ": Relative Path don't exist: '" + entryOnEventScript + "'. ");
                    }
                }

            } else { // 脚本是绝对路径，可以做个简单存在性检查（可选）
                if (!Files.isRegularFile(scriptForWatchPath)) {
                    throw new ConfigurationException("Watch Entry #" + (i + 1) + ": Absolute Path don't exist: '" + scriptForWatchPath + "'. ");
                }
            }
        }
        config.setWatchEntries(watchEntriesFromUI);
        LOG.debug("[" + projectName + "][Settings] Watch entries applied. Count: " + watchEntriesFromUI.size());


        // 通知配置已更改
        project.getMessageBus().syncPublisher(SyncFilesNotifier.TOPIC).configurationChanged();
        LOG.info("[" + projectName + "][Settings] configurationChanged notification published.");

        // 更新 watcher services (这些服务会从 config 中读取最新的配置)
        ProjectDirectoryWatcherService pyScriptDirWatcher = project.getService(ProjectDirectoryWatcherService.class);
        if (pyScriptDirWatcher != null) {
            pyScriptDirWatcher.updateWatchedDirectories();
        }
        FileChangeEventWatcherService fileChangeEventWatcher = project.getService(FileChangeEventWatcherService.class);
        if (fileChangeEventWatcher != null) {
            fileChangeEventWatcher.updateWatchersFromConfig();
        }
        LOG.info("[" + projectName + "][Settings] Watcher services instructed to update from new config.");

        updateOriginalState(); // 保存后更新原始状态，以便 isModified() 正确工作
        LOG.info("[" + projectName + "][Settings] Apply complete.");
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
                case 0:
                    return entry.watchedPath;
                case 1:
                    return entry.onEventScript;
                default:
                    return null;
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