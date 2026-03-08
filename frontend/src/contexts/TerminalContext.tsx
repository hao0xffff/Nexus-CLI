import { createContext, useContext, useState, useCallback, useEffect, useRef, ReactNode } from 'react'
import { isNoiseTerminalLine, normalizeTerminalLine } from '../utils/terminalOutput'

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
  backendSessionId?: string  // The actual terminal session ID from backend
  type: 'local' | 'ssh'
  name: string
  status: 'connecting' | 'connected' | 'disconnected' | 'error'
  outputBuffer: number[]
  onOutput?: (data: Uint8Array) => void
}

interface TerminalContextType {
  sessions: TerminalSession[]
  activeSessionId: string | null
  activeBackendSessionId: string | null  // For ReAct and other backend operations
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
  const decodersRef = useRef<Map<string, TextDecoder>>(new Map())
  const decoderCarryRef = useRef<Map<string, string>>(new Map())

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
    decodersRef.current.set(sessionId, new TextDecoder('utf-8'))
    decoderCarryRef.current.set(sessionId, '')

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
        const decoder = decodersRef.current.get(sessionId) || new TextDecoder('utf-8')
        decodersRef.current.set(sessionId, decoder)
        const text = decoder.decode(data, { stream: true })
        const previousCarry = decoderCarryRef.current.get(sessionId) || ''
        const merged = previousCarry + text
        const parts = merged.split(/\r?\n/)
        const nextCarry = parts.pop() || ''
        decoderCarryRef.current.set(sessionId, nextCarry)
        const lines = outputBuffersRef.current.get(sessionId) || []
        for (const item of parts) {
          const normalized = normalizeTerminalLine(item)
          if (isNoiseTerminalLine(normalized)) {
            continue
          }
          lines.push(normalized)
        }
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
            case 'ready': {
              // Store the backend session ID for ReAct and other backend operations
              const backendId = message.sessionId
              console.log(`Backend session ID for ${sessionId}: ${backendId}`)
              setSessions(prev => prev.map(s =>
                s.id === sessionId ? { ...s, status: 'connected', backendSessionId: backendId } : s
              ))
              break
            }
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
      decodersRef.current.delete(sessionId)
      decoderCarryRef.current.delete(sessionId)
    }
  }, [getWsUrl])

  const closeSession = useCallback((id: string) => {
    const ws = websocketsRef.current.get(id)
    if (ws) {
      ws.send(JSON.stringify({ type: 'close' }))
      ws.close()
      websocketsRef.current.delete(id)
    }

    // Clean up all refs for this session
    outputBuffersRef.current.delete(id)
    terminalWritersRef.current.delete(id)
    decodersRef.current.delete(id)
    decoderCarryRef.current.delete(id)
    
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
    const buffer = [...(outputBuffersRef.current.get(sessionId) || [])]
    const carry = decoderCarryRef.current.get(sessionId) || ''
    if (carry.trim()) {
      const normalizedCarry = normalizeTerminalLine(carry)
      if (!isNoiseTerminalLine(normalizedCarry)) {
        buffer.push(normalizedCarry)
      }
    }
    return buffer.slice(-lines).join('\n')
  }, [])

  const registerTerminal = useCallback((sessionId: string, writeCallback: (data: Uint8Array) => void) => {
    terminalWritersRef.current.set(sessionId, writeCallback)
  }, [])

  // Cleanup on unmount
  useEffect(() => {
    const websockets = websocketsRef.current
    const outputBuffers = outputBuffersRef.current
    const terminalWriters = terminalWritersRef.current
    const decoders = decodersRef.current
    const decoderCarry = decoderCarryRef.current
    return () => {
      websockets.forEach(ws => ws.close())
      websockets.clear()
      outputBuffers.clear()
      terminalWriters.clear()
      decoders.clear()
      decoderCarry.clear()
    }
  }, [])

  // Get the backend session ID for the active session
  const activeBackendSessionId = activeSessionId 
    ? sessions.find(s => s.id === activeSessionId)?.backendSessionId || null
    : null

  const value: TerminalContextType = {
    sessions,
    activeSessionId,
    activeBackendSessionId,
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
