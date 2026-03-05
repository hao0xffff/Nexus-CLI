package com.aiterminal.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for command validation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidateCommandRequest {
    
    @NotBlank(message = "Command is required")
    private String command;
}
