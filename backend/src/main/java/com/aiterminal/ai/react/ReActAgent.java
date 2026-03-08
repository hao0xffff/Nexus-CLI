package com.aiterminal.ai.react;

import com.aiterminal.ai.CommandGuard;
import com.aiterminal.ai.config.LLMConfigService;
import com.aiterminal.ai.dto.ChatMessage;
import com.aiterminal.terminal.ITerminalSession;
import com.aiterminal.terminal.TerminalSessionManager;
import com.aiterminal.terminal.sync.SyncCommandResult;
import com.aiterminal.terminal.sync.TerminalSyncExecutor;
import com.aiterminal.ai.ChatHistoryRepository;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct Agent that can autonomously execute multi-step tasks.
 * Implements the ReAct (Reasoning + Acting) paradigm.
 * 
 * KEY ARCHITECTURE:
 * - Commands are executed THROUGH the PTY terminal (user sees everything)
 * - Output is captured synchronously for LLM consumption
 * - Terminal display and LLM context are ALWAYS synchronized
 * 
 * Uses TerminalSyncExecutor for synchronized command execution.
 */
@Slf4j
@Service
public class ReActAgent {

    private final ReActPrompt reactPrompt;
    private final CommandGuard commandGuard;
    private final LLMConfigService configService;
    private final TerminalSessionManager sessionManager;
    private final TerminalSyncExecutor syncExecutor;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService reactExecutor;
    private final ChatHistoryRepository chatHistoryRepository;
    private final Map<String, CompletableFuture<Void>> runningTasks = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    public ReActAgent(ReActPrompt reactPrompt, CommandGuard commandGuard,
            LLMConfigService configService, TerminalSessionManager sessionManager,
            TerminalSyncExecutor syncExecutor, ObjectMapper objectMapper,
            HttpClient sharedHttpClient,
            @Qualifier("reactExecutor") ExecutorService reactExecutor,
            ChatHistoryRepository chatHistoryRepository) {
        this.reactPrompt = reactPrompt;
        this.commandGuard = commandGuard;
        this.configService = configService;
        this.sessionManager = sessionManager;
        this.syncExecutor = syncExecutor;
        this.objectMapper = objectMapper;
        this.httpClient = sharedHttpClient;
        this.reactExecutor = reactExecutor;
        this.chatHistoryRepository = chatHistoryRepository;
    }

    private static final int MAX_STEPS = 30; // For complex multi-step tasks
    private static final long DEFAULT_COMMAND_TIMEOUT_MS = 60000; // Default 60 seconds
    private static final long MAX_COMMAND_TIMEOUT_MS = 300000; // Max 5 minutes for long operations

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
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        cancelFlags.put(sessionId, cancelFlag);

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                runReActLoop(sessionId, task, stepCallback);
            } catch (java.util.concurrent.CancellationException e) {
                stepCallback.accept(ReActStep.cancelled("Task cancelled by user"));
            } catch (Exception e) {
                log.error("ReAct task failed", e);
                stepCallback.accept(ReActStep.error("Task failed: " + e.getMessage()));
            }
        }, reactExecutor);

        runningTasks.compute(sessionId, (key, previous) -> {
            if (previous != null && !previous.isDone()) {
                previous.cancel(true);
            }
            return future;
        });

        future.whenComplete((unused, throwable) -> {
            runningTasks.computeIfPresent(sessionId, (key, current) -> current == future ? null : current);
            cancelFlags.computeIfPresent(sessionId, (key, current) -> current == cancelFlag ? null : current);
        });
    }

    public void cancelTask(String sessionId) {
        AtomicBoolean flag = cancelFlags.get(sessionId);
        if (flag != null) {
            flag.set(true);
        }
        CompletableFuture<Void> task = runningTasks.get(sessionId);
        if (task != null && !task.isDone()) {
            task.cancel(true);
        }
    }

    /**
     * Main ReAct execution loop.
     * Commands are executed through PTY terminal for synchronized display.
     */
    private void runReActLoop(String sessionId, String task, Consumer<ReActStep> stepCallback) throws Exception {
        if (isCancelled(sessionId)) {
            stepCallback.accept(ReActStep.cancelled("Task cancelled by user"));
            return;
        }
        // Verify terminal session exists
        ITerminalSession terminalSession = sessionManager.getSession(sessionId).orElse(null);
        if (terminalSession == null || !terminalSession.isActive()) {
            stepCallback.accept(ReActStep.error("Terminal session not found or inactive"));
            return;
        }

        String systemPrompt = reactPrompt.buildSystemPrompt();
        // Get current directory from terminal session
        String currentDir = terminalSession.getCurrentDirectory();

        String reactSessionId = "react:" + sessionId;

        // 从 Redis 拉取之前的环境上下文（如果用户有之前聊过天）
        List<ChatMessage> conversationHistory = chatHistoryRepository.getRecentHistory(reactSessionId, 4);

        String userMessage = reactPrompt.buildTaskMessage(task, currentDir);

        // 将用户的初始任务声明存入 Redis
        chatHistoryRepository.pushMessage(reactSessionId,
                ChatMessage.builder().role("user").content("请帮我完成任务：" + task).timestamp(System.currentTimeMillis())
                        .build());

        stepCallback.accept(ReActStep.started(task));

        for (int step = 0; step < MAX_STEPS; step++) {
            if (isCancelled(sessionId)) {
                stepCallback.accept(ReActStep.cancelled("Task cancelled by user"));
                return;
            }
            log.info("ReAct step {} for task: {}", step + 1, task);

            // Call LLM
            String llmResponse = callLLM(systemPrompt, userMessage, conversationHistory);
            if (isCancelled(sessionId)) {
                stepCallback.accept(ReActStep.cancelled("Task cancelled by user"));
                return;
            }

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

            // Add to memory history
            conversationHistory.add(new ChatMessage("assistant", llmResponse, System.currentTimeMillis()));

            // 将智能体每一步的思考和计划也存入全局 Redis，让普通对话也能看到（或者以专门的形式）
            chatHistoryRepository.pushMessage(reactSessionId,
                    ChatMessage.builder().role("assistant").content(llmResponse).timestamp(System.currentTimeMillis())
                            .build());

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
                    if (isCancelled(sessionId)) {
                        stepCallback.accept(ReActStep.cancelled("Task cancelled by user"));
                        return;
                    }
                    String command = parsed.actionInput.trim();
                    if (command.isBlank()) {
                        stepResult.setStatus(ReActStatus.RUNNING);
                        stepResult.setOutput(
                                "No executable command parsed. Please provide one concrete single-line command.");
                        stepCallback.accept(stepResult);
                        userMessage = "Your previous ACTION_INPUT did not contain a valid single-line command. Provide exactly one runnable command.";
                        conversationHistory.add(new ChatMessage("user", userMessage, System.currentTimeMillis()));
                        continue;
                    }

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

                    // Calculate smart timeout based on command type
                    long timeout = syncExecutor.estimateTimeout(command);
                    log.info("Executing command in terminal {}: {} (timeout: {}ms)", sessionId, command, timeout);

                    // Update step to show command is executing
                    stepResult.setOutput("Executing: " + command);
                    stepCallback.accept(stepResult);

                    // Execute command THROUGH the PTY terminal
                    // This ensures:
                    // 1. User sees the command and output in the terminal
                    // 2. Output is captured for LLM consumption
                    // 3. Terminal display and LLM context are synchronized
                    SyncCommandResult cmdResult = syncExecutor.execute(sessionId, command, timeout, null);
                    if (isCancelled(sessionId)) {
                        stepCallback.accept(ReActStep.cancelled("Task cancelled by user"));
                        return;
                    }

                    // Log execution result
                    log.info("Command completed: success={}, timedOut={}, outputLen={}",
                            cmdResult.isSuccess(), cmdResult.isTimedOut(),
                            cmdResult.getCleanOutput().length());

                    // Update step with result - this displays in ReAct panel
                    stepResult.setOutput(cmdResult.getCleanOutput());

                    // Determine status based on result
                    if (cmdResult.isTimedOut()) {
                        stepResult.setStatus(ReActStatus.RUNNING);
                        log.warn("Command timed out but continuing with partial output");
                    } else if (cmdResult.isSuccess()) {
                        stepResult.setStatus(ReActStatus.RUNNING);
                    } else {
                        // Command completed (may have errors but we continue)
                        stepResult.setStatus(ReActStatus.RUNNING);
                    }

                    stepCallback.accept(stepResult);

                    // Prepare continuation message for LLM
                    String outputForLLM = buildOutputMessage(cmdResult);
                    userMessage = reactPrompt.buildContinuationMessage(outputForLLM, cmdResult.isSuccess());
                    conversationHistory.add(new ChatMessage("user", userMessage, System.currentTimeMillis()));

                    // Update current directory if available
                    if (cmdResult.getWorkingDirectory() != null) {
                        currentDir = cmdResult.getWorkingDirectory();
                    }
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

    private boolean isCancelled(String sessionId) {
        AtomicBoolean flag = cancelFlags.get(sessionId);
        return Thread.currentThread().isInterrupted() || (flag != null && flag.get());
    }

    /**
     * Build a detailed output message for LLM consumption.
     */
    private String buildOutputMessage(SyncCommandResult result) {
        StringBuilder sb = new StringBuilder();

        // Clear status line first - most important for LLM decision making
        if (result.isTimedOut()) {
            sb.append("COMMAND STATUS: TIMED OUT\n");
        } else if (result.isSuccess()) {
            sb.append("COMMAND STATUS: SUCCESS\n");
        } else {
            sb.append("COMMAND STATUS: COMPLETED\n");
        }

        sb.append("Execution Time: ").append(result.getExecutionTimeMs()).append("ms\n\n");

        String output = result.getCleanOutput();

        if (output == null || output.isEmpty() || output.isBlank() ||
                output.equals("(command executed successfully, no output)") ||
                output.equals("(no output)")) {
            sb.append("OUTPUT: (no output - command completed silently, which is normal for mkdir, cd, etc.)\n");
        } else {
            sb.append("OUTPUT:\n");
            // Limit output size to avoid context overflow
            if (output.length() > 3000) {
                sb.append(output.substring(0, 1500));
                sb.append("\n... (").append(output.length() - 3000).append(" chars omitted) ...\n");
                sb.append(output.substring(output.length() - 1500));
            } else {
                sb.append(output);
            }
        }

        // Add interpretation hints for common scenarios
        if (output != null) {
            if (output.contains("已存在") || output.contains("already exists") || output.contains("ResourceExists")) {
                sb.append("\n\nNOTE: The target already exists - this is usually OK, proceed to next step.");
            }
            if (output.contains("不存在") || output.contains("not found") || output.contains("Cannot find")) {
                sb.append("\n\nNOTE: Something was not found - check if the path is correct.");
            }
        }

        return sb.toString();
    }

    /**
     * Clean output text for LLM consumption.
     * Note: TerminalSyncExecutor already does most cleaning, this is for extra
     * safety.
     */
    private String cleanOutputForLLM(String output) {
        if (output == null)
            return "";

        return output
                // Remove ANSI escape codes
                .replaceAll("\\x1B\\[[0-9;]*[a-zA-Z]", "")
                // Remove other control characters except newline, tab
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "")
                // Normalize line endings
                .replaceAll("\\r\\n", "\n")
                .replaceAll("\\r", "\n")
                // Remove excessive blank lines
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
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
                actionInput = actionInput.replaceAll("```[a-zA-Z]*", "")
                        .replace("```", "")
                        .trim();

                String[] lines = actionInput.split("\\r?\\n");
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        actionInput = trimmed;
                        break;
                    }
                }
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

    // executeCommand method removed - now using commandExecutor directly with smart
    // timeout

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
                        "num_predict", 2048, // Increased for longer responses
                        "num_ctx", 8192 // Larger context window
                ));

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
                "max_tokens", 2048 // Increased for longer responses
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
                "max_tokens", 2048 // Increased for longer responses
        );

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(120)); // Longer timeout for complex tasks

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
    private record ReActParsedResponse(String thought, String action, String actionInput) {
    }
}
