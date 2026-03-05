package com.aiterminal.terminal;

import com.aiterminal.util.SystemInspector;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * PTY-based terminal session using pty4j.
 * Provides true PTY support on all platforms:
 * - Windows: ConPTY (Windows 10 1809+)
 * - macOS/Linux: Native PTY
 */
@Slf4j
public class PtySession extends AbstractTerminalSession {

    private final SystemInspector systemInspector;
    private final String charset;
    
    private PtyProcess ptyProcess;
    private OutputStream ptyInput;
    private ExecutorService readerExecutor;
    private String currentDirectory;

    public PtySession(SystemInspector systemInspector, int bufferSize) {
        super(SessionType.LOCAL, bufferSize);
        this.systemInspector = systemInspector;
        this.charset = systemInspector.getDefaultEncoding();
        this.currentDirectory = systemInspector.getCurrentWorkingDirectory();
    }

    @Override
    public void start(int cols, int rows) throws IOException {
        if (active) {
            throw new IllegalStateException("Session already started");
        }

        this.cols = cols;
        this.rows = rows;

        String[] command;
        
        // Windows: Force PowerShell to use UTF-8
        if (systemInspector.isWindows()) {
            command = new String[]{
                "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                "-NoLogo",
                "-NoExit",
                "-Command",
                "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; [Console]::InputEncoding = [System.Text.Encoding]::UTF8; chcp 65001 | Out-Null"
            };
        } else {
            command = systemInspector.getInteractiveShellArgs();
        }
        
        log.info("Starting PTY session {} with shell: {}", sessionId, String.join(" ", command));

        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("TERM_PROGRAM", "AITerminal");
        
        // Windows specific - Force UTF-8
        if (systemInspector.isWindows()) {
            env.put("PYTHONIOENCODING", "utf-8");
            env.put("LANG", "en_US.UTF-8");
            env.put("LC_ALL", "en_US.UTF-8");
        }

        try {
            PtyProcessBuilder builder = new PtyProcessBuilder()
                    .setCommand(command)
                    .setDirectory(currentDirectory)
                    .setEnvironment(env)
                    .setInitialColumns(cols)
                    .setInitialRows(rows)
                    .setConsole(false)  // Use PTY mode, not console mode
                    .setCygwin(false)
                    .setLogFile(null);
            
            // Windows 10 1809+ uses ConPTY automatically
            if (systemInspector.isWindows()) {
                builder.setWindowsAnsiColorEnabled(true);
            }

            ptyProcess = builder.start();
            ptyInput = ptyProcess.getOutputStream();
            active = true;

            // Start output reader
            readerExecutor = Executors.newSingleThreadExecutor();
            readerExecutor.submit(this::readPtyOutput);

            // Monitor process exit
            CompletableFuture.runAsync(() -> {
                try {
                    int exitCode = ptyProcess.waitFor();
                    log.info("PTY session {} process exited with code: {}", sessionId, exitCode);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    close();
                }
            });

            log.info("PTY session {} started successfully (ConPTY: {})", 
                    sessionId, systemInspector.isWindows());
            
        } catch (Exception e) {
            log.error("Failed to start PTY session", e);
            throw new IOException("Failed to start PTY: " + e.getMessage(), e);
        }
    }

    private void readPtyOutput() {
        InputStream input = ptyProcess.getInputStream();
        byte[] buffer = new byte[4096];
        int bytesRead;

        try {
            while (active && (bytesRead = input.read(buffer)) != -1) {
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
                log.error("Error reading PTY output for session {}", sessionId, e);
            }
        }
        log.debug("PTY reader stopped for session {}", sessionId);
    }

    /**
     * Convert Windows encoding to UTF-8 for client transmission.
     * With UTF-8 configured in PowerShell, this is mostly a pass-through,
     * but we handle any remaining GBK content.
     */
    private byte[] convertEncoding(byte[] data) {
        try {
            // First try UTF-8 (should work with our PowerShell config)
            String text = new String(data, StandardCharsets.UTF_8);
            // Check for obvious encoding issues (replacement char)
            if (!text.contains("\uFFFD")) {
                return data; // Already valid UTF-8
            }
            // Fallback: try GBK
            text = new String(data, Charset.forName("GBK"));
            return text.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("Encoding conversion failed, returning original data", e);
            return data;
        }
    }

    @Override
    public void write(byte[] data) throws IOException {
        if (!active || ptyInput == null) {
            throw new IOException("Session is not active");
        }

        // Log input for debugging
        if (log.isTraceEnabled()) {
            StringBuilder hex = new StringBuilder();
            for (byte b : data) {
                hex.append(String.format("%02X ", b));
            }
            log.trace("PTY write: {} bytes: {}", data.length, hex);
        }

        // With UTF-8 PowerShell, input should remain UTF-8
        // Just write directly
        ptyInput.write(data);
        ptyInput.flush();
    }

    @Override
    public void write(String text) throws IOException {
        write(text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void resize(int cols, int rows) {
        super.resize(cols, rows);

        if (ptyProcess != null && ptyProcess.isAlive()) {
            try {
                ptyProcess.setWinSize(new WinSize(cols, rows));
                log.debug("PTY session {} resized to {}x{}", sessionId, cols, rows);
            } catch (Exception e) {
                log.warn("Failed to resize PTY session {}", sessionId, e);
            }
        }
    }

    @Override
    public void close() {
        if (!active) {
            return;
        }

        active = false;
        log.info("Closing PTY session {}", sessionId);

        // Close input stream
        if (ptyInput != null) {
            try {
                ptyInput.close();
            } catch (IOException e) {
                log.debug("Error closing PTY input", e);
            }
        }

        // Terminate PTY process
        if (ptyProcess != null && ptyProcess.isAlive()) {
            ptyProcess.destroy();
            try {
                if (!ptyProcess.waitFor(5, TimeUnit.SECONDS)) {
                    ptyProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ptyProcess.destroyForcibly();
            }
        }

        // Shutdown reader thread
        if (readerExecutor != null) {
            readerExecutor.shutdownNow();
        }

        notifyClose();
        log.info("PTY session {} closed", sessionId);
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

        String marker = "###CMD_END_" + System.currentTimeMillis() + "###";
        StringBuilder output = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);

        java.util.function.Consumer<byte[]> captureHandler = data -> {
            String text = new String(data, StandardCharsets.UTF_8);
            output.append(text);
            if (text.contains(marker)) {
                latch.countDown();
            }
        };

        outputHandlers.add(captureHandler);

        try {
            String fullCommand = command + " && echo " + marker + "\n";
            write(fullCommand);

            boolean completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!completed) {
                throw new IOException("Command execution timed out");
            }

            String result = output.toString();
            int markerIndex = result.indexOf(marker);
            if (markerIndex > 0) {
                result = result.substring(0, markerIndex);
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

    /**
     * Check if PTY is supported on current system.
     */
    public static boolean isPtySupported() {
        try {
            // Check if pty4j native library can be loaded
            Class.forName("com.pty4j.PtyProcess");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
