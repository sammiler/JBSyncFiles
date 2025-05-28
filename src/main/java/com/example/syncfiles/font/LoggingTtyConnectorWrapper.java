package com.example.syncfiles.font; // 请替换为你的实际包名

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TtyConnector;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Dimension; // 尽管resize(Dimension)已废弃，但接口仍有它
import java.io.IOException;

public class LoggingTtyConnectorWrapper implements TtyConnector {
    private static final Logger LOG = LoggerFactory.getLogger(LoggingTtyConnectorWrapper.class);
    private final TtyConnector delegate;

    public LoggingTtyConnectorWrapper(@NotNull TtyConnector delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("Delegate TtyConnector cannot be null");
        }
        this.delegate = delegate;
        LOG.info("LoggingTtyConnectorWrapper initialized, wrapping: " + delegate.getClass().getName());
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        int charsRead = delegate.read(buffer, offset, length); // 从原始 TtyConnector 读取

        if (charsRead > 0) {
            // --- BEGIN HACK: Attempt to fix suspected broken ESC characters ---
            for (int i = 0; i < charsRead; i++) {
                int currentIndexInOriginalBuffer = offset + i;
                if (buffer[currentIndexInOriginalBuffer] == '?') {
                    // 检查下一个字符，如果存在并且是 '[' 或 'c'，则认为这个 '?' 应该是 ESC
                    if (i + 1 < charsRead) {
                        char nextChar = buffer[currentIndexInOriginalBuffer + 1];
                        if (nextChar == '[' || nextChar == 'c' || nextChar == ']') { // 添加 ']' 作为另一个可能的 ESC 后继 (例如 OSC)
                            LOG.warn("LTCW.read(): HACK! Detected '?' followed by '" + nextChar + "'. Replacing '?' with <ESC> at buffer index " + currentIndexInOriginalBuffer);
                            buffer[currentIndexInOriginalBuffer] = '\u001B'; // 将 '?' 修正为 ESC
                        }
                    }
                }
            }
            // --- END HACK ---

            // --- 日志记录 (基于可能已被修正的 buffer) ---
            String chunk = new String(buffer, offset, charsRead);
            LOG.info("LTCW.read(): Read " + charsRead + " chars. Chunk (after hack): [" + escapeString(chunk) + "]");

            for (int i = 0; i < charsRead; i++) {
                char ch = buffer[offset + i]; // 读取 (可能已被修正的) 字符
                if (ch == '\u001B' || ch == '?') { // 只打印 ESC 和 ? 的详细信息
                    LOG.info("  LTCW.read() detailed char (after hack): " + (ch == '\u001B' ? "<ESC>" : "'?'") + " (0x" + Integer.toHexString(ch).toUpperCase() + ")");
                }
            }
            // --- 日志记录结束 ---

        } else if (charsRead == -1) {
            LOG.info("LTCW.read(): End of stream reached (-1).");
        }
        return charsRead;
    }

    // --- 其他所有 TtyConnector 方法都委托给 delegate (保持不变) ---
    @Override public void write(byte[] bytes) throws IOException { delegate.write(bytes); }
    @Override public void write(String string) throws IOException { delegate.write(string); }
    @Override public boolean isConnected() { return delegate.isConnected(); }
    @Override public int waitFor() throws InterruptedException { return delegate.waitFor(); }
    @Override public boolean ready() throws IOException { return delegate.ready(); }
    @Override public String getName() { return delegate.getName(); }
    @Override public void close() { LOG.info("LTCW.close() called."); delegate.close(); }
    @Override public void resize(@NotNull TermSize termSize) { delegate.resize(termSize); }

    /** @deprecated */
    @Deprecated
    @Override
    public void resize(@NotNull Dimension termWinSize) { delegate.resize(termWinSize); }

    /** @deprecated */
    @Deprecated
    @Override
    public void resize(Dimension termWinSize, Dimension pixelSize) { delegate.resize(termWinSize, pixelSize); }



    private String escapeString(String s) {
        if (s == null) return "null";
        return s.replace("\u001B", "<ESC>")
                .replace("\n", "<LF>")
                .replace("\r", "<CR>")
                .replace("\t", "<TAB>");
    }
}