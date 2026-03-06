import { createContext, useContext, useState, useCallback, useEffect, ReactNode } from 'react'
import { useTerminal } from './TerminalContext'
import { getBackendUrl } from '../utils/api'

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

interface ProviderInfo {
  provider: 'ollama' | 'openai' | 'custom'
  model: string
  name: string  // Display name (e.g., "DeepSeek" for custom)
}

interface AIContextType {
  messages: ChatMessage[]
  isLoading: boolean
  provider: 'ollama' | 'openai' | 'custom'
  providerInfo: ProviderInfo | null
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
  const [providerInfo, setProviderInfo] = useState<ProviderInfo | null>(null)
  const [commandExecutions, setCommandExecutions] = useState<CommandExecution[]>([])
  const { activeSessionId, sendInput, getRecentOutput } = useTerminal()

  // Load current provider from backend config
  const refreshProvider = useCallback(async () => {
    try {
      const backendUrl = await getBackendUrl()
      // Fetch summary and config in parallel for better performance
      const [summaryRes, configRes] = await Promise.all([
        fetch(`${backendUrl}/api/llm/summary`),
        fetch(`${backendUrl}/api/llm/config`)
      ])
      
      if (!summaryRes.ok) return
      const data = await summaryRes.json()
      const activeProvider = data.provider as 'ollama' | 'openai' | 'custom'
      setProvider(activeProvider)
      
      // Build provider info from parallel-fetched config
      let displayName = activeProvider
      if (activeProvider === 'custom' && configRes.ok) {
        const config = await configRes.json()
        displayName = config.custom?.name || 'Custom'
      }
      
      setProviderInfo({
        provider: activeProvider,
        model: data.model || '',
        name: displayName
      })
    } catch (error) {
      console.error('Failed to load provider info:', error)
    }
  }, [])

  // Load provider info on mount
  useEffect(() => {
    refreshProvider()
  }, [refreshProvider])

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
      
      // Get recent terminal output for context (reduced for speed)
      const terminalOutput = activeSessionId ? getRecentOutput(activeSessionId, 20) : ''
      
      // Build history (last 6 messages for speed)
      const history = messages.slice(-6).map(m => ({
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
          recentLines: 20,
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
    providerInfo,
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
