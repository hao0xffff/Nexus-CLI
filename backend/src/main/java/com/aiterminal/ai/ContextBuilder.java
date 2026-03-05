package com.aiterminal.ai;

import com.aiterminal.terminal.ITerminalSession;
import com.aiterminal.util.SystemInspector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Builds AI context from system information and terminal output.
 * Assembles system fingerprint + recent terminal output for AI prompts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextBuilder {

    private final SystemInspector systemInspector;

    // Pattern to strip ANSI escape sequences
    private static final Pattern ANSI_PATTERN = Pattern.compile(
            "\\x1B(?:[@-Z\\\\-_]|\\[[0-?]*[ -/]*[@-~])"
    );

    // Compact system prompt for faster responses
    private static final String SYSTEM_PROMPT_TEMPLATE = """
        You are a terminal assistant. Be concise and direct.
        
        System: %s
        
        %s
        
        Command format (when suggesting commands):
        ```command
        {"cmd": "command", "desc": "description"}
        ```
        
        Rules: Use commands for the current OS only. Be brief. Warn about destructive commands.
        """;

    private static final String USER_CONTEXT_TEMPLATE = """
        Terminal output:
        %s
        
        Query: %s
        """;

    /**
     * Build the system prompt with current system information.
     */
    public String buildSystemPrompt() {
        String systemContext = systemInspector.buildSystemContext();
        String platformGuide = buildPlatformGuide();
        return String.format(SYSTEM_PROMPT_TEMPLATE, systemContext, platformGuide);
    }

    /**
     * Build compact platform-specific command guide.
     */
    private String buildPlatformGuide() {
        if (systemInspector.isWindows()) {
            return "PowerShell: dir/ls, cd, type/cat, Get-Process. Avoid: /dev/null, grep, chmod, sudo.";
        } else if (systemInspector.getOsType() == SystemInspector.OSType.MACOS) {
            return "macOS: ls, cd, cat, grep, ps, kill.";
        } else {
            return "Linux: ls, cd, cat, grep, ps, kill.";
        }
    }

    /**
     * Build user message with terminal context.
     */
    public String buildUserMessage(String userQuery, ITerminalSession session, int recentLines) {
        String terminalOutput = "";
        
        if (session != null && session.isActive()) {
            terminalOutput = session.getRecentOutput(recentLines);
            terminalOutput = stripAnsiCodes(terminalOutput);
            terminalOutput = truncateIfTooLong(terminalOutput, 4000);
        }
        
        if (terminalOutput.isEmpty()) {
            return userQuery;
        }
        
        return String.format(USER_CONTEXT_TEMPLATE, terminalOutput, userQuery);
    }

    /**
     * Build user message with provided terminal output.
     */
    public String buildUserMessage(String userQuery, String terminalOutput, int recentLines) {
        if (terminalOutput == null || terminalOutput.isEmpty()) {
            return userQuery;
        }
        
        String cleanOutput = stripAnsiCodes(terminalOutput);
        // Limit to 2000 chars for faster processing
        cleanOutput = truncateIfTooLong(cleanOutput, 2000);
        
        return String.format(USER_CONTEXT_TEMPLATE, cleanOutput, userQuery);
    }

    /**
     * Strip ANSI escape codes from text.
     */
    public String stripAnsiCodes(String text) {
        if (text == null) {
            return "";
        }
        return ANSI_PATTERN.matcher(text).replaceAll("");
    }

    /**
     * Truncate text if it exceeds maximum length.
     */
    private String truncateIfTooLong(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        
        // Keep the most recent content (end of string)
        int startIndex = text.length() - maxLength;
        return "...(truncated)...\n" + text.substring(startIndex);
    }

    /**
     * Build a minimal context for quick queries.
     */
    public String buildMinimalContext(String userQuery) {
        return String.format("""
            System: %s %s, Shell: %s
            Query: %s
            """,
            systemInspector.getOsType(),
            systemInspector.getOsVersion(),
            systemInspector.getShellType(),
            userQuery
        );
    }
}
