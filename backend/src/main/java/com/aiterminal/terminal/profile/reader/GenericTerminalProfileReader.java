package com.aiterminal.terminal.profile.reader;

import com.aiterminal.terminal.profile.TerminalProfile;
import com.aiterminal.terminal.profile.TerminalProfileReader;
import com.aiterminal.util.SystemInspector;
import org.springframework.stereotype.Component;

@Component
public class GenericTerminalProfileReader implements TerminalProfileReader {
    @Override
    public boolean supports(SystemInspector.OSType osType) {
        return osType == SystemInspector.OSType.UNKNOWN;
    }

    @Override
    public TerminalProfile read(SystemInspector inspector) {
        return TerminalProfile.builder()
                .osType(inspector.getOsType())
                .shellType(inspector.getShellType())
                .osName(inspector.getOsName())
                .osVersion(inspector.getOsVersion())
                .shellPath(inspector.getShellPath())
                .shellVersion(inspector.getShellVersion())
                .encoding(inspector.getDefaultEncoding())
                .username(inspector.getUsername())
                .homeDirectory(inspector.getHomeDirectory())
                .workingDirectory(inspector.getCurrentWorkingDirectory())
                .desktopDirectory(inspector.getHomeDirectory())
                .build();
    }
}
