package com.aiterminal.util;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * System fingerprint inspector for detecting OS, shell, and environment details.
 */
@Slf4j
@Component
@Getter
public class SystemInspector {

    public enum OSType {
        WINDOWS, MACOS, LINUX, UNKNOWN
    }

    public enum ShellType {
        POWERSHELL, CMD, BASH, ZSH, SH, FISH, UNKNOWN
    }

    private OSType osType;
    private ShellType shellType;
    private String osName;
    private String osVersion;
    private String shellPath;
    private String shellVersion;
    private String defaultEncoding;
    private String homeDirectory;
    private String currentWorkingDirectory;
    private String username;

    @PostConstruct
    public void init() {
        detectOS();
        detectShell();
        detectEnvironment();
        logSystemInfo();
    }

    private void detectOS() {
        String os = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
        osName = System.getProperty("os.name");
        osVersion = System.getProperty("os.version");

        if (os.contains("win")) {
            osType = OSType.WINDOWS;
            defaultEncoding = "GBK";
        } else if (os.contains("mac") || os.contains("darwin")) {
            osType = OSType.MACOS;
            defaultEncoding = "UTF-8";
        } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
            osType = OSType.LINUX;
            defaultEncoding = "UTF-8";
        } else {
            osType = OSType.UNKNOWN;
            defaultEncoding = Charset.defaultCharset().name();
        }
    }

    private void detectShell() {
        shellPath = detectDefaultShellPath();
        shellType = determineShellType(shellPath);
        shellVersion = detectShellVersion();
    }

    private String detectDefaultShellPath() {
        if (osType == OSType.WINDOWS) {
            // Check for PowerShell first
            String pwshPath = findExecutable("pwsh");
            if (pwshPath != null) {
                return pwshPath;
            }
            // Fallback to Windows PowerShell
            String powershellPath = System.getenv("SYSTEMROOT") + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
            if (Paths.get(powershellPath).toFile().exists()) {
                return powershellPath;
            }
            // Fallback to cmd
            return System.getenv("COMSPEC");
        } else {
            // Unix-like systems: check SHELL environment variable
            String shell = System.getenv("SHELL");
            if (shell != null && !shell.isEmpty()) {
                return shell;
            }
            // Fallback to /bin/sh
            return "/bin/sh";
        }
    }

    private String findExecutable(String name) {
        try {
            ProcessBuilder pb = osType == OSType.WINDOWS
                    ? new ProcessBuilder("where", name)
                    : new ProcessBuilder("which", name);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                int exitCode = process.waitFor();
                if (exitCode == 0 && line != null && !line.isEmpty()) {
                    return line.trim();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to find executable: {}", name, e);
        }
        return null;
    }

    private ShellType determineShellType(String path) {
        if (path == null) {
            return ShellType.UNKNOWN;
        }
        String lowerPath = path.toLowerCase(Locale.ROOT);
        
        if (lowerPath.contains("pwsh") || lowerPath.contains("powershell")) {
            return ShellType.POWERSHELL;
        } else if (lowerPath.contains("cmd")) {
            return ShellType.CMD;
        } else if (lowerPath.contains("zsh")) {
            return ShellType.ZSH;
        } else if (lowerPath.contains("fish")) {
            return ShellType.FISH;
        } else if (lowerPath.contains("bash")) {
            return ShellType.BASH;
        } else if (lowerPath.contains("sh")) {
            return ShellType.SH;
        }
        return ShellType.UNKNOWN;
    }

    private String detectShellVersion() {
        try {
            String[] command = switch (shellType) {
                case POWERSHELL -> new String[]{shellPath, "-Command", "$PSVersionTable.PSVersion.ToString()"};
                case CMD -> new String[]{shellPath, "/c", "ver"};
                case BASH, ZSH, SH, FISH -> new String[]{shellPath, "--version"};
                default -> null;
            };

            if (command == null) {
                return "unknown";
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                process.waitFor();
                return line != null ? line.trim() : "unknown";
            }
        } catch (Exception e) {
            log.debug("Failed to detect shell version", e);
            return "unknown";
        }
    }

    private void detectEnvironment() {
        homeDirectory = System.getProperty("user.home");
        currentWorkingDirectory = System.getProperty("user.dir");
        username = System.getProperty("user.name");
    }

    private void logSystemInfo() {
        log.info("=== System Fingerprint ===");
        log.info("OS: {} {} ({})", osName, osVersion, osType);
        log.info("Shell: {} ({}) - {}", shellType, shellPath, shellVersion);
        log.info("Encoding: {}", defaultEncoding);
        log.info("User: {} @ {}", username, homeDirectory);
        log.info("CWD: {}", currentWorkingDirectory);
        if (isWindows()) {
            log.info("Windows Build: {} (ConPTY: {})", getWindowsBuild(), isConPtySupported() ? "supported" : "not supported");
        }
        log.info("==========================");
    }
    
    /**
     * Get Windows build number.
     */
    public String getWindowsBuild() {
        if (!isWindows()) {
            return "N/A";
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "ver");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("Version")) {
                        // Extract build number from "Microsoft Windows [Version 10.0.19045.3803]"
                        int start = line.indexOf("10.0.");
                        if (start > 0) {
                            int end = line.indexOf(']');
                            if (end > start) {
                                return line.substring(start + 5, end);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to get Windows build", e);
        }
        return "unknown";
    }
    
    /**
     * Check if Windows ConPTY is supported (Windows 10 1809+, build 17763+).
     */
    public boolean isConPtySupported() {
        if (!isWindows()) {
            return false;
        }
        String build = getWindowsBuild();
        if ("unknown".equals(build) || "N/A".equals(build)) {
            // If we can't determine the build, assume ConPTY is supported on modern Windows
            // since pty4j will gracefully handle it if not
            String osVersion = System.getProperty("os.version", "");
            try {
                // Windows 10+ has version 10.0.x
                if (osVersion.startsWith("10.")) {
                    return true;
                }
            } catch (Exception ignored) {}
            return true; // Default to trying ConPTY
        }
        try {
            String[] parts = build.split("\\.");
            if (parts.length > 0) {
                int buildNumber = Integer.parseInt(parts[0]);
                return buildNumber >= 17763;
            }
        } catch (Exception e) {
            log.debug("Failed to parse Windows build: {}", build, e);
            return true; // Default to trying ConPTY on parse failure
        }
        return true;
    }

    /**
     * Build system context string for AI prompts.
     */
    public String buildSystemContext() {
        return String.format("""
            System Information:
            - OS: %s %s (%s)
            - Shell: %s (%s)
            - Working Directory: %s
            - User: %s
            - Encoding: %s
            """,
            osName, osVersion, osType,
            shellType, shellPath,
            currentWorkingDirectory,
            username,
            defaultEncoding
        );
    }

    /**
     * Get the appropriate encoding for terminal operations.
     */
    public Charset getTerminalCharset() {
        return Charset.forName(defaultEncoding);
    }

    /**
     * Check if current OS is Windows.
     */
    public boolean isWindows() {
        return osType == OSType.WINDOWS;
    }

    /**
     * Check if current OS is Unix-like (macOS or Linux).
     */
    public boolean isUnixLike() {
        return osType == OSType.MACOS || osType == OSType.LINUX;
    }

    /**
     * Get shell command prefix for executing commands.
     */
    public String[] getShellCommandPrefix() {
        return switch (shellType) {
            case POWERSHELL -> new String[]{shellPath, "-NoProfile", "-Command"};
            case CMD -> new String[]{shellPath, "/c"};
            case BASH, ZSH, SH, FISH -> new String[]{shellPath, "-c"};
            default -> new String[]{shellPath, "-c"};
        };
    }

    /**
     * Get interactive shell arguments.
     */
    public String[] getInteractiveShellArgs() {
        return switch (shellType) {
            case POWERSHELL -> new String[]{shellPath, "-NoLogo", "-NoExit"};
            case CMD -> new String[]{shellPath};
            case BASH -> new String[]{shellPath, "--login", "-i"};
            case ZSH -> new String[]{shellPath, "-i"};
            case FISH -> new String[]{shellPath, "-i"};
            default -> new String[]{shellPath};
        };
    }
}
