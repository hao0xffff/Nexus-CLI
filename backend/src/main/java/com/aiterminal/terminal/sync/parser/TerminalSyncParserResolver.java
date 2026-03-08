package com.aiterminal.terminal.sync.parser;

import com.aiterminal.util.SystemInspector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class TerminalSyncParserResolver {
    private final SystemInspector systemInspector;
    private final List<TerminalSyncParser> parsers;

    public TerminalSyncParser resolve() {
        return parsers.stream()
                .filter(parser -> parser.supports(systemInspector.getOsType()))
                .findFirst()
                .orElseThrow();
    }
}
