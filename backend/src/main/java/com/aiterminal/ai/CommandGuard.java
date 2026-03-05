package com.aiterminal.ai;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Security interceptor for validating AI-generated commands.
 * Detects and blocks dangerous commands before execution.
 */
@Slf4j
@Component
public class CommandGuard {

    @Value("${terminal.dangerous-commands:}")
    private List<String> dangerousPatterns;

    private List<Pattern> compiledPatterns;

    @Getter
    public static class ValidationResult {
        private final boolean safe;
        private final String warning;
        private final RiskLevel riskLevel;

        public ValidationResult(boolean safe, String warning, RiskLevel riskLevel) {
            this.safe = safe;
            this.warning = warning;
            this.riskLevel = riskLevel;
        }

        public static ValidationResult safe() {
            return new ValidationResult(true, null, RiskLevel.SAFE);
        }

        public static ValidationResult warning(String message) {
            return new ValidationResult(true, message, RiskLevel.WARNING);
        }

        public static ValidationResult dangerous(String message) {
            return new ValidationResult(false, message, RiskLevel.DANGEROUS);
        }
    }

    public enum RiskLevel {
        SAFE,
        WARNING,
        DANGEROUS
    }

    // Built-in dangerous patterns
    private static final List<String> BUILTIN_DANGEROUS_PATTERNS = List.of(
            // Unix dangerous commands
            "rm\\s+(-[rf]+\\s+)*[/\\\\]\\s*$",           // rm -rf /
            "rm\\s+(-[rf]+\\s+)*[/\\\\]\\*",             // rm -rf /*
            "rm\\s+(-[rf]+\\s+)*~",                       // rm -rf ~
            "mkfs\\s+",                                   // mkfs commands
            "dd\\s+if=/dev/(zero|random|urandom)",       // dd dangerous
            ":\\(\\)\\s*\\{\\s*:\\|:\\s*&\\s*\\}\\s*;\\s*:", // Fork bomb
            ">(\\s*/dev/sd[a-z]|\\s*/dev/hd[a-z])",       // Overwrite disk
            "chmod\\s+(-R\\s+)?777\\s+/",                 // Chmod 777 root
            "chown\\s+(-R\\s+)?.*\\s+/\\s*$",            // Chown root
            "wget.*\\|.*sh",                              // Download and execute
            "curl.*\\|.*sh",                              // Download and execute
            "mv\\s+/\\s+",                               // Move root
            "mv\\s+~\\s+",                               // Move home
            
            // Windows dangerous commands
            "format\\s+[a-zA-Z]:",                        // Format drive
            "del\\s+/[sf].*[/\\\\]\\*",                  // Delete system files
            "rd\\s+/[sq].*[/\\\\]Windows",               // Remove Windows
            "rd\\s+/[sq].*[/\\\\]System32",              // Remove System32
            "reg\\s+delete\\s+HKLM",                      // Delete registry
            "bcdedit\\s+/delete",                         // Delete boot config
            "diskpart",                                   // Disk partitioning
            
            // Database dangerous
            "DROP\\s+DATABASE",                           // Drop database
            "DROP\\s+TABLE.*\\*",                        // Drop all tables
            "TRUNCATE\\s+",                              // Truncate
            "DELETE\\s+FROM\\s+\\w+\\s*;?\\s*$"         // Delete all from table
    );

    // Warning patterns (not blocked, but flagged)
    private static final List<String> WARNING_PATTERNS = List.of(
            "rm\\s+-[rf]",                               // rm with force/recursive
            "sudo\\s+",                                  // sudo commands
            "su\\s+-",                                   // Switch user
            "chmod\\s+",                                 // Permission changes
            "chown\\s+",                                 // Ownership changes
            "kill\\s+-9",                               // Force kill
            "pkill\\s+",                                // Process kill
            "shutdown",                                  // Shutdown
            "reboot",                                   // Reboot
            "systemctl\\s+stop",                        // Stop services
            "service\\s+.*\\s+stop",                    // Stop services
            "iptables\\s+-F",                           // Flush firewall
            "firewall-cmd",                             // Firewall changes
            "net\\s+stop",                              // Windows stop service
            "taskkill\\s+/f",                           // Windows force kill
            "runas\\s+"                                 // Windows runas
    );

    @PostConstruct
    public void init() {
        compiledPatterns = new ArrayList<>();
        
        // Compile built-in patterns
        for (String pattern : BUILTIN_DANGEROUS_PATTERNS) {
            try {
                compiledPatterns.add(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE));
            } catch (Exception e) {
                log.warn("Failed to compile pattern: {}", pattern, e);
            }
        }
        
        // Compile configured patterns
        if (dangerousPatterns != null) {
            for (String pattern : dangerousPatterns) {
                try {
                    compiledPatterns.add(Pattern.compile(Pattern.quote(pattern), Pattern.CASE_INSENSITIVE));
                } catch (Exception e) {
                    log.warn("Failed to compile configured pattern: {}", pattern, e);
                }
            }
        }
        
        log.info("CommandGuard initialized with {} dangerous patterns", compiledPatterns.size());
    }

    /**
     * Validate a command for safety.
     */
    public ValidationResult validate(String command) {
        if (command == null || command.isBlank()) {
            return ValidationResult.safe();
        }

        String normalizedCommand = normalizeCommand(command);
        
        // Check dangerous patterns first
        for (Pattern pattern : compiledPatterns) {
            if (pattern.matcher(normalizedCommand).find()) {
                String warning = String.format(
                        "DANGEROUS COMMAND BLOCKED: This command matches a dangerous pattern and has been blocked for safety. " +
                        "Command: '%s'. If you really need to run this, execute it manually in the terminal.",
                        command
                );
                log.warn("Blocked dangerous command: {}", command);
                return ValidationResult.dangerous(warning);
            }
        }

        // Check warning patterns
        for (String pattern : WARNING_PATTERNS) {
            if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(normalizedCommand).find()) {
                String warning = String.format(
                        "CAUTION: This command ('%s') may have significant effects. Please review carefully before executing.",
                        command
                );
                log.info("Warning for command: {}", command);
                return ValidationResult.warning(warning);
            }
        }

        return ValidationResult.safe();
    }

    /**
     * Normalize command for pattern matching.
     */
    private String normalizeCommand(String command) {
        return command
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }

    /**
     * Check if a command is safe to execute.
     */
    public boolean isSafe(String command) {
        return validate(command).isSafe();
    }

    /**
     * Get risk level for a command.
     */
    public RiskLevel getRiskLevel(String command) {
        return validate(command).getRiskLevel();
    }

    /**
     * Add a custom dangerous pattern at runtime.
     */
    public void addDangerousPattern(String pattern) {
        try {
            Pattern compiled = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            compiledPatterns.add(compiled);
            log.info("Added dangerous pattern: {}", pattern);
        } catch (Exception e) {
            log.error("Failed to add pattern: {}", pattern, e);
        }
    }
}
