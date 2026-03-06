package com.aiterminal.ai.react;

import com.aiterminal.ai.CommandGuard;
import com.aiterminal.ai.config.LLMConfigService;
import com.aiterminal.ai.dto.ChatMessage;
import com.aiterminal.terminal.CommandExecutor;
import com.aiterminal.terminal.CommandExecutor.CommandResult;
import com.aiterminal.terminal.ITerminalSession;
import com.aiterminal.terminal.TerminalSessionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct Agent that can autonomously execute multi-step tasks.
 * Implements the ReAct (Reasoning + Acting) paradigm.
 * 
 * Uses CommandExecutor for clean command output capture instead of PTY terminal parsing.
 * Uses shared HttpClient and dedicated executor for optimal performance.
 */
@Slf4j
@Service
public class ReActAgent {

    private final ReActPrompt reactPrompt;
    private final CommandGuard commandGuard;
    private final LLMConfigService configService;
    private final TerminalSessionManager sessionManager;
    private final CommandExecutor commandExecutor;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService reactExecutor;

    public ReActAgent(ReActPrompt reactPrompt, CommandGuard commandGuard,
                      LLMConfigService configService, TerminalSessionManager sessionManager,
                      CommandExecutor commandExecutor, ObjectMapper objectMapper,
                      HttpClient sharedHttpClient,
                      @Qualifier("reactExecutor") ExecutorService reactExecutor) {
        this.reactPrompt = reactPrompt;
        this.commandGuard = commandGuard;
        this.configService = configService;
        this.sessionManager = sessionManager;
        this.commandExecutor = commandExecutor;
        this.objectMapper = objectMapper;
        this.httpClient = sharedHttpClient;
        this.reactExecutor = reactExecutor;
    }

    private static final int MAX_STEPS = 30;  // For complex multi-step tasks
    private static final long DEFAULT_COMMAND_TIMEOUT_MS = 60000;  // Default 60 seconds
    private static final long MAX_COMMAND_TIMEOUT_MS = 300000;  // Max 5 minutes for long operations

    // Pattern to parse ReAct response
    private static final Pattern THOUGHT_PATTERN = Pattern.compile(
            "THOUGHT:\\s*(.+?)(?=ACTION:|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "ACTION:\\s*(EXECUTE|OBSERVE|COMPLETE|ERROR)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_INPUT_PATTERN = Pattern.compile(
            "ACTION_INPUT:\\s*(.+?)(?=THOUGHT:|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * Execute a ReAct task asynchronously using dedicated executor.
     */
    public void executeTask(String sessionId, String task, Consumer<ReActStep> stepCallback) {
        CompletableFuture.runAsync(() -> {
            try {
                runReActLoop(sessionId, task, stepCallback);
            } catch (Exception e) {
                log.error("ReAct task failed", e);
                stepCallback.accept(ReActStep.error("Task failed: " + e.getMessage()));
            }
        }, reactExecutor);  // Use dedicated ReAct executor instead of ForkJoinPool
    }

    /**
     * Main ReAct execution loop.
     */
    private void runReActLoop(String sessionId, String task, Consumer<ReActStep> stepCallback) throws Exception {
        // Verify terminal session exists (for display purposes)
        ITerminalSession terminalSession = sessionManager.getSession(sessionId).orElse(null);
        if (terminalSession == null || !terminalSession.isActive()) {
            stepCallback.accept(ReActStep.error("Terminal session not found or inactive"));
            return;
        }

        String systemPrompt = reactPrompt.buildSystemPrompt();
        // Use CommandExecutor's directory tracking instead of terminal session
        String currentDir = commandExecutor.getSessionDirectory(sessionId);
        
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

                    // FIRST: Echo command to terminal so user sees it immediately
                    try {
                        terminalSession.write(command + "\r");
                        Thread.sleep(100); // Brief pause to let terminal display the command
                    } catch (Exception e) {
                        log.warn("Failed to echo command to terminal: {}", e.getMessage());
                    }
                    
                    // Calculate smart timeout based on command type
                    long timeout = commandExecutor.estimateTimeout(command);
                    log.info("Executing command with {}ms timeout: {}", timeout, command);
                    
                    // Update step to show command is executing
                    stepResult.setOutput("Executing command...");
                    stepCallback.accept(stepResult);
                    
                    // Execute command using CommandExecutor (clean output capture)
                    CommandResult cmdResult = commandExecutor.execute(sessionId, command, timeout, 
                        line -> {
                            // Real-time output streaming (optional enhancement for future)
                            log.debug("Command output line: {}", line);
                        });
                    
                    // Update step with final result
                    stepResult.setOutput(cmdResult.getCombinedOutput());
                    
                    // Determine status based on result
                    if (cmdResult.isTimedOut()) {
                        stepResult.setStatus(ReActStatus.RUNNING); // Continue despite timeout
                        log.warn("Command timed out but continuing with partial output");
                    } else if (cmdResult.isCancelled()) {
                        stepResult.setStatus(ReActStatus.CANCELLED);
                    } else if (cmdResult.isSuccess()) {
                        stepResult.setStatus(ReActStatus.RUNNING);
                    } else {
                        // Command failed but we can still continue
                        stepResult.setStatus(ReActStatus.RUNNING);
                    }
                    
                    stepCallback.accept(stepResult);

                    // Prepare continuation message with detailed output info
                    String outputForLLM = buildOutputMessage(cmdResult);
                    userMessage = reactPrompt.buildContinuationMessage(outputForLLM, cmdResult.isSuccess());
                    conversationHistory.add(new ChatMessage("user", userMessage, System.currentTimeMillis()));
                    
                    // Update current directory from CommandExecutor
                    currentDir = commandExecutor.getSessionDirectory(sessionId);
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
     * Build a detailed output message for LLM consumption.
     */
    private String buildOutputMessage(CommandResult result) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("Exit Code: ").append(result.getExitCode()).append("\n");
        sb.append("Execution Time: ").append(result.getExecutionTimeMs()).append("ms\n");
        
        if (result.isTimedOut()) {
            sb.append("Status: TIMED OUT (partial output may be available)\n");
        } else if (result.isCancelled()) {
            sb.append("Status: CANCELLED\n");
        } else if (result.isSuccess()) {
            sb.append("Status: SUCCESS\n");
        } else {
            sb.append("Status: FAILED (exit code ").append(result.getExitCode()).append(")\n");
        }
        
        sb.append("\n--- Output ---\n");
        String output = result.getCombinedOutput();
        if (output.isEmpty() || output.isBlank()) {
            sb.append("(no output)\n");
        } else {
            // Limit output size to avoid context overflow
            if (output.length() > 4000) {
                sb.append(output.substring(0, 2000));
                sb.append("\n... (output truncated, ").append(output.length() - 4000).append(" chars omitted) ...\n");
                sb.append(output.substring(output.length() - 2000));
            } else {
                sb.append(output);
            }
        }
        
        return sb.toString();
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

    // executeCommand method removed - now using commandExecutor directly with smart timeout

    /**
     * Call the LLM API.
     */
    private String callLLM(String systemPrompt, String userMessage, List<ChatMessage> history) throws Exception {
        String activeProvider = configService.getActiveProvider();
        
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        
        // Add full conversation history for complete context understanding
        for (ChatMessage msg : history) {
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
                        "temperature", 0.3,
                        "num_predict", 2048,  // Increased for longer responses
                        "num_ctx", 8192       // Larger context window
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
                "temperature", 0.3,
                "max_tokens", 2048  // Increased for longer responses
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
        JsonNode choices = responseJson.path("choices");
        if (choices.isMissingNode() || !choices.isArray() || choices.isEmpty()) {
            throw new RuntimeException("Invalid OpenAI API response: no choices in response");
        }
        return choices.get(0).path("message").path("content").asText();
    }

    private String callCustomAPI(List<Map<String, String>> messages) throws Exception {
        var customConfig = configService.getConfig().getCustom();
        String baseUrl = customConfig.getBaseUrl();
        String apiKey = customConfig.getApiKey();
        String model = customConfig.getModel();

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", messages,
                "temperature", 0.3,
                "max_tokens", 2048  // Increased for longer responses
        );

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(120));  // Longer timeout for complex tasks

        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            log.error("Custom API error - Status: {}, Body: {}", response.statusCode(), response.body());
            throw new RuntimeException("Custom API error: " + response.statusCode() + " - " + response.body());
        }

        JsonNode responseJson = objectMapper.readTree(response.body());
        JsonNode choices = responseJson.path("choices");
        if (choices.isMissingNode() || !choices.isArray() || choices.isEmpty()) {
            throw new RuntimeException("Invalid Custom API response: no choices in response");
        }
        return choices.get(0).path("message").path("content").asText();
    }

    /**
     * Internal class for parsed response.
     */
    private record ReActParsedResponse(String thought, String action, String actionInput) {}
}
