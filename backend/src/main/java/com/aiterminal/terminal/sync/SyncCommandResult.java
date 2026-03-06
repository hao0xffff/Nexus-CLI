package com.aiterminal.terminal.sync;

import lombok.Builder;
import lombok.Getter;

/**
 * Result of a synchronized command execution.
 * Contains both the clean output for LLM and raw output for terminal display.
 */
@Getter
@Builder
public class SyncCommandResult {
    
    /**
     * The command that was executed.
     */
    private final String command;
    
    /**
     * Clean output text suitable for LLM consumption.
     * ANSI codes stripped, encoding normalized.
     */
    private final String cleanOutput;
    
    /**
     * Raw output as displayed in terminal.
     * Contains ANSI codes and original formatting.
     */
    private final String rawOutput;
    
    /**
     * Whether the command completed successfully.
     * Determined by exit markers or timeout.
     */
    private final boolean success;
    
    /**
     * Whether the command timed out.
     */
    private final boolean timedOut;
    
    /**
     * Execution time in milliseconds.
     */
    private final long executionTimeMs;
    
    /**
     * Current working directory after command execution.
     */
    private final String workingDirectory;
    
    /**
     * Get output suitable for display to user.
     */
    public String getDisplayOutput() {
        if (timedOut) {
            return cleanOutput + "\n[Command timed out]";
        }
        return cleanOutput;
    }
    
    /**
     * Get output suitable for LLM context.
     * Includes status information.
     */
    public String getLLMOutput() {
        StringBuilder sb = new StringBuilder();
        sb.append("COMMAND: ").append(command).append("\n");
        sb.append("STATUS: ").append(success ? "SUCCESS" : (timedOut ? "TIMED OUT" : "COMPLETED")).append("\n");
        sb.append("OUTPUT:\n").append(cleanOutput);
        return sb.toString();
    }
}
