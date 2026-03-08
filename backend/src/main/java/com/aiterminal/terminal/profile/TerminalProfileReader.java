package com.aiterminal.terminal.profile;

import com.aiterminal.util.SystemInspector;

public interface TerminalProfileReader {
    boolean supports(SystemInspector.OSType osType);
    TerminalProfile read(SystemInspector inspector);
}
