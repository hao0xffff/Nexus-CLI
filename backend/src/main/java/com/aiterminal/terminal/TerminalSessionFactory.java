package com.aiterminal.terminal;

import com.aiterminal.util.SystemInspector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Factory for creating terminal sessions.
 * Automatically selects the best terminal implementation based on system capabilities:
 * - PTY (pty4j): Preferred, uses ConPTY on Windows 10 1809+, native PTY on Unix
 * - ProcessBuilder: Fallback when PTY is not available
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TerminalSessionFactory {

    private final SystemInspector systemInspector;

    @Value("${terminal.buffer-size:50}")
    private int bufferSize;
    
    @Value("${terminal.prefer-pty:true}")
    private boolean preferPty;
    
    private boolean ptyAvailable = false;

    @PostConstruct
    public void init() {
        // Check if PTY support is available
        ptyAvailable = checkPtySupport();
        
        if (ptyAvailable) {
            log.info("PTY support available - using native terminal (ConPTY on Windows, PTY on Unix)");
        } else {
            log.warn("PTY support not available - falling back to ProcessBuilder (may have input/output sync issues)");
        }
    }
    
    /**
     * Check if PTY is supported on current system.
     */
    private boolean checkPtySupport() {
        try {
            // Try to load pty4j classes
            Class.forName("com.pty4j.PtyProcess");
            Class.forName("com.pty4j.PtyProcessBuilder");
            
            // Additional Windows check: ConPTY requires Windows 10 1809+ (build 17763+)
            if (systemInspector.isWindows()) {
                String osVersion = System.getProperty("os.version", "");
                String buildNumber = getWindowsBuildNumber();
                if (buildNumber != null) {
                    try {
                        int build = Integer.parseInt(buildNumber);
                        if (build < 17763) {
                            log.warn("Windows build {} is below 17763, ConPTY not available", build);
                            return false;
                        }
                    } catch (NumberFormatException e) {
                        // Continue anyway
                    }
                }
            }
            
            return true;
        } catch (ClassNotFoundException e) {
            log.debug("pty4j not available: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Get Windows build number for ConPTY compatibility check.
     */
    private String getWindowsBuildNumber() {
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "ver");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Parse: "Microsoft Windows [Version 10.0.19045.3803]"
                    if (line.contains("Version")) {
                        int start = line.lastIndexOf('.');
                        int end = line.lastIndexOf(']');
                        if (start > 0 && end > start) {
                            // Get the build number (third part)
                            String version = line.substring(line.indexOf("10.0.") + 5, end);
                            String[] parts = version.split("\\.");
                            if (parts.length > 0) {
                                return parts[0];
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to get Windows build number", e);
        }
        return null;
    }

    /**
     * Create a new local terminal session.
     * Uses PTY if available, otherwise falls back to ProcessBuilder.
     */
    public ITerminalSession createLocalSession() {
        if (preferPty && ptyAvailable) {
            return createPtySession();
        } else {
            return createProcessBuilderSession();
        }
    }
    
    /**
     * Create a PTY-based session (preferred).
     */
    private ITerminalSession createPtySession() {
        PtySession session = new PtySession(systemInspector, bufferSize);
        log.debug("Created PTY session: {} (ConPTY: {})", 
                session.getSessionId(), systemInspector.isWindows());
        return session;
    }
    
    /**
     * Create a ProcessBuilder-based session (fallback).
     */
    private ITerminalSession createProcessBuilderSession() {
        LocalSession session = new LocalSession(systemInspector, bufferSize);
        log.debug("Created ProcessBuilder session: {}", session.getSessionId());
        return session;
    }
    
    /**
     * Check if PTY mode is being used.
     */
    public boolean isPtyMode() {
        return preferPty && ptyAvailable;
    }
    
    /**
     * Get terminal mode description.
     */
    public String getTerminalMode() {
        if (preferPty && ptyAvailable) {
            return systemInspector.isWindows() ? "ConPTY" : "PTY";
        }
        return "ProcessBuilder";
    }

    /**
     * Create a new SSH terminal session.
     */
    public SSHSession createSSHSession(SSHSession.SSHConfig config) {
        SSHSession session = new SSHSession(config, bufferSize);
        log.debug("Created SSH session: {} for {}@{}", 
                session.getSessionId(), config.getUsername(), config.getHost());
        return session;
    }

    /**
     * Create SSH session from simplified parameters.
     */
    public SSHSession createSSHSession(String host, int port, String username, String password) {
        SSHSession.SSHConfig config = SSHSession.SSHConfig.builder()
                .host(host)
                .port(port)
                .username(username)
                .password(password)
                .build();
        return createSSHSession(config);
    }

    /**
     * Create SSH session with private key authentication.
     */
    public SSHSession createSSHSessionWithKey(String host, int port, String username, 
                                               String privateKeyPath, String passphrase) {
        SSHSession.SSHConfig config = SSHSession.SSHConfig.builder()
                .host(host)
                .port(port)
                .username(username)
                .privateKeyPath(privateKeyPath)
                .passphrase(passphrase)
                .build();
        return createSSHSession(config);
    }
}
