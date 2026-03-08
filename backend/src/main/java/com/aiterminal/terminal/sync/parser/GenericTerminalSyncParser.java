package com.aiterminal.terminal.sync.parser;

import com.aiterminal.util.SystemInspector;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class GenericTerminalSyncParser implements TerminalSyncParser {
    private static final Pattern PROMPT_END_PATTERN = Pattern.compile("(?s).*[>$#%]\\s*$");

    @Override
    public boolean supports(SystemInspector.OSType osType) {
        return osType == SystemInspector.OSType.UNKNOWN;
    }

    @Override
    public String buildCommandWithMarker(String command, String marker) {
        if (marker == null || marker.isBlank()) {
            return command + "\n";
        }
        return command + "; __nexus_exit=$?; printf '\\033]9;" + marker + ":%s\\a' \"$__nexus_exit\"\n";
    }

    @Override
    public boolean isPromptAtEnd(String outputTail) {
        return PROMPT_END_PATTERN.matcher(outputTail).matches();
    }

    @Override
    public boolean shouldStripPromptLine(String line) {
        return false;
    }
}
