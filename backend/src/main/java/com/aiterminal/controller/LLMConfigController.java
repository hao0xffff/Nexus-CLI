package com.aiterminal.controller;

import com.aiterminal.ai.config.LLMConfig;
import com.aiterminal.ai.config.LLMConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for LLM configuration management.
 */
@Slf4j
@RestController
@RequestMapping("/api/llm")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class LLMConfigController {

    private final LLMConfigService configService;

    /**
     * Get current LLM configuration.
     */
    @GetMapping("/config")
    public ResponseEntity<LLMConfig> getConfig() {
        return ResponseEntity.ok(configService.getConfig());
    }

    /**
     * Update LLM configuration.
     */
    @PutMapping("/config")
    public ResponseEntity<LLMConfig> updateConfig(@RequestBody LLMConfig config) {
        configService.updateConfig(config);
        return ResponseEntity.ok(configService.getConfig());
    }

    /**
     * Set active provider.
     */
    @PostMapping("/provider")
    public ResponseEntity<Map<String, Object>> setProvider(@RequestBody Map<String, String> request) {
        String provider = request.get("provider");
        if (provider == null || provider.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Provider is required",
                    "success", false
            ));
        }
        
        // Validate provider value
        if (!provider.equals("ollama") && !provider.equals("openai") && !provider.equals("custom")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid provider. Must be one of: ollama, openai, custom",
                    "success", false
            ));
        }
        
        configService.setActiveProvider(provider);
        log.info("Active provider changed to: {}", provider);
        return ResponseEntity.ok(Map.of(
                "provider", configService.getActiveProvider(),
                "message", "Provider updated successfully",
                "success", true
        ));
    }

    /**
     * Update Ollama configuration.
     */
    @PutMapping("/ollama")
    public ResponseEntity<Map<String, Object>> updateOllama(@RequestBody Map<String, String> request) {
        String baseUrl = request.get("baseUrl");
        String model = request.get("model");
        
        configService.updateOllamaConfig(baseUrl, model);
        
        return ResponseEntity.ok(Map.of(
                "success", true,
                "baseUrl", configService.getOllamaBaseUrl(),
                "model", configService.getOllamaModel() != null ? configService.getOllamaModel() : ""
        ));
    }

    /**
     * Update OpenAI configuration.
     */
    @PutMapping("/openai")
    public ResponseEntity<Map<String, Object>> updateOpenAI(@RequestBody Map<String, String> request) {
        String baseUrl = request.get("baseUrl");
        String apiKey = request.get("apiKey");
        String model = request.get("model");
        
        configService.updateOpenAIConfig(baseUrl, apiKey, model);
        
        return ResponseEntity.ok(Map.of(
                "success", true,
                "baseUrl", configService.getOpenAIBaseUrl(),
                "model", configService.getOpenAIModel()
        ));
    }

    /**
     * Update custom API configuration.
     */
    @PutMapping("/custom")
    public ResponseEntity<Map<String, Object>> updateCustom(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        String baseUrl = request.get("baseUrl");
        String apiKey = request.get("apiKey");
        String model = request.get("model");
        
        configService.updateCustomConfig(name, baseUrl, apiKey, model);
        
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * List available Ollama models.
     */
    @GetMapping("/ollama/models")
    public ResponseEntity<Map<String, Object>> listOllamaModels() {
        List<LLMConfigService.OllamaModel> models = configService.listOllamaModels();
        return ResponseEntity.ok(Map.of(
                "models", models,
                "currentModel", configService.getOllamaModel() != null ? configService.getOllamaModel() : ""
        ));
    }

    /**
     * Test Ollama connection.
     */
    @PostMapping("/ollama/test")
    public ResponseEntity<LLMConfigService.ConnectionTestResult> testOllama(@RequestBody Map<String, String> request) {
        String baseUrl = request.getOrDefault("baseUrl", configService.getOllamaBaseUrl());
        LLMConfigService.ConnectionTestResult result = configService.testOllamaConnection(baseUrl);
        return ResponseEntity.ok(result);
    }

    /**
     * Test OpenAI connection.
     */
    @PostMapping("/openai/test")
    public ResponseEntity<LLMConfigService.ConnectionTestResult> testOpenAI(@RequestBody Map<String, String> request) {
        String baseUrl = request.getOrDefault("baseUrl", configService.getOpenAIBaseUrl());
        String apiKey = request.getOrDefault("apiKey", configService.getOpenAIApiKey());
        LLMConfigService.ConnectionTestResult result = configService.testOpenAIConnection(baseUrl, apiKey);
        return ResponseEntity.ok(result);
    }

    /**
     * Get available providers.
     */
    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> getProviders() {
        LLMConfig config = configService.getConfig();
        return ResponseEntity.ok(Map.of(
                "providers", List.of(
                        Map.of(
                                "id", "ollama",
                                "name", "Ollama (Local)",
                                "enabled", config.getOllama() != null && config.getOllama().isEnabled(),
                                "configured", config.getOllama() != null && config.getOllama().getModel() != null
                        ),
                        Map.of(
                                "id", "openai",
                                "name", "OpenAI",
                                "enabled", config.getOpenai() != null && config.getOpenai().isEnabled(),
                                "configured", config.getOpenai() != null && config.getOpenai().getApiKey() != null && !config.getOpenai().getApiKey().isEmpty()
                        ),
                        Map.of(
                                "id", "custom",
                                "name", config.getCustom() != null && config.getCustom().getName() != null 
                                        ? config.getCustom().getName() 
                                        : "Custom API",
                                "enabled", config.getCustom() != null && config.getCustom().isEnabled(),
                                "configured", config.getCustom() != null && config.getCustom().getBaseUrl() != null
                        )
                ),
                "active", configService.getActiveProvider()
        ));
    }
    
    /**
     * Get configuration summary.
     */
    @GetMapping("/summary")
    public ResponseEntity<LLMConfigService.ConfigSummary> getSummary() {
        return ResponseEntity.ok(configService.getConfigSummary());
    }
    
    /**
     * Validate current configuration.
     */
    @GetMapping("/validate")
    public ResponseEntity<LLMConfigService.ConfigValidationResult> validateConfig() {
        return ResponseEntity.ok(configService.validateConfig());
    }
    
    /**
     * Test Custom API connection (DeepSeek, Anthropic, etc.).
     */
    @PostMapping("/custom/test")
    public ResponseEntity<LLMConfigService.ConnectionTestResult> testCustom(@RequestBody Map<String, String> request) {
        String baseUrl = request.get("baseUrl");
        String apiKey = request.get("apiKey");
        
        if (baseUrl == null || baseUrl.isEmpty()) {
            return ResponseEntity.ok(LLMConfigService.ConnectionTestResult.failure("Base URL is required"));
        }
        if (apiKey == null || apiKey.isEmpty()) {
            return ResponseEntity.ok(LLMConfigService.ConnectionTestResult.failure("API key is required"));
        }
        
        // Use OpenAI-compatible test for custom APIs
        LLMConfigService.ConnectionTestResult result = configService.testOpenAIConnection(baseUrl, apiKey);
        return ResponseEntity.ok(result);
    }
}
