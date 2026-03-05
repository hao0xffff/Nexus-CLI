package com.aiterminal.terminal;

import com.aiterminal.util.SystemInspector;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Robust command executor with streaming output support.
 * Executes commands in separate processes with proper timeout handling.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandExecutor {

    private final SystemInspector systemInspector;
    
    // Track current directory per session
    private final ConcurrentHashMap<String, String> sessionDirectories = new ConcurrentHashMap<>();
    
    // Track running processes for cancellation
    private final ConcurrentHashMap<String, RunningCommand> runningCommands = new ConcurrentHashMap<>();

    /**
     * Result of command execution.
     */
    @Getter
    public static class CommandResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;
        private final boolean timedOut;
        private final boolean cancelled;
        private final long executionTimeMs;
        
        public CommandResult(int exitCode, String stdout, String stderr, 
                           boolean timedOut, boolean cancelled, long executionTimeMs) {
            this.exitCode = exitCode;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
            this.timedOut = timedOut;
            this.cancelled = cancelled;
            this.executionTimeMs = executionTimeMs;
        }
        
        public boolean isSuccess() {
            return exitCode == 0 && !timedOut && !cancelled;
        }
        
        public String getCombinedOutput() {
            StringBuilder sb = new StringBuilder();
            if (!stdout.isBlank()) {
                sb.append(stdout);
            }
            if (!stderr.isBlank()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("[STDERR] ").append(stderr);
            }
            if (timedOut) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("[Command timed out]");
            }
            if (cancelled) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("[Command cancelled]");
            }
            return sb.toString();
        }
    }
    
    /**
     * Tracks a running command for cancellation.
     */
    private static class RunningCommand {
        final Process process;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        
        RunningCommand(Process process) {
            this.process = process;
        }
    }

    /**
     * Get session directory, initializing if needed.
     */
    public String getSessionDirectory(String sessionId) {
        return sessionDirectories.computeIfAbsent(sessionId, 
            k -> systemInspector.getHomeDirectory());
    }

    /**
     * Set session directory.
     */
    public void setSessionDirectory(String sessionId, String directory) {
        sessionDirectories.put(sessionId, directory);
    }

    /**
     * Cancel a running command.
     */
    public boolean cancelCommand(String executionId) {
        RunningCommand cmd = runningCommands.get(executionId);
        if (cmd != null) {
            cmd.cancelled.set(true);
            cmd.process.destroyForcibly();
            log.info("Cancelled command: {}", executionId);
            return true;
        }
        return false;
    }

    /**
     * Execute a command with streaming output support.
     * 
     * @param sessionId Session ID for directory tracking
     * @param command Command to execute
     * @param timeoutMs Timeout in milliseconds
     * @param outputCallback Optional callback for real-time output (can be null)
     * @return CommandResult with full output
     */
    public CommandResult execute(String sessionId, String command, long timeoutMs, 
                                Consumer<String> outputCallback) {
        String executionId = sessionId + "-" + System.currentTimeMillis();
        long startTime = System.currentTimeMillis();
        String workingDir = getSessionDirectory(sessionId);
        
        log.info("Executing [{}] in {}: {}", executionId, workingDir, command);
        
        ExecutorService executor = null;
        Process process = null;
        
        try {
            ProcessBuilder pb = createProcessBuilder(command, workingDir);
            pb.redirectErrorStream(false); // Keep stderr separate
            
            process = pb.start();
            final Process finalProcess = process; // Effectively final for lambda
            RunningCommand runningCmd = new RunningCommand(process);
            runningCommands.put(executionId, runningCmd);
            
            // Create output collectors with streaming support
            StringBuilder stdoutBuilder = new StringBuilder();
            StringBuilder stderrBuilder = new StringBuilder();
            
            // Read streams in separate threads
            executor = Executors.newFixedThreadPool(2);
            final Consumer<String> callback = outputCallback;
            
            Future<Void> stdoutFuture = executor.submit(() -> {
                readStreamWithCallback(finalProcess.getInputStream(), stdoutBuilder, callback);
                return null;
            });
            
            Future<Void> stderrFuture = executor.submit(() -> {
                readStreamWithCallback(finalProcess.getErrorStream(), stderrBuilder, null);
                return null;
            });
            
            // Wait for process with timeout
            boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            
            // Check if cancelled
            if (runningCmd.cancelled.get()) {
                return new CommandResult(-1, stdoutBuilder.toString(), stderrBuilder.toString(),
                    false, true, System.currentTimeMillis() - startTime);
            }
            
            if (!completed) {
                // Timeout - kill process but keep partial output
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS); // Wait for cleanup
                
                // Give streams a moment to flush
                try {
                    stdoutFuture.get(1, TimeUnit.SECONDS);
                } catch (Exception ignored) {}
                try {
                    stderrFuture.get(1, TimeUnit.SECONDS);
                } catch (Exception ignored) {}
                
                String stdout = stdoutBuilder.toString();
                String stderr = stderrBuilder.toString();
                
                log.warn("Command timed out after {}ms, partial output: {} chars", 
                    timeoutMs, stdout.length());
                
                return new CommandResult(-1, stdout, stderr, true, false,
                    System.currentTimeMillis() - startTime);
            }
            
            // Wait for stream reading to complete
            try {
                stdoutFuture.get(5, TimeUnit.SECONDS);
                stderrFuture.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException | ExecutionException e) {
                log.warn("Stream reading issue, using partial output: {}", e.getMessage());
            }
            
            int exitCode = process.exitValue();
            String stdout = stdoutBuilder.toString();
            String stderr = stderrBuilder.toString();
            
            // Handle directory changes
            updateDirectoryIfNeeded(sessionId, command, workingDir, exitCode == 0);
            
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Command completed [{}] in {}ms, exit={}, stdout={} chars, stderr={} chars",
                executionId, elapsed, exitCode, stdout.length(), stderr.length());
            
            return new CommandResult(exitCode, stdout, stderr, false, false, elapsed);
            
        } catch (IOException e) {
            log.error("Failed to start command: {}", command, e);
            return new CommandResult(-1, "", "Failed to execute: " + e.getMessage(),
                false, false, System.currentTimeMillis() - startTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "", "Execution interrupted",
                false, true, System.currentTimeMillis() - startTime);
        } finally {
            // Always clean up resources
            runningCommands.remove(executionId);
            if (executor != null) {
                executor.shutdownNow();
            }
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
    
    /**
     * Execute without streaming callback.
     */
    public CommandResult execute(String sessionId, String command, long timeoutMs) {
        return execute(sessionId, command, timeoutMs, null);
    }

    /**
     * Create ProcessBuilder for command.
     */
    private ProcessBuilder createProcessBuilder(String command, String workingDir) {
        ProcessBuilder pb;
        
        if (systemInspector.isWindows()) {
            // PowerShell with UTF-8 encoding
            pb = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", 
                "-OutputFormat", "Text",
                "-Command",
                // Ensure UTF-8 output
                "$OutputEncoding = [Console]::OutputEncoding = [System.Text.Encoding]::UTF8; " +
                command
            );
        } else {
            String shell = System.getenv("SHELL");
            if (shell == null || shell.isEmpty()) {
                shell = "/bin/bash";
            }
            pb = new ProcessBuilder(shell, "-c", command);
        }
        
        // Set working directory
        File workDir = new File(workingDir);
        if (workDir.exists() && workDir.isDirectory()) {
            pb.directory(workDir);
        }
        
        // Set UTF-8 environment
        pb.environment().put("LANG", "en_US.UTF-8");
        pb.environment().put("LC_ALL", "en_US.UTF-8");
        if (systemInspector.isWindows()) {
            pb.environment().put("PYTHONIOENCODING", "utf-8");
        }
        
        return pb;
    }

    /**
     * Read stream line by line with optional callback.
     */
    private void readStreamWithCallback(InputStream inputStream, StringBuilder output,
                                       Consumer<String> lineCallback) {
        Charset charset = StandardCharsets.UTF_8;
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, charset))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (output) {
                    if (output.length() > 0) {
                        output.append("\n");
                    }
                    output.append(line);
                }
                if (lineCallback != null) {
                    try {
                        lineCallback.accept(line);
                    } catch (Exception e) {
                        log.debug("Output callback error: {}", e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            // Stream closed, normal during process termination
            log.debug("Stream read ended: {}", e.getMessage());
        }
    }

    /**
     * Update directory if cd command was executed.
     */
    private void updateDirectoryIfNeeded(String sessionId, String command, 
                                        String currentDir, boolean success) {
        if (!success) return;
        
        String trimmedCmd = command.trim().toLowerCase();
        
        // Detect cd commands
        if (trimmedCmd.startsWith("cd ") || trimmedCmd.startsWith("set-location ") ||
            trimmedCmd.startsWith("pushd ")) {
            
            String path = command.trim().substring(command.indexOf(' ') + 1).trim();
            path = path.replaceAll("^['\"]|['\"]$", "");
            
            if (path.isEmpty() || path.equals("~")) {
                setSessionDirectory(sessionId, systemInspector.getHomeDirectory());
            } else if (path.equals("..")) {
                File parent = new File(currentDir).getParentFile();
                if (parent != null) {
                    setSessionDirectory(sessionId, parent.getAbsolutePath());
                }
            } else if (new File(path).isAbsolute()) {
                File newDir = new File(path);
                if (newDir.exists() && newDir.isDirectory()) {
                    setSessionDirectory(sessionId, newDir.getAbsolutePath());
                }
            } else {
                File newDir = new File(currentDir, path);
                if (newDir.exists() && newDir.isDirectory()) {
                    try {
                        setSessionDirectory(sessionId, newDir.getCanonicalPath());
                    } catch (IOException e) {
                        setSessionDirectory(sessionId, newDir.getAbsolutePath());
                    }
                }
            }
            log.debug("Directory changed to: {}", getSessionDirectory(sessionId));
        }
    }

    /**
     * Estimate appropriate timeout based on command type.
     */
    public long estimateTimeout(String command) {
        String cmd = command.toLowerCase().trim();
        
        // Long-running commands - 5 minutes
        if (cmd.contains("-recurse") || cmd.contains("-r ") || 
            cmd.contains("find ") || cmd.contains("grep -r") ||
            cmd.contains("npm install") || cmd.contains("pip install") ||
            cmd.contains("mvn ") || cmd.contains("gradle ") ||
            cmd.contains("docker build") || cmd.contains("git clone")) {
            return 300000L; // 5 minutes
        }
        
        // Medium commands - 2 minutes
        if (cmd.contains("wget") || cmd.contains("curl") || 
            cmd.contains("download") || cmd.contains("invoke-webrequest")) {
            return 120000L; // 2 minutes
        }
        
        // Quick commands - 30 seconds
        if (cmd.startsWith("echo ") || cmd.startsWith("pwd") || 
            cmd.startsWith("ls") || cmd.startsWith("dir") ||
            cmd.startsWith("cd ") || cmd.startsWith("cat ") ||
            cmd.startsWith("type ") || cmd.equals("whoami")) {
            return 30000L; // 30 seconds
        }
        
        // Default - 1 minute
        return 60000L;
    }

    /**
     * Clean up session data.
     */
    public void removeSession(String sessionId) {
        sessionDirectories.remove(sessionId);
        // Cancel any running commands for this session
        runningCommands.keySet().stream()
            .filter(k -> k.startsWith(sessionId))
            .forEach(this::cancelCommand);
    }
}
