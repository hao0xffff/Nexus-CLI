package com.aiterminal.ai.react.dto;

import com.aiterminal.ai.react.ReActStatus;
import lombok.Builder;
import lombok.Data;

/**
 * Response for a single ReAct step (sent via SSE).
 */
@Data
@Builder
public class ReActStepResponse {
    private int stepNumber;
    private String thought;
    private String action;
    private String actionInput;
    private String output;
    private String status;
    private long timestamp;

    public static ReActStepResponse from(com.aiterminal.ai.react.ReActStep step) {
        return ReActStepResponse.builder()
                .stepNumber(step.getStepNumber())
                .thought(step.getThought())
                .action(step.getAction())
                .actionInput(step.getActionInput())
                .output(step.getOutput())
                .status(step.getStatus().name())
                .timestamp(step.getTimestamp() > 0 ? step.getTimestamp() : System.currentTimeMillis())
                .build();
    }
}
