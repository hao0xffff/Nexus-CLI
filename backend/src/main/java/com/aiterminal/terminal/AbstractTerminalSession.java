package com.aiterminal.terminal;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Abstract base class for terminal sessions with common functionality.
 */
@Slf4j
public abstract class AbstractTerminalSession implements ITerminalSession {

    // ANSI escape sequence pattern for stripping terminal control codes
    private static final Pattern ANSI_PATTERN = Pattern.compile(
            "\\x1B(?:[@-Z\\\\-_]|\\[[0-?]*[ -/]*[@-~])"
    );

    @Getter
    protected final String sessionId;
    
    @Getter
    protected final SessionType type;
    
    protected volatile boolean active = false;
    protected int cols = 80;
    protected int rows = 24;
    
    // Output buffer for AI context
    protected final List<String> outputBuffer = new CopyOnWriteArrayList<>();
    protected final int maxBufferLines;
    
    // Event handlers
    protected final List<Consumer<byte[]>> outputHandlers = new CopyOnWriteArrayList<>();
    protected final List<Runnable> closeHandlers = new CopyOnWriteArrayList<>();

    protected AbstractTerminalSession(SessionType type, int maxBufferLines) {
        this.sessionId = UUID.randomUUID().toString();
        this.type = type;
        this.maxBufferLines = maxBufferLines;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void onOutput(Consumer<byte[]> handler) {
        if (handler != null) {
            outputHandlers.add(handler);
        }
    }

    @Override
    public void onClose(Runnable handler) {
        if (handler != null) {
            closeHandlers.add(handler);
        }
    }

    /**
     * Notify all output handlers with data.
     */
    protected void notifyOutput(byte[] data) {
        for (Consumer<byte[]> handler : outputHandlers) {
            try {
                handler.accept(data);
            } catch (Exception e) {
                log.error("Error in output handler", e);
            }
        }
        
        // Buffer output for AI context
        bufferOutput(data);
    }

    /**
     * Notify all close handlers.
     */
    protected void notifyClose() {
        for (Runnable handler : closeHandlers) {
            try {
                handler.run();
            } catch (Exception e) {
                log.error("Error in close handler", e);
            }
        }
    }

    /**
     * Buffer output for AI context retrieval.
     */
    protected void bufferOutput(byte[] data) {
        try {
            String text = new String(data, getCharset());
            String[] lines = text.split("\n");
            
            synchronized (outputBuffer) {
                for (String line : lines) {
                    if (!line.isEmpty()) {
                        outputBuffer.add(line);
                        // Keep buffer size limited
                        while (outputBuffer.size() > maxBufferLines) {
                            outputBuffer.remove(0);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error buffering output", e);
        }
    }

    @Override
    public String getRecentOutput(int lines) {
        synchronized (outputBuffer) {
            int startIndex = Math.max(0, outputBuffer.size() - lines);
            List<String> recentLines = new ArrayList<>(outputBuffer.subList(startIndex, outputBuffer.size()));
            
            // Strip ANSI escape sequences for clean AI context
            StringBuilder sb = new StringBuilder();
            for (String line : recentLines) {
                String cleanLine = stripAnsiCodes(line);
                if (!cleanLine.isBlank()) {
                    sb.append(cleanLine).append("\n");
                }
            }
            return sb.toString();
        }
    }

    /**
     * Strip ANSI escape sequences from text.
     */
    protected String stripAnsiCodes(String text) {
        if (text == null) {
            return "";
        }
        return ANSI_PATTERN.matcher(text).replaceAll("");
    }

    /**
     * Get charset for encoding/decoding.
     */
    protected abstract String getCharset();

    @Override
    public void resize(int cols, int rows) {
        this.cols = cols;
        this.rows = rows;
        log.debug("Session {} resized to {}x{}", sessionId, cols, rows);
    }
}
