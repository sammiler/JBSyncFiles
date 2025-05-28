package com.example.syncfiles.font; // 请替换为你的实际包名

import com.intellij.openapi.project.Project; // JBTerminalSystemSettingsProviderBase 可能需要 Project
import com.intellij.terminal.JBTerminalSystemSettingsProviderBase; // 确保这个 import 正确
import java.awt.Font;

public class TerminalFontSettingsProvider extends JBTerminalSystemSettingsProviderBase {
    private final Font customFont;
    private final float customFontSize;

    /**
     * 构造函数.
     * @param fontName 你想使用的字体名称 (例如 "等距更纱黑体 SC")
     * @param style Font.PLAIN, Font.BOLD, etc.
     * @param size 字体大小
     */
    public TerminalFontSettingsProvider(String fontName, int style, int size) {

        super(); // 或者 super(); 如果有无参构造
        // 如果父类构造函数有其他参数，你需要相应提供。

        this.customFont = new Font(fontName, style, size);
        this.customFontSize = this.customFont.getSize2D(); // 或者直接用传入的 size

        System.out.println("MyFontOnlySettingsProvider: Initialized. Using custom font: " +
                this.customFont.getFontName() + " (" + this.customFont.getFamily() + ")" +
                ", Size: " + this.customFontSize +
                ". Other settings will be inherited from JBTerminalSystemSettingsProviderBase.");
    }


    @Override
    public Font getTerminalFont() {
        // 返回我们自定义的字体
        // System.out.println("MyFontOnlySettingsProvider.getTerminalFont() CALLED, returning custom font: " + customFont.getName());
        return this.customFont;
    }

    @Override
    public float getTerminalFontSize() {
        // 返回我们自定义的字体大小
        // System.out.println("MyFontOnlySettingsProvider.getTerminalFontSize() CALLED, returning custom size: " + customFontSize);
        return this.customFontSize;
    }

}