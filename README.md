# AI Terminal

A cross-platform AI-powered terminal application built with Spring Boot 3, Electron, and React.

## Features

- **Multi-mode Terminal**: Local shell and SSH session support
- **Cross-Platform**: Works on Windows, macOS, and Linux
- **AI Integration**: Ollama (local) and OpenAI support for intelligent command assistance
- **Smart Context**: Automatic system fingerprint injection and terminal output context for AI
- **Command Safety**: Built-in dangerous command detection and blocking
- **Modern UI**: Beautiful terminal interface with xterm.js

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Electron App                             │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                    React Frontend                          │  │
│  │  ┌─────────────────────┐  ┌─────────────────────────────┐ │  │
│  │  │    xterm.js         │  │      AI Sidebar             │ │  │
│  │  │    Terminal         │  │  - Chat Interface           │ │  │
│  │  │                     │  │  - Command Cards            │ │  │
│  │  │                     │  │  - Markdown Rendering       │ │  │
│  │  └─────────────────────┘  └─────────────────────────────┘ │  │
│  └───────────────────────────────────────────────────────────┘  │
│                              │ WebSocket                         │
└──────────────────────────────┼──────────────────────────────────┘
                               │
┌──────────────────────────────┼──────────────────────────────────┐
│                    Spring Boot Backend                          │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  WebSocket Handler ←──→ Terminal Session Manager          │  │
│  │        │                       │                           │  │
│  │        ▼                       ▼                           │  │
│  │  ┌──────────────┐    ┌──────────────────┐                 │  │
│  │  │ LocalSession │    │   SSHSession     │                 │  │
│  │  │ ProcessBuilder│    │     JSch        │                 │  │
│  │  └──────────────┘    └──────────────────┘                 │  │
│  └───────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                    AI Service                              │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐ │  │
│  │  │Context Builder│  │ CommandGuard │  │  AI Clients     │ │  │
│  │  │System Inspect │  │   Security   │  │ Ollama/OpenAI   │ │  │
│  │  └──────────────┘  └──────────────┘  └──────────────────┘ │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

## Prerequisites

- Java 21+
- Node.js 18+
- Maven 3.8+
- Ollama (optional, for local AI)

## Quick Start

### 1. Build Backend

```bash
cd backend
mvn clean package -DskipTests
```

### 2. Install Frontend Dependencies

```bash
cd frontend
npm install
```

### 3. Development Mode

Start backend:
```bash
cd backend
mvn spring-boot:run
```

Start frontend (in another terminal):
```bash
cd frontend
npm run dev
```

### 4. Production Build

```bash
# Build backend JAR
cd backend
mvn clean package -DskipTests

# Build Electron app
cd frontend
npm run build:electron
```

## Configuration

### Backend Configuration (application.yml)

```yaml
server:
  port: 8080

spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: llama3
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o

terminal:
  buffer-size: 50
  dangerous-commands:
    - "rm -rf /"
    - "mkfs"
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| OPENAI_API_KEY | OpenAI API key | - |
| OLLAMA_BASE_URL | Ollama server URL | http://localhost:11434 |

## Project Structure

```
AITerminal/
├── backend/                    # Spring Boot backend
│   ├── src/main/java/com/aiterminal/
│   │   ├── terminal/          # Terminal session management
│   │   │   ├── ITerminalSession.java
│   │   │   ├── LocalSession.java
│   │   │   └── SSHSession.java
│   │   ├── ai/                # AI integration
│   │   │   ├── AIService.java
│   │   │   ├── ContextBuilder.java
│   │   │   └── CommandGuard.java
│   │   ├── websocket/         # WebSocket handlers
│   │   ├── controller/        # REST controllers
│   │   └── util/              # Utilities
│   └── pom.xml
│
├── frontend/                   # Electron + React frontend
│   ├── electron/              # Electron main process
│   │   ├── main.ts
│   │   └── preload.ts
│   ├── src/                   # React application
│   │   ├── components/        # UI components
│   │   │   ├── Terminal.tsx
│   │   │   ├── AISidebar.tsx
│   │   │   └── CommandCard.tsx
│   │   └── contexts/          # React contexts
│   └── package.json
│
└── README.md
```

## API Reference

### WebSocket Protocol

Connect to `ws://localhost:8080/ws/terminal`

#### Messages

**Initialize Local Terminal:**
```json
{
  "type": "init",
  "cols": 80,
  "rows": 24
}
```

**Initialize SSH Terminal:**
```json
{
  "type": "init_ssh",
  "cols": 80,
  "rows": 24,
  "host": "example.com",
  "port": 22,
  "username": "user",
  "password": "pass"
}
```

**Resize Terminal:**
```json
{
  "type": "resize",
  "cols": 120,
  "rows": 40
}
```

### REST API

**Chat with AI:**
```
POST /api/ai/chat
{
  "message": "How do I list all files?",
  "provider": "ollama",
  "terminalOutput": "...",
  "recentLines": 50
}
```

**Validate Command:**
```
POST /api/ai/validate-command
{
  "command": "rm -rf /"
}
```

## AI Command Format

When AI suggests commands, it uses this format:
```
`command:{"cmd":"ls -la", "desc":"List all files with details"}`
```

This renders as a clickable card in the UI.

## Security

The CommandGuard intercepts all AI-generated commands and:
- Blocks dangerous commands (rm -rf /, mkfs, etc.)
- Warns about potentially risky commands (sudo, chmod, etc.)
- Allows safe commands to execute with one click

## License

MIT License
