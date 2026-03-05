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

interface AIContextType {
  messages: ChatMessage[]
  isLoading: boolean
  provider: 'ollama' | 'openai'
  setProvider: (provider: 'ollama' | 'openai') => void
  sendMessage: (message: string) => Promise<void>
  executeCommand: (command: string) => void
  clearHistory: () => void
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
  const [provider, setProvider] = useState<'ollama' | 'openai'>('ollama')
  const { activeSessionId, sendInput, getRecentOutput } = useTerminal()

  const getBackendUrl = useCallback(async () => {
    if (window.electronAPI) {
      return window.electronAPI.getBackendUrl()
    }
    return 'http://localhost:8080'
  }, [])

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

  const executeCommand = useCallback((command: string) => {
    if (activeSessionId) {
      sendInput(activeSessionId, command + '\n')
    }
  }, [activeSessionId, sendInput])

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
  }

  return (
    <AIContext.Provider value={value}>
      {children}
    </AIContext.Provider>
  )
}
