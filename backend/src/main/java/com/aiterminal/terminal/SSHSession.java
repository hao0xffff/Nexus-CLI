package com.aiterminal.terminal;

import com.jcraft.jsch.*;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.*;

/**
 * SSH terminal session using JSch library.
 * Supports PTY allocation for proper terminal emulation.
 */
@Slf4j
public class SSHSession extends AbstractTerminalSession {

    @Getter
    @Builder
    public static class SSHConfig {
        private String host;
        private int port;
        private String username;
        private String password;
        private String privateKeyPath;
        private String passphrase;
        private int connectionTimeout;
        private boolean strictHostKeyChecking;
        
        public static SSHConfigBuilder builder() {
            return new SSHConfigBuilder()
                    .port(22)
                    .connectionTimeout(30000)
                    .strictHostKeyChecking(false);
        }
    }

    private final SSHConfig config;
    
    private JSch jsch;
    private Session sshSession;
    private ChannelShell channel;
    private OutputStream channelInput;
    private ExecutorService readerExecutor;
    private String currentDirectory;

    public SSHSession(SSHConfig config, int bufferSize) {
        super(SessionType.SSH, bufferSize);
        this.config = config;
        this.currentDirectory = "~";
    }

    @Override
    public void start(int cols, int rows) throws IOException {
        if (active) {
            throw new IllegalStateException("Session already started");
        }

        this.cols = cols;
        this.rows = rows;

        try {
            initializeSSH();
            connectSession();
            openChannel();
            
            active = true;
            log.info("SSH session {} started successfully to {}@{}", 
                    sessionId, config.getUsername(), config.getHost());
            
        } catch (JSchException e) {
            close();
            throw new IOException("Failed to establish SSH connection: " + e.getMessage(), e);
        }
    }

    private void initializeSSH() throws JSchException {
        jsch = new JSch();
        
        // Configure private key authentication if provided
        if (config.getPrivateKeyPath() != null && !config.getPrivateKeyPath().isEmpty()) {
            if (config.getPassphrase() != null && !config.getPassphrase().isEmpty()) {
                jsch.addIdentity(config.getPrivateKeyPath(), config.getPassphrase());
            } else {
                jsch.addIdentity(config.getPrivateKeyPath());
            }
            log.debug("Using private key authentication from: {}", config.getPrivateKeyPath());
        }
    }

    private void connectSession() throws JSchException {
        sshSession = jsch.getSession(config.getUsername(), config.getHost(), config.getPort());
        
        // Configure session
        Properties sessionConfig = new Properties();
        sessionConfig.put("StrictHostKeyChecking", config.isStrictHostKeyChecking() ? "yes" : "no");
        sessionConfig.put("PreferredAuthentications", "publickey,password");
        sshSession.setConfig(sessionConfig);
        
        // Set password if using password authentication
        if (config.getPassword() != null && !config.getPassword().isEmpty()) {
            sshSession.setPassword(config.getPassword());
        }
        
        sshSession.setTimeout(config.getConnectionTimeout());
        
        log.info("Connecting to SSH server {}:{} as {}", 
                config.getHost(), config.getPort(), config.getUsername());
        
        sshSession.connect(config.getConnectionTimeout());
    }

    private void openChannel() throws JSchException, IOException {
        channel = (ChannelShell) sshSession.openChannel("shell");
        
        // Configure PTY
        channel.setPtyType("xterm-256color", cols, rows, cols * 8, rows * 16);
        channel.setEnv("TERM", "xterm-256color");
        channel.setEnv("COLORTERM", "truecolor");
        
        // Get streams
        InputStream channelOutput = channel.getInputStream();
        InputStream channelError = channel.getExtInputStream();
        channelInput = channel.getOutputStream();
        
        // Connect channel
        channel.connect(config.getConnectionTimeout());
        
        // Start output readers
        readerExecutor = Executors.newFixedThreadPool(2);
        readerExecutor.submit(() -> readStream(channelOutput, "stdout"));
        readerExecutor.submit(() -> readStream(channelError, "stderr"));
        
        // Monitor channel status
        CompletableFuture.runAsync(() -> {
            while (active && channel.isConnected()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (active) {
                log.info("SSH channel disconnected for session {}", sessionId);
                close();
            }
        });
    }

    private void readStream(InputStream stream, String name) {
        byte[] buffer = new byte[4096];
        int bytesRead;
        
        try {
            while (active && (bytesRead = stream.read(buffer)) != -1) {
                byte[] data = new byte[bytesRead];
                System.arraycopy(buffer, 0, data, 0, bytesRead);
                notifyOutput(data);
            }
        } catch (IOException e) {
            if (active) {
                log.error("Error reading {} for SSH session {}", name, sessionId, e);
            }
        }
        log.debug("SSH stream reader {} stopped for session {}", name, sessionId);
    }

    @Override
    public void write(byte[] data) throws IOException {
        if (!active || channelInput == null) {
            throw new IOException("SSH session is not active");
        }
        
        channelInput.write(data);
        channelInput.flush();
    }

    @Override
    public void write(String text) throws IOException {
        write(text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void resize(int cols, int rows) {
        super.resize(cols, rows);
        
        if (channel != null && channel.isConnected()) {
            channel.setPtySize(cols, rows, cols * 8, rows * 16);
            log.debug("SSH session {} PTY resized to {}x{}", sessionId, cols, rows);
        }
    }

    @Override
    public void close() {
        if (!active) {
            return;
        }
        
        active = false;
        log.info("Closing SSH session {}", sessionId);

        // Close channel input
        if (channelInput != null) {
            try {
                channelInput.close();
            } catch (IOException e) {
                log.debug("Error closing channel input", e);
            }
        }

        // Disconnect channel
        if (channel != null && channel.isConnected()) {
            channel.disconnect();
        }

        // Disconnect session
        if (sshSession != null && sshSession.isConnected()) {
            sshSession.disconnect();
        }

        // Shutdown reader threads
        if (readerExecutor != null) {
            readerExecutor.shutdownNow();
        }

        notifyClose();
        log.info("SSH session {} closed", sessionId);
    }

    @Override
    public String getCurrentDirectory() {
        return currentDirectory;
    }

    @Override
    public String executeCommand(String command, long timeoutMs) throws IOException {
        if (!active) {
            throw new IOException("SSH session is not active");
        }
        
        // Use marker technique for output capture
        String marker = "###SSH_CMD_END_" + System.currentTimeMillis() + "###";
        StringBuilder output = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        
        Consumer<byte[]> captureHandler = data -> {
            String text = new String(data, StandardCharsets.UTF_8);
            output.append(text);
            if (text.contains(marker)) {
                latch.countDown();
            }
        };
        
        outputHandlers.add(captureHandler);
        
        try {
            // Execute command with marker
            String fullCommand = command + " ; echo " + marker + "\n";
            write(fullCommand);
            
            boolean completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!completed) {
                throw new IOException("SSH command execution timed out");
            }
            
            // Extract output before marker
            String result = output.toString();
            int markerIndex = result.indexOf(marker);
            if (markerIndex > 0) {
                result = result.substring(0, markerIndex);
            }
            
            return stripAnsiCodes(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("SSH command execution interrupted", e);
        } finally {
            outputHandlers.remove(captureHandler);
        }
    }

    @Override
    protected String getCharset() {
        return StandardCharsets.UTF_8.name();
    }

    /**
     * Get SSH session info for display.
     */
    public String getConnectionInfo() {
        return String.format("%s@%s:%d", config.getUsername(), config.getHost(), config.getPort());
    }
}
