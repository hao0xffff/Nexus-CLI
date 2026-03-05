package com.aiterminal.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Command card DTO for clickable command buttons.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommandCard {
    private String command;
    private String description;
    private boolean safe;
    private String warning;
    private String riskLevel;  // SAFE, WARNING, DANGEROUS
}
