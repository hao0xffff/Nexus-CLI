package com.aiterminal.ai;

import com.aiterminal.ai.dto.ChatMessage;
import com.aiterminal.ai.dto.ChatRequest;
import com.aiterminal.ai.dto.ChatResponse;
import com.aiterminal.ai.dto.CommandCard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${spring.ai.ollama.chat.options.model:llama3}")
    private String ollamaModel;

    @Value("${spring.ai.openai.api-key:}")
    private String openaiApiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String openaiBaseUrl;

    @Value("${spring.ai.openai.chat.options.model:gpt-4o}")
    private String openaiModel;

    // Pattern to extract command JSON from AI responses
    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "`command:\\{\"cmd\":\"([^\"]+)\",\\s*\"desc\":\"([^\"]+)\"\\}`"
    );

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * Process a chat request and return AI response.
     */
    public ChatResponse chat(ChatRequest request) {
        try {
            AIProvider provider = request.getProvider() != null 
                    ? AIProvider.fromString(request.getProvider())
                    : AIProvider.OLLAMA;

            String systemPrompt = contextBuilder.buildSystemPrompt();
            String userMessage = contextBuilder.buildUserMessage(
                    request.getMessage(),
                    request.getTerminalOutput(),
                    request.getRecentLines() > 0 ? request.getRecentLines() : 50
            );

            String response = switch (provider) {
                case OLLAMA -> callOllama(systemPrompt, userMessage, request.getHistory());
                case OPENAI -> callOpenAI(systemPrompt, userMessage, request.getHistory());
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

        Map<String, Object> requestBody = Map.of(
                "model", ollamaModel,
                "messages", messages,
                "stream", false
        );

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ollamaBaseUrl + "/api/chat"))
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
     * Call OpenAI API.
     */
    private String callOpenAI(String systemPrompt, String userMessage, List<ChatMessage> history) throws Exception {
        if (openaiApiKey == null || openaiApiKey.isEmpty() || openaiApiKey.equals("your-api-key")) {
            throw new RuntimeException("OpenAI API key not configured");
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

        Map<String, Object> requestBody = Map.of(
                "model", openaiModel,
                "messages", messages,
                "temperature", 0.7
        );

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(openaiBaseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + openaiApiKey)
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
     * Parse command cards from AI response.
     */
    private List<CommandCard> parseCommands(String response) {
        List<CommandCard> commands = new ArrayList<>();
        Matcher matcher = COMMAND_PATTERN.matcher(response);

        while (matcher.find()) {
            String cmd = matcher.group(1);
            String desc = matcher.group(2);

            // Validate command safety
            CommandGuard.ValidationResult validation = commandGuard.validate(cmd);

            CommandCard card = CommandCard.builder()
                    .command(cmd)
                    .description(desc)
                    .safe(validation.isSafe())
                    .warning(validation.getWarning())
                    .riskLevel(validation.getRiskLevel().name())
                    .build();

            commands.add(card);
        }

        return commands;
    }

    /**
     * Validate a command before execution.
     */
    public CommandGuard.ValidationResult validateCommand(String command) {
        return commandGuard.validate(command);
    }
}
