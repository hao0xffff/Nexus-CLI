package com.aiterminal.terminal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manager for terminal sessions.
 * Handles session lifecycle and cleanup.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TerminalSessionManager {

    private final TerminalSessionFactory sessionFactory;
    
    private final Map<String, ITerminalSession> sessions = new ConcurrentHashMap<>();

    /**
     * Create and register a new local session.
     * Uses PTY if available (ConPTY on Windows, native PTY on Unix).
     */
    public ITerminalSession createLocalSession() {
        ITerminalSession session = sessionFactory.createLocalSession();
        registerSession(session);
        log.info("Created local session {} using {} mode", 
                session.getSessionId(), sessionFactory.getTerminalMode());
        return session;
    }

    /**
     * Create and register a new SSH session.
     */
    public ITerminalSession createSSHSession(SSHSession.SSHConfig config) {
        SSHSession session = sessionFactory.createSSHSession(config);
        registerSession(session);
        return session;
    }

    /**
     * Register a session and set up cleanup on close.
     */
    private void registerSession(ITerminalSession session) {
        sessions.put(session.getSessionId(), session);
        session.onClose(() -> {
            sessions.remove(session.getSessionId());
            log.info("Session {} removed from manager", session.getSessionId());
        });
        log.info("Session {} registered with manager", session.getSessionId());
    }

    /**
     * Get a session by ID.
     */
    public Optional<ITerminalSession> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * Get all active sessions.
     */
    public Collection<ITerminalSession> getAllSessions() {
        return sessions.values();
    }

    /**
     * Get count of active sessions.
     */
    public int getSessionCount() {
        return sessions.size();
    }

    /**
     * Close a specific session.
     */
    public boolean closeSession(String sessionId) {
        ITerminalSession session = sessions.get(sessionId);
        if (session != null) {
            session.close();
            return true;
        }
        return false;
    }

    /**
     * Close all sessions on shutdown.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down terminal session manager with {} active sessions", sessions.size());
        sessions.values().forEach(ITerminalSession::close);
        sessions.clear();
    }
}
