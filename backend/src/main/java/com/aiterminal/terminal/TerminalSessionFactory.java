package com.aiterminal.terminal;

import com.aiterminal.util.SystemInspector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Factory for creating terminal sessions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TerminalSessionFactory {

    private final SystemInspector systemInspector;

    @Value("${terminal.buffer-size:50}")
    private int bufferSize;

    /**
     * Create a new local terminal session.
     */
    public LocalSession createLocalSession() {
        LocalSession session = new LocalSession(systemInspector, bufferSize);
        log.debug("Created local session: {}", session.getSessionId());
        return session;
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
