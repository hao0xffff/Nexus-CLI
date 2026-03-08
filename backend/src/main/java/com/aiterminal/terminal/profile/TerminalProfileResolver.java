package com.aiterminal.terminal.profile;

import com.aiterminal.util.SystemInspector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class TerminalProfileResolver {
    private final SystemInspector systemInspector;
    private final List<TerminalProfileReader> readers;
    private final List<TerminalProfileParser> parsers;

    public TerminalProfile resolveProfile() {
        SystemInspector.OSType osType = systemInspector.getOsType();
        TerminalProfileReader reader = readers.stream()
                .filter(item -> item.supports(osType))
                .findFirst()
                .orElseThrow();
        return reader.read(systemInspector);
    }

    public String buildSystemContext() {
        TerminalProfile profile = resolveProfile();
        return resolveParser(profile.getOsType()).formatSystemContext(profile);
    }

    public String buildCompactCommandGuide() {
        TerminalProfile profile = resolveProfile();
        return resolveParser(profile.getOsType()).formatCompactCommandGuide(profile);
    }

    public String buildReActCommandGuide() {
        TerminalProfile profile = resolveProfile();
        return resolveParser(profile.getOsType()).formatReActCommandGuide(profile);
    }

    private TerminalProfileParser resolveParser(SystemInspector.OSType osType) {
        return parsers.stream()
                .filter(item -> item.supports(osType))
                .findFirst()
                .orElseThrow();
    }
}
