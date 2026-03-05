import { useState } from 'react'
import { X, Server, Key, Lock } from 'lucide-react'
import { useTerminal } from '../contexts/TerminalContext'

interface ConnectionDialogProps {
  isOpen: boolean
  onClose: () => void
}

export default function ConnectionDialog({ isOpen, onClose }: ConnectionDialogProps) {
  const { createSession } = useTerminal()
  const [authType, setAuthType] = useState<'password' | 'key'>('password')
  const [formData, setFormData] = useState({
    host: '',
    port: '22',
    username: '',
    password: '',
    privateKey: '',
    passphrase: '',
  })
  const [connecting, setConnecting] = useState(false)
  const [error, setError] = useState('')

  if (!isOpen) return null

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setConnecting(true)

    try {
      await createSession('ssh', {
        host: formData.host,
        port: parseInt(formData.port),
        username: formData.username,
        password: authType === 'password' ? formData.password : undefined,
        privateKey: authType === 'key' ? formData.privateKey : undefined,
        passphrase: authType === 'key' ? formData.passphrase : undefined,
      })
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Connection failed')
    } finally {
      setConnecting(false)
    }
  }

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-[#1f2335] rounded-lg w-[480px] shadow-xl">
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-3 border-b border-[#292e42]">
          <div className="flex items-center gap-2">
            <Server size={18} className="text-terminal-blue" />
            <span className="font-medium">New SSH Connection</span>
          </div>
          <button
            onClick={onClose}
            className="p-1 hover:bg-[#292e42] rounded transition-colors"
          >
            <X size={18} className="text-terminal-fg/60" />
          </button>
        </div>

        {/* Form */}
        <form onSubmit={handleSubmit} className="p-4 space-y-4">
          {/* Host & Port */}
          <div className="flex gap-3">
            <div className="flex-1">
              <label className="block text-sm text-terminal-fg/60 mb-1">Host</label>
              <input
                type="text"
                value={formData.host}
                onChange={(e) => setFormData({ ...formData, host: e.target.value })}
                className="w-full bg-[#24283b] border border-[#292e42] rounded px-3 py-2 text-sm focus:outline-none focus:border-terminal-blue"
                placeholder="example.com"
                required
              />
            </div>
            <div className="w-24">
              <label className="block text-sm text-terminal-fg/60 mb-1">Port</label>
              <input
                type="number"
                value={formData.port}
                onChange={(e) => setFormData({ ...formData, port: e.target.value })}
                className="w-full bg-[#24283b] border border-[#292e42] rounded px-3 py-2 text-sm focus:outline-none focus:border-terminal-blue"
                required
              />
            </div>
          </div>

          {/* Username */}
          <div>
            <label className="block text-sm text-terminal-fg/60 mb-1">Username</label>
            <input
              type="text"
              value={formData.username}
              onChange={(e) => setFormData({ ...formData, username: e.target.value })}
              className="w-full bg-[#24283b] border border-[#292e42] rounded px-3 py-2 text-sm focus:outline-none focus:border-terminal-blue"
              placeholder="root"
              required
            />
          </div>

          {/* Auth Type Toggle */}
          <div>
            <label className="block text-sm text-terminal-fg/60 mb-2">Authentication</label>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => setAuthType('password')}
                className={`flex-1 flex items-center justify-center gap-2 py-2 rounded transition-colors ${
                  authType === 'password'
                    ? 'bg-terminal-blue/20 text-terminal-blue border border-terminal-blue'
                    : 'bg-[#24283b] border border-[#292e42] text-terminal-fg/60 hover:text-terminal-fg'
                }`}
              >
                <Lock size={16} />
                <span className="text-sm">Password</span>
              </button>
              <button
                type="button"
                onClick={() => setAuthType('key')}
                className={`flex-1 flex items-center justify-center gap-2 py-2 rounded transition-colors ${
                  authType === 'key'
                    ? 'bg-terminal-blue/20 text-terminal-blue border border-terminal-blue'
                    : 'bg-[#24283b] border border-[#292e42] text-terminal-fg/60 hover:text-terminal-fg'
                }`}
              >
                <Key size={16} />
                <span className="text-sm">Private Key</span>
              </button>
            </div>
          </div>

          {/* Password Auth */}
          {authType === 'password' && (
            <div>
              <label className="block text-sm text-terminal-fg/60 mb-1">Password</label>
              <input
                type="password"
                value={formData.password}
                onChange={(e) => setFormData({ ...formData, password: e.target.value })}
                className="w-full bg-[#24283b] border border-[#292e42] rounded px-3 py-2 text-sm focus:outline-none focus:border-terminal-blue"
                required={authType === 'password'}
              />
            </div>
          )}

          {/* Key Auth */}
          {authType === 'key' && (
            <>
              <div>
                <label className="block text-sm text-terminal-fg/60 mb-1">Private Key Path</label>
                <input
                  type="text"
                  value={formData.privateKey}
                  onChange={(e) => setFormData({ ...formData, privateKey: e.target.value })}
                  className="w-full bg-[#24283b] border border-[#292e42] rounded px-3 py-2 text-sm focus:outline-none focus:border-terminal-blue"
                  placeholder="~/.ssh/id_rsa"
                  required={authType === 'key'}
                />
              </div>
              <div>
                <label className="block text-sm text-terminal-fg/60 mb-1">Passphrase (optional)</label>
                <input
                  type="password"
                  value={formData.passphrase}
                  onChange={(e) => setFormData({ ...formData, passphrase: e.target.value })}
                  className="w-full bg-[#24283b] border border-[#292e42] rounded px-3 py-2 text-sm focus:outline-none focus:border-terminal-blue"
                />
              </div>
            </>
          )}

          {/* Error Message */}
          {error && (
            <div className="text-terminal-red text-sm bg-terminal-red/10 px-3 py-2 rounded">
              {error}
            </div>
          )}

          {/* Actions */}
          <div className="flex gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="flex-1 py-2 bg-[#24283b] hover:bg-[#292e42] rounded transition-colors text-sm"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={connecting}
              className="flex-1 py-2 bg-terminal-blue hover:bg-terminal-blue/80 rounded transition-colors text-sm font-medium disabled:opacity-50"
            >
              {connecting ? 'Connecting...' : 'Connect'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
