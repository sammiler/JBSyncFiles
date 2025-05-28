package com.example.syncfiles.font; // 请替换为你的实际包名

import com.jediterm.terminal.TerminalDataStream;
import org.jetbrains.annotations.NotNull; // 如果你的项目使用 IntelliJ 注解
import org.slf4j.Logger; // 或者你选择的日志框架
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays; // 用于数组复制

public class LoggingTerminalDataStream implements TerminalDataStream {
    private static final Logger LOG = LoggerFactory.getLogger(LoggingTerminalDataStream.class);
    private final TerminalDataStream delegate; // 真正的 (原始的) TerminalDataStream 实例

    public LoggingTerminalDataStream(@NotNull TerminalDataStream delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("Delegate TerminalDataStream cannot be null");
        }
        this.delegate = delegate;
        LOG.info("LoggingTerminalDataStream initialized, wrapping: " + delegate.getClass().getName());
    }

    @Override
    public char getChar() throws IOException {
        char ch = delegate.getChar(); // 从原始流获取字符
        // 记录获取到的字符
        if (ch == '\u001B') { // ESCAPE character
            LOG.info("LTS.getChar(): <ESC> (0x" + Integer.toHexString(ch).toUpperCase() + ")");
        } else if (ch == '?') { // 特别关注问号
            LOG.info("LTS.getChar(): '?' (0x" + Integer.toHexString(ch).toUpperCase() + ")");
        } else if (ch < 32 || ch == 127) { // 其他一些控制字符
            LOG.info(String.format("LTS.getChar(): CTRL (0x%02X)", (int) ch));
        } else {
            // LOG.info("LTS.getChar(): '" + ch + "' (0x" + Integer.toHexString(ch).toUpperCase() + ")"); // 可能会产生大量日志
        }
        return ch;
    }

    @Override
    public void pushChar(char c) throws IOException {
        // 记录被推回的字符
        if (c == '\u001B') {
            LOG.info("LTS.pushChar(): <ESC> (0x" + Integer.toHexString(c).toUpperCase() + ")");
        } else {
            // LOG.info("LTS.pushChar(): '" + c + "' (0x" + Integer.toHexString(c).toUpperCase() + ")");
        }
        delegate.pushChar(c); // 委托给原始流
    }

    @Override
    public String readNonControlCharacters(int maxChars) throws IOException {
        String result = delegate.readNonControlCharacters(maxChars); // 从原始流读取
        if (result != null && !result.isEmpty()) {
            // LOG.info("LTS.readNonControlCharacters(" + maxChars + "): [" + escapeString(result) + "]");
        }
        return result;
    }

    @Override
    public void pushBackBuffer(char[] chars, int len) throws IOException {
        if (chars != null && len > 0) {
            // LOG.info("LTS.pushBackBuffer(): len=" + len + ", chars=[" + escapeString(new String(chars, 0, len)) + "]");
        }
        delegate.pushBackBuffer(chars, len); // 委托给原始流
    }

    @Override
    public boolean isEmpty() {
        boolean empty = delegate.isEmpty(); // 从原始流获取状态
        // LOG.info("LTS.isEmpty(): " + empty); // 这个可能会非常频繁
        return empty;
    }

    // 辅助方法，用于转义字符串以便日志输出 (如果需要)
    private String escapeString(String s) {
        if (s == null) return "null";
        return s.replace("\u001B", "<ESC>")
                .replace("\n", "<LF>")
                .replace("\r", "<CR>")
                .replace("\t", "<TAB>");
    }
}