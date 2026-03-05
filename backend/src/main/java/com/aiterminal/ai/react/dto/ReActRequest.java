package com.aiterminal.ai.react.dto;

import lombok.Data;

/**
 * Request to start a ReAct task.
 */
@Data
public class ReActRequest {
    private String sessionId;
    private String task;
}
