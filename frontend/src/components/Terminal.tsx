import { useEffect, useRef, useCallback } from 'react'
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

export default function Terminal({ sessionId }: TerminalProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const terminalRef = useRef<XTerm | null>(null)
  const fitAddonRef = useRef<FitAddon | null>(null)
  const { getSession, sendInput, sendResize } = useTerminal()

  const session = getSession(sessionId)

  // Initialize terminal
  useEffect(() => {
    if (!containerRef.current || terminalRef.current) return

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
    fitAddon.fit()

    terminalRef.current = terminal
    fitAddonRef.current = fitAddon

    // Handle user input
    terminal.onData((data) => {
      sendInput(sessionId, data)
    })

    // Handle binary input
    terminal.onBinary((data) => {
      sendInput(sessionId, data)
    })

    // Cleanup
    return () => {
      terminal.dispose()
      terminalRef.current = null
      fitAddonRef.current = null
    }
  }, [sessionId, sendInput])

  // Handle terminal output from session
  useEffect(() => {
    if (!session || !terminalRef.current) return

    // Write any buffered output
    if (session.outputBuffer.length > 0) {
      terminalRef.current.write(new Uint8Array(session.outputBuffer))
    }
  }, [session?.outputBuffer])

  // Handle resize
  const handleResize = useCallback(() => {
    if (fitAddonRef.current && terminalRef.current) {
      fitAddonRef.current.fit()
      const { cols, rows } = terminalRef.current
      sendResize(sessionId, cols, rows)
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

  // Expose terminal write function to context
  useEffect(() => {
    if (!terminalRef.current || !session) return

    // Register output handler
    const handleOutput = (data: Uint8Array) => {
      terminalRef.current?.write(data)
    }

    session.onOutput = handleOutput

    return () => {
      session.onOutput = undefined
    }
  }, [session])

  return (
    <div
      ref={containerRef}
      className="w-full h-full bg-terminal-bg"
      style={{ padding: '8px' }}
    />
  )
}
