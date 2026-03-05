import { useState, useEffect, useCallback } from 'react'
import { 
  X, Settings, Server, Key, CheckCircle2, XCircle, RefreshCw, 
  Loader2, Globe, Cpu, Zap, Cloud, AlertCircle, Eye, EyeOff,
  ChevronRight, Sparkles
} from 'lucide-react'

// Provider preset configurations
const PROVIDER_PRESETS = {
  ollama: {
    id: 'ollama',
    name: 'Ollama',
    description: 'Run models locally with Ollama',
    icon: Cpu,
    color: 'terminal-green',
    defaultConfig: {
      baseUrl: 'http://localhost:11434',
      model: '',
    },
    requiresApiKey: false,
    models: [], // Loaded dynamically
  },
  openai: {
    id: 'openai',
    name: 'OpenAI',
    description: 'GPT-4o, GPT-4, GPT-3.5',
    icon: Globe,
    color: 'terminal-cyan',
    defaultConfig: {
      baseUrl: 'https://api.openai.com',
      model: 'gpt-4o',
    },
    requiresApiKey: true,
    models: [
      { id: 'gpt-4o', name: 'GPT-4o', description: 'Most capable model' },
      { id: 'gpt-4o-mini', name: 'GPT-4o Mini', description: 'Fast and affordable' },
      { id: 'gpt-4-turbo', name: 'GPT-4 Turbo', description: 'High capability' },
      { id: 'gpt-3.5-turbo', name: 'GPT-3.5 Turbo', description: 'Fast responses' },
    ],
  },
  deepseek: {
    id: 'deepseek',
    name: 'DeepSeek',
    description: 'DeepSeek Chat & Reasoner',
    icon: Zap,
    color: 'terminal-blue',
    defaultConfig: {
      baseUrl: 'https://api.deepseek.com',
      model: 'deepseek-chat',
    },
    requiresApiKey: true,
    models: [
      { id: 'deepseek-chat', name: 'DeepSeek Chat', description: 'General purpose' },
      { id: 'deepseek-reasoner', name: 'DeepSeek Reasoner', description: 'Complex reasoning' },
    ],
  },
  anthropic: {
    id: 'anthropic',
    name: 'Anthropic Claude',
    description: 'Claude 3.5, Claude 3',
    icon: Sparkles,
    color: 'terminal-yellow',
    defaultConfig: {
      baseUrl: 'https://api.anthropic.com',
      model: 'claude-3-5-sonnet-20241022',
    },
    requiresApiKey: true,
    models: [
      { id: 'claude-3-5-sonnet-20241022', name: 'Claude 3.5 Sonnet', description: 'Best balance' },
      { id: 'claude-3-opus-20240229', name: 'Claude 3 Opus', description: 'Most capable' },
      { id: 'claude-3-haiku-20240307', name: 'Claude 3 Haiku', description: 'Fast and light' },
    ],
  },
  custom: {
    id: 'custom',
    name: 'Custom API',
    description: 'Any OpenAI-compatible API',
    icon: Server,
    color: 'terminal-magenta',
    defaultConfig: {
      baseUrl: '',
      model: '',
    },
    requiresApiKey: true,
    models: [],
  },
}

type ProviderId = keyof typeof PROVIDER_PRESETS

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
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [activeProvider, setActiveProviderState] = useState<string>('ollama')
  const [selectedPreset, setSelectedPreset] = useState<ProviderId>('ollama')
  
  // Configuration states
  const [ollamaUrl, setOllamaUrl] = useState('http://localhost:11434')
  const [ollamaModel, setOllamaModel] = useState('')
  const [ollamaModels, setOllamaModels] = useState<OllamaModel[]>([])
  const [loadingModels, setLoadingModels] = useState(false)
  
  const [openaiUrl, setOpenaiUrl] = useState('https://api.openai.com')
  const [openaiKey, setOpenaiKey] = useState('')
  const [openaiModel, setOpenaiModel] = useState('gpt-4o')
  
  const [customName, setCustomName] = useState('')
  const [customUrl, setCustomUrl] = useState('')
  const [customKey, setCustomKey] = useState('')
  const [customModel, setCustomModel] = useState('')
  
  // UI states
  const [showApiKey, setShowApiKey] = useState(false)
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null)
  const [testingConnection, setTestingConnection] = useState(false)

  // Load config
  useEffect(() => {
    if (isOpen) {
      loadConfig()
    }
  }, [isOpen])

  const loadConfig = async () => {
    try {
      setLoading(true)
      const res = await fetch(`${API_BASE}/config`)
      const data: LLMConfig = await res.json()
      
      // Set active provider
      const provider = data.activeProvider || 'ollama'
      setActiveProviderState(provider)
      
      // Map provider to preset
      if (provider === 'custom' && data.custom?.baseUrl?.includes('deepseek')) {
        setSelectedPreset('deepseek')
      } else if (provider === 'custom' && data.custom?.baseUrl?.includes('anthropic')) {
        setSelectedPreset('anthropic')
      } else if (provider === 'custom') {
        setSelectedPreset('custom')
      } else {
        setSelectedPreset(provider as ProviderId)
      }
      
      // Set Ollama config
      if (data.ollama) {
        setOllamaUrl(data.ollama.baseUrl || 'http://localhost:11434')
        setOllamaModel(data.ollama.model || '')
      }
      
      // Set OpenAI config
      if (data.openai) {
        setOpenaiUrl(data.openai.baseUrl || 'https://api.openai.com')
        setOpenaiKey(data.openai.apiKey || '')
        setOpenaiModel(data.openai.model || 'gpt-4o')
      }
      
      // Set Custom config (also used for DeepSeek/Anthropic)
      if (data.custom) {
        setCustomName(data.custom.name || '')
        setCustomUrl(data.custom.baseUrl || '')
        setCustomKey(data.custom.apiKey || '')
        setCustomModel(data.custom.model || '')
      }
      
      // Load Ollama models
      loadOllamaModels()
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
      if (data.currentModel && !ollamaModel) {
        setOllamaModel(data.currentModel)
      }
    } catch (err) {
      console.error('Failed to load Ollama models:', err)
    } finally {
      setLoadingModels(false)
    }
  }

  const handlePresetSelect = (presetId: ProviderId) => {
    setSelectedPreset(presetId)
    setTestResult(null)
    
    const preset = PROVIDER_PRESETS[presetId]
    
    // Apply preset defaults
    if (presetId === 'deepseek') {
      setCustomName('DeepSeek')
      setCustomUrl(preset.defaultConfig.baseUrl)
      setCustomModel(preset.defaultConfig.model)
    } else if (presetId === 'anthropic') {
      setCustomName('Anthropic')
      setCustomUrl(preset.defaultConfig.baseUrl)
      setCustomModel(preset.defaultConfig.model)
    } else if (presetId === 'openai') {
      setOpenaiUrl(preset.defaultConfig.baseUrl)
      if (!openaiModel) setOpenaiModel(preset.defaultConfig.model)
    } else if (presetId === 'ollama') {
      setOllamaUrl(preset.defaultConfig.baseUrl)
    }
  }

  const testConnection = async () => {
    setTestingConnection(true)
    setTestResult(null)
    
    try {
      let res
      if (selectedPreset === 'ollama') {
        res = await fetch(`${API_BASE}/ollama/test`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ baseUrl: ollamaUrl })
        })
      } else if (selectedPreset === 'openai') {
        res = await fetch(`${API_BASE}/openai/test`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ baseUrl: openaiUrl, apiKey: openaiKey })
        })
      } else {
        // DeepSeek, Anthropic, Custom - all use OpenAI-compatible test
        res = await fetch(`${API_BASE}/openai/test`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ baseUrl: customUrl, apiKey: customKey })
        })
      }
      
      const data = await res.json()
      setTestResult({ success: data.success, message: data.message })
      
      if (selectedPreset === 'ollama' && data.success) {
        loadOllamaModels()
      }
    } catch (err) {
      setTestResult({ success: false, message: 'Connection failed' })
    } finally {
      setTestingConnection(false)
    }
  }

  const saveAndActivate = async () => {
    setSaving(true)
    setTestResult(null)
    
    try {
      // Save configuration based on selected preset
      if (selectedPreset === 'ollama') {
        await fetch(`${API_BASE}/ollama`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ baseUrl: ollamaUrl, model: ollamaModel })
        })
        await setActiveProvider('ollama')
      } else if (selectedPreset === 'openai') {
        await fetch(`${API_BASE}/openai`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ baseUrl: openaiUrl, apiKey: openaiKey, model: openaiModel })
        })
        await setActiveProvider('openai')
      } else {
        // DeepSeek, Anthropic, Custom - save as custom
        await fetch(`${API_BASE}/custom`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ 
            name: customName || PROVIDER_PRESETS[selectedPreset].name, 
            baseUrl: customUrl, 
            apiKey: customKey, 
            model: customModel 
          })
        })
        await setActiveProvider('custom')
      }
      
      setTestResult({ success: true, message: 'Configuration saved and activated!' })
      onConfigChange?.()
    } catch (err) {
      setTestResult({ success: false, message: 'Failed to save configuration' })
    } finally {
      setSaving(false)
    }
  }

  const setActiveProvider = async (provider: string) => {
    await fetch(`${API_BASE}/provider`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider })
    })
    setActiveProviderState(provider)
  }

  const formatSize = (bytes?: number) => {
    if (!bytes) return ''
    const gb = bytes / (1024 * 1024 * 1024)
    return gb >= 1 ? `${gb.toFixed(1)}GB` : `${(bytes / (1024 * 1024)).toFixed(0)}MB`
  }

  const getCurrentProviderLabel = () => {
    if (activeProvider === 'ollama') return `Ollama: ${ollamaModel || 'Not configured'}`
    if (activeProvider === 'openai') return `OpenAI: ${openaiModel}`
    if (activeProvider === 'custom') return `${customName || 'Custom'}: ${customModel}`
    return 'Not configured'
  }

  const isConfigValid = useCallback(() => {
    if (selectedPreset === 'ollama') {
      return !!ollamaModel
    } else if (selectedPreset === 'openai') {
      return !!openaiKey && !!openaiModel
    } else {
      return !!customUrl && !!customKey && !!customModel
    }
  }, [selectedPreset, ollamaModel, openaiKey, openaiModel, customUrl, customKey, customModel])

  if (!isOpen) return null

  const currentPreset = PROVIDER_PRESETS[selectedPreset]
  const IconComponent = currentPreset.icon

  return (
    <div className="fixed inset-0 bg-black/60 flex items-center justify-center z-50 p-4">
      <div className="bg-[#1a1b26] border border-[#414868] rounded-xl shadow-2xl w-full max-w-2xl max-h-[85vh] overflow-hidden flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-[#414868] bg-[#1f2335]">
          <div className="flex items-center gap-3">
            <div className="p-2 bg-terminal-blue/20 rounded-lg">
              <Settings size={20} className="text-terminal-blue" />
            </div>
            <div>
              <h2 className="font-semibold text-lg">LLM Configuration</h2>
              <p className="text-xs text-terminal-fg/50">Current: {getCurrentProviderLabel()}</p>
            </div>
          </div>
          <button 
            onClick={onClose} 
            className="p-2 hover:bg-[#414868] rounded-lg transition-colors"
          >
            <X size={18} />
          </button>
        </div>

        {loading ? (
          <div className="flex-1 flex items-center justify-center py-12">
            <Loader2 size={32} className="animate-spin text-terminal-blue" />
          </div>
        ) : (
          <div className="flex-1 overflow-y-auto">
            {/* Provider Selection Grid */}
            <div className="p-6 border-b border-[#414868]">
              <h3 className="text-sm font-medium text-terminal-fg/70 mb-4">Select Provider</h3>
              <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
                {(Object.keys(PROVIDER_PRESETS) as ProviderId[]).map((presetId) => {
                  const preset = PROVIDER_PRESETS[presetId]
                  const Icon = preset.icon
                  const isSelected = selectedPreset === presetId
                  const isActive = (
                    (presetId === 'ollama' && activeProvider === 'ollama') ||
                    (presetId === 'openai' && activeProvider === 'openai') ||
                    (presetId === 'deepseek' && activeProvider === 'custom' && customUrl.includes('deepseek')) ||
                    (presetId === 'anthropic' && activeProvider === 'custom' && customUrl.includes('anthropic')) ||
                    (presetId === 'custom' && activeProvider === 'custom' && !customUrl.includes('deepseek') && !customUrl.includes('anthropic'))
                  )
                  
                  return (
                    <button
                      key={presetId}
                      onClick={() => handlePresetSelect(presetId)}
                      className={`relative p-4 rounded-xl border-2 transition-all text-left ${
                        isSelected 
                          ? `border-${preset.color} bg-${preset.color}/10` 
                          : 'border-[#414868] hover:border-[#565f89] bg-[#1f2335]'
                      }`}
                    >
                      {isActive && (
                        <div className="absolute top-2 right-2">
                          <CheckCircle2 size={14} className="text-terminal-green" />
                        </div>
                      )}
                      <Icon size={24} className={`text-${preset.color} mb-2`} />
                      <div className="font-medium text-sm">{preset.name}</div>
                      <div className="text-xs text-terminal-fg/50 mt-1">{preset.description}</div>
                    </button>
                  )
                })}
              </div>
            </div>

            {/* Configuration Panel */}
            <div className="p-6 space-y-5">
              <div className="flex items-center gap-3 pb-4 border-b border-[#414868]">
                <div className={`p-2 rounded-lg bg-${currentPreset.color}/20`}>
                  <IconComponent size={20} className={`text-${currentPreset.color}`} />
                </div>
                <div>
                  <h3 className="font-medium">{currentPreset.name} Configuration</h3>
                  <p className="text-xs text-terminal-fg/50">{currentPreset.description}</p>
                </div>
              </div>

              {/* Ollama Configuration */}
              {selectedPreset === 'ollama' && (
                <div className="space-y-4">
                  <div>
                    <label className="block text-sm text-terminal-fg/70 mb-2">Server URL</label>
                    <div className="flex gap-2">
                      <input
                        type="text"
                        value={ollamaUrl}
                        onChange={(e) => setOllamaUrl(e.target.value)}
                        className="flex-1 bg-[#1f2335] border border-[#414868] rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:border-terminal-green transition-colors"
                        placeholder="http://localhost:11434"
                      />
                      <button
                        onClick={testConnection}
                        disabled={testingConnection}
                        className="px-4 py-2.5 bg-[#414868] hover:bg-[#565f89] rounded-lg text-sm transition-colors disabled:opacity-50 flex items-center gap-2"
                      >
                        {testingConnection ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
                        Test
                      </button>
                    </div>
                  </div>

                  <div>
                    <div className="flex items-center justify-between mb-2">
                      <label className="text-sm text-terminal-fg/70">Model</label>
                      <button
                        onClick={loadOllamaModels}
                        disabled={loadingModels}
                        className="text-xs text-terminal-blue hover:text-terminal-blue/80 flex items-center gap-1"
                      >
                        <RefreshCw size={12} className={loadingModels ? 'animate-spin' : ''} />
                        Refresh
                      </button>
                    </div>
                    <select
                      value={ollamaModel}
                      onChange={(e) => setOllamaModel(e.target.value)}
                      className="w-full bg-[#1f2335] border border-[#414868] rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:border-terminal-green transition-colors"
                    >
                      <option value="">Select a model...</option>
                      {ollamaModels.map((model) => (
                        <option key={model.name} value={model.name}>
                          {model.name} {model.parameterSize && `(${model.parameterSize})`} {formatSize(model.size)}
                        </option>
                      ))}
                    </select>
                    {ollamaModels.length === 0 && !loadingModels && (
                      <p className="text-xs text-terminal-yellow mt-2 flex items-center gap-1">
                        <AlertCircle size={12} />
                        No models found. Make sure Ollama is running.
                      </p>
                    )}
                  </div>
                </div>
              )}

              {/* OpenAI Configuration */}
              {selectedPreset === 'openai' && (
                <div className="space-y-4">
                  <div>
                    <label className="block text-sm text-terminal-fg/70 mb-2">API Base URL</label>
                    <input
                      type="text"
                      value={openaiUrl}
                      onChange={(e) => setOpenaiUrl(e.target.value)}
                      className="w-full bg-[#1f2335] border border-[#414868] rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:border-terminal-cyan transition-colors"
                      placeholder="https://api.openai.com"
                    />
                  </div>

                  <div>
                    <label className="block text-sm text-terminal-fg/70 mb-2">API Key</label>
                    <div className="flex gap-2">
                      <div className="flex-1 relative">
                        <input
                          type={showApiKey ? 'text' : 'password'}
                          value={openaiKey}
                          onChange={(e) => setOpenaiKey(e.target.value)}
                          className="w-full bg-[#1f2335] border border-[#414868] rounded-lg px-4 py-2.5 pr-10 text-sm focus:outline-none focus:border-terminal-cyan transition-colors"
                          placeholder="sk-..."
                        />
                        <button
                          type="button"
                          onClick={() => setShowApiKey(!showApiKey)}
                          className="absolute right-3 top-1/2 -translate-y-1/2 text-terminal-fg/50 hover:text-terminal-fg"
                        >
                          {showApiKey ? <EyeOff size={16} /> : <Eye size={16} />}
                        </button>
                      </div>
                      <button
                        onClick={testConnection}
                        disabled={testingConnection || !openaiKey}
                        className="px-4 py-2.5 bg-[#414868] hover:bg-[#565f89] rounded-lg text-sm transition-colors disabled:opacity-50 flex items-center gap-2"
                      >
                        {testingConnection ? <Loader2 size={14} className="animate-spin" /> : <Key size={14} />}
                        Verify
                      </button>
                    </div>
                  </div>

                  <div>
                    <label className="block text-sm text-terminal-fg/70 mb-2">Model</label>
                    <select
                      value={openaiModel}
                      onChange={(e) => setOpenaiModel(e.target.value)}
                      className="w-full bg-[#1f2335] border border-[#414868] rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:border-terminal-cyan transition-colors"
                    >
                      {PROVIDER_PRESETS.openai.models.map((model) => (
                        <option key={model.id} value={model.id}>
                          {model.name} - {model.description}
                        </option>
                      ))}
                    </select>
                  </div>
                </div>
              )}

              {/* DeepSeek / Anthropic / Custom Configuration */}
              {(selectedPreset === 'deepseek' || selectedPreset === 'anthropic' || selectedPreset === 'custom') && (
                <div className="space-y-4">
                  {selectedPreset === 'custom' && (
                    <div>
                      <label className="block text-sm text-terminal-fg/70 mb-2">Provider Name</label>
                      <input
                        type="text"
                        value={customName}
                        onChange={(e) => setCustomName(e.target.value)}
                        className="w-full bg-[#1f2335] border border-[#414868] rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:border-terminal-magenta transition-colors"
                        placeholder="My API Provider"
                      />
                    </div>
                  )}

                  <div>
                    <label className="block text-sm text-terminal-fg/70 mb-2">API Base URL</label>
                    <input
                      type="text"
                      value={customUrl}
                      onChange={(e) => setCustomUrl(e.target.value)}
                      className={`w-full bg-[#1f2335] border border-[#414868] rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:border-${currentPreset.color} transition-colors`}
                      placeholder={currentPreset.defaultConfig.baseUrl || 'https://api.example.com'}
                    />
                  </div>

                  <div>
                    <label className="block text-sm text-terminal-fg/70 mb-2">API Key</label>
                    <div className="flex gap-2">
                      <div className="flex-1 relative">
                        <input
                          type={showApiKey ? 'text' : 'password'}
                          value={customKey}
                          onChange={(e) => setCustomKey(e.target.value)}
                          className={`w-full bg-[#1f2335] border border-[#414868] rounded-lg px-4 py-2.5 pr-10 text-sm focus:outline-none focus:border-${currentPreset.color} transition-colors`}
                          placeholder="Your API key"
                        />
                        <button
                          type="button"
                          onClick={() => setShowApiKey(!showApiKey)}
                          className="absolute right-3 top-1/2 -translate-y-1/2 text-terminal-fg/50 hover:text-terminal-fg"
                        >
                          {showApiKey ? <EyeOff size={16} /> : <Eye size={16} />}
                        </button>
                      </div>
                      <button
                        onClick={testConnection}
                        disabled={testingConnection || !customUrl || !customKey}
                        className="px-4 py-2.5 bg-[#414868] hover:bg-[#565f89] rounded-lg text-sm transition-colors disabled:opacity-50 flex items-center gap-2"
                      >
                        {testingConnection ? <Loader2 size={14} className="animate-spin" /> : <Key size={14} />}
                        Verify
                      </button>
                    </div>
                  </div>

                  <div>
                    <label className="block text-sm text-terminal-fg/70 mb-2">Model</label>
                    {currentPreset.models.length > 0 ? (
                      <select
                        value={customModel}
                        onChange={(e) => setCustomModel(e.target.value)}
                        className={`w-full bg-[#1f2335] border border-[#414868] rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:border-${currentPreset.color} transition-colors`}
                      >
                        {currentPreset.models.map((model) => (
                          <option key={model.id} value={model.id}>
                            {model.name} - {model.description}
                          </option>
                        ))}
                      </select>
                    ) : (
                      <input
                        type="text"
                        value={customModel}
                        onChange={(e) => setCustomModel(e.target.value)}
                        className={`w-full bg-[#1f2335] border border-[#414868] rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:border-${currentPreset.color} transition-colors`}
                        placeholder="model-name"
                      />
                    )}
                  </div>
                </div>
              )}

              {/* Test Result */}
              {testResult && (
                <div className={`flex items-center gap-3 px-4 py-3 rounded-lg ${
                  testResult.success 
                    ? 'bg-terminal-green/10 border border-terminal-green/30' 
                    : 'bg-terminal-red/10 border border-terminal-red/30'
                }`}>
                  {testResult.success ? (
                    <CheckCircle2 size={18} className="text-terminal-green flex-shrink-0" />
                  ) : (
                    <XCircle size={18} className="text-terminal-red flex-shrink-0" />
                  )}
                  <span className={`text-sm ${testResult.success ? 'text-terminal-green' : 'text-terminal-red'}`}>
                    {testResult.message}
                  </span>
                </div>
              )}
            </div>
          </div>
        )}

        {/* Footer */}
        <div className="px-6 py-4 border-t border-[#414868] bg-[#1f2335] flex items-center justify-between">
          <p className="text-xs text-terminal-fg/40">
            Config: ~/.ai-terminal/llm-config.json
          </p>
          <div className="flex gap-3">
            <button
              onClick={onClose}
              className="px-4 py-2 text-sm text-terminal-fg/70 hover:text-terminal-fg transition-colors"
            >
              Cancel
            </button>
            <button
              onClick={saveAndActivate}
              disabled={saving || !isConfigValid()}
              className={`px-6 py-2 bg-${currentPreset.color} hover:opacity-90 rounded-lg text-sm font-medium transition-all disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2`}
            >
              {saving ? (
                <>
                  <Loader2 size={14} className="animate-spin" />
                  Saving...
                </>
              ) : (
                <>
                  <CheckCircle2 size={14} />
                  Save & Activate
                </>
              )}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
