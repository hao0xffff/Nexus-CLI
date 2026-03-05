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
            
            ## Rules
            1. Execute ONE command at a time, then wait for output
            2. Always THINK before acting - explain your reasoning
            3. Use OBSERVE after EXECUTE to check results
            4. If a command fails, analyze the error and try a different approach
            5. Never run dangerous commands (rm -rf /, format, etc.)
            6. Use platform-appropriate commands for this system
            7. Keep commands simple and safe
            8. Maximum 10 steps per task to prevent infinite loops
            
            ## Example
            
            User: Create a folder called "test" and create a file inside it
            
            THOUGHT: I need to first create the "test" folder, then create a file inside it. I'll start by creating the folder.
            ACTION: EXECUTE
            ACTION_INPUT: mkdir test
            
            [After receiving output]
            
            THOUGHT: The folder was created successfully. Now I need to create a file inside it.
            ACTION: EXECUTE
            ACTION_INPUT: echo "Hello World" > test/hello.txt
            
            [After receiving output]
            
            THOUGHT: Both the folder and file have been created successfully.
            ACTION: COMPLETE
            ACTION_INPUT: Created folder "test" and file "hello.txt" with content "Hello World"
            """, osInfo, platformGuide);
    }

    /**
     * Build platform-specific command examples.
     */
    private String buildPlatformGuide() {
        if (systemInspector.isWindows()) {
            return """
                Windows/PowerShell commands:
                - Create folder: mkdir <name> or New-Item -ItemType Directory -Name <name>
                - Create file: New-Item -ItemType File -Name <name> or echo "content" > file.txt
                - List files: dir or Get-ChildItem or ls
                - Read file: type <file> or Get-Content <file>
                - Delete file: del <file> or Remove-Item <file>
                - Delete folder: rmdir <folder> or Remove-Item -Recurse <folder>
                - Current path: pwd or Get-Location
                - Change directory: cd <path>
                - Find files: Get-ChildItem -Recurse -Filter "*.txt"
                - Process list: Get-Process or tasklist
                - Environment var: $env:VARNAME
                
                AVOID: /dev/null, grep, chmod, sudo, bash-specific syntax
                """;
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
        String status = success ? "Command executed successfully" : "Command failed or produced no output";
        
        // Ensure output is not null or empty
        String output = (commandOutput == null || commandOutput.trim().isEmpty()) 
            ? "(No output captured - command may still have executed successfully. Check the terminal.)"
            : commandOutput;
        
        return String.format("""
            ## Command Execution Result
            
            ### Output:
            ```
            %s
            ```
            
            ### Status: %s
            
            Based on this output, continue with your task. Think about:
            1. Did the command succeed?
            2. What does the output tell you?
            3. What should be the next step?
            
            Respond with THOUGHT, ACTION, and ACTION_INPUT.
            """, output, status);
    }
}
