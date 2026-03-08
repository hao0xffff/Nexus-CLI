package com.aiterminal.terminal.sync;

import com.aiterminal.terminal.ITerminalSession;
import com.aiterminal.terminal.TerminalSessionManager;
import com.aiterminal.terminal.sync.parser.TerminalSyncParser;
import com.aiterminal.terminal.sync.parser.TerminalSyncParserResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Synchronized command executor that works THROUGH the PTY terminal.
 * 
 * This ensures:
 * 1. Commands are executed in the actual terminal (user sees input/output)
 * 2. Output is captured for LLM consumption
 * 3. Terminal display and LLM context are always synchronized
 * 
 * Architecture:
 * - Command is written to PTY terminal (visible to user)
 * - Output is captured via output handler (for LLM)
 * - End marker detects command completion
 */
@Slf4j
@Component
public class TerminalSyncExecutor {
    
    private static final long DEFAULT_TIMEOUT_MS = 30000;
    private static final long IDLE_TIMEOUT_MS = 5000;
    
    // ANSI escape code pattern
    private static final Pattern ANSI_PATTERN = Pattern.compile("\\x1B\\[[0-9;]*[a-zA-Z]");
    // Control character pattern
    private static final Pattern CONTROL_CHAR_PATTERN = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    
    private final TerminalSessionManager sessionManager;
    private final TerminalSyncParserResolver parserResolver;
    private final ExecutorService executor;
    
    public TerminalSyncExecutor(
            TerminalSessionManager sessionManager,
            TerminalSyncParserResolver parserResolver,
            @Qualifier("commandExecutorPool") ExecutorService executor) {
        this.sessionManager = sessionManager;
        this.parserResolver = parserResolver;
        this.executor = executor;
    }
    
    /**
     * Execute a command in the terminal synchronously.
     * The command is written to the PTY and output is captured.
     * 
     * @param sessionId Terminal session ID
     * @param command Command to execute
     * @param timeoutMs Timeout in milliseconds
     * @param outputCallback Optional callback for real-time output streaming
     * @return Execution result with clean and raw output
     */
    public SyncCommandResult execute(String sessionId, String command, long timeoutMs,
                                     Consumer<String> outputCallback) {
        ITerminalSession session = sessionManager.getSession(sessionId).orElse(null);
        if (session == null || !session.isActive()) {
            log.error("Session {} not found or inactive", sessionId);
            return SyncCommandResult.builder()
                    .command(command)
                    .cleanOutput("Error: Terminal session not found or inactive")
                    .rawOutput("")
                    .success(false)
                    .timedOut(false)
                    .executionTimeMs(0)
                    .build();
        }
        
        long startTime = System.currentTimeMillis();
        
        StringBuilder rawOutput = new StringBuilder();
        String marker = generateEndMarker();
        TerminalSyncParser parser = parserResolver.resolve();
        String commandProbe = command == null ? "" : command.substring(0, Math.min(20, command.length()));
        AtomicBoolean promptFound = new AtomicBoolean(false);
        AtomicBoolean markerFound = new AtomicBoolean(false);
        AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
        AtomicLong lastOutputTime = new AtomicLong(System.currentTimeMillis());
        AtomicBoolean commandEchoed = new AtomicBoolean(false);
        CountDownLatch completionLatch = new CountDownLatch(1);
        
        // Output capture handler - detects completion by watching for shell prompt
        Consumer<byte[]> captureHandler = data -> {
            String text = new String(data, StandardCharsets.UTF_8);
            synchronized (rawOutput) {
                rawOutput.append(text);
            }
            lastOutputTime.set(System.currentTimeMillis());
            
            // Notify callback if provided
            if (outputCallback != null) {
                try {
                    outputCallback.accept(text);
                } catch (Exception e) {
                    log.debug("Output callback error: {}", e.getMessage());
                }
            }
            
            String currentOutput = rawOutput.toString();
            String outputWithoutAnsi = ANSI_PATTERN.matcher(currentOutput).replaceAll("");
            Integer parsedExitCode = extractExitCode(outputWithoutAnsi, marker);
            if (parsedExitCode != null) {
                markerFound.set(true);
                exitCode.set(parsedExitCode);
                completionLatch.countDown();
                return;
            }
            
            // First, check if command has been echoed (command is being processed)
            if (!commandEchoed.get() && !commandProbe.isBlank() && currentOutput.contains(commandProbe)) {
                commandEchoed.set(true);
            }
            
            // After command is echoed, look for a new prompt at the end
            // This indicates command has completed
            if (commandEchoed.get()) {
                // Check for PowerShell prompt pattern at the end: PS C:\...>
                // or bash prompt: user@host:~$
                String trimmedEnd = currentOutput.substring(Math.max(0, currentOutput.length() - 200));
                if (parser.isPromptAtEnd(trimmedEnd)) {
                    promptFound.set(true);
                    completionLatch.countDown();
                }
            }
        };
        
        // Register output handler
        session.onOutput(captureHandler);
        
        try {
            // Send just the command (no marker)
            String fullCommand = buildCommandWithMarker(parser, command, marker);
            log.info("Executing synchronized command in session {}: {}", sessionId, command);
            
            // Write command to terminal (this makes it visible to user)
            session.write(fullCommand);
            
            // Wait for completion (prompt detection)
            boolean completed = waitForCompletion(completionLatch, rawOutput, lastOutputTime, 
                                                   promptFound, timeoutMs);
            
            long executionTime = System.currentTimeMillis() - startTime;
            
            // Process output
            String raw = rawOutput.toString();
            String clean = cleanOutput(parser, raw, command, marker);
            boolean success = completed && (markerFound.get() ? exitCode.get() == 0 : promptFound.get());
            
            log.info("Command completed in {}ms, success={}, outputLen={}", 
                     executionTime, success, clean.length());
            
            return SyncCommandResult.builder()
                    .command(command)
                    .cleanOutput(clean)
                    .rawOutput(raw)
                    .success(success)
                    .timedOut(!completed)
                    .executionTimeMs(executionTime)
                    .workingDirectory(session.getCurrentDirectory())
                    .build();
                    
        } catch (IOException e) {
            log.error("Failed to execute command in session {}", sessionId, e);
            return SyncCommandResult.builder()
                    .command(command)
                    .cleanOutput("Error: " + e.getMessage())
                    .rawOutput("")
                    .success(false)
                    .timedOut(false)
                    .executionTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        } finally {
            // Always remove the capture handler to prevent memory leaks
            session.removeOutputHandler(captureHandler);
        }
    }
    
    /**
     * Execute with default timeout.
     */
    public SyncCommandResult execute(String sessionId, String command) {
        return execute(sessionId, command, DEFAULT_TIMEOUT_MS, null);
    }
    
    /**
     * Estimate appropriate timeout for a command.
     */
    public long estimateTimeout(String command) {
        String cmdLower = command.toLowerCase().trim();
        
        // Long-running commands
        if (cmdLower.contains("npm install") || cmdLower.contains("yarn install") ||
            cmdLower.contains("pip install") || cmdLower.contains("mvn") ||
            cmdLower.contains("gradle") || cmdLower.contains("docker")) {
            return 120000; // 2 minutes
        }
        
        // Network commands
        if (cmdLower.contains("curl") || cmdLower.contains("wget") ||
            cmdLower.contains("git clone") || cmdLower.contains("git pull")) {
            return 60000; // 1 minute
        }
        
        // File operations with large files
        if (cmdLower.contains("copy") || cmdLower.contains("xcopy") ||
            cmdLower.contains("cp -r") || cmdLower.contains("tar")) {
            return 60000;
        }
        
        // Default for simple commands
        return DEFAULT_TIMEOUT_MS;
    }
    
    /**
     * Generate unique end marker - uses a minimal marker that's less intrusive.
     */
    private String generateEndMarker() {
        // Use a short numeric marker that's less visually intrusive
        return "~" + (System.currentTimeMillis() % 100000) + "~";
    }
    
    /**
     * Build command string - just the command with newline, no marker appended.
     * We detect completion by watching for the shell prompt to reappear.
     */
    private String buildCommandWithMarker(TerminalSyncParser parser, String command, String marker) {
        return parser.buildCommandWithMarker(command, marker);
    }
    
    /**
     * Wait for command completion with adaptive polling.
     */
    private boolean waitForCompletion(CountDownLatch latch, StringBuilder output,
                                       AtomicLong lastOutputTime, AtomicBoolean markerFound,
                                       long timeoutMs) {
        long startTime = System.currentTimeMillis();
        long checkInterval = 500; // Start with 500ms checks
        
        try {
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                // Wait for a bit
                boolean done = latch.await(checkInterval, TimeUnit.MILLISECONDS);
                if (done) {
                    return true;
                }
                
                // Check if we've been idle too long (command might be done but prompt missed)
                long idleTime = System.currentTimeMillis() - lastOutputTime.get();
                if (output.length() > 0 && idleTime > IDLE_TIMEOUT_MS && markerFound.get()) {
                    return true;
                }
                
                // Adaptive interval: increase if no activity
                if (idleTime > 2000) {
                    checkInterval = Math.min(checkInterval * 2, 2000);
                }
            }
            return false; // Timed out
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Integer extractExitCode(String output, String marker) {
        if (output == null || marker == null || marker.isBlank()) {
            return null;
        }
        Pattern markerPattern = Pattern.compile(Pattern.quote(marker) + ":(-?\\d+)");
        Matcher matcher = markerPattern.matcher(output);
        Integer parsed = null;
        while (matcher.find()) {
            try {
                parsed = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return parsed;
    }
    
    /**
     * Clean output for LLM consumption.
     * Removes ANSI codes, prompts, and the command echo.
     */
    private String cleanOutput(TerminalSyncParser parser, String raw, String command, String marker) {
        if (raw == null || raw.isEmpty()) {
            return "(no output)";
        }
        
        // Step 1: Remove ANSI escape codes
        String clean = ANSI_PATTERN.matcher(raw).replaceAll("");
        
        // Step 2: Remove control characters
        clean = CONTROL_CHAR_PATTERN.matcher(clean).replaceAll("");
        
        // Step 3: Split into lines and process
        String[] lines = clean.split("\\r?\\n");
        StringBuilder result = new StringBuilder();
        boolean passedCommand = false;
        
        for (String line : lines) {
            String trimmed = line.trim();
            
            // Skip empty lines
            if (trimmed.isEmpty()) {
                continue;
            }
            
            // Skip lines containing old marker patterns (for cleanup)
            if (trimmed.contains("###SYNC_END_") || trimmed.contains("Write-Output '###")) {
                continue;
            }

            if (!marker.isBlank() && trimmed.contains(marker)) {
                continue;
            }
            
            if (parser.shouldStripPromptLine(trimmed)) {
                passedCommand = true;
                continue;
            }
            
            // Skip the command echo (first substantial line often contains the command)
            if (!passedCommand && containsCommand(trimmed, command)) {
                passedCommand = true;
                continue;
            }
            
            // Add to result
            if (result.length() > 0) {
                result.append("\n");
            }
            result.append(trimmed);
        }
        
        String finalOutput = result.toString().trim();
        return finalOutput.isEmpty() ? "(command executed successfully, no output)" : finalOutput;
    }
    
    /**
     * Check if a line contains the command (for echo detection).
     */
    private boolean containsCommand(String line, String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        // Compare first 30 chars of command
        String cmdPrefix = command.substring(0, Math.min(30, command.length()));
        if (cmdPrefix.isBlank()) {
            return false;
        }
        return line.contains(cmdPrefix);
    }
}
