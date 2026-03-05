# AI Terminal - 打包与启动指南

本文档说明如何重新打包后端以及如何启动整个项目。

---

## 一、重新打包

### 1. 先结束已有进程（可选）

若应用正在运行，需先关闭 Electron 窗口或手动结束相关进程，否则打包时可能因 JAR 被占用而失败。

**Windows (PowerShell)：**

```powershell
# 结束 Java 后端与 Electron
Get-Process -Name "java","electron" -ErrorAction SilentlyContinue | Stop-Process -Force

# 若需一并结束 Vite/Node 进程，可再执行：
Get-Process -Name "node" -ErrorAction SilentlyContinue | Where-Object {
  (Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)" -ErrorAction SilentlyContinue).CommandLine -match "vite|electron|ai-terminal"
} | Stop-Process -Force
```

### 2. 打包后端

在项目根目录下执行：

```powershell
cd backend
mvn clean package -DskipTests
```

- `clean`：清理 `target` 目录
- `package`：编译并打成 JAR
- `-DskipTests`：跳过测试，加快打包

打包成功后，可执行 JAR 位于：

```
backend/target/ai-terminal-backend-1.0.0-SNAPSHOT.jar
```

### 3. 仅编译不打包（开发时）

若只改代码、不改配置，且不打算重新生成 JAR，可只编译到 `target/classes`：

```powershell
cd backend
mvn compile resources:resources
```

---

## 二、启动方式

### 方式一：一键启动（推荐）

前端会启动 Electron + Vite，并**自动启动**后端 JAR。

```powershell
cd frontend
npm install   # 首次运行需安装依赖
npm run dev
```

执行后将会：

1. 编译 Electron 主进程（TypeScript）
2. 启动 Vite 开发服务器（默认 http://localhost:5173）
3. 启动 Electron 窗口
4. Electron 自动查找并运行 `backend/target/ai-terminal-backend-1.0.0-SNAPSHOT.jar`，后端监听 8080 端口

**前提：** 已执行过 `mvn package`，且 JAR 存在。

---

### 方式二：后端与前端分开启动

适合需要单独调试后端或前端的场景。

**终端 1 - 启动后端：**

```powershell
cd backend
mvn spring-boot:run
```

或直接运行已打包的 JAR：

```powershell
cd backend
java -jar target/ai-terminal-backend-1.0.0-SNAPSHOT.jar
```

**终端 2 - 启动前端：**

```powershell
cd frontend
npm run dev
```

若只开发前端、不通过 Electron 启动 JAR，可只跑 Vite：

```powershell
cd frontend
npm run dev:renderer
```

再在浏览器访问 http://localhost:5173（需后端已在 8080 运行）。

---

### 方式三：生产环境运行

先构建前端并打包后端，再通过 JAR 运行：

```powershell
# 1. 打包后端
cd backend
mvn clean package -DskipTests

# 2. 构建前端 + 打包 Electron（可选，生成安装包）
cd ../frontend
npm run build          # 构建 React 页面
npm run build:electron # 生成 Electron 安装包到 frontend/release/
```

开发阶段通常只需 `npm run dev`，无需每次执行 `build` / `build:electron`。

---

## 三、常用脚本速查

| 操作           | 命令 |
|----------------|------|
| 重新打包后端   | `cd backend && mvn clean package -DskipTests` |
| 启动项目       | `cd frontend && npm run dev` |
| 仅启动后端     | `cd backend && mvn spring-boot:run` |
| 仅启动前端页面 | `cd frontend && npm run dev:renderer` |

---

## 四、端口与地址

| 服务     | 默认地址 / 端口 |
|----------|------------------|
| 后端 API | http://localhost:8080 |
| WebSocket 终端 | ws://localhost:8080/ws/terminal |
| Vite 开发服务器 | http://localhost:5173 |

---

## 五、故障排查

- **打包失败：Failed to delete ... JAR**  
  说明 JAR 被占用。请先关闭 AI Terminal 窗口或结束 Java/Electron 进程，再执行 `mvn clean package`。

- **Electron 启动后无法连后端**  
  确认 `backend/target/ai-terminal-backend-1.0.0-SNAPSHOT.jar` 存在，且 8080 端口未被占用。

- **前端白屏或无法访问**  
  确认 Vite 已启动（终端中看到 `Local: http://localhost:5173`），必要时在 Electron 窗口按 `Ctrl+R` 刷新。
