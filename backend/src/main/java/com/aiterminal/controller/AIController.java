package com.aiterminal.controller;

import com.aiterminal.ai.AIService;
import com.aiterminal.ai.CommandGuard;
import com.aiterminal.ai.dto.ChatRequest;
import com.aiterminal.ai.dto.ChatResponse;
import com.aiterminal.ai.dto.ValidateCommandRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.Map;

/**
 * REST controller for AI chat and command validation.
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AIController {

    private final AIService aiService;

    /**
     * Send a chat message and receive AI response.
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        log.info("Chat request received: {} (provider: {})", 
                request.getMessage().substring(0, Math.min(50, request.getMessage().length())),
                request.getProvider());
        
        ChatResponse response = aiService.chat(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Validate a command before execution.
     */
    @PostMapping("/validate-command")
    public ResponseEntity<Map<String, Object>> validateCommand(@Valid @RequestBody ValidateCommandRequest request) {
        log.info("Validating command: {}", request.getCommand());
        
        CommandGuard.ValidationResult result = aiService.validateCommand(request.getCommand());
        
        return ResponseEntity.ok(Map.of(
                "safe", result.isSafe(),
                "warning", result.getWarning() != null ? result.getWarning() : "",
                "riskLevel", result.getRiskLevel().name()
        ));
    }

    /**
     * Get available AI providers.
     */
    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> getProviders() {
        return ResponseEntity.ok(Map.of(
                "providers", new String[]{"ollama", "openai"},
                "default", "ollama"
        ));
    }
}
