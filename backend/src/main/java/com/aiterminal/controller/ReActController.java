package com.aiterminal.controller;

import com.aiterminal.ai.react.ReActAgent;
import com.aiterminal.ai.react.ReActStatus;
import com.aiterminal.ai.react.dto.ReActRequest;
import com.aiterminal.ai.react.dto.ReActStepResponse;
import com.aiterminal.worklog.WorkLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST Controller for ReAct (autonomous agent) endpoints.
 */
@Slf4j
@RestController
@RequestMapping("/api/react")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ReActController {

    private final ReActAgent reactAgent;
    private final ObjectMapper objectMapper;
    private final WorkLogService workLogService;

    // Track active tasks for cancellation
    private final Map<String, Boolean> activeTasks = new ConcurrentHashMap<>();
    
    // Track emitter status to avoid sending after completion
    private final Map<String, Boolean> emitterActive = new ConcurrentHashMap<>();

    /**
     * Start a ReAct task with Server-Sent Events for real-time updates.
     */
    @PostMapping(value = "/execute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter executeTask(@RequestBody ReActRequest request) {
        log.info("Starting ReAct task: {} for session: {}", request.getTask(), request.getSessionId());
        workLogService.record(
                "REACT",
                "TASK_START",
                "INFO",
                request.getSessionId(),
                null,
                "ReAct task started",
                request.getTask(),
                "RUNNING",
                null
        );
        
        SseEmitter emitter = new SseEmitter(600000L); // 10 minute timeout for long tasks
        String taskId = request.getSessionId() + "-" + System.currentTimeMillis();
        
        activeTasks.put(taskId, true);
        emitterActive.put(taskId, true);

        emitter.onCompletion(() -> {
            log.info("ReAct SSE completed for task: {}", taskId);
            activeTasks.remove(taskId);
            emitterActive.remove(taskId); // Remove instead of put(false) to avoid memory leak
        });
        
        emitter.onTimeout(() -> {
            log.warn("ReAct SSE timeout for task: {}", taskId);
            activeTasks.remove(taskId);
            emitterActive.remove(taskId);
        });
        
        emitter.onError(e -> {
            log.error("ReAct SSE error for task: {}", taskId, e);
            activeTasks.remove(taskId);
            emitterActive.remove(taskId);
        });

        reactAgent.executeTask(request.getSessionId(), request.getTask(), step -> {
            // Check if task was cancelled
            if (!activeTasks.getOrDefault(taskId, false)) {
                step.setStatus(ReActStatus.CANCELLED);
            }
            
            // Only send if emitter is still active
            if (!emitterActive.getOrDefault(taskId, false)) {
                log.warn("SSE emitter already closed, skipping step: {}", step.getStepNumber());
                return;
            }
            
            try {
                ReActStepResponse response = ReActStepResponse.from(step);
                String json = objectMapper.writeValueAsString(response);
                
                synchronized (emitter) {
                    if (emitterActive.getOrDefault(taskId, false)) {
                        emitter.send(SseEmitter.event()
                                .name("step")
                                .data(json));
                        
                        // Complete emitter on terminal states
                        if (step.getStatus() == ReActStatus.COMPLETED || 
                            step.getStatus() == ReActStatus.ERROR ||
                            step.getStatus() == ReActStatus.CANCELLED) {
                            workLogService.record(
                                    "REACT",
                                    "TASK_FINISH",
                                    step.getStatus() == ReActStatus.COMPLETED ? "INFO" : "WARN",
                                    request.getSessionId(),
                                    null,
                                    "ReAct task finished",
                                    step.getOutput() == null ? "" : step.getOutput(),
                                    step.getStatus().name(),
                                    null
                            );
                            emitterActive.remove(taskId);
                            emitter.complete();
                        }
                    }
                }
            } catch (IOException e) {
                log.error("Failed to send SSE event: {}", e.getMessage());
                emitterActive.remove(taskId);
                try {
                    emitter.completeWithError(e);
                } catch (Exception ex) {
                    // Ignore - emitter may already be closed
                }
            } catch (IllegalStateException e) {
                // Emitter already completed
                log.warn("SSE emitter state error: {}", e.getMessage());
                emitterActive.remove(taskId);
            }
        });

        return emitter;
    }

    /**
     * Cancel a running ReAct task.
     */
    @PostMapping("/cancel/{sessionId}")
    public Map<String, Object> cancelTask(@PathVariable String sessionId) {
        log.info("Cancelling ReAct task for session: {}", sessionId);
        workLogService.record(
                "REACT",
                "TASK_CANCEL",
                "WARN",
                sessionId,
                null,
                "ReAct task cancellation requested",
                "",
                "CANCELLED",
                null
        );
        
        // Find and cancel active tasks for this session
        activeTasks.keySet().stream()
                .filter(k -> k.startsWith(sessionId))
                .forEach(k -> activeTasks.put(k, false));

        reactAgent.cancelTask(sessionId);
        
        return Map.of(
                "success", true,
                "message", "Task cancellation requested"
        );
    }

    /**
     * Check if ReAct mode is available.
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        return Map.of(
                "available", true,
                "maxSteps", 30, // Match ReActAgent.MAX_STEPS
                "features", Map.of(
                        "execute", true,
                        "observe", true,
                        "cancel", true
                )
        );
    }
}
