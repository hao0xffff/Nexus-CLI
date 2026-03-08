package com.aiterminal.controller;

import com.aiterminal.terminal.CommandExecutor;
import com.aiterminal.terminal.CommandExecutor.CommandResult;
import com.aiterminal.worklog.WorkLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST Controller for direct command execution.
 * This provides clean command output for AI consumption.
 */
@Slf4j
@RestController
@RequestMapping("/api/command")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CommandController {

    private final CommandExecutor commandExecutor;
    private final WorkLogService workLogService;

    /**
     * Execute a command and return clean output.
     */
    @PostMapping("/execute")
    public Map<String, Object> executeCommand(@RequestBody Map<String, Object> request) {
        String sessionId = (String) request.get("sessionId");
        String command = (String) request.get("command");
        Long timeoutMs = request.get("timeoutMs") != null ? 
            ((Number) request.get("timeoutMs")).longValue() : 60000L;

        if (sessionId == null || sessionId.isBlank()) {
            return Map.of(
                "success", false,
                "error", "sessionId is required"
            );
        }

        if (command == null || command.isBlank()) {
            return Map.of(
                "success", false,
                "error", "command is required"
            );
        }

        log.info("API command execution request: session={}, command={}", sessionId, command);
        long startTime = System.currentTimeMillis();

        CommandResult result = commandExecutor.execute(sessionId, command, timeoutMs);
        workLogService.record(
                "COMMAND",
                "EXECUTE",
                result.isSuccess() ? "INFO" : "WARN",
                sessionId,
                null,
                "Command executed via API",
                "command=" + command + ", exitCode=" + result.getExitCode() + ", timedOut=" + result.isTimedOut(),
                result.isSuccess() ? "SUCCESS" : "FAILED",
                System.currentTimeMillis() - startTime
        );

        return Map.of(
            "success", result.isSuccess(),
            "exitCode", result.getExitCode(),
            "stdout", result.getStdout(),
            "stderr", result.getStderr(),
            "output", result.getCombinedOutput(),
            "timedOut", result.isTimedOut(),
            "cancelled", result.isCancelled(),
            "executionTimeMs", result.getExecutionTimeMs(),
            "workingDirectory", commandExecutor.getSessionDirectory(sessionId)
        );
    }

    /**
     * Get current working directory for a session.
     */
    @GetMapping("/cwd/{sessionId}")
    public Map<String, Object> getCurrentDirectory(@PathVariable String sessionId) {
        return Map.of(
            "sessionId", sessionId,
            "directory", commandExecutor.getSessionDirectory(sessionId)
        );
    }

    /**
     * Set working directory for a session.
     */
    @PostMapping("/cwd")
    public Map<String, Object> setCurrentDirectory(@RequestBody Map<String, String> request) {
        String sessionId = request.get("sessionId");
        String directory = request.get("directory");

        if (sessionId == null || directory == null) {
            return Map.of(
                "success", false,
                "error", "sessionId and directory are required"
            );
        }

        commandExecutor.setSessionDirectory(sessionId, directory);
        
        return Map.of(
            "success", true,
            "directory", commandExecutor.getSessionDirectory(sessionId)
        );
    }
}
