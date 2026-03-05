import { useState } from 'react'
import { Play, AlertTriangle, ShieldAlert, Shield, Copy, Check, CheckCircle2, Loader2, XCircle } from 'lucide-react'
import { useAI } from '../contexts/AIContext'

interface CommandCardProps {
  command: {
    command: string
    description: string
    safe: boolean
    warning?: string
    riskLevel: 'SAFE' | 'WARNING' | 'DANGEROUS'
  }
}

export default function CommandCard({ command }: CommandCardProps) {
  const { executeCommand } = useAI()
  const [copied, setCopied] = useState(false)
  const [showWarning, setShowWarning] = useState(false)
  const [execStatus, setExecStatus] = useState<'idle' | 'running' | 'success' | 'error'>('idle')
  const [execOutput, setExecOutput] = useState<string>('')

  const handleCopy = async () => {
    await navigator.clipboard.writeText(command.command)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  const handleExecute = async () => {
    if (command.riskLevel === 'DANGEROUS') {
      setShowWarning(true)
      return
    }
    
    if (command.riskLevel === 'WARNING' && !showWarning) {
      setShowWarning(true)
      return
    }
    
    setShowWarning(false)
    setExecStatus('running')
    setExecOutput('')
    
    try {
      const result = await executeCommand(command.command)
      setExecStatus(result.status === 'success' ? 'success' : 'error')
      setExecOutput(result.output || '')
      
      // Reset after 5 seconds
      setTimeout(() => {
        setExecStatus('idle')
        setExecOutput('')
      }, 5000)
    } catch (error) {
      setExecStatus('error')
      setExecOutput(error instanceof Error ? error.message : 'Execution failed')
      setTimeout(() => {
        setExecStatus('idle')
        setExecOutput('')
      }, 5000)
    }
  }

  const getRiskStyles = () => {
    switch (command.riskLevel) {
      case 'DANGEROUS':
        return {
          border: 'border-terminal-red',
          bg: 'bg-terminal-red/10',
          icon: <ShieldAlert size={14} className="text-terminal-red" />,
          badge: 'bg-terminal-red/20 text-terminal-red',
        }
      case 'WARNING':
        return {
          border: 'border-terminal-yellow',
          bg: 'bg-terminal-yellow/10',
          icon: <AlertTriangle size={14} className="text-terminal-yellow" />,
          badge: 'bg-terminal-yellow/20 text-terminal-yellow',
        }
      default:
        return {
          border: 'border-terminal-green',
          bg: 'bg-terminal-green/10',
          icon: <Shield size={14} className="text-terminal-green" />,
          badge: 'bg-terminal-green/20 text-terminal-green',
        }
    }
  }

  const styles = getRiskStyles()

  return (
    <div className={`command-card rounded-lg border ${styles.border} ${styles.bg} p-3`}>
      {/* Header */}
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-2">
          {styles.icon}
          <span className={`text-xs px-2 py-0.5 rounded ${styles.badge}`}>
            {command.riskLevel}
          </span>
        </div>
        <div className="flex items-center gap-1">
          <button
            onClick={handleCopy}
            className="p-1.5 text-terminal-fg/60 hover:text-terminal-fg rounded transition-colors"
            title="Copy command"
          >
            {copied ? <Check size={14} className="text-terminal-green" /> : <Copy size={14} />}
          </button>
          <button
            onClick={handleExecute}
            disabled={command.riskLevel === 'DANGEROUS' || execStatus === 'running'}
            className={`p-1.5 rounded transition-colors ${
              execStatus === 'running'
                ? 'text-terminal-blue'
                : execStatus === 'success'
                ? 'text-terminal-green'
                : execStatus === 'error'
                ? 'text-terminal-red'
                : command.riskLevel === 'DANGEROUS'
                ? 'text-terminal-fg/30 cursor-not-allowed'
                : 'text-terminal-fg/60 hover:text-terminal-green hover:bg-terminal-green/10'
            }`}
            title={
              execStatus === 'running' ? 'Executing...' :
              execStatus === 'success' ? 'Executed!' :
              execStatus === 'error' ? 'Execution failed' :
              command.riskLevel === 'DANGEROUS' ? 'Command blocked' : 'Execute command'
            }
          >
            {execStatus === 'running' ? <Loader2 size={14} className="animate-spin" /> :
             execStatus === 'success' ? <CheckCircle2 size={14} /> :
             execStatus === 'error' ? <XCircle size={14} /> :
             <Play size={14} />}
          </button>
        </div>
      </div>

      {/* Command */}
      <div 
        className={`font-mono text-sm bg-[#1a1b26] rounded px-2 py-1.5 mb-2 overflow-x-auto cursor-pointer hover:bg-[#1a1b26]/80 transition-colors ${
          execStatus === 'success' ? 'ring-1 ring-terminal-green/50' :
          execStatus === 'error' ? 'ring-1 ring-terminal-red/50' :
          execStatus === 'running' ? 'ring-1 ring-terminal-blue/50' : ''
        }`}
        onClick={handleCopy}
        title="Click to copy"
      >
        <code className="text-terminal-cyan">{command.command}</code>
      </div>

      {/* Description */}
      <p className="text-xs text-terminal-fg/70">{command.description}</p>

      {/* Execution status feedback */}
      {execStatus === 'running' && (
        <div className="mt-2 flex items-center gap-2 text-xs text-terminal-blue animate-fade-in">
          <Loader2 size={12} className="animate-spin" />
          <span>Executing command...</span>
        </div>
      )}
      
      {execStatus === 'success' && (
        <div className="mt-2 p-2 bg-terminal-green/10 rounded border border-terminal-green/30">
          <div className="flex items-center gap-2 text-xs text-terminal-green">
            <CheckCircle2 size={12} />
            <span className="font-medium">Command executed successfully</span>
          </div>
          {execOutput && (
            <pre className="mt-1 text-xs text-terminal-fg/70 font-mono whitespace-pre-wrap max-h-20 overflow-auto">
              {execOutput}
            </pre>
          )}
        </div>
      )}
      
      {execStatus === 'error' && (
        <div className="mt-2 p-2 bg-terminal-red/10 rounded border border-terminal-red/30">
          <div className="flex items-center gap-2 text-xs text-terminal-red">
            <XCircle size={12} />
            <span className="font-medium">Execution failed</span>
          </div>
          {execOutput && (
            <pre className="mt-1 text-xs text-terminal-fg/70 font-mono whitespace-pre-wrap max-h-20 overflow-auto">
              {execOutput}
            </pre>
          )}
        </div>
      )}

      {/* Warning message */}
      {showWarning && command.warning && (
        <div className="mt-2 p-2 bg-[#1a1b26] rounded text-xs">
          <div className="flex items-start gap-2">
            <AlertTriangle size={14} className="text-terminal-yellow mt-0.5 flex-shrink-0" />
            <div>
              <p className="text-terminal-yellow font-medium mb-1">Warning</p>
              <p className="text-terminal-fg/70">{command.warning}</p>
              {command.riskLevel !== 'DANGEROUS' && (
                <div className="flex gap-2 mt-2">
                  <button
                    onClick={async () => {
                      setShowWarning(false)
                      setExecStatus('running')
                      try {
                        const result = await executeCommand(command.command)
                        setExecStatus(result.status === 'success' ? 'success' : 'error')
                        setExecOutput(result.output || '')
                        setTimeout(() => {
                          setExecStatus('idle')
                          setExecOutput('')
                        }, 5000)
                      } catch (error) {
                        setExecStatus('error')
                        setExecOutput(error instanceof Error ? error.message : 'Execution failed')
                        setTimeout(() => {
                          setExecStatus('idle')
                          setExecOutput('')
                        }, 5000)
                      }
                    }}
                    className="px-2 py-1 bg-terminal-yellow/20 text-terminal-yellow rounded text-xs hover:bg-terminal-yellow/30 transition-colors"
                  >
                    Execute Anyway
                  </button>
                  <button
                    onClick={() => setShowWarning(false)}
                    className="px-2 py-1 bg-[#292e42] text-terminal-fg/60 rounded text-xs hover:text-terminal-fg transition-colors"
                  >
                    Cancel
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Blocked message for dangerous commands */}
      {command.riskLevel === 'DANGEROUS' && (
        <div className="mt-2 p-2 bg-[#1a1b26] rounded text-xs">
          <div className="flex items-start gap-2">
            <ShieldAlert size={14} className="text-terminal-red mt-0.5 flex-shrink-0" />
            <div>
              <p className="text-terminal-red font-medium mb-1">Command Blocked</p>
              <p className="text-terminal-fg/70">
                This command has been identified as potentially dangerous and cannot be executed through the AI interface.
                If you need to run this command, please enter it manually in the terminal.
              </p>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
