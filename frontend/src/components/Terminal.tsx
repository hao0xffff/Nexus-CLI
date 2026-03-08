import { useEffect, useRef, useCallback, useState } from 'react'
import { Terminal as XTerm } from 'xterm'
import { FitAddon } from '@xterm/addon-fit'
import { WebLinksAddon } from '@xterm/addon-web-links'
import { SearchAddon } from '@xterm/addon-search'
import { Unicode11Addon } from '@xterm/addon-unicode11'
import 'xterm/css/xterm.css'
import { useTerminal } from '../contexts/TerminalContext'

interface TerminalProps {
  sessionId: string
}

interface ContextMenuState {
  visible: boolean
  x: number
  y: number
}

export default function Terminal({ sessionId }: TerminalProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const terminalRef = useRef<XTerm | null>(null)
  const fitAddonRef = useRef<FitAddon | null>(null)
  const searchAddonRef = useRef<SearchAddon | null>(null)
  const initializedRef = useRef(false)
  const { sendInput, sendResize, registerTerminal } = useTerminal()
  const [contextMenu, setContextMenu] = useState<ContextMenuState>({ visible: false, x: 0, y: 0 })

  // Initialize terminal
  useEffect(() => {
    if (!containerRef.current || initializedRef.current) return
    initializedRef.current = true

    const terminal = new XTerm({
      cursorBlink: true,
      cursorStyle: 'block',
      fontSize: 14,
      fontFamily: "'Cascadia Code', 'JetBrains Mono', 'Fira Code', 'Consolas', monospace",
      theme: {
        background: '#1a1b26',
        foreground: '#c0caf5',
        cursor: '#c0caf5',
        cursorAccent: '#1a1b26',
        selectionBackground: '#33467c',
        black: '#15161e',
        brightBlack: '#414868',
        red: '#f7768e',
        brightRed: '#f7768e',
        green: '#9ece6a',
        brightGreen: '#9ece6a',
        yellow: '#e0af68',
        brightYellow: '#e0af68',
        blue: '#7aa2f7',
        brightBlue: '#7aa2f7',
        magenta: '#bb9af7',
        brightMagenta: '#bb9af7',
        cyan: '#7dcfff',
        brightCyan: '#7dcfff',
        white: '#a9b1d6',
        brightWhite: '#c0caf5',
      },
      allowProposedApi: true,
      windowsMode: navigator.platform.toLowerCase().includes('win'),
    })

    // Load addons
    const fitAddon = new FitAddon()
    const webLinksAddon = new WebLinksAddon()
    const searchAddon = new SearchAddon()
    const unicode11Addon = new Unicode11Addon()

    terminal.loadAddon(fitAddon)
    terminal.loadAddon(webLinksAddon)
    terminal.loadAddon(searchAddon)
    terminal.loadAddon(unicode11Addon)
    terminal.unicode.activeVersion = '11'

    terminal.open(containerRef.current)
    
    // Delay fit to ensure container has proper size
    setTimeout(() => {
      fitAddon.fit()
      terminal.focus()
    }, 100)

    terminalRef.current = terminal
    fitAddonRef.current = fitAddon
    searchAddonRef.current = searchAddon

    // Register terminal write function with context
    registerTerminal(sessionId, (data: Uint8Array) => {
      terminal.write(data)
    })

    // Handle user input - send to backend
    terminal.onData((data) => {
      sendInput(sessionId, data)
    })

    // Handle binary input
    terminal.onBinary((data) => {
      sendInput(sessionId, data)
    })

    // Handle keyboard shortcuts
    terminal.attachCustomKeyEventHandler((event) => {
      // Ctrl+Shift+C: Copy
      if (event.ctrlKey && event.shiftKey && event.key === 'C') {
        const selection = terminal.getSelection()
        if (selection) {
          navigator.clipboard.writeText(selection)
        }
        return false
      }
      
      // Ctrl+Shift+V: Paste
      if (event.ctrlKey && event.shiftKey && event.key === 'V') {
        navigator.clipboard.readText().then(text => {
          sendInput(sessionId, text)
        }).catch(err => {
          console.error('Failed to read clipboard:', err)
        })
        return false
      }
      
      // Ctrl+L: Clear screen
      if (event.ctrlKey && event.key === 'l') {
        terminal.clear()
        return true // Let it pass through to shell too
      }
      
      // Ctrl+Shift+F: Search
      if (event.ctrlKey && event.shiftKey && event.key === 'F') {
        // Could trigger a search UI here
        return false
      }
      
      return true
    })

    // Cleanup
    return () => {
      terminal.dispose()
      terminalRef.current = null
      fitAddonRef.current = null
      searchAddonRef.current = null
      initializedRef.current = false
    }
  }, [sessionId, sendInput, registerTerminal])

  // Handle resize
  const handleResize = useCallback(() => {
    if (fitAddonRef.current && terminalRef.current) {
      fitAddonRef.current.fit()
      const { cols, rows } = terminalRef.current
      // Only send resize if dimensions are valid (at least 10 cols)
      if (cols >= 10 && rows >= 5) {
        sendResize(sessionId, cols, rows)
      }
    }
  }, [sessionId, sendResize])

  // Set up resize observer
  useEffect(() => {
    if (!containerRef.current) return

    const resizeObserver = new ResizeObserver(() => {
      handleResize()
    })

    resizeObserver.observe(containerRef.current)

    // Also handle window resize
    window.addEventListener('resize', handleResize)

    return () => {
      resizeObserver.disconnect()
      window.removeEventListener('resize', handleResize)
    }
  }, [handleResize])

  // Focus terminal when clicked
  const handleClick = useCallback(() => {
    terminalRef.current?.focus()
    setContextMenu({ visible: false, x: 0, y: 0 })
  }, [])

  // Handle right-click context menu
  const handleContextMenu = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    setContextMenu({
      visible: true,
      x: e.clientX,
      y: e.clientY,
    })
  }, [])

  // Context menu actions
  const handleCopy = useCallback(() => {
    const selection = terminalRef.current?.getSelection()
    if (selection) {
      navigator.clipboard.writeText(selection)
    }
    setContextMenu({ visible: false, x: 0, y: 0 })
  }, [])

  const handlePaste = useCallback(async () => {
    try {
      const text = await navigator.clipboard.readText()
      sendInput(sessionId, text)
    } catch (err) {
      console.error('Failed to read clipboard:', err)
    }
    setContextMenu({ visible: false, x: 0, y: 0 })
    terminalRef.current?.focus()
  }, [sessionId, sendInput])

  const handleClear = useCallback(() => {
    terminalRef.current?.clear()
    setContextMenu({ visible: false, x: 0, y: 0 })
    terminalRef.current?.focus()
  }, [])

  const handleSelectAll = useCallback(() => {
    terminalRef.current?.selectAll()
    setContextMenu({ visible: false, x: 0, y: 0 })
  }, [])

  // Close context menu when clicking outside
  useEffect(() => {
    const handleClickOutside = () => {
      setContextMenu({ visible: false, x: 0, y: 0 })
    }
    if (contextMenu.visible) {
      document.addEventListener('click', handleClickOutside)
      return () => document.removeEventListener('click', handleClickOutside)
    }
  }, [contextMenu.visible])

  return (
    <div className="relative w-full h-full">
      <div
        ref={containerRef}
        className="w-full h-full bg-terminal-bg cursor-text"
        style={{ padding: '8px' }}
        onClick={handleClick}
        onContextMenu={handleContextMenu}
      />
      
      {/* Context Menu */}
      {contextMenu.visible && (
        <div
          className="fixed z-50 bg-[#24283b] border border-[#414868] rounded-lg shadow-xl py-1 min-w-[160px]"
          style={{ left: contextMenu.x, top: contextMenu.y }}
        >
          <button
            onClick={handleCopy}
            className="w-full px-4 py-2 text-left text-sm text-terminal-fg hover:bg-[#414868] flex items-center gap-3"
          >
            <span className="w-4">📋</span>
            <span>Copy</span>
            <span className="ml-auto text-xs text-terminal-fg/50">Ctrl+Shift+C</span>
          </button>
          <button
            onClick={handlePaste}
            className="w-full px-4 py-2 text-left text-sm text-terminal-fg hover:bg-[#414868] flex items-center gap-3"
          >
            <span className="w-4">📥</span>
            <span>Paste</span>
            <span className="ml-auto text-xs text-terminal-fg/50">Ctrl+Shift+V</span>
          </button>
          <div className="border-t border-[#414868] my-1" />
          <button
            onClick={handleSelectAll}
            className="w-full px-4 py-2 text-left text-sm text-terminal-fg hover:bg-[#414868] flex items-center gap-3"
          >
            <span className="w-4">📝</span>
            <span>Select All</span>
          </button>
          <button
            onClick={handleClear}
            className="w-full px-4 py-2 text-left text-sm text-terminal-fg hover:bg-[#414868] flex items-center gap-3"
          >
            <span className="w-4">🧹</span>
            <span>Clear</span>
            <span className="ml-auto text-xs text-terminal-fg/50">Ctrl+L</span>
          </button>
        </div>
      )}
    </div>
  )
}
