package com.aiterminal.websocket;

import com.aiterminal.terminal.ITerminalSession;
import com.aiterminal.terminal.SSHSession;
import com.aiterminal.terminal.TerminalSessionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler for terminal communication.
 * Supports binary (terminal I/O) and text (control messages) communication.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TerminalWebSocketHandler extends AbstractWebSocketHandler {

    private final TerminalSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    // Map WebSocket sessions to terminal sessions
    private final Map<String, ITerminalSession> wsToTerminal = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> terminalToWs = new ConcurrentHashMap<>();
    
    // Lock objects for synchronized WebSocket sends
    private final Map<String, Object> wsLocks = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("WebSocket connection established: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("Received text message from {}: {}", session.getId(), payload);

        try {
            JsonNode json = objectMapper.readTree(payload);
            String type = json.path("type").asText();

            switch (type) {
                case "init" -> handleInit(session, json);
                case "init_ssh" -> handleInitSSH(session, json);
                case "resize" -> handleResize(session, json);
                case "input" -> handleInput(session, json);
                case "ping" -> handlePing(session);
                case "close" -> handleClose(session);
                default -> sendError(session, "Unknown message type: " + type);
            }
        } catch (Exception e) {
            log.error("Error handling text message", e);
            sendError(session, "Error processing message: " + e.getMessage());
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        ITerminalSession terminal = wsToTerminal.get(session.getId());
        if (terminal != null && terminal.isActive()) {
            byte[] data = message.getPayload().array();
            terminal.write(data);
        }
    }

    private void handleInit(WebSocketSession wsSession, JsonNode json) throws IOException {
        int cols = json.path("cols").asInt(80);
        int rows = json.path("rows").asInt(24);

        ITerminalSession terminal = sessionManager.createLocalSession();
        linkSessions(wsSession, terminal);

        terminal.start(cols, rows);

        sendMessage(wsSession, Map.of(
                "type", "ready",
                "sessionId", terminal.getSessionId(),
                "sessionType", "local"
        ));

        log.info("Local terminal session {} started for WebSocket {}", 
                terminal.getSessionId(), wsSession.getId());
    }

    private void handleInitSSH(WebSocketSession wsSession, JsonNode json) throws IOException {
        int cols = json.path("cols").asInt(80);
        int rows = json.path("rows").asInt(24);
        String host = json.path("host").asText();
        int port = json.path("port").asInt(22);
        String username = json.path("username").asText();
        String password = json.path("password").asText("");
        String privateKey = json.path("privateKey").asText("");
        String passphrase = json.path("passphrase").asText("");

        SSHSession.SSHConfig.SSHConfigBuilder configBuilder = SSHSession.SSHConfig.builder()
                .host(host)
                .port(port)
                .username(username);

        if (!privateKey.isEmpty()) {
            configBuilder.privateKeyPath(privateKey).passphrase(passphrase);
        } else {
            configBuilder.password(password);
        }

        ITerminalSession terminal = sessionManager.createSSHSession(configBuilder.build());
        linkSessions(wsSession, terminal);

        try {
            terminal.start(cols, rows);
            sendMessage(wsSession, Map.of(
                    "type", "ready",
                    "sessionId", terminal.getSessionId(),
                    "sessionType", "ssh",
                    "connectionInfo", ((SSHSession) terminal).getConnectionInfo()
            ));
            log.info("SSH terminal session {} started for WebSocket {}", 
                    terminal.getSessionId(), wsSession.getId());
        } catch (IOException e) {
            wsToTerminal.remove(wsSession.getId());
            sendError(wsSession, "SSH connection failed: " + e.getMessage());
        }
    }

    private void linkSessions(WebSocketSession wsSession, ITerminalSession terminal) {
        wsToTerminal.put(wsSession.getId(), terminal);
        terminalToWs.put(terminal.getSessionId(), wsSession);
        
        // Create lock for this WebSocket session
        Object lock = new Object();
        wsLocks.put(wsSession.getId(), lock);

        // Forward terminal output to WebSocket (synchronized to prevent concurrent writes)
        terminal.onOutput(data -> {
            Object wsLock = wsLocks.get(wsSession.getId());
            if (wsLock != null) {
                synchronized (wsLock) {
                    try {
                        if (wsSession.isOpen()) {
                            wsSession.sendMessage(new BinaryMessage(ByteBuffer.wrap(data)));
                        }
                    } catch (IOException e) {
                        log.error("Error sending terminal output to WebSocket", e);
                    }
                }
            }
        });

        // Handle terminal close
        terminal.onClose(() -> {
            Object wsLock = wsLocks.get(wsSession.getId());
            if (wsLock != null) {
                synchronized (wsLock) {
                    try {
                        // Double check session is still open before sending
                        if (wsSession.isOpen()) {
                            sendMessageInternal(wsSession, Map.of("type", "closed"));
                        }
                    } catch (Exception e) {
                        // Ignore - session may have been closed concurrently
                        log.debug("Could not send close notification (session likely closed)", e);
                    }
                }
            }
            terminalToWs.remove(terminal.getSessionId());
            wsLocks.remove(wsSession.getId());
        });
    }

    private void handleResize(WebSocketSession wsSession, JsonNode json) {
        int cols = json.path("cols").asInt();
        int rows = json.path("rows").asInt();

        ITerminalSession terminal = wsToTerminal.get(wsSession.getId());
        if (terminal != null) {
            terminal.resize(cols, rows);
            log.debug("Terminal {} resized to {}x{}", terminal.getSessionId(), cols, rows);
        }
    }

    private void handleInput(WebSocketSession wsSession, JsonNode json) throws IOException {
        String input = json.path("data").asText();
        ITerminalSession terminal = wsToTerminal.get(wsSession.getId());
        if (terminal != null && terminal.isActive()) {
            terminal.write(input);
        }
    }

    private void handlePing(WebSocketSession wsSession) throws IOException {
        sendMessage(wsSession, Map.of("type", "pong"));
    }

    private void handleClose(WebSocketSession wsSession) {
        ITerminalSession terminal = wsToTerminal.remove(wsSession.getId());
        if (terminal != null) {
            terminal.close();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("WebSocket connection closed: {} with status {}", session.getId(), status);
        handleClose(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error for session {}", session.getId(), exception);
        handleClose(session);
    }

    private void sendMessage(WebSocketSession session, Map<String, Object> data) throws IOException {
        Object lock = wsLocks.get(session.getId());
        if (lock != null) {
            synchronized (lock) {
                sendMessageInternal(session, data);
            }
        } else {
            sendMessageInternal(session, data);
        }
    }
    
    private void sendMessageInternal(WebSocketSession session, Map<String, Object> data) throws IOException {
        if (session.isOpen()) {
            String json = objectMapper.writeValueAsString(data);
            session.sendMessage(new TextMessage(json));
        }
    }

    private void sendError(WebSocketSession session, String error) throws IOException {
        sendMessage(session, Map.of("type", "error", "message", error));
    }

    /**
     * Get terminal session for a WebSocket session.
     */
    public ITerminalSession getTerminalForWebSocket(String wsSessionId) {
        return wsToTerminal.get(wsSessionId);
    }
}
