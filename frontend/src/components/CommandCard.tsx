import { useState } from 'react'
import { Play, AlertTriangle, ShieldAlert, Shield, Copy, Check } from 'lucide-react'
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

  const handleCopy = async () => {
    await navigator.clipboard.writeText(command.command)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  const handleExecute = () => {
    if (command.riskLevel === 'DANGEROUS') {
      // Don't execute dangerous commands
      setShowWarning(true)
      return
    }
    
    if (command.riskLevel === 'WARNING' && !showWarning) {
      setShowWarning(true)
      return
    }
    
    executeCommand(command.command)
    setShowWarning(false)
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
            {copied ? <Check size={14} /> : <Copy size={14} />}
          </button>
          <button
            onClick={handleExecute}
            disabled={command.riskLevel === 'DANGEROUS'}
            className={`p-1.5 rounded transition-colors ${
              command.riskLevel === 'DANGEROUS'
                ? 'text-terminal-fg/30 cursor-not-allowed'
                : 'text-terminal-fg/60 hover:text-terminal-green hover:bg-terminal-green/10'
            }`}
            title={command.riskLevel === 'DANGEROUS' ? 'Command blocked' : 'Execute command'}
          >
            <Play size={14} />
          </button>
        </div>
      </div>

      {/* Command */}
      <div className="font-mono text-sm bg-[#1a1b26] rounded px-2 py-1.5 mb-2 overflow-x-auto">
        <code className="text-terminal-cyan">{command.command}</code>
      </div>

      {/* Description */}
      <p className="text-xs text-terminal-fg/70">{command.description}</p>

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
                    onClick={() => {
                      executeCommand(command.command)
                      setShowWarning(false)
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
