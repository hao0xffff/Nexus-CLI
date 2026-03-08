package com.aiterminal.terminal.profile;

import com.aiterminal.util.SystemInspector;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class TerminalProfile {
    SystemInspector.OSType osType;
    SystemInspector.ShellType shellType;
    String osName;
    String osVersion;
    String shellPath;
    String shellVersion;
    String encoding;
    String username;
    String homeDirectory;
    String workingDirectory;
    String desktopDirectory;
}
