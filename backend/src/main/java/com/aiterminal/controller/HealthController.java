package com.aiterminal.controller;

import com.aiterminal.terminal.TerminalSessionManager;
import com.aiterminal.util.SystemInspector;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Health check and system info controller.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final SystemInspector systemInspector;
    private final TerminalSessionManager sessionManager;

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "timestamp", System.currentTimeMillis()
        );
    }

    @GetMapping("/system-info")
    public Map<String, Object> systemInfo() {
        return Map.of(
                "os", Map.of(
                        "type", systemInspector.getOsType(),
                        "name", systemInspector.getOsName(),
                        "version", systemInspector.getOsVersion()
                ),
                "shell", Map.of(
                        "type", systemInspector.getShellType(),
                        "path", systemInspector.getShellPath(),
                        "version", systemInspector.getShellVersion()
                ),
                "encoding", systemInspector.getDefaultEncoding(),
                "user", Map.of(
                        "name", systemInspector.getUsername(),
                        "home", systemInspector.getHomeDirectory()
                ),
                "cwd", systemInspector.getCurrentWorkingDirectory(),
                "activeSessions", sessionManager.getSessionCount()
        );
    }
}
