package com.aiterminal.ai;

/**
 * Enumeration of supported AI providers.
 */
public enum AIProvider {
    OLLAMA("ollama"),
    OPENAI("openai"),
    CUSTOM("custom");

    private final String value;

    AIProvider(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static AIProvider fromString(String value) {
        for (AIProvider provider : values()) {
            if (provider.value.equalsIgnoreCase(value)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("Unknown AI provider: " + value);
    }
}
