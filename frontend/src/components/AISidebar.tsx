import { useState, useRef, useEffect } from 'react'
import { Send, Trash2, Bot, User, AlertTriangle, Play, Loader2 } from 'lucide-react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { useAI } from '../contexts/AIContext'
import CommandCardComponent from './CommandCard'

export default function AISidebar() {
  const { messages, isLoading, provider, setProvider, sendMessage, clearHistory } = useAI()
  const [input, setInput] = useState('')
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)

  // Auto-scroll to bottom when new messages arrive
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  // Auto-resize textarea
  useEffect(() => {
    if (inputRef.current) {
      inputRef.current.style.height = 'auto'
      inputRef.current.style.height = Math.min(inputRef.current.scrollHeight, 120) + 'px'
    }
  }, [input])

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (input.trim() && !isLoading) {
      sendMessage(input.trim())
      setInput('')
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSubmit(e)
    }
  }

  // Remove command patterns from display text
  const cleanMessageContent = (content: string) => {
    return content.replace(/`command:\{[^}]+\}`/g, '').trim()
  }

  return (
    <div className="flex flex-col h-full bg-[#1f2335]">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-[#292e42]">
        <div className="flex items-center gap-2">
          <Bot size={18} className="text-terminal-blue" />
          <span className="font-medium text-sm">AI Assistant</span>
        </div>
        <div className="flex items-center gap-2">
          {/* Provider selector */}
          <select
            value={provider}
            onChange={(e) => setProvider(e.target.value as 'ollama' | 'openai')}
            className="bg-[#24283b] text-xs px-2 py-1 rounded border border-[#292e42] focus:outline-none focus:border-terminal-blue"
          >
            <option value="ollama">Ollama</option>
            <option value="openai">OpenAI</option>
          </select>
          {/* Clear history */}
          <button
            onClick={clearHistory}
            className="p-1.5 text-terminal-fg/60 hover:text-terminal-red rounded transition-colors"
            title="Clear history"
          >
            <Trash2 size={14} />
          </button>
        </div>
      </div>

      {/* Messages */}
      <div className="flex-1 overflow-y-auto p-4 space-y-4">
        {messages.length === 0 ? (
          <div className="text-center text-terminal-fg/40 py-8">
            <Bot size={48} className="mx-auto mb-4 opacity-50" />
            <p className="text-sm">Ask me anything about terminal commands,</p>
            <p className="text-sm">scripting, or system administration.</p>
          </div>
        ) : (
          messages.map((message) => (
            <div
              key={message.id}
              className={`animate-fade-in ${
                message.role === 'user' ? 'flex justify-end' : ''
              }`}
            >
              <div
                className={`max-w-[90%] rounded-lg p-3 ${
                  message.role === 'user'
                    ? 'bg-terminal-blue/20 text-terminal-fg'
                    : 'bg-[#24283b] text-terminal-fg'
                }`}
              >
                {/* Message header */}
                <div className="flex items-center gap-2 mb-2 text-xs text-terminal-fg/60">
                  {message.role === 'user' ? (
                    <>
                      <User size={12} />
                      <span>You</span>
                    </>
                  ) : (
                    <>
                      <Bot size={12} />
                      <span>AI Assistant</span>
                    </>
                  )}
                  <span className="ml-auto">
                    {new Date(message.timestamp).toLocaleTimeString()}
                  </span>
                </div>

                {/* Message content */}
                <div className="markdown-body text-sm">
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>
                    {cleanMessageContent(message.content)}
                  </ReactMarkdown>
                </div>

                {/* Command cards */}
                {message.commands && message.commands.length > 0 && (
                  <div className="mt-3 space-y-2">
                    {message.commands.map((cmd, index) => (
                      <CommandCardComponent key={index} command={cmd} />
                    ))}
                  </div>
                )}
              </div>
            </div>
          ))
        )}

        {/* Loading indicator */}
        {isLoading && (
          <div className="flex items-center gap-2 text-terminal-fg/60 animate-fade-in">
            <Loader2 size={16} className="animate-spin" />
            <span className="text-sm">Thinking...</span>
          </div>
        )}

        <div ref={messagesEndRef} />
      </div>

      {/* Input area */}
      <form onSubmit={handleSubmit} className="p-4 border-t border-[#292e42]">
        <div className="flex gap-2">
          <textarea
            ref={inputRef}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Ask about commands, errors, or get help..."
            className="flex-1 bg-[#24283b] rounded-lg px-3 py-2 text-sm resize-none focus:outline-none focus:ring-1 focus:ring-terminal-blue min-h-[40px] max-h-[120px]"
            rows={1}
            disabled={isLoading}
          />
          <button
            type="submit"
            disabled={!input.trim() || isLoading}
            className="px-3 py-2 bg-terminal-blue hover:bg-terminal-blue/80 rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <Send size={16} />
          </button>
        </div>
        <p className="text-xs text-terminal-fg/40 mt-2">
          Press Enter to send, Shift+Enter for new line
        </p>
      </form>
    </div>
  )
}
