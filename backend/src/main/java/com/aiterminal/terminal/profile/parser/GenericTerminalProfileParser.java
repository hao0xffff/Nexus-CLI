package com.aiterminal.terminal.profile.parser;

import com.aiterminal.terminal.profile.TerminalProfile;
import com.aiterminal.terminal.profile.TerminalProfileParser;
import com.aiterminal.util.SystemInspector;
import org.springframework.stereotype.Component;

@Component
public class GenericTerminalProfileParser implements TerminalProfileParser {
    @Override
    public boolean supports(SystemInspector.OSType osType) {
        return osType == SystemInspector.OSType.UNKNOWN;
    }

    @Override
    public String formatSystemContext(TerminalProfile profile) {
        return String.format("""
            System Information:
            - OS: %s %s (%s)
            - Shell: %s (%s)
            - Working Directory: %s
            - User: %s
            - Encoding: %s
            """,
            profile.getOsName(), profile.getOsVersion(), profile.getOsType(),
            profile.getShellType(), profile.getShellPath(),
            profile.getWorkingDirectory(),
            profile.getUsername(),
            profile.getEncoding()
        );
    }

    @Override
    public String formatCompactCommandGuide(TerminalProfile profile) {
        return "Use native commands for current shell only. Prefer one command per step.";
    }

    @Override
    public String formatReActCommandGuide(TerminalProfile profile) {
        return """
            Generic terminal command reference:
            - Create folder: mkdir <name>
            - Create file: touch <name>
            - List files: ls -la or dir
            - Read file: cat <file> or type <file>
            - Delete file: rm <file> or Remove-Item <file>
            """;
    }
}
