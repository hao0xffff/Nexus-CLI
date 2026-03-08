package com.aiterminal.terminal.profile.parser;

import com.aiterminal.terminal.profile.TerminalProfile;
import com.aiterminal.terminal.profile.TerminalProfileParser;
import com.aiterminal.util.SystemInspector;
import org.springframework.stereotype.Component;

@Component
public class MacOSTerminalProfileParser implements TerminalProfileParser {
    @Override
    public boolean supports(SystemInspector.OSType osType) {
        return osType == SystemInspector.OSType.MACOS;
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
            - Desktop: %s
            """,
            profile.getOsName(), profile.getOsVersion(), profile.getOsType(),
            profile.getShellType(), profile.getShellPath(),
            profile.getWorkingDirectory(),
            profile.getUsername(),
            profile.getEncoding(),
            profile.getDesktopDirectory()
        );
    }

    @Override
    public String formatCompactCommandGuide(TerminalProfile profile) {
        return "macOS: ls, cd, cat, grep, ps, kill, find, chmod, open.";
    }

    @Override
    public String formatReActCommandGuide(TerminalProfile profile) {
        return String.format("""
            ## macOS Terminal Command Reference
            
            ### IMPORTANT PATHS:
            - Desktop: %s
            - Home: %s
            
            ### ONE COMMAND PER STEP:
            - Go to desktop: cd "%s"
            - Create folder: mkdir foldername
            - Create file: touch file.txt
            - Write file: printf 'content' > file.txt
            - List files: ls -la
            - Rename file: mv old.txt new.txt
            - Delete file: rm file.txt
            - Delete folder: rm -r folder
            """, profile.getDesktopDirectory(), profile.getHomeDirectory(), profile.getDesktopDirectory());
    }
}
