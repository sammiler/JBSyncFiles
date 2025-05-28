package com.example.syncfiles.util; // 假设放在 util 包

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import groovyjarjarantlr4.v4.runtime.misc.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory;
import com.intellij.openapi.util.Key;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static com.intellij.util.ui.FontInfo.isMonospaced;

public class TerminalFontUtil {

    public static final  String PREFERRED_FONT_SARASA = "等距更纱黑体 SC（Sarasa Mono SC）";
    private static final Logger LOG = Logger.getInstance(TerminalFontUtil.class);

    private static final String FONT_NAME_PROPERTY_KEY = "com.example.plugin.terminal.font.family"; // 使用FamilyName存储
    private static final String FONT_SIZE_PROPERTY_KEY = "com.example.plugin.terminal.font.size";

    public static final int DEFAULT_FONT_SIZE = 14;
    private static final String DEFAULT_MONOSPACED_LOGICAL_FONT = "Monospaced"; // Java逻辑字体

    // 跨平台优先字体列表 (按偏好顺序) - 这些是字体家族名
    // 我们优先选择那些已知中英文等宽效果都很好的字体
    private static final List<String> PREFERRED_FONTS_CJK_MONO = Collections.unmodifiableList(Arrays.asList(
            "Sarasa Mono SC",           // 更纱黑体 Mono SC (简体) - 跨平台，需用户安装
            "Sarasa Gothic SC",         // 更纱黑体 SC (有时Java识别为这个家族名，但它也包含等宽) - 跨平台，需用户安装
            "Sarasa Term SC",           // 更纱终端体 SC - 跨平台，需用户安装
            "Microsoft YaHei Mono",     // 微软雅黑等宽 - Windows
            "DengXian Mono",            // 等线等宽 - Windows
            "PingFang SC",              // 苹方 SC (macOS默认中文字体，其等宽性需依赖具体子字体或系统渲染) - macOS
            "Noto Sans Mono CJK SC",    // Noto Sans Mono CJK SC - 跨平台，需用户安装
            "WenQuanYi Micro Hei Mono", // 文泉驿等宽微米黑 - Linux
            "Source Han Mono SC",       // 思源等宽黑体 SC (与Noto Sans Mono CJK SC同源) - 跨平台，需用户安装
            "SimSun-ExtB",              // 宋体-ExtB (中文覆盖广，等宽) - Windows
            "NSimSun"                   // 新宋体 (类似SimSun) - Windows
    ));

    // 纯英文等宽字体备选 (如果上述都找不到，或者用户不需要中文)
    private static final List<String> PREFERRED_FONTS_ENG_MONO = Collections.unmodifiableList(Arrays.asList(
            "JetBrains Mono",           // IntelliJ 捆绑
            "Consolas",                 // Windows
            "Cascadia Mono",            // Windows (新)
            "Menlo",                    // macOS
            "Monaco",                   // macOS
            "DejaVu Sans Mono",         // Linux / 跨平台
            "Liberation Mono",          // Linux / 跨平台
            "Fira Code",                // 编程连字字体，需用户安装
            "Source Code Pro"           // Adobe 开源
    ));

    private static Font currentConfiguredFont = null;
    private static String cachedFontFamily = null;
    private static int cachedFontSize = -1;

    /**
     * 获取当前为终端配置的字体。
     * 如果没有配置，则自动探测并选择一个最佳可用字体，并保存该选择。
     * @return 配置的或自动选择的 {@link Font} 对象。
     */
    public static synchronized Font getConfiguredFont() {
        PropertiesComponent props = PropertiesComponent.getInstance();
        String configuredFamily = props.getValue(FONT_NAME_PROPERTY_KEY);
        int configuredSize = props.getInt(FONT_SIZE_PROPERTY_KEY, DEFAULT_FONT_SIZE);

        // 如果缓存有效，直接返回缓存
        if (currentConfiguredFont != null &&
                configuredFamily != null && configuredFamily.equals(cachedFontFamily) &&
                configuredSize == cachedFontSize) {
            // LOG.debug("Returning cached font: " + cachedFontFamily + ", size: " + cachedFontSize);
            return currentConfiguredFont;
        }

        Font fontToUse;
        String familyToUse;
        int sizeToUse = configuredSize;

        if (configuredFamily != null && !configuredFamily.isEmpty()) {
            Font testFont = new Font(configuredFamily, Font.PLAIN, sizeToUse);
            // 验证配置的字体是否真的被加载了，而不是回退到 "Dialog"
            // (有时字体卸载了，但配置还在)
            if (!testFont.getFamily().equalsIgnoreCase("Dialog") || configuredFamily.equalsIgnoreCase("Dialog")) {
                fontToUse = testFont;
                familyToUse = configuredFamily; // 使用配置中的家族名，因为testFont.getFamily()可能返回 slightly different
                LOG.info("Using configured font: " + familyToUse + " (resolved as: " + fontToUse.getFontName() + "), Size: " + sizeToUse);
            } else {
                LOG.warn("Configured font '" + configuredFamily + "' was not found or resolved to Dialog. Finding best available.");
                fontToUse = findBestAvailableOrDefaultFont(sizeToUse);
                familyToUse = fontToUse.getFamily();
                // 如果自动选择的与配置不同，更新配置
                props.setValue(FONT_NAME_PROPERTY_KEY, familyToUse);
                // props.setValue(FONT_SIZE_PROPERTY_KEY, String.valueOf(sizeToUse)); //大小通常不变
                LOG.info("Updated configuration to best available font: " + familyToUse + ", Size: " + sizeToUse);
            }
        } else {
            LOG.info("No font configured. Finding best available.");
            fontToUse = findBestAvailableOrDefaultFont(sizeToUse);
            familyToUse = fontToUse.getFamily();
            // 保存首次自动选择的字体
            props.setValue(FONT_NAME_PROPERTY_KEY, familyToUse);
            props.setValue(FONT_SIZE_PROPERTY_KEY, String.valueOf(sizeToUse)); // 保存大小
            LOG.info("Automatically selected and saved font: " + familyToUse + ", Size: " + sizeToUse);
        }

        // 更新缓存
        currentConfiguredFont = fontToUse;
        cachedFontFamily = familyToUse;
        cachedFontSize = sizeToUse;

        return currentConfiguredFont;
    }

    /**
     * 保存用户选择的字体配置。
     * @param fontFamily 字体家族名称。
     * @param fontSize   字体大小。
     */
    public static synchronized void saveFontConfiguration(String fontFamily, int fontSize) {
        if (fontFamily == null || fontFamily.isEmpty()) {
            LOG.warn("Attempted to save null or empty font family. Ignoring.");
            return;
        }
        PropertiesComponent props = PropertiesComponent.getInstance();
        props.setValue(FONT_NAME_PROPERTY_KEY, fontFamily);
        props.setValue(FONT_SIZE_PROPERTY_KEY, String.valueOf(fontSize));

        // 清除缓存，以便下次 getConfiguredFont() 重新加载
        cachedFontFamily = null; // Or set to new values
        cachedFontSize = -1;
        currentConfiguredFont = null;
        LOG.info("Saved terminal font configuration: Family='" + fontFamily + "', Size=" + fontSize);
    }

    /**
     * 获取系统中所有可用的字体家族名称列表。
     * @return 字体家族名称列表，已排序。
     */
    public static List<String> getAvailableFontFamilies() {
        return Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
                .distinct() // 确保唯一性
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    /**
     * 查找最佳可用字体，如果找不到任何偏好字体，则返回系统默认等宽字体。
     * @param targetSize 期望的字体大小。
     * @return {@link Font} 对象。
     */
    private static Font findBestAvailableOrDefaultFont(int targetSize) {
        List<String> availableFamilies = getAvailableFontFamilies(); // 获取一次即可

        // 1. 尝试 CJK 优选字体
        for (String preferredFamily : PREFERRED_FONTS_CJK_MONO) {
            if (isFontFamilyActuallyAvailable(preferredFamily, availableFamilies)) {
                Font font = new Font(preferredFamily, Font.PLAIN, targetSize);
                // 进一步验证是否真的是等宽且能显示中文（简单的测试）
                if (isMonospaced(font) && canDisplayChinese(font)) {
                    LOG.info("Found preferred CJK monospaced font: " + preferredFamily);
                    return font;
                }
            }
        }

        // 2. 尝试英文优选字体 (中文将依赖系统回退)
        for (String preferredFamily : PREFERRED_FONTS_ENG_MONO) {
            if (isFontFamilyActuallyAvailable(preferredFamily, availableFamilies)) {
                Font font = new Font(preferredFamily, Font.PLAIN, targetSize);
                if (isMonospaced(font)) { // 主要保证等宽
                    LOG.info("Found preferred English monospaced font: " + preferredFamily);
                    return font;
                }
            }
        }

        // 3. 如果都找不到，使用 Java 逻辑等宽字体 "Monospaced"
        LOG.warn("No preferred fonts found. Falling back to Java's logical '" + DEFAULT_MONOSPACED_LOGICAL_FONT + "' font.");
        return new Font(DEFAULT_MONOSPACED_LOGICAL_FONT, Font.PLAIN, targetSize);
    }

    /**
     * 检查指定的字体家族名称是否真的在系统中可用，并且不是回退到 "Dialog"。
     */
    private static boolean isFontFamilyActuallyAvailable(String fontFamilyName, List<String> allAvailableFamilies) {
        if (!allAvailableFamilies.contains(fontFamilyName)) { // 先快速检查列表
            // LOG.trace("Font family '" + fontFamilyName + "' not in direct list of available families.");
            return false;
        }
        // 再次尝试创建，确保它没有回退到Dialog (除非请求的就是Dialog)
        try {
            Font testFont = new Font(fontFamilyName, Font.PLAIN, 12);
            boolean available = !testFont.getFamily().equalsIgnoreCase("Dialog") || fontFamilyName.equalsIgnoreCase("Dialog");
            // if (!available) LOG.trace("Font family '" + fontFamilyName + "' resolved to Dialog.");
            return available;
        } catch (Exception e) {
            // LOG.trace("Exception when trying to create font '" + fontFamilyName + "': " + e.getMessage());
            return false; // 创建失败
        }
    }

    /**
     * 简单判断字体是否为等宽。
     * 注意：这不是100%精确的方法，但对多数情况有效。
     */
    public static boolean isLikelyMonospaced(Font font, FontRenderContext frc) {
        if (font == null) return false;

        // 优先相信字体名称中明确包含 "Mono" 或 "Monospace"
        String lowerFamily = font.getFamily().toLowerCase(Locale.ROOT);
        String lowerName = font.getName().toLowerCase(Locale.ROOT);
        if (lowerFamily.contains("mono") || lowerFamily.contains("monospace") ||
                lowerName.contains("mono") || lowerName.contains("monospace")) {
            // 对于名称中含 Mono 的字体，我们给予更高的信任度，但仍可进行抽样检查
            // 可以稍微放宽下面的宽度比较容差，或直接返回 true
             return true; // 如果想完全信任名称
        }

        // 测试字符集
        String[] testChars = {"i", "l", "m", "W", "0", "O", "@"}; // 英文/符号

        double firstCharWidth = -1;

        // 检查英文/符号字符的等宽性
        for (String s : testChars) {
            if (!font.canDisplay(s.charAt(0))) continue; // 如果不能显示，跳过
            GlyphVector gv = font.createGlyphVector(frc, s);
            double width = gv.getVisualBounds().getWidth();

            if (firstCharWidth == -1) {
                firstCharWidth = width;
            } else {
                // 允许非常小的误差 (例如 0.1 像素，具体值可能需要调整)
                if (Math.abs(firstCharWidth - width) > 0.1) {
                    // LOG.trace("Font " + font.getFontName() + " is not monospaced (char '" + s + "' width " + width + " vs first " + firstCharWidth + ")");
                    return false;
                }
            }
        }

        return firstCharWidth != -1; // 所有测试通过
    }

    /**
     * 获取系统中所有可用的、可能是等宽的字体家族名称列表。
     * @return 可能是等宽的字体家族名称列表，已排序。
     */
    public static List<String> getAvailableMonospacedFontFamilies() {
        Set<String> monoFamilies = new HashSet<>(); // 使用 Set 避免重复家族名
        Font[] allFonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts();
        FontRenderContext frc = new FontRenderContext(null, true, true); // Antialiasing on, FractionalMetrics on

        for (Font font : allFonts) {
            // 只处理PLAIN样式的字体进行判断，避免Bold/Italic等影响宽度判断的准确性
            if (font.getStyle() == Font.PLAIN) {
                if (isLikelyMonospaced(font, frc)) {
                    monoFamilies.add(font.getFamily());
                }
            }
        }
        List<String> sortedMonoFamilies = new ArrayList<>(monoFamilies);
        sortedMonoFamilies.sort(String.CASE_INSENSITIVE_ORDER);
        // LOG.info("Found " + sortedMonoFamilies.size() + " likely monospaced font families.");
        return sortedMonoFamilies;
    }
    /**
     * 简单判断字体是否能显示一些基本的中文字符。
     */
    public static boolean canDisplayChinese(Font font) {
        if (font == null) return false;
        // 测试一些常见的中文字符
        return font.canDisplay('你') && font.canDisplay('好') && font.canDisplay('世') && font.canDisplay('界');
    }
}