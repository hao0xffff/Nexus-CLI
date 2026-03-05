package com.aiterminal.ai.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM configuration model supporting multiple providers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LLMConfig {
    
    // Current active provider
    private String activeProvider;
    
    // Ollama configuration
    private OllamaConfig ollama;
    
    // OpenAI configuration
    private OpenAIConfig openai;
    
    // Custom/compatible API configuration
    private CustomAPIConfig custom;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OllamaConfig {
        @Builder.Default
        private String baseUrl = "http://localhost:11434";
        private String model;
        @Builder.Default
        private boolean enabled = true;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OpenAIConfig {
        @Builder.Default
        private String baseUrl = "https://api.openai.com";
        private String apiKey;
        @Builder.Default
        private String model = "gpt-4o";
        @Builder.Default
        private boolean enabled = false;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CustomAPIConfig {
        private String baseUrl;
        private String apiKey;
        private String model;
        @Builder.Default
        private boolean enabled = false;
        private String name;
    }
    
    /**
     * Create default configuration.
     */
    public static LLMConfig createDefault() {
        return LLMConfig.builder()
                .activeProvider("ollama")
                .ollama(OllamaConfig.builder()
                        .baseUrl("http://localhost:11434")
                        .enabled(true)
                        .build())
                .openai(OpenAIConfig.builder()
                        .baseUrl("https://api.openai.com")
                        .model("gpt-4o")
                        .enabled(false)
                        .build())
                .custom(CustomAPIConfig.builder()
                        .enabled(false)
                        .build())
                .build();
    }
}
