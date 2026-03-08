package com.aiterminal.worklog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface WorkLogRepository extends JpaRepository<WorkLogEntry, Long>, JpaSpecificationExecutor<WorkLogEntry> {
}
