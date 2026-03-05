# AI Terminal

A cross-platform AI-powered terminal application built with Spring Boot 3, Electron, and React.

## Features

- **Multi-mode Terminal**: Local shell and SSH session support
- **Cross-Platform**: Works on Windows, macOS, and Linux
- **AI Integration**: Ollama (local), OpenAI, DeepSeek, and custom API support
- **ReAct Agent Mode**: Autonomous multi-step task execution with AI
- **Smart Context**: Automatic system fingerprint injection and terminal output context
- **Command Safety**: Built-in dangerous command detection and blocking
- **Hot-pluggable LLM**: Switch between AI providers without restart
- **Modern UI**: Beautiful terminal interface with xterm.js

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Electron Main Process                          │
│  - Manages application lifecycle                                         │
│  - Spawns and monitors Java backend process                             │
│  - Handles IPC communication with renderer                              │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │ IPC
┌────────────────────────────────┼────────────────────────────────────────┐
│                        React Frontend (Renderer)                         │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  TerminalContext          │  AIContext                          │    │
│  │  - Session management     │  - Chat history                     │    │
│  │  - WebSocket connection   │  - Command execution                │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│  ┌──────────────────────┐  ┌─────────────────┐  ┌────────────────┐     │
│  │   xterm.js Terminal  │  │   AI Sidebar    │  │  ReAct Panel   │     │
│  │   - Input/Output     │  │   - Chat UI     │  │  - Agent Mode  │     │
│  │   - PTY rendering    │  │   - Commands    │  │  - Task Steps  │     │
│  └──────────────────────┘  └─────────────────┘  └────────────────┘     │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │ WebSocket / REST API
┌────────────────────────────────┼────────────────────────────────────────┐
│                        Spring Boot Backend                               │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  WebSocket Handler                                               │    │
│  │  - Binary data for terminal I/O                                  │    │
│  │  - JSON messages for control (init, resize, close)              │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  Terminal Layer                                                  │    │
│  │  ┌──────────────┐  ┌──────────────┐  ┌────────────────────┐    │    │
│  │  │ PtySession   │  │ LocalSession │  │ SSHSession         │    │    │
│  │  │ (pty4j)      │  │ (fallback)   │  │ (JSch)             │    │    │
│  │  │ ConPTY/PTY   │  │ ProcessBuilder│  │ Remote terminals  │    │    │
│  │  └──────────────┘  └──────────────┘  └────────────────────┘    │    │
│  │  ┌──────────────────────────────────────────────────────┐       │    │
│  │  │ CommandExecutor - Independent process for AI output  │       │    │
│  │  └──────────────────────────────────────────────────────┘       │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  AI Layer                                                        │    │
│  │  ┌──────────────┐  ┌──────────────┐  ┌────────────────────┐    │    │
│  │  │ AIService    │  │ ReActAgent   │  │ LLMConfigService   │    │    │
│  │  │ Chat API     │  │ Autonomous   │  │ Hot-plug config    │    │    │
│  │  └──────────────┘  └──────────────┘  └────────────────────┘    │    │
│  │  ┌──────────────┐  ┌──────────────┐  ┌────────────────────┐    │    │
│  │  │ContextBuilder│  │ CommandGuard │  │ SystemInspector    │    │    │
│  │  │ AI prompts   │  │ Security     │  │ OS detection       │    │    │
│  │  └──────────────┘  └──────────────┘  └────────────────────┘    │    │
│  └─────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          External Services                               │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────────┐            │
│  │ Ollama       │  │ OpenAI API   │  │ Custom API         │            │
│  │ localhost    │  │ api.openai   │  │ DeepSeek/Claude    │            │
│  └──────────────┘  └──────────────┘  └────────────────────┘            │
└─────────────────────────────────────────────────────────────────────────┘
```

## Framework Relationships

| Layer | Technology | Role |
|-------|------------|------|
| **Desktop Shell** | Electron | Cross-platform desktop app, process management |
| **UI Framework** | React + TypeScript | Component-based UI with hooks and context |
| **Terminal Emulator** | xterm.js | Terminal rendering and input handling |
| **Communication** | WebSocket | Real-time bidirectional terminal I/O |
| **Backend Framework** | Spring Boot 3 | REST API, WebSocket server, DI container |
| **Terminal Backend** | pty4j (ConPTY/PTY) | Native terminal emulation |
| **SSH Client** | JSch | Remote SSH connections |
| **AI Integration** | HTTP Client | LLM API calls (Ollama, OpenAI, etc.) |

## Prerequisites

- **Java 17+** (Java 21 recommended)
- **Node.js 18+**
- **Maven 3.8+**
- **Ollama** (optional, for local AI models)

## Quick Start

### Startup Sequence

The application components must be started in this order:

```
1. Backend (Spring Boot)  →  2. Frontend (Electron + React)
        ↓                              ↓
   Port 8080 ready              Connects to backend
        ↓                              ↓
   WebSocket server             Opens browser window
        ↓                              ↓
   AI services ready            Terminal sessions created
```

### Method 1: Development Mode (Recommended for Development)

**Step 1: Start Backend**

```bash
cd backend
mvn spring-boot:run
```

Wait until you see:
```
Started AiTerminalApplication in X.XXX seconds
```

**Step 2: Start Frontend** (in a new terminal)

```bash
cd frontend
npm install    # First time only
npm run dev
```

The Electron window will open automatically.

### Method 2: Production Mode

**Step 1: Build Backend JAR**

```bash
cd backend
mvn clean package -DskipTests
```

**Step 2: Start Backend**

```bash
# Windows
java -jar target/ai-terminal-0.0.1-SNAPSHOT.jar

# macOS/Linux
java -jar target/ai-terminal-0.0.1-SNAPSHOT.jar
```

**Step 3: Start Frontend**

```bash
cd frontend
npm install    # First time only
npm run dev
```

### Method 3: One-Command Start (Windows PowerShell)

```powershell
# Stop existing processes
Get-Process -Name "java","node","electron" -ErrorAction SilentlyContinue | Stop-Process -Force

# Build and start backend
cd backend
mvn clean package -DskipTests -q
Start-Process -FilePath "java" -ArgumentList "-jar", "target/ai-terminal-0.0.1-SNAPSHOT.jar" -WindowStyle Hidden

# Wait for backend
Start-Sleep -Seconds 5

# Start frontend
cd ../frontend
npm run dev
```

## Configuration

### LLM Configuration

LLM settings are stored in `~/.ai-terminal/llm-config.json` and can be configured through the UI:

1. Click the **⚙️** (settings) button in the AI sidebar
2. Select a provider preset (Ollama, OpenAI, DeepSeek, Anthropic, or Custom)
3. Enter API key and model name
4. Click **Test Connection** to verify
5. Click **Save & Activate**

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
          model: deepseek-coder:1.5b

terminal:
  prefer-pty: true  # Use ConPTY on Windows, PTY on Unix
  buffer-size: 50
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `OPENAI_API_KEY` | OpenAI API key | - |
| `OLLAMA_BASE_URL` | Ollama server URL | http://localhost:11434 |

## Project Structure

```
AITerminal/
├── backend/                          # Spring Boot backend
│   ├── src/main/java/com/aiterminal/
│   │   ├── terminal/                 # Terminal session management
│   │   │   ├── ITerminalSession.java # Session interface
│   │   │   ├── PtySession.java       # PTY-based session (primary)
│   │   │   ├── LocalSession.java     # ProcessBuilder fallback
│   │   │   ├── SSHSession.java       # SSH remote session
│   │   │   ├── CommandExecutor.java  # Independent command execution
│   │   │   └── TerminalSessionFactory.java
│   │   ├── ai/                       # AI integration
│   │   │   ├── AIService.java        # Chat API service
│   │   │   ├── ContextBuilder.java   # System context for AI
│   │   │   ├── CommandGuard.java     # Command safety checker
│   │   │   ├── config/               # LLM configuration
│   │   │   │   ├── LLMConfig.java
│   │   │   │   └── LLMConfigService.java
│   │   │   └── react/                # ReAct agent
│   │   │       ├── ReActAgent.java
│   │   │       ├── ReActPrompt.java
│   │   │       └── dto/
│   │   ├── websocket/                # WebSocket handlers
│   │   ├── controller/               # REST controllers
│   │   └── util/                     # Utilities
│   │       ├── SystemInspector.java  # OS detection
│   │       └── SecureStorage.java    # API key encryption
│   └── pom.xml
│
├── frontend/                         # Electron + React frontend
│   ├── electron/                     # Electron main process
│   │   ├── main.ts                   # App lifecycle, backend spawning
│   │   └── preload.ts                # IPC bridge
│   ├── src/                          # React application
│   │   ├── components/               # UI components
│   │   │   ├── Terminal.tsx          # xterm.js wrapper
│   │   │   ├── AISidebar.tsx         # Chat interface
│   │   │   ├── CommandCard.tsx       # Clickable command cards
│   │   │   ├── ReActPanel.tsx        # Agent mode UI
│   │   │   ├── LLMSettings.tsx       # LLM configuration dialog
│   │   │   └── ErrorBoundary.tsx     # Error handling
│   │   ├── contexts/                 # React contexts
│   │   │   ├── TerminalContext.tsx   # Terminal state management
│   │   │   └── AIContext.tsx         # AI state management
│   │   └── App.tsx
│   └── package.json
│
├── BUILD_AND_RUN.md                  # Build instructions
└── README.md
```

## Key Features Explained

### Terminal Modes

| Mode | Technology | Use Case |
|------|------------|----------|
| **PTY Mode** | pty4j + ConPTY (Windows) / PTY (Unix) | Full terminal emulation, recommended |
| **Fallback Mode** | ProcessBuilder | Basic I/O when PTY unavailable |
| **SSH Mode** | JSch | Remote server connections |

### ReAct Agent Mode

The ReAct (Reasoning + Acting) agent enables autonomous multi-step task execution:

1. Click the **🪄** (magic wand) button in the AI sidebar
2. Describe your task (e.g., "Find all .log files larger than 10MB")
3. The AI will:
   - **Think**: Analyze the task and plan steps
   - **Act**: Execute commands
   - **Observe**: Read command output
   - **Repeat**: Until task is complete

### Command Safety

Commands are classified into three risk levels:

| Level | Color | Action |
|-------|-------|--------|
| **SAFE** | Green | Execute immediately |
| **WARNING** | Yellow | Show confirmation dialog |
| **DANGEROUS** | Red | Block execution |

## API Reference

### WebSocket Protocol

Connect to `ws://localhost:8080/ws/terminal`

**Initialize Local Terminal:**
```json
{"type": "init", "cols": 80, "rows": 24}
```

**Initialize SSH Terminal:**
```json
{"type": "init_ssh", "cols": 80, "rows": 24, "host": "example.com", "port": 22, "username": "user", "password": "pass"}
```

**Resize Terminal:**
```json
{"type": "resize", "cols": 120, "rows": 40}
```

### REST API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/ai/chat` | POST | Send message to AI |
| `/api/ai/validate-command` | POST | Check command safety |
| `/api/llm/config` | GET | Get LLM configuration |
| `/api/llm/provider` | POST | Set active provider |
| `/api/llm/ollama/models` | GET | List Ollama models |
| `/api/react/execute` | POST | Start ReAct task (SSE) |
| `/api/react/cancel/{sessionId}` | POST | Cancel ReAct task |

## Troubleshooting

### Backend Won't Start

```bash
# Check if port 8080 is in use
netstat -ano | findstr :8080   # Windows
lsof -i :8080                  # macOS/Linux

# Kill the process using the port
taskkill /PID <pid> /F         # Windows
kill -9 <pid>                  # macOS/Linux
```

### Frontend Can't Connect to Backend

1. Ensure backend is running and shows "Started AiTerminalApplication"
2. Check backend logs for errors
3. Verify port 8080 is accessible

### Terminal Not Responding

1. Check WebSocket connection in browser DevTools (Network tab)
2. Restart the application
3. If using SSH, verify credentials and network connectivity

### AI Not Working

1. Check LLM configuration in settings (⚙️ button)
2. For Ollama: Ensure Ollama is running (`ollama serve`)
3. For OpenAI/DeepSeek: Verify API key is correct
4. Check backend logs for API errors

## License

MIT License
