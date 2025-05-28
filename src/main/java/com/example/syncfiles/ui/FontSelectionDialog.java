package com.example.syncfiles.ui;


import com.example.syncfiles.util.TerminalFontUtil;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;

import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Objects;
import java.util.Vector;

public class FontSelectionDialog extends DialogWrapper {
    private ComboBox<String> fontFamilyComboBox;
    private JSpinner fontSizeSpinner;
    private JLabel previewLabel;
    private JPanel previewPanel;

    private final String initialFontFamily;
    private final int initialFontSize;

    public FontSelectionDialog(@Nullable Project project, String currentFontFamily, int currentFontSize) {
        super(project, true);
        this.initialFontFamily = currentFontFamily;
        this.initialFontSize = currentFontSize;
        setTitle("Configure Terminal Font");
        init();
        if (fontFamilyComboBox != null) {
            setSelectedFontInComboBox(this.initialFontFamily);
        }
        if (fontSizeSpinner != null) {
            fontSizeSpinner.setValue(this.initialFontSize);
        }
        updatePreview();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        List<String> availableFamilies = TerminalFontUtil.getAvailableMonospacedFontFamilies();
        DefaultComboBoxModel<String> comboBoxModel = new DefaultComboBoxModel<>(new Vector<>(availableFamilies));
        fontFamilyComboBox = new ComboBox<>(comboBoxModel);

        SpinnerModel sizeModel = new SpinnerNumberModel(this.initialFontSize, 8, 100, 1);
        fontSizeSpinner = new JSpinner(sizeModel);

        fontFamilyComboBox.addActionListener(e -> updatePreview());
        fontSizeSpinner.addChangeListener(e -> updatePreview());

        previewLabel = new JBLabel("AaBbCc 你好世界 123 []{}#@!");
        previewLabel.setOpaque(true);
        previewPanel = new JPanel(new BorderLayout());
        previewPanel.setBorder(JBUI.Borders.customLine(JBColor.border(), 1));
        previewPanel.add(previewLabel, BorderLayout.CENTER);
        previewPanel.setPreferredSize(new Dimension(JBUI.scale(350), JBUI.scale(100)));

        JBLabel recommendationLabel = new JBLabel(
                "<html>For best CJK display, a font like '<b>" +
                        TerminalFontUtil.PREFERRED_FONT_SARASA +
                        "</b>' is recommended.<br>You may need to install it on your system.</html>",
                SwingConstants.LEFT);
        recommendationLabel.setForeground(JBColor.GRAY);

        return FormBuilder.createFormBuilder()
                .setAlignLabelOnRight(false)
                .addLabeledComponent(new JBLabel("Font Family:"), fontFamilyComboBox, JBUI.scale(5), false)
                .addLabeledComponent(new JBLabel("Font Size:"), fontSizeSpinner, JBUI.scale(5), false)
                .addLabeledComponent(new JBLabel("Preview:"), previewPanel, JBUI.scale(15), false)
                .addComponentToRightColumn(recommendationLabel, JBUI.scale(5))
                .getPanel();
    }

    private void setSelectedFontInComboBox(String familyName) {
        if (fontFamilyComboBox == null || familyName == null) return;
        ComboBoxModel<String> model = fontFamilyComboBox.getModel();
        for (int i = 0; i < model.getSize(); i++) {
            if (Objects.equals(model.getElementAt(i), familyName)) {
                fontFamilyComboBox.setSelectedIndex(i);
                return;
            }
        }
        for (int i = 0; i < model.getSize(); i++) {
            if (familyName.equalsIgnoreCase(model.getElementAt(i))) {
                fontFamilyComboBox.setSelectedIndex(i);
                return;
            }
        }
    }

    private void updatePreview() {
        if (fontFamilyComboBox == null || fontSizeSpinner == null || previewLabel == null) {
            return;
        }

        String selectedFamily = (String) fontFamilyComboBox.getSelectedItem();
        Object sizeValue = fontSizeSpinner.getValue();
        int selectedSize = initialFontSize;

        if (sizeValue instanceof Integer) {
            selectedSize = (Integer) sizeValue;
        } else if (sizeValue instanceof Number) {
            selectedSize = ((Number) sizeValue).intValue();
        }

        if (selectedFamily != null) {
            try {
                Font previewFont = new Font(selectedFamily, Font.PLAIN, selectedSize);
                previewLabel.setFont(previewFont);

                EditorColorsScheme globalScheme = EditorColorsManager.getInstance().getGlobalScheme();
                Color editorBgColor = globalScheme.getDefaultBackground();
                Color editorFgColor = globalScheme.getDefaultForeground();

                previewLabel.setBackground(editorBgColor != null ? editorBgColor : JBColor.PanelBackground);
                previewLabel.setForeground(editorFgColor != null ? editorFgColor : JBColor.foreground());
                previewLabel.setText("AaBbCc 你好世界 123 " + selectedFamily);

            } catch (Exception e) {
                previewLabel.setFont(UIManager.getFont("Label.font"));
                previewLabel.setText("Font Error: " + selectedFamily);
                previewLabel.setForeground(JBColor.RED);
                System.err.println("Error creating font for preview: " + selectedFamily + " - " + e.getMessage());
            }
        } else {
            previewLabel.setFont(UIManager.getFont("Label.font"));
            previewLabel.setText("Select a font family");
            previewLabel.setBackground(JBColor.PanelBackground);

            // 获取标准禁用的前景颜色
            Color disabledFg = UIManager.getColor("Label.disabledForeground");
            if (disabledFg == null) {
                // 如果 UIManager 中没有这个颜色，提供一个合理的备用值
                disabledFg = JBColor.namedColor("Label.disabledForeground", JBColor.GRAY);
            }
            previewLabel.setForeground(disabledFg);
        }

        if (previewPanel != null) {
            previewPanel.revalidate();
            previewPanel.repaint();
        }
    }

    @Nullable
    public String getSelectedFontFamily() {
        if (fontFamilyComboBox == null) return null;
        return (String) fontFamilyComboBox.getSelectedItem();
    }

    public int getSelectedFontSize() {
        if (fontSizeSpinner == null) return initialFontSize;
        Object value = fontSizeSpinner.getValue();
        if (value instanceof Integer) {
            return (Integer) value;
        } else if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return initialFontSize;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return fontFamilyComboBox;
    }
}