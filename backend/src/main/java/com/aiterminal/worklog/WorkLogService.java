package com.aiterminal.worklog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
public class WorkLogService {
    private final WorkLogRepository repository;

    public WorkLogService(WorkLogRepository repository) {
        this.repository = repository;
    }

    public WorkLogEntry record(String moduleName, String eventType, String level, String sessionId,
                               String provider, String summary, String details, String status, Long durationMs) {
        WorkLogEntry entry = WorkLogEntry.builder()
                .moduleName(safe(moduleName, 64, "unknown"))
                .eventType(safe(eventType, 64, "event"))
                .level(safe(level, 16, "INFO"))
                .sessionId(safe(sessionId, 128, null))
                .provider(safe(provider, 32, null))
                .summary(safe(summary, 255, ""))
                .details(details == null ? "" : details)
                .status(safe(status, 32, "OK"))
                .durationMs(durationMs)
                .build();
        return repository.save(entry);
    }

    public Page<WorkLogEntry> query(String moduleName, String level, String sessionId, int page, int size) {
        Specification<WorkLogEntry> spec = Specification.where(null);
        if (moduleName != null && !moduleName.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("moduleName"), moduleName));
        }
        if (level != null && !level.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("level"), level));
        }
        if (sessionId != null && !sessionId.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("sessionId"), sessionId));
        }
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, 200));
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return repository.findAll(spec, pageable);
    }

    public void clear() {
        repository.deleteAllInBatch();
    }

    private String safe(String value, int maxLength, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return defaultValue;
        }
        if (trimmed.length() > maxLength) {
            return trimmed.substring(0, maxLength);
        }
        return trimmed;
    }
}
