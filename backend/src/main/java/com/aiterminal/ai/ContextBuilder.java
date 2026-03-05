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
        
        ## CRITICAL: Current System Information
        %s
        
        ## VERY IMPORTANT: Platform-Specific Commands
        You MUST provide commands that work on the CURRENT SYSTEM shown above.
        
        %s
        
        ## Your Capabilities
        1. Suggest and explain terminal commands for the current platform
        2. Help debug command outputs and errors
        3. Assist with scripting
        4. Provide system administration guidance
        5. Explain technical concepts
        
        ## IMPORTANT: Command Output Format
        When you want to suggest an executable command, you MUST use this exact JSON format:
        ```command
        {"cmd": "your-command-here", "desc": "Brief description of what this command does"}
        ```
        
        The command MUST be valid for the current system. Do NOT mix Unix and Windows commands.
        
        ## Guidelines
        - ALWAYS use commands appropriate for the current OS and shell
        - Keep commands simple and directly executable
        - Explain what commands do before suggesting them
        - Be concise but thorough
        
        ## Safety Rules
        - NEVER suggest commands that could cause data loss without explicit warning
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
        String platformGuide = buildPlatformGuide();
        return String.format(SYSTEM_PROMPT_TEMPLATE, systemContext, platformGuide);
    }

    /**
     * Build platform-specific command guide.
     */
    private String buildPlatformGuide() {
        if (systemInspector.isWindows()) {
            return """
                ### Windows/PowerShell Command Examples:
                - List files: `dir` or `Get-ChildItem` or `ls` (PowerShell alias)
                - Current directory: `pwd` or `Get-Location`
                - Change directory: `cd <path>`
                - Show file content: `type <file>` or `Get-Content <file>` or `cat <file>`
                - Find text: `Select-String -Pattern "text" -Path <file>`
                - Process list: `Get-Process` or `tasklist`
                - Kill process: `Stop-Process -Name <name>` or `taskkill /IM <name>.exe /F`
                - Environment variable: `$env:VARNAME` or `echo $env:PATH`
                - Network info: `ipconfig` or `Get-NetIPAddress`
                - Disk space: `Get-PSDrive` or `wmic logicaldisk get size,freespace,caption`
                
                ### DO NOT USE on Windows:
                - `/dev/null` (use `$null` or `Out-Null` instead)
                - `grep` (use `Select-String` instead)
                - `chmod`, `chown` (Windows uses icacls)
                - Unix paths like `/usr/bin`
                - `sudo` (use elevated PowerShell instead)
                - `pshell` (not a valid command, use `powershell` or `pwsh`)
                """;
        } else if (systemInspector.getOsType() == SystemInspector.OSType.MACOS) {
            return """
                ### macOS/Bash/Zsh Command Examples:
                - List files: `ls -la`
                - Current directory: `pwd`
                - Show file content: `cat <file>`
                - Find text: `grep "pattern" <file>`
                - Process list: `ps aux`
                - Kill process: `kill -9 <pid>` or `pkill <name>`
                - Environment variable: `echo $VARNAME`
                - Network info: `ifconfig` or `networksetup -listallhardwareports`
                - Disk space: `df -h`
                """;
        } else {
            return """
                ### Linux/Bash Command Examples:
                - List files: `ls -la`
                - Current directory: `pwd`
                - Show file content: `cat <file>`
                - Find text: `grep "pattern" <file>`
                - Process list: `ps aux`
                - Kill process: `kill -9 <pid>` or `pkill <name>`
                - Environment variable: `echo $VARNAME`
                - Network info: `ip addr` or `ifconfig`
                - Disk space: `df -h`
                """;
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
