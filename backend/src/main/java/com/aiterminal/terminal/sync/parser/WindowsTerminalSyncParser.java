package com.aiterminal.terminal.sync.parser;

import com.aiterminal.util.SystemInspector;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class WindowsTerminalSyncParser implements TerminalSyncParser {
    private static final Pattern PROMPT_LINE_PATTERN = Pattern.compile("^PS\\s+[A-Za-z]:\\\\.*>\\s*");
    private static final Pattern PROMPT_END_PATTERN = Pattern.compile("(?s).*PS [A-Za-z]:\\\\[^>]*>\\s*$");

    @Override
    public boolean supports(SystemInspector.OSType osType) {
        return osType == SystemInspector.OSType.WINDOWS;
    }

    @Override
    public String buildCommandWithMarker(String command, String marker) {
        if (marker == null || marker.isBlank()) {
            return command + "\r";
        }
        return command + "; " +
                "$nexusExit = if ($?) { 0 } elseif ($LASTEXITCODE -ne $null) { $LASTEXITCODE } else { 1 }; " +
                "[Console]::Out.Write(\"`e]9;" + marker + ":$nexusExit`a\")\r";
    }

    @Override
    public boolean isPromptAtEnd(String outputTail) {
        return PROMPT_END_PATTERN.matcher(outputTail).matches();
    }

    @Override
    public boolean shouldStripPromptLine(String line) {
        return PROMPT_LINE_PATTERN.matcher(line).find();
    }
}
