package com.aiterminal.terminal.sync.parser;

import com.aiterminal.util.SystemInspector;

public interface TerminalSyncParser {
    boolean supports(SystemInspector.OSType osType);
    String buildCommandWithMarker(String command, String marker);
    boolean isPromptAtEnd(String outputTail);
    boolean shouldStripPromptLine(String line);
}
