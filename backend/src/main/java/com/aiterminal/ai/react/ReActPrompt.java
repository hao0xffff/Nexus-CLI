package com.aiterminal.ai.react;

import com.aiterminal.util.SystemInspector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ReAct (Reasoning + Acting) prompt templates.
 * Enables AI to think step-by-step and execute commands autonomously.
 */
@Component
@RequiredArgsConstructor
public class ReActPrompt {

    private final SystemInspector systemInspector;

    /**
     * Main ReAct system prompt template.
     */
    public String buildSystemPrompt() {
        String osInfo = systemInspector.buildSystemContext();
        String platformGuide = buildPlatformGuide();
        
        return String.format("""
            You are an autonomous AI agent operating in a terminal. You can THINK, PLAN, and ACT to accomplish user tasks.
            
            ## System Information
            %s
            
            ## Platform Commands
            %s
            
            ## ReAct Loop
            For each step, you MUST respond in this EXACT format:
            
            THOUGHT: [Your reasoning about what to do next]
            ACTION: [One of: EXECUTE, OBSERVE, COMPLETE, ERROR]
            ACTION_INPUT: [The command to execute or message to report]
            
            ## Actions Available
            
            1. **EXECUTE** - Run a terminal command
               ACTION: EXECUTE
               ACTION_INPUT: <single command to run>
               
            2. **OBSERVE** - Wait and analyze the output from previous command
               ACTION: OBSERVE
               ACTION_INPUT: <what you're looking for in the output>
               
            3. **COMPLETE** - Task finished successfully
               ACTION: COMPLETE
               ACTION_INPUT: <summary of what was accomplished>
               
            4. **ERROR** - Task cannot be completed
               ACTION: ERROR
               ACTION_INPUT: <explanation of why it failed>
            
            ## STRICT RULES (MUST FOLLOW)
            1. **ONE COMMAND per ACTION_INPUT** - never combine commands
            2. Wait for output after each command before next step
            3. Always THINK before acting
            4. Maximum 30 steps per task
            5. **NEVER use && or || to chain commands**
            6. **NEVER use > or >> redirection** (use Set-Content instead)
            7. **NEVER use Unix/bash commands on Windows**
            8. Use ONLY the commands shown in the Platform Commands section
            9. For multi-line content, use `n escape (not actual newlines)
            
            ## Example (Windows)
            
            User: Create a folder called "test" and create an HTML file inside it
            
            THOUGHT: I need to first create the "test" folder.
            ACTION: EXECUTE
            ACTION_INPUT: mkdir test
            
            [After receiving: COMMAND STATUS: SUCCESS]
            
            THOUGHT: Folder created. Now I'll change to that directory.
            ACTION: EXECUTE
            ACTION_INPUT: cd test
            
            [After receiving: COMMAND STATUS: SUCCESS]
            
            THOUGHT: I'm in the test folder. Now I'll create an HTML file using Set-Content.
            ACTION: EXECUTE
            ACTION_INPUT: Set-Content -Path "hello.html" -Value "<!DOCTYPE html><html><body><h1>Hello</h1></body></html>"
            
            [After receiving: COMMAND STATUS: SUCCESS]
            
            THOUGHT: HTML file created successfully. Task complete.
            ACTION: COMPLETE
            ACTION_INPUT: Created folder "test" and HTML file "hello.html"
            """, osInfo, platformGuide);
    }

    /**
     * Build platform-specific command examples.
     */
    private String buildPlatformGuide() {
        // Get actual desktop path to include in prompt
        String desktopPath = System.getProperty("user.home") + "\\Desktop";
        String homePath = System.getProperty("user.home");
        
        if (systemInspector.isWindows()) {
            return String.format("""
                ## Windows PowerShell Command Reference
                
                ### IMPORTANT PATHS:
                - Desktop: %s
                - Home: %s
                
                ### ONE COMMAND PER STEP - Execute commands ONE AT A TIME:
                
                | Task | Command |
                |------|---------|
                | Go to desktop | cd "%s" |
                | Create folder | mkdir "foldername" |
                | Create file with content | Set-Content -Path "file.html" -Value "content" |
                | Create multi-line file | [System.IO.File]::WriteAllText("file.html", "line1`nline2") |
                | List files | dir |
                | Rename file | Rename-Item "old.txt" "new.txt" |
                | Delete file | Remove-Item "file.txt" |
                
                ### FORBIDDEN - NEVER USE THESE:
                - NEVER use && to chain commands (not valid PowerShell)
                - NEVER use > or >> redirection (use Set-Content instead)
                - NEVER use echo (use Set-Content or Write-Output)
                - NEVER use ren (use Rename-Item)
                - NEVER use Unix commands (grep, chmod, cat, touch)
                - NEVER use here-strings (@" or @')
                - NEVER combine multiple commands in one ACTION_INPUT
                
                ### CORRECT EXAMPLES:
                - To create HTML file: Set-Content -Path "game.html" -Value "<html><body>Game</body></html>"
                - Multi-line HTML: [System.IO.File]::WriteAllText("game.html", "<!DOCTYPE html>`n<html>`n<body>Game</body>`n</html>")
                """, desktopPath, homePath, desktopPath);
        } else {
            return """
                Unix/Linux/macOS commands:
                - Create folder: mkdir <name>
                - Create file: touch <name> or echo "content" > file.txt
                - List files: ls -la
                - Read file: cat <file>
                - Delete file: rm <file>
                - Delete folder: rm -r <folder>
                - Current path: pwd
                - Change directory: cd <path>
                - Find files: find . -name "*.txt"
                - Process list: ps aux
                - Environment var: echo $VARNAME
                """;
        }
    }

    /**
     * Build the initial user message for a ReAct task.
     */
    public String buildTaskMessage(String task, String currentDirectory) {
        return String.format("""
            ## Task
            %s
            
            ## Current Working Directory
            %s
            
            Begin working on this task. Remember to THINK first, then take action.
            """, task, currentDirectory);
    }

    /**
     * Build a continuation message with command output.
     */
    public String buildContinuationMessage(String commandOutput, boolean success) {
        String status = success ? "SUCCESS (exit code 0)" : "FAILED (non-zero exit code)";
        
        // Clean and format output
        String output;
        if (commandOutput == null || commandOutput.trim().isEmpty()) {
            output = "(No output - command executed silently, which is normal for many commands like mkdir, cd, etc.)";
        } else {
            // Clean up any encoding issues or control characters
            output = commandOutput
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")  // Remove control chars except \n, \r, \t
                .trim();
            
            // If output contains error indicators, highlight them
            if (output.contains("不存在") || output.contains("not found") || 
                output.contains("已存在") || output.contains("already exists") ||
                output.contains("ResourceExists") || output.contains("Cannot find")) {
                // These are informational, not necessarily errors
                status = success ? "SUCCESS (with informational message)" : "FAILED";
            }
        }
        
        return String.format("""
            ## Command Execution Result
            
            ### Status: %s
            
            ### Output:
            ```
            %s
            ```
            
            Based on this output, continue with your task. Think about:
            1. Did the command succeed? (Check the Status line above)
            2. What does the output tell you?
            3. What should be the next step?
            
            IMPORTANT: If a folder or file "already exists", that means it's ALREADY CREATED - proceed to the next step!
            
            Respond with THOUGHT, ACTION, and ACTION_INPUT.
            """, status, output);
    }
}
