package com.aiterminal.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Chat request DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    
    @NotBlank(message = "Message is required")
    private String message;
    
    private String provider;  // "ollama" or "openai"
    
    private String terminalOutput;  // Recent terminal output for context
    
    private int recentLines;  // Number of recent lines to include
    
    private List<ChatMessage> history;  // Conversation history
    
    private String sessionId;  // Terminal session ID (optional)
}
