package com.aiterminal.terminal;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Interface for terminal session abstraction.
 * Supports both local and SSH terminal sessions.
 */
public interface ITerminalSession {

    /**
     * Session type enumeration.
     */
    enum SessionType {
        LOCAL, SSH
    }

    /**
     * Get the session identifier.
     */
    String getSessionId();

    /**
     * Get the session type.
     */
    SessionType getType();

    /**
     * Check if the session is active.
     */
    boolean isActive();

    /**
     * Start the terminal session.
     *
     * @param cols Terminal columns
     * @param rows Terminal rows
     * @throws IOException if session cannot be started
     */
    void start(int cols, int rows) throws IOException;

    /**
     * Write data to the terminal input.
     *
     * @param data Data to write
     * @throws IOException if write fails
     */
    void write(byte[] data) throws IOException;

    /**
     * Write string to the terminal input.
     *
     * @param text Text to write
     * @throws IOException if write fails
     */
    void write(String text) throws IOException;

    /**
     * Resize the terminal.
     *
     * @param cols New column count
     * @param rows New row count
     */
    void resize(int cols, int rows);

    /**
     * Register output handler to receive terminal output.
     *
     * @param handler Consumer to receive output bytes
     */
    void onOutput(Consumer<byte[]> handler);

    /**
     * Register close handler.
     *
     * @param handler Runnable to execute on session close
     */
    void onClose(Runnable handler);

    /**
     * Close the terminal session.
     */
    void close();

    /**
     * Get the current working directory.
     */
    String getCurrentDirectory();

    /**
     * Get recent output buffer (for AI context).
     *
     * @param lines Number of recent lines to retrieve
     * @return Recent terminal output
     */
    String getRecentOutput(int lines);

    /**
     * Execute a command and wait for completion.
     *
     * @param command Command to execute
     * @param timeoutMs Timeout in milliseconds
     * @return Command output
     * @throws IOException if execution fails
     */
    String executeCommand(String command, long timeoutMs) throws IOException;
}
