package com.aiterminal.ai.react;

import com.aiterminal.ai.CommandGuard;
import com.aiterminal.ai.config.LLMConfigService;
import com.aiterminal.ai.dto.ChatMessage;
import com.aiterminal.terminal.ITerminalSession;
import com.aiterminal.terminal.TerminalSessionManager;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct Agent that can autonomously execute multi-step tasks.
 * Implements the ReAct (Reasoning + Acting) paradigm.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReActAgent {

    private final ReActPrompt reactPrompt;
    private final CommandGuard commandGuard;
    private final LLMConfigService configService;
    private final TerminalSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    private static final int MAX_STEPS = 10;
    private static final long COMMAND_TIMEOUT_MS = 30000;

    // Pattern to parse ReAct response
    private static final Pattern THOUGHT_PATTERN = Pattern.compile(
            "THOUGHT:\\s*(.+?)(?=ACTION:|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "ACTION:\\s*(EXECUTE|OBSERVE|COMPLETE|ERROR)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_INPUT_PATTERN = Pattern.compile(
            "ACTION_INPUT:\\s*(.+?)(?=THOUGHT:|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * Execute a ReAct task asynchronously.
     */
    public void executeTask(String sessionId, String task, Consumer<ReActStep> stepCallback) {
        CompletableFuture.runAsync(() -> {
            try {
                runReActLoop(sessionId, task, stepCallback);
            } catch (Exception e) {
                log.error("ReAct task failed", e);
                stepCallback.accept(ReActStep.error("Task failed: " + e.getMessage()));
            }
        });
    }

    /**
     * Main ReAct execution loop.
     */
    private void runReActLoop(String sessionId, String task, Consumer<ReActStep> stepCallback) throws Exception {
        ITerminalSession session = sessionManager.getSession(sessionId).orElse(null);
        if (session == null || !session.isActive()) {
            stepCallback.accept(ReActStep.error("Terminal session not found or inactive"));
            return;
        }

        String systemPrompt = reactPrompt.buildSystemPrompt();
        String currentDir = session.getCurrentDirectory();
        
        List<ChatMessage> conversationHistory = new ArrayList<>();
        String userMessage = reactPrompt.buildTaskMessage(task, currentDir);
        
        stepCallback.accept(ReActStep.started(task));

        for (int step = 0; step < MAX_STEPS; step++) {
            log.info("ReAct step {} for task: {}", step + 1, task);
            
            // Call LLM
            String llmResponse = callLLM(systemPrompt, userMessage, conversationHistory);
            
            // Parse response
            ReActParsedResponse parsed = parseResponse(llmResponse);
            
            if (parsed == null) {
                stepCallback.accept(ReActStep.error("Failed to parse AI response"));
                return;
            }

            // Create step result
            ReActStep stepResult = ReActStep.builder()
                    .stepNumber(step + 1)
                    .thought(parsed.thought)
                    .action(parsed.action)
                    .actionInput(parsed.actionInput)
                    .status(ReActStatus.RUNNING)
                    .build();
            
            stepCallback.accept(stepResult);

            // Add to history
            conversationHistory.add(new ChatMessage("assistant", llmResponse, System.currentTimeMillis()));

            // Handle action
            switch (parsed.action.toUpperCase()) {
                case "COMPLETE" -> {
                    stepResult.setStatus(ReActStatus.COMPLETED);
                    stepResult.setOutput(parsed.actionInput);
                    stepCallback.accept(stepResult);
                    return;
                }
                case "ERROR" -> {
                    stepResult.setStatus(ReActStatus.ERROR);
                    stepResult.setOutput(parsed.actionInput);
                    stepCallback.accept(stepResult);
                    return;
                }
                case "EXECUTE" -> {
                    String command = parsed.actionInput.trim();
                    
                    // Validate command safety
                    CommandGuard.ValidationResult validation = commandGuard.validate(command);
                    if (!validation.isSafe() && validation.getRiskLevel() == CommandGuard.RiskLevel.DANGEROUS) {
                        stepResult.setStatus(ReActStatus.BLOCKED);
                        stepResult.setOutput("Command blocked: " + validation.getWarning());
                        stepCallback.accept(stepResult);
                        
                        userMessage = "The command was blocked for safety reasons: " + validation.getWarning() + 
                                     ". Please try a different approach.";
                        conversationHistory.add(new ChatMessage("user", userMessage, System.currentTimeMillis()));
                        continue;
                    }

                    // Execute command
                    String output = executeCommand(session, command);
                    stepResult.setOutput(output);
                    stepResult.setStatus(ReActStatus.RUNNING);
                    stepCallback.accept(stepResult);

                    // Prepare continuation message
                    userMessage = reactPrompt.buildContinuationMessage(output, true);
                    conversationHistory.add(new ChatMessage("user", userMessage, System.currentTimeMillis()));
                }
                case "OBSERVE" -> {
                    // OBSERVE is typically used after EXECUTE, continue loop
                    userMessage = "Observation noted. Continue with the task.";
                    conversationHistory.add(new ChatMessage("user", userMessage, System.currentTimeMillis()));
                }
                default -> {
                    stepResult.setStatus(ReActStatus.ERROR);
                    stepResult.setOutput("Unknown action: " + parsed.action);
                    stepCallback.accept(stepResult);
                    return;
                }
            }
        }

        // Max steps reached
        stepCallback.accept(ReActStep.error("Maximum steps (" + MAX_STEPS + ") reached. Task may be incomplete."));
    }

    /**
     * Parse LLM response into structured format.
     */
    private ReActParsedResponse parseResponse(String response) {
        try {
            String thought = "";
            String action = "";
            String actionInput = "";

            Matcher thoughtMatcher = THOUGHT_PATTERN.matcher(response);
            if (thoughtMatcher.find()) {
                thought = thoughtMatcher.group(1).trim();
            }

            Matcher actionMatcher = ACTION_PATTERN.matcher(response);
            if (actionMatcher.find()) {
                action = actionMatcher.group(1).trim();
            }

            Matcher inputMatcher = ACTION_INPUT_PATTERN.matcher(response);
            if (inputMatcher.find()) {
                actionInput = inputMatcher.group(1).trim();
                // Clean up any trailing whitespace or newlines
                actionInput = actionInput.split("\\n")[0].trim();
            }

            if (action.isEmpty()) {
                log.warn("Could not parse action from response: {}", response);
                return null;
            }

            return new ReActParsedResponse(thought, action, actionInput);
        } catch (Exception e) {
            log.error("Failed to parse ReAct response", e);
            return null;
        }
    }

    /**
     * Execute a command in the terminal session.
     */
    private String executeCommand(ITerminalSession session, String command) {
        try {
            log.info("Executing command: {}", command);
            return session.executeCommand(command, COMMAND_TIMEOUT_MS);
        } catch (Exception e) {
            log.error("Command execution failed: {}", command, e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Call the LLM API.
     */
    private String callLLM(String systemPrompt, String userMessage, List<ChatMessage> history) throws Exception {
        String activeProvider = configService.getActiveProvider();
        
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        
        // Add history (limit to prevent context overflow)
        int startIdx = Math.max(0, history.size() - 10);
        for (int i = startIdx; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
        }
        
        messages.add(Map.of("role", "user", "content", userMessage));

        return switch (activeProvider) {
            case "ollama" -> callOllama(messages);
            case "openai" -> callOpenAI(messages);
            case "custom" -> callCustomAPI(messages);
            default -> callOllama(messages);
        };
    }

    private String callOllama(List<Map<String, String>> messages) throws Exception {
        String baseUrl = configService.getOllamaBaseUrl();
        String model = configService.getOllamaModel();

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", messages,
                "stream", false,
                "options", Map.of(
                        "temperature", 0.2,
                        "num_predict", 1024
                )
        );

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(120))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama API error: " + response.statusCode());
        }

        JsonNode responseJson = objectMapper.readTree(response.body());
        return responseJson.path("message").path("content").asText();
    }

    private String callOpenAI(List<Map<String, String>> messages) throws Exception {
        String baseUrl = configService.getOpenAIBaseUrl();
        String apiKey = configService.getOpenAIApiKey();
        String model = configService.getOpenAIModel();

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", messages,
                "temperature", 0.2,
                "max_tokens", 1024
        );

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            log.error("OpenAI API error - Status: {}, Body: {}", response.statusCode(), response.body());
            throw new RuntimeException("OpenAI API error: " + response.statusCode() + " - " + response.body());
        }

        JsonNode responseJson = objectMapper.readTree(response.body());
        return responseJson.path("choices").get(0).path("message").path("content").asText();
    }

    private String callCustomAPI(List<Map<String, String>> messages) throws Exception {
        var customConfig = configService.getConfig().getCustom();
        String baseUrl = customConfig.getBaseUrl();
        String apiKey = customConfig.getApiKey();
        String model = customConfig.getModel();

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", messages,
                "temperature", 0.2,
                "max_tokens", 1024
        );

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(60));

        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            log.error("Custom API error - Status: {}, Body: {}", response.statusCode(), response.body());
            throw new RuntimeException("Custom API error: " + response.statusCode() + " - " + response.body());
        }

        JsonNode responseJson = objectMapper.readTree(response.body());
        return responseJson.path("choices").get(0).path("message").path("content").asText();
    }

    /**
     * Internal class for parsed response.
     */
    private record ReActParsedResponse(String thought, String action, String actionInput) {}
}
