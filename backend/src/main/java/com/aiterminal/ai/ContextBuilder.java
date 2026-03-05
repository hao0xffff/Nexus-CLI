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

    private static final String SYSTEM_PROMPT_TEMPLATE = """
        You are an AI assistant integrated into a terminal application. You help users with command-line tasks, scripting, and system administration.
        
        ## Current System Information
        %s
        
        ## Your Capabilities
        1. Suggest and explain terminal commands
        2. Help debug command outputs and errors
        3. Assist with scripting (bash, PowerShell, etc.)
        4. Provide system administration guidance
        5. Explain technical concepts
        
        ## Response Format
        When suggesting commands, use this JSON format:
        `command:{"cmd":"<command>", "desc":"<brief description>"}`
        
        This format will render as a clickable button in the UI that users can click to execute.
        
        ## Guidelines
        - Always consider the current OS and shell type when suggesting commands
        - Provide cross-platform alternatives when relevant
        - Warn about potentially dangerous commands
        - Explain what commands do before suggesting them
        - Be concise but thorough
        
        ## Safety Rules
        - NEVER suggest commands that could cause data loss without explicit warning
        - NEVER suggest commands like `rm -rf /`, `format`, `mkfs` without strong warnings
        - Always explain the impact of destructive commands
        """;

    private static final String USER_CONTEXT_TEMPLATE = """
        
        ## Recent Terminal Output (last %d lines)
        ```
        %s
        ```
        
        ## User Query
        %s
        """;

    /**
     * Build the system prompt with current system information.
     */
    public String buildSystemPrompt() {
        String systemContext = systemInspector.buildSystemContext();
        return String.format(SYSTEM_PROMPT_TEMPLATE, systemContext);
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
        
        return String.format(USER_CONTEXT_TEMPLATE, recentLines, terminalOutput, userQuery);
    }

    /**
     * Build user message with provided terminal output.
     */
    public String buildUserMessage(String userQuery, String terminalOutput, int recentLines) {
        if (terminalOutput == null || terminalOutput.isEmpty()) {
            return userQuery;
        }
        
        String cleanOutput = stripAnsiCodes(terminalOutput);
        cleanOutput = truncateIfTooLong(cleanOutput, 4000);
        
        return String.format(USER_CONTEXT_TEMPLATE, recentLines, cleanOutput, userQuery);
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
