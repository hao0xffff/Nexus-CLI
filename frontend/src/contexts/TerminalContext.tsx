import { createContext, useContext, useState, useCallback, useEffect, useRef, ReactNode } from 'react'

interface SSHConfig {
  host: string
  port: number
  username: string
  password?: string
  privateKey?: string
  passphrase?: string
}

interface TerminalSession {
  id: string
  type: 'local' | 'ssh'
  name: string
  status: 'connecting' | 'connected' | 'disconnected' | 'error'
  outputBuffer: number[]
  onOutput?: (data: Uint8Array) => void
}

interface TerminalContextType {
  sessions: TerminalSession[]
  activeSessionId: string | null
  createSession: (type: 'local' | 'ssh', config?: SSHConfig) => Promise<void>
  closeSession: (id: string) => void
  setActiveSession: (id: string) => void
  getSession: (id: string) => TerminalSession | undefined
  sendInput: (sessionId: string, data: string) => void
  sendResize: (sessionId: string, cols: number, rows: number) => void
  getRecentOutput: (sessionId: string, lines?: number) => string
  registerTerminal: (sessionId: string, writeCallback: (data: Uint8Array) => void) => void
}

const TerminalContext = createContext<TerminalContextType | null>(null)

export function useTerminal() {
  const context = useContext(TerminalContext)
  if (!context) {
    throw new Error('useTerminal must be used within a TerminalProvider')
  }
  return context
}

interface TerminalProviderProps {
  children: ReactNode
}

export function TerminalProvider({ children }: TerminalProviderProps) {
  const [sessions, setSessions] = useState<TerminalSession[]>([])
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null)
  const websocketsRef = useRef<Map<string, WebSocket>>(new Map())
  const outputBuffersRef = useRef<Map<string, string[]>>(new Map())
  const terminalWritersRef = useRef<Map<string, (data: Uint8Array) => void>>(new Map())

  // Get WebSocket URL from Electron
  const getWsUrl = useCallback(async () => {
    if (window.electronAPI) {
      return window.electronAPI.getWsUrl()
    }
    return 'ws://localhost:8080/ws/terminal'
  }, [])

  const createSession = useCallback(async (type: 'local' | 'ssh', config?: SSHConfig) => {
    const sessionId = crypto.randomUUID()
    const wsUrl = await getWsUrl()

    const session: TerminalSession = {
      id: sessionId,
      type,
      name: type === 'ssh' && config ? `${config.username}@${config.host}` : 'Local',
      status: 'connecting',
      outputBuffer: [],
    }

    setSessions(prev => [...prev, session])
    setActiveSessionId(sessionId)
    outputBuffersRef.current.set(sessionId, [])

    // Create WebSocket connection
    const ws = new WebSocket(wsUrl)
    websocketsRef.current.set(sessionId, ws)

    ws.binaryType = 'arraybuffer'

    ws.onopen = () => {
      console.log(`WebSocket connected for session ${sessionId}`)
      
      // Send init message
      const initMessage = type === 'local'
        ? { type: 'init', cols: 80, rows: 24 }
        : { type: 'init_ssh', cols: 80, rows: 24, ...config }
      
      ws.send(JSON.stringify(initMessage))
    }

    ws.onmessage = (event) => {
      if (event.data instanceof ArrayBuffer) {
        // Binary message - terminal output
        const data = new Uint8Array(event.data)
        
        // Update output buffer for AI context
        const text = new TextDecoder().decode(data)
        const lines = outputBuffersRef.current.get(sessionId) || []
        lines.push(...text.split('\n').filter(l => l.length > 0))
        while (lines.length > 100) lines.shift()
        outputBuffersRef.current.set(sessionId, lines)

        // Write to terminal component via registered callback
        const writer = terminalWritersRef.current.get(sessionId)
        if (writer) {
          writer(data)
        }
      } else {
        // Text message - control message
        try {
          const message = JSON.parse(event.data)
          console.log(`Control message for ${sessionId}:`, message)

          switch (message.type) {
            case 'ready':
              setSessions(prev => prev.map(s =>
                s.id === sessionId ? { ...s, status: 'connected' } : s
              ))
              break
            case 'closed':
              setSessions(prev => prev.map(s =>
                s.id === sessionId ? { ...s, status: 'disconnected' } : s
              ))
              break
            case 'error':
              console.error(`Session ${sessionId} error:`, message.message)
              setSessions(prev => prev.map(s =>
                s.id === sessionId ? { ...s, status: 'error' } : s
              ))
              break
          }
        } catch (e) {
          console.error('Failed to parse control message:', e)
        }
      }
    }

    ws.onerror = (error) => {
      console.error(`WebSocket error for session ${sessionId}:`, error)
      setSessions(prev => prev.map(s =>
        s.id === sessionId ? { ...s, status: 'error' } : s
      ))
    }

    ws.onclose = () => {
      console.log(`WebSocket closed for session ${sessionId}`)
      setSessions(prev => prev.map(s =>
        s.id === sessionId ? { ...s, status: 'disconnected' } : s
      ))
      websocketsRef.current.delete(sessionId)
    }
  }, [getWsUrl])

  const closeSession = useCallback((id: string) => {
    const ws = websocketsRef.current.get(id)
    if (ws) {
      ws.send(JSON.stringify({ type: 'close' }))
      ws.close()
      websocketsRef.current.delete(id)
    }

    outputBuffersRef.current.delete(id)
    
    setSessions(prev => {
      const remaining = prev.filter(s => s.id !== id)
      if (activeSessionId === id && remaining.length > 0) {
        setActiveSessionId(remaining[remaining.length - 1].id)
      } else if (remaining.length === 0) {
        setActiveSessionId(null)
      }
      return remaining
    })
  }, [activeSessionId])

  const setActiveSession = useCallback((id: string) => {
    setActiveSessionId(id)
  }, [])

  const getSession = useCallback((id: string) => {
    return sessions.find(s => s.id === id)
  }, [sessions])

  const sendInput = useCallback((sessionId: string, data: string) => {
    const ws = websocketsRef.current.get(sessionId)
    if (ws && ws.readyState === WebSocket.OPEN) {
      // Send as binary for terminal data
      const encoder = new TextEncoder()
      ws.send(encoder.encode(data))
    }
  }, [])

  const sendResize = useCallback((sessionId: string, cols: number, rows: number) => {
    const ws = websocketsRef.current.get(sessionId)
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'resize', cols, rows }))
    }
  }, [])

  const getRecentOutput = useCallback((sessionId: string, lines = 50) => {
    const buffer = outputBuffersRef.current.get(sessionId) || []
    return buffer.slice(-lines).join('\n')
  }, [])

  const registerTerminal = useCallback((sessionId: string, writeCallback: (data: Uint8Array) => void) => {
    terminalWritersRef.current.set(sessionId, writeCallback)
  }, [])

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      websocketsRef.current.forEach(ws => ws.close())
      websocketsRef.current.clear()
    }
  }, [])

  const value: TerminalContextType = {
    sessions,
    activeSessionId,
    createSession,
    closeSession,
    setActiveSession,
    getSession,
    sendInput,
    sendResize,
    getRecentOutput,
    registerTerminal,
  }

  return (
    <TerminalContext.Provider value={value}>
      {children}
    </TerminalContext.Provider>
  )
}
