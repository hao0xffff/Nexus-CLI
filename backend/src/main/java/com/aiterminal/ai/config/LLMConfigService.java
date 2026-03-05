package com.aiterminal.ai.config;

import com.aiterminal.util.SecureStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import org.springframework.context.ApplicationEventPublisher;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Service for managing LLM configurations with persistence and hot-reload support.
 */
@Slf4j
@Service
public class LLMConfigService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final SecureStorage secureStorage;
    private final ApplicationEventPublisher eventPublisher;
    
    private LLMConfig config;
    private Path configFilePath;
    
    // Cache for Ollama models
    private List<OllamaModel> ollamaModelsCache = new ArrayList<>();
    private long ollamaModelsCacheTime = 0;
    private static final long CACHE_TTL_MS = 30000; // 30 seconds
    
    // Listeners for hot-reload
    private final List<ConfigChangeListener> changeListeners = new CopyOnWriteArrayList<>();
    
    /**
     * Listener interface for configuration changes.
     */
    @FunctionalInterface
    public interface ConfigChangeListener {
        void onConfigChanged(String provider, LLMConfig config);
    }
    
    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String defaultOllamaUrl;
    
    @Value("${spring.ai.ollama.chat.options.model:}")
    private String defaultOllamaModel;
    
    @Value("${spring.ai.openai.api-key:}")
    private String defaultOpenaiKey;
    
    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String defaultOpenaiUrl;
    
    @Value("${spring.ai.openai.chat.options.model:gpt-4o}")
    private String defaultOpenaiModel;

    public LLMConfigService(ObjectMapper objectMapper, ApplicationEventPublisher eventPublisher) {
        this.objectMapper = objectMapper.copy();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.secureStorage = SecureStorage.getInstance();
        this.eventPublisher = eventPublisher;
    }
    
    /**
     * Register a listener for configuration changes.
     */
    public void addChangeListener(ConfigChangeListener listener) {
        changeListeners.add(listener);
    }
    
    /**
     * Remove a configuration change listener.
     */
    public void removeChangeListener(ConfigChangeListener listener) {
        changeListeners.remove(listener);
    }
    
    /**
     * Notify all listeners of a configuration change.
     */
    private void notifyConfigChanged(String reason) {
        String provider = config.getActiveProvider();
        log.info("LLM config changed ({}): provider={}", reason, provider);
        
        for (ConfigChangeListener listener : changeListeners) {
            try {
                listener.onConfigChanged(provider, config);
            } catch (Exception e) {
                log.warn("Error notifying config change listener", e);
            }
        }
        
        // Also publish Spring event
        eventPublisher.publishEvent(new LLMConfigChangedEvent(this, provider, config));
    }

    @PostConstruct
    public void init() {
        // Determine config file path
        String userHome = System.getProperty("user.home");
        Path configDir = Paths.get(userHome, ".ai-terminal");
        
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            log.warn("Failed to create config directory: {}", configDir, e);
        }
        
        configFilePath = configDir.resolve("llm-config.json");
        
        // Load or create config
        loadConfig();
        
        log.info("LLM Config loaded: activeProvider={}, ollamaUrl={}, ollamaModel={}", 
                config.getActiveProvider(),
                config.getOllama() != null ? config.getOllama().getBaseUrl() : "N/A",
                config.getOllama() != null ? config.getOllama().getModel() : "N/A");
    }

    /**
     * Load configuration from file or create default.
     */
    private void loadConfig() {
        File configFile = configFilePath.toFile();
        
        if (configFile.exists()) {
            try {
                config = objectMapper.readValue(configFile, LLMConfig.class);
                log.info("Loaded LLM config from: {}", configFilePath);
                
                // Decrypt API keys after loading
                decryptApiKeys();
                
                // Check if migration needed (plaintext -> encrypted)
                if (needsMigration()) {
                    log.info("Migrating plaintext API keys to encrypted storage...");
                    saveConfig(); // Will encrypt on save
                }
                return;
            } catch (IOException e) {
                log.warn("Failed to load config file, using defaults", e);
            }
        }
        
        // Create default config from application properties
        config = LLMConfig.createDefault();
        
        // Apply defaults from application.yml
        if (config.getOllama() != null) {
            config.getOllama().setBaseUrl(defaultOllamaUrl);
            if (defaultOllamaModel != null && !defaultOllamaModel.isEmpty()) {
                config.getOllama().setModel(defaultOllamaModel);
            }
        }
        
        if (config.getOpenai() != null) {
            config.getOpenai().setBaseUrl(defaultOpenaiUrl);
            config.getOpenai().setModel(defaultOpenaiModel);
            if (defaultOpenaiKey != null && !defaultOpenaiKey.isEmpty() && !defaultOpenaiKey.equals("your-api-key")) {
                config.getOpenai().setApiKey(defaultOpenaiKey);
                config.getOpenai().setEnabled(true);
            }
        }
        
        // Save initial config
        saveConfig();
    }
    
    /**
     * Check if there are plaintext API keys that need migration.
     */
    private boolean needsMigration() {
        // Check OpenAI API key
        if (config.getOpenai() != null && config.getOpenai().getApiKey() != null) {
            String key = config.getOpenai().getApiKey();
            if (!key.isEmpty() && !secureStorage.isEncrypted(key)) {
                return true;
            }
        }
        
        // Check Custom API key
        if (config.getCustom() != null && config.getCustom().getApiKey() != null) {
            String key = config.getCustom().getApiKey();
            if (!key.isEmpty() && !secureStorage.isEncrypted(key)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Decrypt API keys after loading from file.
     */
    private void decryptApiKeys() {
        if (config.getOpenai() != null && config.getOpenai().getApiKey() != null) {
            String decrypted = secureStorage.decrypt(config.getOpenai().getApiKey());
            config.getOpenai().setApiKey(decrypted);
        }
        
        if (config.getCustom() != null && config.getCustom().getApiKey() != null) {
            String decrypted = secureStorage.decrypt(config.getCustom().getApiKey());
            config.getCustom().setApiKey(decrypted);
        }
    }

    /**
     * Save configuration to file with encrypted API keys.
     */
    public void saveConfig() {
        try {
            // Create a copy for saving with encrypted keys
            LLMConfig configToSave = createEncryptedCopy();
            objectMapper.writeValue(configFilePath.toFile(), configToSave);
            log.info("Saved LLM config to: {} (API keys encrypted)", configFilePath);
        } catch (IOException e) {
            log.error("Failed to save config file", e);
        }
    }
    
    /**
     * Create a copy of config with encrypted API keys for saving.
     */
    private LLMConfig createEncryptedCopy() {
        LLMConfig copy = LLMConfig.builder()
                .activeProvider(config.getActiveProvider())
                .build();
        
        // Copy Ollama config (no secrets)
        if (config.getOllama() != null) {
            copy.setOllama(LLMConfig.OllamaConfig.builder()
                    .baseUrl(config.getOllama().getBaseUrl())
                    .model(config.getOllama().getModel())
                    .enabled(config.getOllama().isEnabled())
                    .build());
        }
        
        // Copy OpenAI config with encrypted API key
        if (config.getOpenai() != null) {
            String encryptedKey = config.getOpenai().getApiKey();
            if (encryptedKey != null && !encryptedKey.isEmpty()) {
                encryptedKey = secureStorage.encrypt(encryptedKey);
            }
            copy.setOpenai(LLMConfig.OpenAIConfig.builder()
                    .baseUrl(config.getOpenai().getBaseUrl())
                    .apiKey(encryptedKey)
                    .model(config.getOpenai().getModel())
                    .enabled(config.getOpenai().isEnabled())
                    .build());
        }
        
        // Copy Custom config with encrypted API key
        if (config.getCustom() != null) {
            String encryptedKey = config.getCustom().getApiKey();
            if (encryptedKey != null && !encryptedKey.isEmpty()) {
                encryptedKey = secureStorage.encrypt(encryptedKey);
            }
            copy.setCustom(LLMConfig.CustomAPIConfig.builder()
                    .name(config.getCustom().getName())
                    .baseUrl(config.getCustom().getBaseUrl())
                    .apiKey(encryptedKey)
                    .model(config.getCustom().getModel())
                    .enabled(config.getCustom().isEnabled())
                    .build());
        }
        
        return copy;
    }

    /**
     * Get current configuration.
     */
    public LLMConfig getConfig() {
        return config;
    }

    /**
     * Update configuration (hot-reload).
     */
    public void updateConfig(LLMConfig newConfig) {
        this.config = newConfig;
        saveConfig();
        notifyConfigChanged("full update");
    }

    /**
     * Update Ollama configuration.
     */
    public void updateOllamaConfig(String baseUrl, String model) {
        if (config.getOllama() == null) {
            config.setOllama(new LLMConfig.OllamaConfig());
        }
        if (baseUrl != null && !baseUrl.isEmpty()) {
            config.getOllama().setBaseUrl(baseUrl);
        }
        if (model != null && !model.isEmpty()) {
            config.getOllama().setModel(model);
        }
        config.getOllama().setEnabled(true);
        saveConfig();
        notifyConfigChanged("ollama config updated");
    }

    /**
     * Update OpenAI configuration.
     */
    public void updateOpenAIConfig(String baseUrl, String apiKey, String model) {
        if (config.getOpenai() == null) {
            config.setOpenai(new LLMConfig.OpenAIConfig());
        }
        if (baseUrl != null && !baseUrl.isEmpty()) {
            config.getOpenai().setBaseUrl(baseUrl);
        }
        if (apiKey != null && !apiKey.isEmpty()) {
            config.getOpenai().setApiKey(apiKey);
        }
        if (model != null && !model.isEmpty()) {
            config.getOpenai().setModel(model);
        }
        config.getOpenai().setEnabled(true);
        saveConfig();
        notifyConfigChanged("openai config updated");
    }

    /**
     * Update custom API configuration.
     */
    public void updateCustomConfig(String name, String baseUrl, String apiKey, String model) {
        if (config.getCustom() == null) {
            config.setCustom(new LLMConfig.CustomAPIConfig());
        }
        config.getCustom().setName(name);
        config.getCustom().setBaseUrl(baseUrl);
        config.getCustom().setApiKey(apiKey);
        config.getCustom().setModel(model);
        config.getCustom().setEnabled(true);
        saveConfig();
        notifyConfigChanged("custom config updated");
    }

    /**
     * Set active provider.
     */
    public void setActiveProvider(String provider) {
        String oldProvider = config.getActiveProvider();
        config.setActiveProvider(provider);
        saveConfig();
        if (!provider.equals(oldProvider)) {
            notifyConfigChanged("provider switched from " + oldProvider + " to " + provider);
        }
    }
    
    /**
     * Validate the current configuration.
     */
    public ConfigValidationResult validateConfig() {
        String provider = config.getActiveProvider();
        
        switch (provider) {
            case "ollama":
                if (config.getOllama() == null || config.getOllama().getModel() == null || config.getOllama().getModel().isEmpty()) {
                    return ConfigValidationResult.invalid("Ollama model not configured");
                }
                if (config.getOllama().getBaseUrl() == null || config.getOllama().getBaseUrl().isEmpty()) {
                    return ConfigValidationResult.invalid("Ollama base URL not configured");
                }
                break;
                
            case "openai":
                if (config.getOpenai() == null || config.getOpenai().getApiKey() == null || config.getOpenai().getApiKey().isEmpty()) {
                    return ConfigValidationResult.invalid("OpenAI API key not configured");
                }
                if (config.getOpenai().getModel() == null || config.getOpenai().getModel().isEmpty()) {
                    return ConfigValidationResult.invalid("OpenAI model not configured");
                }
                break;
                
            case "custom":
                if (config.getCustom() == null) {
                    return ConfigValidationResult.invalid("Custom API not configured");
                }
                if (config.getCustom().getBaseUrl() == null || config.getCustom().getBaseUrl().isEmpty()) {
                    return ConfigValidationResult.invalid("Custom API base URL not configured");
                }
                if (config.getCustom().getApiKey() == null || config.getCustom().getApiKey().isEmpty()) {
                    return ConfigValidationResult.invalid("Custom API key not configured");
                }
                if (config.getCustom().getModel() == null || config.getCustom().getModel().isEmpty()) {
                    return ConfigValidationResult.invalid("Custom API model not configured");
                }
                break;
                
            default:
                return ConfigValidationResult.invalid("Unknown provider: " + provider);
        }
        
        return ConfigValidationResult.valid();
    }
    
    /**
     * Get a summary of the current configuration.
     */
    public ConfigSummary getConfigSummary() {
        String provider = config.getActiveProvider();
        String model = "";
        String baseUrl = "";
        boolean hasApiKey = false;
        
        switch (provider) {
            case "ollama":
                model = getOllamaModel();
                baseUrl = getOllamaBaseUrl();
                break;
            case "openai":
                model = getOpenAIModel();
                baseUrl = getOpenAIBaseUrl();
                hasApiKey = config.getOpenai() != null && config.getOpenai().getApiKey() != null && !config.getOpenai().getApiKey().isEmpty();
                break;
            case "custom":
                model = config.getCustom() != null ? config.getCustom().getModel() : "";
                baseUrl = config.getCustom() != null ? config.getCustom().getBaseUrl() : "";
                hasApiKey = config.getCustom() != null && config.getCustom().getApiKey() != null && !config.getCustom().getApiKey().isEmpty();
                break;
        }
        
        return new ConfigSummary(provider, model, baseUrl, hasApiKey, validateConfig().isValid());
    }

    /**
     * Get current Ollama base URL.
     */
    public String getOllamaBaseUrl() {
        return config.getOllama() != null ? config.getOllama().getBaseUrl() : defaultOllamaUrl;
    }

    /**
     * Get current Ollama model.
     */
    public String getOllamaModel() {
        return config.getOllama() != null && config.getOllama().getModel() != null 
                ? config.getOllama().getModel() 
                : defaultOllamaModel;
    }

    /**
     * Get OpenAI API key.
     */
    public String getOpenAIApiKey() {
        return config.getOpenai() != null ? config.getOpenai().getApiKey() : null;
    }

    /**
     * Get OpenAI base URL.
     */
    public String getOpenAIBaseUrl() {
        return config.getOpenai() != null ? config.getOpenai().getBaseUrl() : defaultOpenaiUrl;
    }

    /**
     * Get OpenAI model.
     */
    public String getOpenAIModel() {
        return config.getOpenai() != null ? config.getOpenai().getModel() : defaultOpenaiModel;
    }

    /**
     * Get active provider.
     */
    public String getActiveProvider() {
        return config.getActiveProvider();
    }

    /**
     * List available Ollama models.
     */
    public List<OllamaModel> listOllamaModels() {
        // Return cached if valid
        if (System.currentTimeMillis() - ollamaModelsCacheTime < CACHE_TTL_MS && !ollamaModelsCache.isEmpty()) {
            return ollamaModelsCache;
        }
        
        try {
            String baseUrl = getOllamaBaseUrl();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                Map<String, Object> data = objectMapper.readValue(response.body(), Map.class);
                List<Map<String, Object>> models = (List<Map<String, Object>>) data.get("models");
                
                ollamaModelsCache = new ArrayList<>();
                if (models != null) {
                    for (Map<String, Object> model : models) {
                        OllamaModel om = new OllamaModel();
                        om.setName((String) model.get("name"));
                        om.setModifiedAt((String) model.get("modified_at"));
                        
                        Object sizeObj = model.get("size");
                        if (sizeObj instanceof Number) {
                            om.setSize(((Number) sizeObj).longValue());
                        }
                        
                        Map<String, Object> details = (Map<String, Object>) model.get("details");
                        if (details != null) {
                            om.setFamily((String) details.get("family"));
                            om.setParameterSize((String) details.get("parameter_size"));
                            om.setQuantizationLevel((String) details.get("quantization_level"));
                        }
                        
                        ollamaModelsCache.add(om);
                    }
                }
                ollamaModelsCacheTime = System.currentTimeMillis();
                log.info("Loaded {} Ollama models", ollamaModelsCache.size());
            }
        } catch (Exception e) {
            log.warn("Failed to list Ollama models: {}", e.getMessage());
        }
        
        return ollamaModelsCache;
    }

    /**
     * Test Ollama connection.
     */
    public ConnectionTestResult testOllamaConnection(String baseUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                return ConnectionTestResult.success("Connected to Ollama successfully");
            } else {
                return ConnectionTestResult.failure("Ollama returned status: " + response.statusCode());
            }
        } catch (Exception e) {
            return ConnectionTestResult.failure("Connection failed: " + e.getMessage());
        }
    }

    /**
     * Test OpenAI connection.
     */
    public ConnectionTestResult testOpenAIConnection(String baseUrl, String apiKey) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/models"))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                return ConnectionTestResult.success("Connected to OpenAI successfully");
            } else if (response.statusCode() == 401) {
                return ConnectionTestResult.failure("Invalid API key");
            } else {
                return ConnectionTestResult.failure("OpenAI returned status: " + response.statusCode());
            }
        } catch (Exception e) {
            return ConnectionTestResult.failure("Connection failed: " + e.getMessage());
        }
    }

    /**
     * Ollama model info.
     */
    @lombok.Data
    public static class OllamaModel {
        private String name;
        private String modifiedAt;
        private long size;
        private String family;
        private String parameterSize;
        private String quantizationLevel;
    }

    /**
     * Connection test result.
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ConnectionTestResult {
        private boolean success;
        private String message;
        
        public static ConnectionTestResult success(String message) {
            return new ConnectionTestResult(true, message);
        }
        
        public static ConnectionTestResult failure(String message) {
            return new ConnectionTestResult(false, message);
        }
    }
    
    /**
     * Configuration validation result.
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ConfigValidationResult {
        private boolean valid;
        private String message;
        
        public static ConfigValidationResult valid() {
            return new ConfigValidationResult(true, "Configuration is valid");
        }
        
        public static ConfigValidationResult invalid(String message) {
            return new ConfigValidationResult(false, message);
        }
    }
    
    /**
     * Configuration summary for display.
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ConfigSummary {
        private String provider;
        private String model;
        private String baseUrl;
        private boolean hasApiKey;
        private boolean isValid;
    }
    
    /**
     * Spring event for configuration changes.
     */
    public static class LLMConfigChangedEvent extends org.springframework.context.ApplicationEvent {
        private final String provider;
        private final LLMConfig config;
        
        public LLMConfigChangedEvent(Object source, String provider, LLMConfig config) {
            super(source);
            this.provider = provider;
            this.config = config;
        }
        
        public String getProvider() {
            return provider;
        }
        
        public LLMConfig getConfig() {
            return config;
        }
    }
}
