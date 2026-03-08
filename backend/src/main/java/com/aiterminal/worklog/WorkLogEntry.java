package com.aiterminal.worklog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "work_logs", indexes = {
        @Index(name = "idx_work_logs_created_at", columnList = "createdAt"),
        @Index(name = "idx_work_logs_module_name", columnList = "moduleName"),
        @Index(name = "idx_work_logs_level", columnList = "level"),
        @Index(name = "idx_work_logs_session_id", columnList = "sessionId")
})
public class WorkLogEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String moduleName;

    @Column(nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false, length = 16)
    private String level;

    @Column(length = 128)
    private String sessionId;

    @Column(length = 32)
    private String provider;

    @Column(nullable = false, length = 255)
    private String summary;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(length = 32)
    private String status;

    private Long durationMs;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
