import { createContext, useContext, useState, useCallback, ReactNode } from 'react'
import { useTerminal } from './TerminalContext'

interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  timestamp: number
  commands?: CommandCard[]
}

interface CommandCard {
  command: string
  description: string
  safe: boolean
  warning?: string
  riskLevel: 'SAFE' | 'WARNING' | 'DANGEROUS'
}

interface CommandExecution {
  id: string
  command: string
  status: 'pending' | 'running' | 'success' | 'error'
  timestamp: number
  output?: string
}

interface AIContextType {
  messages: ChatMessage[]
  isLoading: boolean
  provider: 'ollama' | 'openai' | 'custom'
  setProvider: (provider: 'ollama' | 'openai' | 'custom') => void
  sendMessage: (message: string) => Promise<void>
  executeCommand: (command: string) => Promise<CommandExecution>
  clearHistory: () => void
  refreshProvider: () => Promise<void>
  commandExecutions: CommandExecution[]
}

const AIContext = createContext<AIContextType | null>(null)

export function useAI() {
  const context = useContext(AIContext)
  if (!context) {
    throw new Error('useAI must be used within an AIProvider')
  }
  return context
}

interface AIProviderProps {
  children: ReactNode
}

export function AIProvider({ children }: AIProviderProps) {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [isLoading, setIsLoading] = useState(false)
  const [provider, setProvider] = useState<'ollama' | 'openai' | 'custom'>('ollama')
  const [commandExecutions, setCommandExecutions] = useState<CommandExecution[]>([])
  const { activeSessionId, sendInput, getRecentOutput } = useTerminal()

  const getBackendUrl = useCallback(async () => {
    if (window.electronAPI) {
      return window.electronAPI.getBackendUrl()
    }
    return 'http://localhost:8080'
  }, [])

  // Load current provider from backend config
  const refreshProvider = useCallback(async () => {
    try {
      const backendUrl = await getBackendUrl()
      const response = await fetch(`${backendUrl}/api/llm/config`)
      if (response.ok) {
        const data = await response.json()
        if (data.activeProvider) {
          setProvider(data.activeProvider as 'ollama' | 'openai' | 'custom')
        }
      }
    } catch (error) {
      console.error('Failed to refresh provider:', error)
    }
  }, [getBackendUrl])

  const sendMessage = useCallback(async (message: string) => {
    if (!message.trim()) return

    const userMessage: ChatMessage = {
      id: crypto.randomUUID(),
      role: 'user',
      content: message,
      timestamp: Date.now(),
    }

    setMessages(prev => [...prev, userMessage])
    setIsLoading(true)

    try {
      const backendUrl = await getBackendUrl()
      
      // Get recent terminal output for context
      const terminalOutput = activeSessionId ? getRecentOutput(activeSessionId, 50) : ''
      
      // Build history (last 10 messages for context)
      const history = messages.slice(-10).map(m => ({
        role: m.role,
        content: m.content,
      }))

      const response = await fetch(`${backendUrl}/api/ai/chat`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          message,
          provider,
          terminalOutput,
          recentLines: 50,
          history,
        }),
      })

      if (!response.ok) {
        throw new Error(`HTTP error: ${response.status}`)
      }

      const data = await response.json()

      const assistantMessage: ChatMessage = {
        id: crypto.randomUUID(),
        role: 'assistant',
        content: data.message,
        timestamp: Date.now(),
        commands: data.commands,
      }

      setMessages(prev => [...prev, assistantMessage])
    } catch (error) {
      console.error('AI chat error:', error)
      
      const errorMessage: ChatMessage = {
        id: crypto.randomUUID(),
        role: 'assistant',
        content: `Sorry, I couldn't process your request. ${error instanceof Error ? error.message : 'Unknown error'}`,
        timestamp: Date.now(),
      }
      setMessages(prev => [...prev, errorMessage])
    } finally {
      setIsLoading(false)
    }
  }, [messages, provider, activeSessionId, getRecentOutput, getBackendUrl])

  const executeCommand = useCallback(async (command: string): Promise<CommandExecution> => {
    const execution: CommandExecution = {
      id: crypto.randomUUID(),
      command,
      status: 'pending',
      timestamp: Date.now(),
    }
    
    setCommandExecutions(prev => [...prev.slice(-10), execution]) // Keep last 10
    
    if (!activeSessionId) {
      execution.status = 'error'
      execution.output = 'No active terminal session'
      setCommandExecutions(prev => prev.map(e => e.id === execution.id ? execution : e))
      return execution
    }
    
    try {
      // Get output before execution
      const outputBefore = getRecentOutput(activeSessionId, 20)
      
      // Send command with newline to execute
      execution.status = 'running'
      setCommandExecutions(prev => prev.map(e => e.id === execution.id ? execution : e))
      
      // Use \r (carriage return) for terminal - this is what terminals expect
      sendInput(activeSessionId, command + '\r')
      
      // Wait a bit for command to execute and capture output
      await new Promise(resolve => setTimeout(resolve, 500))
      
      // Get output after execution
      const outputAfter = getRecentOutput(activeSessionId, 20)
      
      // Extract new output (simple diff)
      const newOutput = outputAfter.replace(outputBefore, '').trim()
      
      execution.status = 'success'
      execution.output = newOutput || 'Command sent successfully'
      setCommandExecutions(prev => prev.map(e => e.id === execution.id ? execution : e))
      
    } catch (error) {
      execution.status = 'error'
      execution.output = error instanceof Error ? error.message : 'Unknown error'
      setCommandExecutions(prev => prev.map(e => e.id === execution.id ? execution : e))
    }
    
    return execution
  }, [activeSessionId, sendInput, getRecentOutput])

  const clearHistory = useCallback(() => {
    setMessages([])
  }, [])

  const value: AIContextType = {
    messages,
    isLoading,
    provider,
    setProvider,
    sendMessage,
    executeCommand,
    clearHistory,
    refreshProvider,
    commandExecutions,
  }

  return (
    <AIContext.Provider value={value}>
      {children}
    </AIContext.Provider>
  )
}
