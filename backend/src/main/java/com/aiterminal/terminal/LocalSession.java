package com.aiterminal.terminal;

import com.aiterminal.util.SystemInspector;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Local terminal session using ProcessBuilder.
 * Handles Windows encoding (GBK/UTF-8) and Unix PTY allocation.
 */
@Slf4j
public class LocalSession extends AbstractTerminalSession {

    private final SystemInspector systemInspector;
    private final String charset;
    
    private Process process;
    private OutputStream processInput;
    private ExecutorService readerExecutor;
    private String currentDirectory;

    public LocalSession(SystemInspector systemInspector, int bufferSize) {
        super(SessionType.LOCAL, bufferSize);
        this.systemInspector = systemInspector;
        this.charset = systemInspector.getDefaultEncoding();
        // Start terminal in user's home directory, not the Java process's working directory
        this.currentDirectory = systemInspector.getHomeDirectory();
    }

    @Override
    public void start(int cols, int rows) throws IOException {
        if (active) {
            throw new IllegalStateException("Session already started");
        }

        this.cols = cols;
        this.rows = rows;

        ProcessBuilder pb = createProcessBuilder();
        
        log.info("Starting local session {} with shell: {}", sessionId, pb.command());
        
        process = pb.start();
        processInput = process.getOutputStream();
        active = true;

        // Start output reader threads
        readerExecutor = Executors.newFixedThreadPool(2);
        readerExecutor.submit(() -> readStream(process.getInputStream(), "stdout"));
        readerExecutor.submit(() -> readStream(process.getErrorStream(), "stderr"));

        // Monitor process exit
        CompletableFuture.runAsync(() -> {
            try {
                int exitCode = process.waitFor();
                log.info("Session {} process exited with code: {}", sessionId, exitCode);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                close();
            }
        });

        log.info("Local session {} started successfully", sessionId);
    }

    private ProcessBuilder createProcessBuilder() {
        String[] shellArgs = systemInspector.getInteractiveShellArgs();
        ProcessBuilder pb = new ProcessBuilder(shellArgs);
        
        pb.directory(new File(currentDirectory));
        pb.redirectErrorStream(false);
        
        // Set environment variables
        Map<String, String> env = pb.environment();
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        
        // Windows-specific settings
        if (systemInspector.isWindows()) {
            // Enable virtual terminal processing on Windows
            env.put("PYTHONIOENCODING", "utf-8");
            // ConPTY hint for some applications
            env.put("TERM_PROGRAM", "AITerminal");
        } else {
            // Unix PTY settings
            env.put("COLUMNS", String.valueOf(cols));
            env.put("LINES", String.valueOf(rows));
        }
        
        return pb;
    }

    private void readStream(InputStream stream, String name) {
        byte[] buffer = new byte[4096];
        int bytesRead;
        
        try {
            while (active && (bytesRead = stream.read(buffer)) != -1) {
                byte[] data = new byte[bytesRead];
                System.arraycopy(buffer, 0, data, 0, bytesRead);
                
                // Handle encoding conversion for Windows
                if (systemInspector.isWindows()) {
                    data = convertEncoding(data);
                }
                
                notifyOutput(data);
            }
        } catch (IOException e) {
            if (active) {
                log.error("Error reading {} for session {}", name, sessionId, e);
            }
        }
        log.debug("Stream reader {} stopped for session {}", name, sessionId);
    }

    /**
     * Convert Windows GBK encoding to UTF-8 for client transmission.
     */
    private byte[] convertEncoding(byte[] data) {
        try {
            // Decode from system encoding (GBK on Windows)
            String text = new String(data, Charset.forName(charset));
            // Encode to UTF-8 for transmission
            return text.getBytes(Charset.forName("UTF-8"));
        } catch (Exception e) {
            log.debug("Encoding conversion failed, returning original data", e);
            return data;
        }
    }

    @Override
    public void write(byte[] data) throws IOException {
        if (!active || processInput == null) {
            throw new IOException("Session is not active");
        }
        
        // Handle special characters for Windows
        if (systemInspector.isWindows()) {
            // Convert DEL (0x7F) to Backspace (0x08) for Windows terminals
            // xterm.js sends 0x7F for backspace, but Windows expects 0x08
            data = convertSpecialChars(data);
            
            try {
                String text = new String(data, Charset.forName("UTF-8"));
                data = text.getBytes(Charset.forName(charset));
            } catch (Exception e) {
                log.debug("Input encoding conversion failed", e);
            }
        }
        
        processInput.write(data);
        processInput.flush();
    }

    /**
     * Convert special characters for Windows terminal compatibility.
     * - DEL (0x7F) -> Backspace (0x08)
     */
    private byte[] convertSpecialChars(byte[] data) {
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0x7F) {
                // Convert DEL to Backspace
                result[i] = 0x08;
            } else {
                result[i] = data[i];
            }
        }
        return result;
    }

    @Override
    public void write(String text) throws IOException {
        write(text.getBytes(Charset.forName("UTF-8")));
    }

    @Override
    public void resize(int cols, int rows) {
        super.resize(cols, rows);
        
        // For Unix systems, we could send SIGWINCH signal
        // Windows ConPTY resize would need native API calls
        // For now, we update environment variables which some shells read
        if (!systemInspector.isWindows() && process != null && process.isAlive()) {
            try {
                // Send stty command to resize (works in some scenarios)
                String resizeCmd = String.format("stty cols %d rows %d\n", cols, rows);
                processInput.write(resizeCmd.getBytes());
                processInput.flush();
            } catch (IOException e) {
                log.debug("Failed to send resize command", e);
            }
        }
    }

    @Override
    public void close() {
        if (!active) {
            return;
        }
        
        active = false;
        log.info("Closing local session {}", sessionId);

        // Close input stream
        if (processInput != null) {
            try {
                processInput.close();
            } catch (IOException e) {
                log.debug("Error closing process input", e);
            }
        }

        // Terminate process
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        // Shutdown reader threads
        if (readerExecutor != null) {
            readerExecutor.shutdownNow();
        }

        notifyClose();
        log.info("Local session {} closed", sessionId);
    }

    @Override
    public String getCurrentDirectory() {
        return currentDirectory;
    }

    @Override
    public String executeCommand(String command, long timeoutMs) throws IOException {
        if (!active) {
            throw new IOException("Session is not active");
        }
        
        // For interactive execution with output capture, we use a marker technique
        String marker = "###CMD_END_" + System.currentTimeMillis() + "###";
        StringBuilder output = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        
        Consumer<byte[]> captureHandler = data -> {
            String text = new String(data, Charset.forName("UTF-8"));
            output.append(text);
            // Check accumulated output for marker
            if (output.toString().contains(marker)) {
                latch.countDown();
            }
        };
        
        outputHandlers.add(captureHandler);
        
        try {
            // Execute command with marker - use platform-specific syntax
            String fullCommand;
            if (systemInspector.isWindows()) {
                // PowerShell: use semicolon and Write-Output, \r for execution
                fullCommand = command + "; Write-Output '" + marker + "'\r";
            } else {
                // Unix: use && and echo
                fullCommand = command + " && echo " + marker + "\n";
            }
            
            log.debug("Executing full command: {}", fullCommand.replace("\r", "\\r").replace("\n", "\\n"));
            write(fullCommand);
            
            boolean completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            
            // Even if timed out, return what we have
            String result = output.toString();
            log.debug("Command output (raw length={}): {}", result.length(), 
                result.length() > 500 ? result.substring(0, 500) + "..." : result);
            
            if (!completed) {
                log.warn("Command timed out, returning partial output. Marker found: {}", result.contains(marker));
            }
            
            // Extract output before marker
            int markerIndex = result.indexOf(marker);
            if (markerIndex > 0) {
                result = result.substring(0, markerIndex);
            } else if (markerIndex == 0) {
                result = "";
            }
            
            return stripAnsiCodes(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Command execution interrupted", e);
        } finally {
            outputHandlers.remove(captureHandler);
        }
    }

    @Override
    protected String getCharset() {
        return charset;
    }
}
