package com.aiterminal.ai.react;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single step in the ReAct execution loop.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReActStep {
    
    private int stepNumber;
    private String thought;
    private String action;
    private String actionInput;
    private String output;
    private ReActStatus status;
    private long timestamp;

    public static ReActStep started(String task) {
        return ReActStep.builder()
                .stepNumber(0)
                .thought("Starting task: " + task)
                .action("START")
                .actionInput(task)
                .status(ReActStatus.STARTED)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static ReActStep error(String message) {
        return ReActStep.builder()
                .stepNumber(-1)
                .thought(message)
                .action("ERROR")
                .actionInput(message)
                .output(message)
                .status(ReActStatus.ERROR)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static ReActStep cancelled(String message) {
        return ReActStep.builder()
                .stepNumber(-1)
                .thought(message)
                .action("CANCEL")
                .actionInput(message)
                .output(message)
                .status(ReActStatus.CANCELLED)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
