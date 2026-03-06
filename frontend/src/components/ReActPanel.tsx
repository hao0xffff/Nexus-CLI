import { useState, useRef, useEffect, useCallback } from 'react'
import { Play, Square, Bot, Loader2, CheckCircle2, XCircle, AlertTriangle, ChevronDown, ChevronRight, Terminal } from 'lucide-react'
import { useTerminal } from '../contexts/TerminalContext'
import { getBackendUrl } from '../utils/api'

interface ReActStep {
  stepNumber: number
  thought: string
  action: string
  actionInput: string
  output?: string
  status: string
  timestamp: number
}

interface ReActPanelProps {
  onClose: () => void
}

export default function ReActPanel({ onClose }: ReActPanelProps) {
  const [task, setTask] = useState('')
  const [isRunning, setIsRunning] = useState(false)
  const [steps, setSteps] = useState<ReActStep[]>([])
  const [expandedSteps, setExpandedSteps] = useState<Set<number>>(new Set())
  const { activeSessionId, activeBackendSessionId } = useTerminal()
  const abortControllerRef = useRef<AbortController | null>(null)
  const stepsEndRef = useRef<HTMLDivElement>(null)
  const isMountedRef = useRef(true)

  // Track mounted state for cleanup
  useEffect(() => {
    isMountedRef.current = true
    return () => {
      isMountedRef.current = false
      // Abort any running request on unmount
      if (abortControllerRef.current) {
        abortControllerRef.current.abort()
      }
    }
  }, [])

  // Auto-scroll to bottom when new steps arrive
  useEffect(() => {
    stepsEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [steps])

  const startTask = useCallback(async () => {
    if (!task.trim() || !activeBackendSessionId) return

    // Abort any previous request
    if (abortControllerRef.current) {
      abortControllerRef.current.abort()
    }
    
    const abortController = new AbortController()
    abortControllerRef.current = abortController

    setIsRunning(true)
    setSteps([])
    setExpandedSteps(new Set())

    try {
      const backendUrl = await getBackendUrl()
      
      console.log('Starting ReAct task with backend session ID:', activeBackendSessionId)
      
      // Use fetch with POST for SSE (EventSource doesn't support POST)
      const response = await fetch(`${backendUrl}/api/react/execute`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'text/event-stream',
        },
        body: JSON.stringify({
          sessionId: activeBackendSessionId,
          task: task.trim(),
        }),
        signal: abortController.signal,
      })

      if (!response.ok) {
        throw new Error(`HTTP error: ${response.status}`)
      }

      const reader = response.body?.getReader()
      const decoder = new TextDecoder()

      if (!reader) {
        throw new Error('No response body')
      }

      let buffer = ''

      while (true) {
        // Check if aborted
        if (abortController.signal.aborted) {
          reader.cancel()
          break
        }

        const { done, value } = await reader.read()
        
        if (done) {
          if (isMountedRef.current) setIsRunning(false)
          break
        }

        buffer += decoder.decode(value, { stream: true })
        
        // Parse SSE events from buffer
        const lines = buffer.split('\n')
        buffer = lines.pop() || '' // Keep incomplete line in buffer

        for (const line of lines) {
          if (line.startsWith('data:')) {
            const data = line.slice(5).trim()
            if (data && isMountedRef.current) {
              try {
                const step: ReActStep = JSON.parse(data)
                setSteps(prev => {
                  // Update existing step or add new one
                  const existing = prev.findIndex(s => s.stepNumber === step.stepNumber)
                  if (existing >= 0) {
                    const updated = [...prev]
                    updated[existing] = step
                    return updated
                  }
                  return [...prev, step]
                })

                // Auto-expand new steps
                setExpandedSteps(prev => new Set([...prev, step.stepNumber]))

                // Check for terminal states
                if (['COMPLETED', 'ERROR', 'CANCELLED'].includes(step.status)) {
                  setIsRunning(false)
                }
              } catch (e) {
                console.error('Failed to parse SSE data:', e)
              }
            }
          }
        }
      }
    } catch (error) {
      // Ignore abort errors
      if (error instanceof Error && error.name === 'AbortError') {
        console.log('ReAct task aborted')
        return
      }
      
      console.error('ReAct task failed:', error)
      if (isMountedRef.current) {
        setSteps(prev => [...prev, {
          stepNumber: -1,
          thought: 'Task failed',
          action: 'ERROR',
          actionInput: error instanceof Error ? error.message : 'Unknown error',
          status: 'ERROR',
          timestamp: Date.now(),
        }])
        setIsRunning(false)
      }
    }
  }, [task, activeBackendSessionId])

  const cancelTask = useCallback(async () => {
    if (!activeBackendSessionId) return

    // Abort the fetch stream
    if (abortControllerRef.current) {
      abortControllerRef.current.abort()
      abortControllerRef.current = null
    }

    try {
      const backendUrl = await getBackendUrl()
      await fetch(`${backendUrl}/api/react/cancel/${activeBackendSessionId}`, {
        method: 'POST',
      })
    } catch (error) {
      console.error('Failed to cancel task:', error)
    }
    
    if (isMountedRef.current) {
      setIsRunning(false)
    }
  }, [activeBackendSessionId])

  const toggleStep = (stepNumber: number) => {
    setExpandedSteps(prev => {
      const next = new Set(prev)
      if (next.has(stepNumber)) {
        next.delete(stepNumber)
      } else {
        next.add(stepNumber)
      }
      return next
    })
  }

  const getStatusIcon = (status: string) => {
    switch (status) {
      case 'COMPLETED':
        return <CheckCircle2 size={16} className="text-terminal-green" />
      case 'ERROR':
        return <XCircle size={16} className="text-terminal-red" />
      case 'BLOCKED':
        return <AlertTriangle size={16} className="text-terminal-yellow" />
      case 'RUNNING':
      case 'STARTED':
        return <Loader2 size={16} className="text-terminal-blue animate-spin" />
      default:
        return <Bot size={16} className="text-terminal-fg/60" />
    }
  }

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'COMPLETED':
        return 'border-terminal-green/30 bg-terminal-green/5'
      case 'ERROR':
        return 'border-terminal-red/30 bg-terminal-red/5'
      case 'BLOCKED':
        return 'border-terminal-yellow/30 bg-terminal-yellow/5'
      case 'RUNNING':
      case 'STARTED':
        return 'border-terminal-blue/30 bg-terminal-blue/5'
      default:
        return 'border-[#414868] bg-[#1a1b26]'
    }
  }

  return (
    <div className="flex flex-col h-full bg-[#1a1b26]">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-[#414868]">
        <div className="flex items-center gap-2">
          <Bot size={18} className="text-terminal-magenta" />
          <span className="font-medium text-terminal-fg">ReAct Agent</span>
          <span className="text-xs px-2 py-0.5 rounded bg-terminal-magenta/20 text-terminal-magenta">
            Auto Mode
          </span>
        </div>
        <button
          onClick={onClose}
          className="text-terminal-fg/60 hover:text-terminal-fg text-xl leading-none"
        >
          ×
        </button>
      </div>

      {/* Task Input */}
      <div className="p-4 border-b border-[#414868]">
        <label className="block text-sm text-terminal-fg/70 mb-2">
          Describe the task you want AI to complete automatically:
        </label>
        <textarea
          value={task}
          onChange={(e) => setTask(e.target.value)}
          placeholder="e.g., Create a folder called 'project', initialize a git repo, and create a README.md file"
          className="w-full h-20 px-3 py-2 bg-[#24283b] border border-[#414868] rounded-lg text-terminal-fg placeholder:text-terminal-fg/40 focus:outline-none focus:border-terminal-magenta resize-none"
          disabled={isRunning}
        />
        <div className="flex gap-2 mt-3">
          {!isRunning ? (
            <button
              onClick={startTask}
              disabled={!task.trim() || !activeBackendSessionId}
              className="flex items-center gap-2 px-4 py-2 bg-terminal-magenta text-white rounded-lg hover:bg-terminal-magenta/80 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              <Play size={16} />
              Start Task
            </button>
          ) : (
            <button
              onClick={cancelTask}
              className="flex items-center gap-2 px-4 py-2 bg-terminal-red text-white rounded-lg hover:bg-terminal-red/80 transition-colors"
            >
              <Square size={16} />
              Cancel
            </button>
          )}
          {steps.length > 0 && !isRunning && (
            <button
              onClick={() => setSteps([])}
              className="px-4 py-2 text-terminal-fg/60 hover:text-terminal-fg border border-[#414868] rounded-lg transition-colors"
            >
              Clear
            </button>
          )}
        </div>
        {!activeBackendSessionId && (
          <div className="flex items-center gap-2 text-xs text-terminal-yellow mt-2">
            <Terminal size={14} />
            <span>Terminal is connecting... Please wait for the connection to establish.</span>
          </div>
        )}
      </div>

      {/* Steps Display */}
      <div className="flex-1 overflow-y-auto p-4 space-y-3">
        {steps.length === 0 && !isRunning && (
          <div className="text-center text-terminal-fg/40 py-8">
            <Bot size={48} className="mx-auto mb-3 opacity-50" />
            <p>Enter a task and click "Start Task" to begin</p>
            <p className="text-xs mt-2">
              The AI will automatically plan and execute commands
            </p>
          </div>
        )}

        {steps.map((step) => (
          <div
            key={`${step.stepNumber}-${step.timestamp}`}
            className={`rounded-lg border ${getStatusColor(step.status)} overflow-hidden`}
          >
            {/* Step Header */}
            <div
              className="flex items-center gap-2 px-3 py-2 cursor-pointer hover:bg-white/5"
              onClick={() => toggleStep(step.stepNumber)}
            >
              {expandedSteps.has(step.stepNumber) ? (
                <ChevronDown size={14} className="text-terminal-fg/60" />
              ) : (
                <ChevronRight size={14} className="text-terminal-fg/60" />
              )}
              {getStatusIcon(step.status)}
              <span className="text-sm font-medium text-terminal-fg">
                {step.stepNumber > 0 ? `Step ${step.stepNumber}` : step.action}
              </span>
              <span className="text-xs text-terminal-fg/50">
                {step.action}
              </span>
              {step.action === 'EXECUTE' && (
                <code className="ml-auto text-xs bg-[#1a1b26] px-2 py-0.5 rounded text-terminal-cyan max-w-[200px] truncate">
                  {step.actionInput}
                </code>
              )}
            </div>

            {/* Step Details */}
            {expandedSteps.has(step.stepNumber) && (
              <div className="px-3 pb-3 space-y-2 border-t border-[#414868]/50">
                {/* Thought */}
                {step.thought && (
                  <div className="mt-2">
                    <div className="text-xs text-terminal-fg/50 mb-1">💭 Thought</div>
                    <p className="text-sm text-terminal-fg/80">{step.thought}</p>
                  </div>
                )}

                {/* Action Input */}
                {step.actionInput && step.action === 'EXECUTE' && (
                  <div>
                    <div className="text-xs text-terminal-fg/50 mb-1">⚡ Command</div>
                    <code className="block text-sm bg-[#1a1b26] p-2 rounded text-terminal-cyan font-mono">
                      {step.actionInput}
                    </code>
                  </div>
                )}

                {/* Output */}
                {step.output && (
                  <div>
                    <div className="text-xs text-terminal-fg/50 mb-1">
                      📤 Output
                      {step.output.includes('[STDERR]') && (
                        <span className="ml-2 text-terminal-yellow">(contains errors)</span>
                      )}
                      {step.output.includes('[Command timed out]') && (
                        <span className="ml-2 text-terminal-yellow">(timed out)</span>
                      )}
                    </div>
                    <pre className="text-xs bg-[#1a1b26] p-2 rounded text-terminal-fg/70 font-mono whitespace-pre-wrap max-h-48 overflow-auto">
                      {step.output === 'Executing command...' ? (
                        <span className="flex items-center gap-2">
                          <Loader2 size={12} className="animate-spin" />
                          Executing command...
                        </span>
                      ) : (
                        step.output
                      )}
                    </pre>
                  </div>
                )}

                {/* Completion/Error message */}
                {(step.action === 'COMPLETE' || step.action === 'ERROR') && step.actionInput && (
                  <div className={`p-2 rounded ${step.action === 'COMPLETE' ? 'bg-terminal-green/10 text-terminal-green' : 'bg-terminal-red/10 text-terminal-red'}`}>
                    <p className="text-sm">{step.actionInput}</p>
                  </div>
                )}
              </div>
            )}
          </div>
        ))}

        <div ref={stepsEndRef} />
      </div>

      {/* Footer Status */}
      {isRunning && (
        <div className="px-4 py-2 border-t border-[#414868] bg-[#24283b]">
          <div className="flex items-center gap-2 text-sm text-terminal-blue">
            <Loader2 size={14} className="animate-spin" />
            <span>Agent is working... (complex operations may take a few minutes)</span>
          </div>
        </div>
      )}
      
      {/* Completed Status */}
      {!isRunning && steps.length > 0 && steps[steps.length - 1]?.status === 'COMPLETED' && (
        <div className="px-4 py-2 border-t border-[#414868] bg-terminal-green/10">
          <div className="flex items-center gap-2 text-sm text-terminal-green">
            <CheckCircle2 size={14} />
            <span>Task completed successfully</span>
          </div>
        </div>
      )}
    </div>
  )
}
