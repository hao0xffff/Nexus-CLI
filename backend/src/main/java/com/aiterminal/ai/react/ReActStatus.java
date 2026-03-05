package com.aiterminal.ai.react;

/**
 * Status of a ReAct execution step or task.
 */
public enum ReActStatus {
    STARTED,     // Task just started
    RUNNING,     // Step is executing
    COMPLETED,   // Task completed successfully
    ERROR,       // Task failed with error
    BLOCKED,     // Command was blocked for safety
    CANCELLED    // Task was cancelled by user
}
