import { useState, useEffect } from 'react'
import { 
  X, Settings, Server, Key, CheckCircle2, XCircle, RefreshCw, 
  Loader2, ChevronDown, ChevronRight, Globe, Cpu
} from 'lucide-react'

interface OllamaModel {
  name: string
  modifiedAt?: string
  size?: number
  family?: string
  parameterSize?: string
  quantizationLevel?: string
}

interface LLMConfig {
  activeProvider: string
  ollama: {
    baseUrl: string
    model: string
    enabled: boolean
  }
  openai: {
    baseUrl: string
    apiKey: string
    model: string
    enabled: boolean
  }
  custom: {
    name: string
    baseUrl: string
    apiKey: string
    model: string
    enabled: boolean
  }
}

interface LLMSettingsProps {
  isOpen: boolean
  onClose: () => void
  onConfigChange?: () => void
}

const API_BASE = 'http://localhost:8080/api/llm'

export default function LLMSettings({ isOpen, onClose, onConfigChange }: LLMSettingsProps) {
  const [config, setConfig] = useState<LLMConfig | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [ollamaModels, setOllamaModels] = useState<OllamaModel[]>([])
  const [loadingModels, setLoadingModels] = useState(false)
  const [testResult, setTestResult] = useState<{ provider: string; success: boolean; message: string } | null>(null)
  const [expandedSection, setExpandedSection] = useState<string>('ollama')
  
  // Form states
  const [ollamaUrl, setOllamaUrl] = useState('http://localhost:11434')
  const [selectedOllamaModel, setSelectedOllamaModel] = useState('')
  const [openaiUrl, setOpenaiUrl] = useState('https://api.openai.com')
  const [openaiKey, setOpenaiKey] = useState('')
  const [openaiModel, setOpenaiModel] = useState('gpt-4o')
  const [customName, setCustomName] = useState('')
  const [customUrl, setCustomUrl] = useState('')
  const [customKey, setCustomKey] = useState('')
  const [customModel, setCustomModel] = useState('')
  const [activeProvider, setActiveProvider] = useState('ollama')

  // Load config on mount
  useEffect(() => {
    if (isOpen) {
      loadConfig()
    }
  }, [isOpen])

  const loadConfig = async () => {
    try {
      setLoading(true)
      const res = await fetch(`${API_BASE}/config`)
      const data = await res.json()
      setConfig(data)
      
      // Set form values
      if (data.ollama) {
        setOllamaUrl(data.ollama.baseUrl || 'http://localhost:11434')
        setSelectedOllamaModel(data.ollama.model || '')
      }
      if (data.openai) {
        setOpenaiUrl(data.openai.baseUrl || 'https://api.openai.com')
        setOpenaiKey(data.openai.apiKey || '')
        setOpenaiModel(data.openai.model || 'gpt-4o')
      }
      if (data.custom) {
        setCustomName(data.custom.name || '')
        setCustomUrl(data.custom.baseUrl || '')
        setCustomKey(data.custom.apiKey || '')
        let model = data.custom.model || ''
        // DeepSeek API only accepts deepseek-chat or deepseek-reasoner
        if (data.custom.baseUrl?.toLowerCase?.().includes('deepseek') && !['deepseek-chat', 'deepseek-reasoner'].includes(model)) {
          model = 'deepseek-chat'
        }
        setCustomModel(model)
      }
      setActiveProvider(data.activeProvider || 'ollama')
      
      // Load Ollama models
      if (data.ollama?.enabled !== false) {
        loadOllamaModels()
      }
    } catch (err) {
      console.error('Failed to load config:', err)
    } finally {
      setLoading(false)
    }
  }

  const loadOllamaModels = async () => {
    try {
      setLoadingModels(true)
      const res = await fetch(`${API_BASE}/ollama/models`)
      const data = await res.json()
      setOllamaModels(data.models || [])
      if (data.currentModel && !selectedOllamaModel) {
        setSelectedOllamaModel(data.currentModel)
      }
    } catch (err) {
      console.error('Failed to load Ollama models:', err)
    } finally {
      setLoadingModels(false)
    }
  }

  const testConnection = async (provider: string) => {
    setTestResult(null)
    try {
      let res
      if (provider === 'ollama') {
        res = await fetch(`${API_BASE}/ollama/test`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ baseUrl: ollamaUrl })
        })
      } else if (provider === 'openai') {
        res = await fetch(`${API_BASE}/openai/test`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ baseUrl: openaiUrl, apiKey: openaiKey })
        })
      } else {
        res = await fetch(`${API_BASE}/openai/test`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ baseUrl: customUrl, apiKey: customKey })
        })
      }
      
      const data = await res.json()
      setTestResult({ provider, success: data.success, message: data.message })
      
      // Reload models if Ollama test successful
      if (provider === 'ollama' && data.success) {
        loadOllamaModels()
      }
    } catch (err) {
      setTestResult({ provider, success: false, message: 'Connection failed' })
    }
  }

  const saveOllamaConfig = async () => {
    setSaving(true)
    try {
      await fetch(`${API_BASE}/ollama`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ baseUrl: ollamaUrl, model: selectedOllamaModel })
      })
      onConfigChange?.()
    } catch (err) {
      console.error('Failed to save Ollama config:', err)
    } finally {
      setSaving(false)
    }
  }

  const saveOpenAIConfig = async () => {
    setSaving(true)
    try {
      await fetch(`${API_BASE}/openai`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ baseUrl: openaiUrl, apiKey: openaiKey, model: openaiModel })
      })
      onConfigChange?.()
    } catch (err) {
      console.error('Failed to save OpenAI config:', err)
    } finally {
      setSaving(false)
    }
  }

  const saveCustomConfig = async () => {
    setSaving(true)
    try {
      await fetch(`${API_BASE}/custom`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: customName, baseUrl: customUrl, apiKey: customKey, model: customModel })
      })
      onConfigChange?.()
    } catch (err) {
      console.error('Failed to save custom config:', err)
    } finally {
      setSaving(false)
    }
  }

  const setProvider = async (provider: string) => {
    try {
      await fetch(`${API_BASE}/provider`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ provider })
      })
      setActiveProvider(provider)
      onConfigChange?.()
    } catch (err) {
      console.error('Failed to set provider:', err)
    }
  }

  const formatSize = (bytes?: number) => {
    if (!bytes) return ''
    const gb = bytes / (1024 * 1024 * 1024)
    return gb >= 1 ? `${gb.toFixed(1)} GB` : `${(bytes / (1024 * 1024)).toFixed(0)} MB`
  }

  if (!isOpen) return null

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-[#1f2335] border border-[#292e42] rounded-lg shadow-xl w-[600px] max-h-[80vh] overflow-hidden flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-3 border-b border-[#292e42]">
          <div className="flex items-center gap-2">
            <Settings size={18} className="text-terminal-blue" />
            <span className="font-medium">LLM Settings</span>
          </div>
          <button onClick={onClose} className="p-1 hover:bg-[#292e42] rounded transition-colors">
            <X size={18} />
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto p-4 space-y-4">
          {loading ? (
            <div className="flex items-center justify-center py-8">
              <Loader2 size={24} className="animate-spin text-terminal-blue" />
            </div>
          ) : (
            <>
              {/* Active Provider Selection */}
              <div className="bg-[#24283b] rounded-lg p-4">
                <h3 className="text-sm font-medium mb-3">Active Provider</h3>
                <div className="flex gap-2">
                  {['ollama', 'openai', 'custom'].map((p) => (
                    <button
                      key={p}
                      onClick={() => setProvider(p)}
                      className={`px-4 py-2 rounded-lg text-sm transition-colors ${
                        activeProvider === p
                          ? 'bg-terminal-blue text-white'
                          : 'bg-[#1f2335] hover:bg-[#292e42] text-terminal-fg/70'
                      }`}
                    >
                      {p === 'ollama' ? 'Ollama (Local)' : p === 'openai' ? 'OpenAI' : customName || 'Custom API'}
                    </button>
                  ))}
                </div>
              </div>

              {/* Ollama Section */}
              <div className="border border-[#292e42] rounded-lg overflow-hidden">
                <button
                  onClick={() => setExpandedSection(expandedSection === 'ollama' ? '' : 'ollama')}
                  className="w-full flex items-center justify-between px-4 py-3 bg-[#24283b] hover:bg-[#292e42] transition-colors"
                >
                  <div className="flex items-center gap-2">
                    <Cpu size={16} className="text-terminal-green" />
                    <span className="font-medium text-sm">Ollama (Local)</span>
                    {activeProvider === 'ollama' && (
                      <span className="px-2 py-0.5 bg-terminal-green/20 text-terminal-green text-xs rounded">Active</span>
                    )}
                  </div>
                  {expandedSection === 'ollama' ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                </button>
                
                {expandedSection === 'ollama' && (
                  <div className="p-4 space-y-4">
                    {/* Base URL */}
                    <div>
                      <label className="block text-xs text-terminal-fg/60 mb-1">Base URL</label>
                      <div className="flex gap-2">
                        <input
                          type="text"
                          value={ollamaUrl}
                          onChange={(e) => setOllamaUrl(e.target.value)}
                          className="flex-1 bg-[#1f2335] border border-[#292e42] rounded px-3 py-2 text-sm focus:outline-none focus:border-terminal-blue"
                          placeholder="http://localhost:11434"
                        />
                        <button
                          onClick={() => testConnection('ollama')}
                          className="px-3 py-2 bg-[#292e42] hover:bg-[#363b54] rounded text-sm transition-colors"
                        >
                          Test
                        </button>
                      </div>
                    </div>

                    {/* Test Result */}
                    {testResult?.provider === 'ollama' && (
                      <div className={`flex items-center gap-2 px-3 py-2 rounded text-sm ${
                        testResult.success ? 'bg-terminal-green/10 text-terminal-green' : 'bg-terminal-red/10 text-terminal-red'
                      }`}>
                        {testResult.success ? <CheckCircle2 size={16} /> : <XCircle size={16} />}
                        {testResult.message}
                      </div>
                    )}

                    {/* Model Selection */}
                    <div>
                      <div className="flex items-center justify-between mb-1">
                        <label className="text-xs text-terminal-fg/60">Model</label>
                        <button
                          onClick={loadOllamaModels}
                          disabled={loadingModels}
                          className="flex items-center gap-1 text-xs text-terminal-blue hover:text-terminal-blue/80 transition-colors"
                        >
                          <RefreshCw size={12} className={loadingModels ? 'animate-spin' : ''} />
                          Refresh
                        </button>
                      </div>
                      <select
                        value={selectedOllamaModel}
                        onChange={(e) => setSelectedOllamaModel(e.target.value)}
                        className="w-full bg-[#1f2335] border border-[#292e42] rounded px-3 py-2 text-sm focus:outline-none focus:border-terminal-blue"
                      >
                        <option value="">Select a model...</option>
                        {ollamaModels.map((model) => (
                          <option key={model.name} value={model.name}>
                            {model.name} {model.parameterSize && `(${model.parameterSize})`} {formatSize(model.size)}
                          </option>
                        ))}
                      </select>
                      {ollamaModels.length === 0 && !loadingModels && (
                        <p className="text-xs text-terminal-fg/40 mt-1">
                          No models found. Make sure Ollama is running and has models installed.
                        </p>
                      )}
                    </div>

                    {/* Save Button */}
                    <button
                      onClick={saveOllamaConfig}
                      disabled={saving || !selectedOllamaModel}
                      className="w-full py-2 bg-terminal-blue hover:bg-terminal-blue/80 rounded text-sm font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      {saving ? 'Saving...' : 'Save Ollama Settings'}
                    </button>
                  </div>
                )}
              </div>

              {/* OpenAI Section */}
              <div className="border border-[#292e42] rounded-lg overflow-hidden">
                <button
                  onClick={() => setExpandedSection(expandedSection === 'openai' ? '' : 'openai')}
                  className="w-full flex items-center justify-between px-4 py-3 bg-[#24283b] hover:bg-[#292e42] transition-colors"
                >
                  <div className="flex items-center gap-2">
                    <Globe size={16} className="text-terminal-yellow" />
                    <span className="font-medium text-sm">OpenAI</span>
                    {activeProvider === 'openai' && (
                      <span className="px-2 py-0.5 bg-terminal-green/20 text-terminal-green text-xs rounded">Active</span>
                    )}
                  </div>
                  {expandedSection === 'openai' ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                </button>
                
                {expandedSection === 'openai' && (
                  <div className="p-4 space-y-4">
                    {/* Base URL */}
                    <div>
                      <label className="block text-xs text-terminal-fg/60 mb-1">Base URL</label>
                      <input
                        type="text"
                        value={openaiUrl}
                        onChange={(e) => setOpenaiUrl(e.target.value)}
                        className="w-full bg-[#1f2335] border border-[#292e42] rounded px-3 py-2 text-sm focus:outline-none focus:border-terminal-blue"
                        placeholder="https://api.openai.com"
                      />
                    </div>

                    {/* API Key */}
                    <div>
                      <label className="block text-xs text-terminal-fg/60 mb-1">API Key</label>
                      <div className="flex gap-2">
                        <input
                          type="password"
                          value={openaiKey}
                          onChange={(e) => setOpenaiKey(e.target.value)}
                          className="flex-1 bg-[#1f2335] border border-[#292e42] rounded px-3 py-2 text-sm focus:outline-none focus:border-terminal-blue"
                          placeholder="sk-..."
                        />
                        <button
                          onClick={() => testConnection('openai')}
                          disabled={!openaiKey}
                          className="px-3 py-2 bg-[#292e42] hover:bg-[#363b54] rounded text-sm transition-colors disabled:opacity-50"
                        >
                          Test
                        </button>
                      </div>
                    </div>

                    {/* Test Result */}
                    {testResult?.provider === 'openai' && (
                      <div className={`flex items-center gap-2 px-3 py-2 rounded text-sm ${
                        testResult.success ? 'bg-terminal-green/10 text-terminal-green' : 'bg-terminal-red/10 text-terminal-red'
                      }`}>
                        {testResult.success ? <CheckCircle2 size={16} /> : <XCircle size={16} />}
                        {testResult.message}
                      </div>
                    )}

                    {/* Model */}
                    <div>
                      <label className="block text-xs text-terminal-fg/60 mb-1">Model</label>
                      <select
                        value={openaiModel}
                        onChange={(e) => setOpenaiModel(e.target.value)}
                        className="w-full bg-[#1f2335] border border-[#292e42] rounded px-3 py-2 text-sm focus:outline-none focus:border-terminal-blue"
                      >
                        <option value="gpt-4o">GPT-4o</option>
                        <option value="gpt-4o-mini">GPT-4o Mini</option>
                        <option value="gpt-4-turbo">GPT-4 Turbo</option>
                        <option value="gpt-4">GPT-4</option>
                        <option value="gpt-3.5-turbo">GPT-3.5 Turbo</option>
                      </select>
                    </div>

                    {/* Save Button */}
                    <button
                      onClick={saveOpenAIConfig}
                      disabled={saving || !openaiKey}
                      className="w-full py-2 bg-terminal-blue hover:bg-terminal-blue/80 rounded text-sm font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      {saving ? 'Saving...' : 'Save OpenAI Settings'}
                    </button>
                  </div>
                )}
              </div>

              {/* Custom API Section */}
              <div className="border border-[#292e42] rounded-lg overflow-hidden">
                <button
                  onClick={() => setExpandedSection(expandedSection === 'custom' ? '' : 'custom')}
                  className="w-full flex items-center justify-between px-4 py-3 bg-[#24283b] hover:bg-[#292e42] transition-colors"
                >
                  <div className="flex items-center gap-2">
                    <Server size={16} className="text-terminal-purple" />
                    <span className="font-medium text-sm">{customName || 'Custom API'}</span>
                    {activeProvider === 'custom' && (
                      <span className="px-2 py-0.5 bg-terminal-green/20 text-terminal-green text-xs rounded">Active</span>
                    )}
                  </div>
                  {expandedSection === 'custom' ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                </button>
                
                {expandedSection === 'custom' && (
                  <div className="p-4 space-y-4">
                    <p className="text-xs text-terminal-fg/60">
                      Configure a custom OpenAI-compatible API (e.g., DeepSeek, Azure OpenAI, etc.)
                    </p>

                    {/* Preset: DeepSeek */}
                    <div className="flex flex-wrap gap-2">
                      <span className="text-xs text-terminal-fg/50 self-center">Preset:</span>
                      <button
                        type="button"
                        onClick={() => {
                          setCustomName('DeepSeek')
                          setCustomUrl('https://api.deepseek.com')
                          setCustomModel('deepseek-chat')
                        }}
                        className="px-3 py-1.5 text-xs bg-[#292e42] hover:bg-[#363b54] rounded border border-[#363b54] transition-colors"
                      >
                        DeepSeek
                      </button>
                    </div>

                    {/* Name */}
                    <div>
                      <label className="block text-xs text-terminal-fg/60 mb-1">Display Name</label>
                      <input
                        type="text"
                        value={customName}
                        onChange={(e) => setCustomName(e.target.value)}
                        className="w-full bg-[#1f2335] border border-[#292e42] rounded px-3 py-2 text-sm focus:outline-none focus:border-terminal-blue"
                        placeholder="My Custom API"
                      />
                    </div>

                    {/* Base URL */}
                    <div>
                      <label className="block text-xs text-terminal-fg/60 mb-1">Base URL</label>
                      <input
                        type="text"
                        value={customUrl}
                        onChange={(e) => setCustomUrl(e.target.value)}
                        className="w-full bg-[#1f2335] border border-[#292e42] rounded px-3 py-2 text-sm focus:outline-none focus:border-terminal-blue"
                        placeholder="https://api.deepseek.com"
                      />
                    </div>

                    {/* API Key */}
                    <div>
                      <label className="block text-xs text-terminal-fg/60 mb-1">API Key</label>
                      <div className="flex gap-2">
                        <input
                          type="password"
                          value={customKey}
                          onChange={(e) => setCustomKey(e.target.value)}
                          className="flex-1 bg-[#1f2335] border border-[#292e42] rounded px-3 py-2 text-sm focus:outline-none focus:border-terminal-blue"
                          placeholder="sk-... or DeepSeek API key"
                        />
                        <button
                          onClick={() => testConnection('custom')}
                          disabled={!customUrl || !customKey}
                          className="px-3 py-2 bg-[#292e42] hover:bg-[#363b54] rounded text-sm transition-colors disabled:opacity-50"
                        >
                          Test
                        </button>
                      </div>
                    </div>

                    {/* Test Result */}
                    {testResult?.provider === 'custom' && (
                      <div className={`flex items-center gap-2 px-3 py-2 rounded text-sm ${
                        testResult.success ? 'bg-terminal-green/10 text-terminal-green' : 'bg-terminal-red/10 text-terminal-red'
                      }`}>
                        {testResult.success ? <CheckCircle2 size={16} /> : <XCircle size={16} />}
                        {testResult.message}
                      </div>
                    )}

                    {/* Model */}
                    <div>
                      <label className="block text-xs text-terminal-fg/60 mb-1">Model Name</label>
                      {customUrl.toLowerCase().includes('deepseek') ? (
                        <select
                          value={['deepseek-chat', 'deepseek-reasoner'].includes(customModel) ? customModel : 'deepseek-chat'}
                          onChange={(e) => setCustomModel(e.target.value)}
                          className="w-full bg-[#1f2335] border border-[#292e42] rounded px-3 py-2 text-sm focus:outline-none focus:border-terminal-blue"
                        >
                          <option value="deepseek-chat">deepseek-chat</option>
                          <option value="deepseek-reasoner">deepseek-reasoner</option>
                        </select>
                      ) : (
                        <input
                          type="text"
                          value={customModel}
                          onChange={(e) => setCustomModel(e.target.value)}
                          className="w-full bg-[#1f2335] border border-[#292e42] rounded px-3 py-2 text-sm focus:outline-none focus:border-terminal-blue"
                          placeholder="e.g. deepseek-chat, model-name"
                        />
                      )}
                      <p className="text-xs text-terminal-fg/40 mt-1">
                        DeepSeek 请使用 deepseek-chat 或 deepseek-reasoner，不要填 gpt-4o 或 Ollama 风格名称
                      </p>
                    </div>

                    {/* Save Button */}
                    <button
                      onClick={saveCustomConfig}
                      disabled={saving || !customUrl || !customModel}
                      className="w-full py-2 bg-terminal-blue hover:bg-terminal-blue/80 rounded text-sm font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      {saving ? 'Saving...' : 'Save Custom API Settings'}
                    </button>
                  </div>
                )}
              </div>
            </>
          )}
        </div>

        {/* Footer */}
        <div className="px-4 py-3 border-t border-[#292e42] text-xs text-terminal-fg/40">
          Configuration is saved to ~/.ai-terminal/llm-config.json
        </div>
      </div>
    </div>
  )
}
