package com.aiterminal.controller;

import com.aiterminal.worklog.WorkLogEntry;
import com.aiterminal.worklog.WorkLogService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/work-logs")
@CrossOrigin(origins = "*")
public class WorkLogController {
    private final WorkLogService workLogService;

    public WorkLogController(WorkLogService workLogService) {
        this.workLogService = workLogService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String sessionId) {
        Page<WorkLogEntry> result = workLogService.query(module, level, sessionId, page, size);
        List<Map<String, Object>> content = result.getContent().stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("module", item.getModuleName());
            row.put("eventType", item.getEventType());
            row.put("level", item.getLevel());
            row.put("sessionId", item.getSessionId() == null ? "" : item.getSessionId());
            row.put("provider", item.getProvider() == null ? "" : item.getProvider());
            row.put("summary", item.getSummary());
            row.put("details", item.getDetails() == null ? "" : item.getDetails());
            row.put("status", item.getStatus() == null ? "" : item.getStatus());
            row.put("durationMs", item.getDurationMs() == null ? 0L : item.getDurationMs());
            row.put("createdAt", item.getCreatedAt().toString());
            return row;
        }).toList();
        return ResponseEntity.ok(Map.of(
                "content", content,
                "page", result.getNumber(),
                "size", result.getSize(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages()
        ));
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> clear() {
        workLogService.clear();
        return ResponseEntity.ok(Map.of("success", true));
    }
}
