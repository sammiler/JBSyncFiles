package com.example.syncfiles.font; // 请替换为你的实际包名

import com.example.syncfiles.util.TerminalFontUtil;
import com.intellij.terminal.JBTerminalSystemSettingsProviderBase; // 确保这个 import 正确
import java.awt.Font;

public class TerminalFontSettingsProvider extends JBTerminalSystemSettingsProviderBase {
    private Font currentFont;
    private float currentFontSize;

    /**
     * 构造函数.
     * @param fontName 你想使用的字体名称 (例如 "等距更纱黑体 SC")
     * @param style Font.PLAIN, Font.BOLD, etc.
     * @param size 字体大小
     */
    public TerminalFontSettingsProvider(String fontName, int style, int size) {

        super(); // 或者 super(); 如果有无参构造
        // 如果父类构造函数有其他参数，你需要相应提供。

        this.currentFont = new Font(fontName, style, size);
        this.currentFontSize = this.currentFont.getSize2D(); // 或者直接用传入的 size

        System.out.println("MyFontOnlySettingsProvider: Initialized. Using custom font: " +
                this.currentFont.getFontName() + " (" + this.currentFont.getFamily() + ")" +
                ", Size: " + this.currentFontSize +
                ". Other settings will be inherited from JBTerminalSystemSettingsProviderBase.");
    }


    @Override
    public Font getTerminalFont() {
        // 返回我们自定义的字体
        // 确保字体大小与获取到的字体一致
        // 总是从工具类获取最新的配置字体
        this.currentFont = TerminalFontUtil.getConfiguredFont();
        // LOG.debug("TerminalFontSettingsProvider.getTerminalFont() returning: " + this.currentFont.getFontName());
        return this.currentFont;
    }

    @Override
    public float getTerminalFontSize() {

        // 确保字体大小与获取到的字体一致
        if (this.currentFont == null ||
                !this.currentFont.getFamily().equals(TerminalFontUtil.getConfiguredFont().getFamily()) ||
                this.currentFontSize != TerminalFontUtil.getConfiguredFont().getSize2D()) {
            // 如果 currentFont 不是最新的，或者大小不匹配，重新获取
            this.currentFont = TerminalFontUtil.getConfiguredFont();
            this.currentFontSize = this.currentFont.getSize2D();
        }
        // LOG.debug("TerminalFontSettingsProvider.getTerminalFontSize() returning: " + this.currentFontSize);
        return this.currentFontSize;
    }

}