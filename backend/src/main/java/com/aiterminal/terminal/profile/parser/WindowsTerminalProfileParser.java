package com.aiterminal.terminal.profile.parser;

import com.aiterminal.terminal.profile.TerminalProfile;
import com.aiterminal.terminal.profile.TerminalProfileParser;
import com.aiterminal.util.SystemInspector;
import org.springframework.stereotype.Component;

@Component
public class WindowsTerminalProfileParser implements TerminalProfileParser {
    @Override
    public boolean supports(SystemInspector.OSType osType) {
        return osType == SystemInspector.OSType.WINDOWS;
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
        return "Windows PowerShell: dir/ls, cd, Get-Content, Set-Content, Get-Process. Avoid grep, chmod, sudo, /dev/null.";
    }

    @Override
    public String formatReActCommandGuide(TerminalProfile profile) {
        return String.format("""
            ## Windows PowerShell Command Reference
            
            ### IMPORTANT PATHS:
            - Desktop: %s
            - Home: %s
            
            ### ONE COMMAND PER STEP:
            
            | Task | Command |
            |------|---------|
            | Go to desktop | cd "%s" |
            | Create folder | mkdir "foldername" |
            | Create file with content | Set-Content -Path "file.html" -Value "content" |
            | Create multi-line file | [System.IO.File]::WriteAllText("file.html", "line1`nline2") |
            | List files | dir |
            | Rename file | Rename-Item "old.txt" "new.txt" |
            | Delete file | Remove-Item "file.txt" |
            
            ### FORBIDDEN:
            - NEVER use && or || to chain commands
            - NEVER use > or >> redirection
            - NEVER use Unix commands (grep, chmod, cat, touch)
            - NEVER combine multiple commands in one ACTION_INPUT
            """, profile.getDesktopDirectory(), profile.getHomeDirectory(), profile.getDesktopDirectory());
    }
}
