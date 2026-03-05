import { Minus, Square, X, Terminal } from 'lucide-react'

export default function TitleBar() {
  const platform = window.electronAPI?.platform || 'unknown'
  const isMac = platform === 'darwin'

  const handleMinimize = () => window.electronAPI?.minimize()
  const handleMaximize = () => window.electronAPI?.maximize()
  const handleClose = () => window.electronAPI?.close()

  return (
    <div className="flex items-center justify-between h-10 bg-[#16161e] border-b border-[#1f2335] drag-region">
      {/* Mac traffic lights space */}
      {isMac && <div className="w-20" />}

      {/* Title */}
      <div className="flex items-center gap-2 flex-1 justify-center no-drag">
        <Terminal size={16} className="text-terminal-blue" />
        <span className="text-sm text-terminal-fg/80 font-medium">AI Terminal</span>
      </div>

      {/* Windows/Linux controls */}
      {!isMac && (
        <div className="flex items-center no-drag">
          <button
            onClick={handleMinimize}
            className="w-10 h-10 flex items-center justify-center hover:bg-[#1f2335] transition-colors"
          >
            <Minus size={14} className="text-terminal-fg/60" />
          </button>
          <button
            onClick={handleMaximize}
            className="w-10 h-10 flex items-center justify-center hover:bg-[#1f2335] transition-colors"
          >
            <Square size={12} className="text-terminal-fg/60" />
          </button>
          <button
            onClick={handleClose}
            className="w-10 h-10 flex items-center justify-center hover:bg-terminal-red transition-colors"
          >
            <X size={14} className="text-terminal-fg/60 hover:text-white" />
          </button>
        </div>
      )}

      {isMac && <div className="w-20" />}
    </div>
  )
}
