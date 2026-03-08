package com.aiterminal.terminal.profile;

import com.aiterminal.util.SystemInspector;

public interface TerminalProfileParser {
    boolean supports(SystemInspector.OSType osType);
    String formatSystemContext(TerminalProfile profile);
    String formatCompactCommandGuide(TerminalProfile profile);
    String formatReActCommandGuide(TerminalProfile profile);
}
