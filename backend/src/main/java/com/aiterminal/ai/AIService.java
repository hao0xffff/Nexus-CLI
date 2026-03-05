package com.aiterminal.ai;

import com.aiterminal.ai.config.LLMConfigService;
import com.aiterminal.ai.dto.ChatMessage;
import com.aiterminal.ai.dto.ChatRequest;
import com.aiterminal.ai.dto.ChatResponse;
import com.aiterminal.ai.dto.CommandCard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI service for chat interactions with Ollama and OpenAI.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIService {

    private final ContextBuilder contextBuilder;
    private final CommandGuard commandGuard;
    private final ObjectMapper objectMapper;
    private final LLMConfigService configService;

    // Multiple patterns to extract command JSON from AI responses
    // Pattern 1: ```command\n{"cmd": "...", "desc": "..."}\n```
    private static final Pattern COMMAND_BLOCK_PATTERN = Pattern.compile(
            "```command\\s*\\n?\\s*\\{\\s*\"cmd\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"desc\"\\s*:\\s*\"([^\"]+)\"\\s*\\}\\s*\\n?```",
            Pattern.MULTILINE | Pattern.DOTALL
    );
    
    // Pattern 2: `command:{"cmd":"...", "desc":"..."}`
    private static final Pattern INLINE_COMMAND_PATTERN = Pattern.compile(
            "`command:\\{\\s*\"cmd\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"desc\"\\s*:\\s*\"([^\"]+)\"\\s*\\}`"
    );
    
    // Pattern 3: Extract commands from regular code blocks (bash/shell/powershell)
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "```(?:bash|shell|sh|powershell|ps1|cmd|bat)?\\s*\\n([^`]+)\\n```"
    );

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * Process a chat request and return AI response.
     */
    public ChatResponse chat(ChatRequest request) {
        try {
            // Use provider from request, or fall back to config service
            AIProvider provider;
            if (request.getProvider() != null && !request.getProvider().isEmpty()) {
                provider = AIProvider.fromString(request.getProvider());
            } else {
                String activeProvider = configService.getActiveProvider();
                provider = activeProvider != null ? AIProvider.fromString(activeProvider) : AIProvider.OLLAMA;
            }

            String systemPrompt = contextBuilder.buildSystemPrompt();
            String userMessage = contextBuilder.buildUserMessage(
                    request.getMessage(),
                    request.getTerminalOutput(),
                    request.getRecentLines() > 0 ? request.getRecentLines() : 50
            );

            String response = switch (provider) {
                case OLLAMA -> callOllama(systemPrompt, userMessage, request.getHistory());
                case OPENAI -> callOpenAI(systemPrompt, userMessage, request.getHistory());
                case CUSTOM -> callCustomAPI(systemPrompt, userMessage, request.getHistory());
            };

            // Parse command cards from response
            List<CommandCard> commands = parseCommands(response);

            return ChatResponse.builder()
                    .message(response)
                    .commands(commands)
                    .provider(provider.getValue())
                    .success(true)
                    .build();

        } catch (Exception e) {
            log.error("AI chat failed", e);
            return ChatResponse.builder()
                    .message("Sorry, I encountered an error: " + e.getMessage())
                    .success(false)
                    .error(e.getMessage())
                    .build();
        }
    }

    /**
     * Call Ollama API.
     */
    private String callOllama(String systemPrompt, String userMessage, List<ChatMessage> history) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        
        // Add system prompt
        messages.add(Map.of("role", "system", "content", systemPrompt));
        
        // Add history
        if (history != null) {
            for (ChatMessage msg : history) {
                messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
            }
        }
        
        // Add current user message
        messages.add(Map.of("role", "user", "content", userMessage));

        // Get configuration from config service
        String baseUrl = configService.getOllamaBaseUrl();
        String model = configService.getOllamaModel();
        
        if (model == null || model.isEmpty()) {
            throw new RuntimeException("No Ollama model configured. Please select a model in settings.");
        }
        
        log.debug("Ollama request: baseUrl={}, model={}", baseUrl, model);

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", messages,
                "stream", false
        );

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofMinutes(2))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama API error: " + response.statusCode() + " - " + response.body());
        }

        JsonNode responseJson = objectMapper.readTree(response.body());
        return responseJson.path("message").path("content").asText();
    }

    /**
     * Call OpenAI API (or compatible API).
     */
    private String callOpenAI(String systemPrompt, String userMessage, List<ChatMessage> history) throws Exception {
        // Get configuration from config service
        String baseUrl = configService.getOpenAIBaseUrl();
        String apiKey = configService.getOpenAIApiKey();
        String model = configService.getOpenAIModel();
        
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("your-api-key")) {
            throw new RuntimeException("OpenAI API key not configured. Please add your API key in settings.");
        }

        List<Map<String, String>> messages = new ArrayList<>();
        
        // Add system prompt
        messages.add(Map.of("role", "system", "content", systemPrompt));
        
        // Add history
        if (history != null) {
            for (ChatMessage msg : history) {
                messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
            }
        }
        
        // Add current user message
        messages.add(Map.of("role", "user", "content", userMessage));
        
        log.debug("OpenAI request: baseUrl={}, model={}", baseUrl, model);

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", messages,
                "temperature", 0.7
        );

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofMinutes(2))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI API error: " + response.statusCode() + " - " + response.body());
        }

        JsonNode responseJson = objectMapper.readTree(response.body());
        return responseJson.path("choices").get(0).path("message").path("content").asText();
    }

    /**
     * Call custom OpenAI-compatible API.
     */
    private String callCustomAPI(String systemPrompt, String userMessage, List<ChatMessage> history) throws Exception {
        // Get custom configuration from config service
        var customConfig = configService.getConfig().getCustom();
        
        if (customConfig == null || customConfig.getBaseUrl() == null || customConfig.getBaseUrl().isEmpty()) {
            throw new RuntimeException("Custom API not configured. Please configure it in settings.");
        }
        
        String baseUrl = customConfig.getBaseUrl();
        String apiKey = customConfig.getApiKey();
        String model = customConfig.getModel();
        
        if (model == null || model.isEmpty()) {
            throw new RuntimeException("Custom API model not configured.");
        }

        List<Map<String, String>> messages = new ArrayList<>();
        
        // Add system prompt
        messages.add(Map.of("role", "system", "content", systemPrompt));
        
        // Add history
        if (history != null) {
            for (ChatMessage msg : history) {
                messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
            }
        }
        
        // Add current user message
        messages.add(Map.of("role", "user", "content", userMessage));
        
        log.debug("Custom API request: baseUrl={}, model={}", baseUrl, model);

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", messages,
                "temperature", 0.7
        );

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofMinutes(2));
        
        // Add API key header if configured
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }
        
        HttpRequest request = requestBuilder.build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("Custom API error: " + response.statusCode() + " - " + response.body());
        }

        JsonNode responseJson = objectMapper.readTree(response.body());
        return responseJson.path("choices").get(0).path("message").path("content").asText();
    }

    /**
     * Parse command cards from AI response.
     * Supports multiple formats: command blocks, inline commands, and code blocks.
     */
    private List<CommandCard> parseCommands(String response) {
        List<CommandCard> commands = new ArrayList<>();
        java.util.Set<String> seenCommands = new java.util.HashSet<>();

        // Try Pattern 1: ```command blocks
        Matcher blockMatcher = COMMAND_BLOCK_PATTERN.matcher(response);
        while (blockMatcher.find()) {
            String cmd = blockMatcher.group(1).trim();
            String desc = blockMatcher.group(2).trim();
            if (!seenCommands.contains(cmd)) {
                commands.add(createCommandCard(cmd, desc));
                seenCommands.add(cmd);
            }
        }

        // Try Pattern 2: inline `command:{}` format
        Matcher inlineMatcher = INLINE_COMMAND_PATTERN.matcher(response);
        while (inlineMatcher.find()) {
            String cmd = inlineMatcher.group(1).trim();
            String desc = inlineMatcher.group(2).trim();
            if (!seenCommands.contains(cmd)) {
                commands.add(createCommandCard(cmd, desc));
                seenCommands.add(cmd);
            }
        }

        // If no structured commands found, try to extract from code blocks
        if (commands.isEmpty()) {
            Matcher codeMatcher = CODE_BLOCK_PATTERN.matcher(response);
            while (codeMatcher.find()) {
                String codeContent = codeMatcher.group(1).trim();
                // Split by newlines and extract individual commands
                String[] lines = codeContent.split("\\n");
                for (String line : lines) {
                    String trimmedLine = line.trim();
                    // Skip empty lines, comments, and non-command content
                    if (trimmedLine.isEmpty() || 
                        trimmedLine.startsWith("#") || 
                        trimmedLine.startsWith("//") ||
                        trimmedLine.startsWith("REM") ||
                        trimmedLine.length() > 200) {
                        continue;
                    }
                    // Only include lines that look like commands (start with common command patterns)
                    if (looksLikeCommand(trimmedLine) && !seenCommands.contains(trimmedLine)) {
                        commands.add(createCommandCard(trimmedLine, "Execute this command"));
                        seenCommands.add(trimmedLine);
                    }
                }
            }
        }

        return commands;
    }

    /**
     * Create a command card with safety validation.
     */
    private CommandCard createCommandCard(String cmd, String desc) {
        CommandGuard.ValidationResult validation = commandGuard.validate(cmd);
        return CommandCard.builder()
                .command(cmd)
                .description(desc)
                .safe(validation.isSafe())
                .warning(validation.getWarning())
                .riskLevel(validation.getRiskLevel().name())
                .build();
    }

    /**
     * Check if a line looks like an executable command.
     */
    private boolean looksLikeCommand(String line) {
        // Skip obvious non-commands
        if (line.contains("pshell") || // Invalid command
            line.contains("/dev/null") || // Unix-only, skip if mixed
            line.startsWith("$") || // Variable assignment/reference
            line.startsWith("@") || // PowerShell splatting or other
            line.contains("->") || // Code syntax
            line.contains("=>") || // Code syntax
            line.contains("function ") || // Function definition
            line.contains("def ") || // Python definition
            line.matches("^[a-zA-Z_][a-zA-Z0-9_]*\\s*=.*") // Assignment
        ) {
            return false;
        }
        
        // Common command prefixes (Unix and Windows)
        String[] commonPrefixes = {
            // Unix common
            "ls", "cd", "pwd", "cat", "echo", "grep", "find", "mkdir", "rm", "cp", "mv",
            "touch", "head", "tail", "less", "more", "wc", "sort", "uniq", "awk", "sed",
            "tar", "zip", "unzip", "gzip", "gunzip",
            "ps", "kill", "pkill", "top", "htop", "df", "du", "free",
            "chmod", "chown", "chgrp", "sudo", "su",
            "ssh", "scp", "rsync", "curl", "wget",
            "systemctl", "service", "journalctl",
            "apt", "apt-get", "yum", "dnf", "brew", "pacman",
            // Windows/PowerShell common
            "dir", "type", "copy", "move", "del", "ren", "md", "rd",
            "ipconfig", "netstat", "ping", "tracert", "nslookup",
            "tasklist", "taskkill", "net", "sc", "reg", "wmic",
            "Get-", "Set-", "New-", "Remove-", "Start-", "Stop-", "Restart-",
            "Select-", "Where-", "ForEach-", "Out-", "Write-", "Read-",
            "Invoke-", "Test-", "Add-", "Clear-", "Copy-", "Move-",
            "Get-ChildItem", "Get-Content", "Get-Process", "Get-Service",
            "Get-Location", "Get-Item", "Get-Help", "Get-Command",
            // Dev tools
            "git", "npm", "yarn", "pnpm", "node", "npx",
            "python", "python3", "pip", "pip3", "conda",
            "java", "javac", "mvn", "gradle",
            "docker", "docker-compose", "kubectl", "helm",
            "code", "vim", "nano", "emacs",
            // Relative paths
            "./", ".\\"
        };
        
        String lowerLine = line.toLowerCase();
        for (String prefix : commonPrefixes) {
            if (lowerLine.startsWith(prefix.toLowerCase())) {
                return true;
            }
        }
        
        // Check for commands with full paths
        return (line.length() > 2 && line.charAt(1) == ':'); // Windows drive path like C:\
    }

    /**
     * Validate a command before execution.
     */
    public CommandGuard.ValidationResult validateCommand(String command) {
        return commandGuard.validate(command);
    }
}
