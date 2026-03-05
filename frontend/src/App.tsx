import React, { useState, useEffect, useCallback, useRef } from 'react'
import Terminal from './components/Terminal'
import AISidebar from './components/AISidebar'
import TitleBar from './components/TitleBar'
import ConnectionDialog from './components/ConnectionDialog'
import { TerminalProvider, useTerminal } from './contexts/TerminalContext'
import { AIProvider } from './contexts/AIContext'
import { PanelLeftClose, PanelLeft, Plus, Settings } from 'lucide-react'

function AppContent() {
  const [sidebarOpen, setSidebarOpen] = useState(true)
  const [connectionDialogOpen, setConnectionDialogOpen] = useState(false)
  const { createSession, sessions, activeSessionId, setActiveSession, closeSession } = useTerminal()

  const handleNewLocalTerminal = useCallback(() => {
    createSession('local')
  }, [createSession])

  const handleNewSSHTerminal = useCallback(() => {
    setConnectionDialogOpen(true)
  }, [])

  // Create initial terminal session only once
  const initializedRef = useRef(false)
  useEffect(() => {
    if (!initializedRef.current && sessions.length === 0) {
      initializedRef.current = true
      createSession('local')
    }
  }, [sessions.length, createSession])

  return (
    <div className="flex flex-col h-screen bg-terminal-bg">
      <TitleBar />
      
      <div className="flex flex-1 overflow-hidden">
        {/* Terminal Area */}
        <div className="flex-1 flex flex-col min-w-0">
          {/* Terminal Tabs */}
          <div className="flex items-center bg-[#16161e] border-b border-[#1f2335] px-2">
            <div className="flex items-center gap-1 overflow-x-auto py-1 flex-1">
              {sessions.map((session, index) => (
                <div
                  key={session.id}
                  className={`group flex items-center gap-1 px-3 py-1.5 text-sm rounded-md transition-colors whitespace-nowrap cursor-pointer ${
                    session.id === activeSessionId
                      ? 'bg-terminal-bg text-terminal-fg'
                      : 'text-terminal-fg/60 hover:text-terminal-fg hover:bg-[#1a1b26]/50'
                  }`}
                  onClick={() => setActiveSession(session.id)}
                >
                  <span className={`w-2 h-2 rounded-full mr-1 ${
                    session.status === 'connected' ? 'bg-terminal-green' :
                    session.status === 'connecting' ? 'bg-terminal-yellow animate-pulse' :
                    session.status === 'error' ? 'bg-terminal-red' : 'bg-terminal-fg/30'
                  }`} />
                  <span>{session.type === 'ssh' ? `SSH: ${session.name}` : `Terminal ${index + 1}`}</span>
                  {sessions.length > 1 && (
                    <button
                      onClick={(e) => {
                        e.stopPropagation()
                        closeSession(session.id)
                      }}
                      className="ml-1 opacity-0 group-hover:opacity-100 hover:text-terminal-red transition-opacity"
                      title="Close terminal"
                    >
                      ×
                    </button>
                  )}
                </div>
              ))}
            </div>
            
            <div className="flex items-center gap-1 ml-2">
              <button
                onClick={handleNewLocalTerminal}
                className="p-1.5 text-terminal-fg/60 hover:text-terminal-fg hover:bg-[#1a1b26]/50 rounded transition-colors"
                title="New Terminal"
              >
                <Plus size={16} />
              </button>
              <button
                onClick={handleNewSSHTerminal}
                className="p-1.5 text-terminal-fg/60 hover:text-terminal-fg hover:bg-[#1a1b26]/50 rounded transition-colors"
                title="New SSH Connection"
              >
                <Settings size={16} />
              </button>
            </div>
          </div>

          {/* Terminal Container */}
          <div className="flex-1 relative">
            {sessions.map((session) => (
              <div
                key={session.id}
                className={`absolute inset-0 ${session.id === activeSessionId ? 'visible' : 'invisible'}`}
              >
                <Terminal sessionId={session.id} />
              </div>
            ))}
          </div>
        </div>

        {/* AI Sidebar */}
        <div
          className={`transition-all duration-300 ease-in-out border-l border-[#1f2335] ${
            sidebarOpen ? 'w-[400px]' : 'w-0'
          }`}
        >
          {sidebarOpen && <AISidebar />}
        </div>

        {/* Sidebar Toggle */}
        <button
          onClick={() => setSidebarOpen(!sidebarOpen)}
          className="absolute right-0 top-1/2 -translate-y-1/2 bg-[#24283b] p-1.5 rounded-l-md text-terminal-fg/60 hover:text-terminal-fg transition-colors z-10"
          style={{ right: sidebarOpen ? '400px' : '0' }}
        >
          {sidebarOpen ? <PanelLeftClose size={16} /> : <PanelLeft size={16} />}
        </button>
      </div>

      {/* SSH Connection Dialog */}
      <ConnectionDialog
        isOpen={connectionDialogOpen}
        onClose={() => setConnectionDialogOpen(false)}
      />
    </div>
  )
}

function App() {
  return (
    <TerminalProvider>
      <AIProvider>
        <AppContent />
      </AIProvider>
    </TerminalProvider>
  )
}

export default App
