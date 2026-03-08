import { useCallback, useEffect, useMemo, useState } from 'react'
import { ArrowLeft, RefreshCw, Trash2, Filter } from 'lucide-react'
import { getBackendUrl } from '../utils/api'

interface WorkLogItem {
  id: number
  module: string
  eventType: string
  level: string
  sessionId: string
  provider: string
  summary: string
  details: string
  status: string
  durationMs: number
  createdAt: string
}

interface WorkLogResponse {
  content: WorkLogItem[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

interface WorkLogPanelProps {
  onClose: () => void
}

export default function WorkLogPanel({ onClose }: WorkLogPanelProps) {
  const [logs, setLogs] = useState<WorkLogItem[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [moduleFilter, setModuleFilter] = useState('')
  const [levelFilter, setLevelFilter] = useState('')

  const levelOptions = useMemo(() => ['', 'INFO', 'WARN', 'ERROR'], [])

  const loadLogs = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const backendUrl = await getBackendUrl()
      const params = new URLSearchParams({
        page: String(page),
        size: '30',
      })
      if (moduleFilter.trim()) {
        params.set('module', moduleFilter.trim())
      }
      if (levelFilter.trim()) {
        params.set('level', levelFilter.trim())
      }
      const res = await fetch(`${backendUrl}/api/work-logs?${params.toString()}`)
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`)
      }
      const data = (await res.json()) as WorkLogResponse
      setLogs(data.content || [])
      setTotalPages(data.totalPages || 0)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载日志失败')
    } finally {
      setLoading(false)
    }
  }, [levelFilter, moduleFilter, page])

  const clearLogs = useCallback(async () => {
    try {
      const backendUrl = await getBackendUrl()
      const res = await fetch(`${backendUrl}/api/work-logs`, { method: 'DELETE' })
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`)
      }
      setPage(0)
      await loadLogs()
    } catch (e) {
      setError(e instanceof Error ? e.message : '清空失败')
    }
  }, [loadLogs])

  useEffect(() => {
    loadLogs()
  }, [loadLogs])

  useEffect(() => {
    const timer = setInterval(() => {
      loadLogs()
    }, 5000)
    return () => clearInterval(timer)
  }, [loadLogs])

  return (
    <div className="flex flex-col h-full bg-[#1a1b26]">
      <div className="flex items-center justify-between px-4 py-3 border-b border-[#414868]">
        <div className="flex items-center gap-2">
          <button
            onClick={onClose}
            className="p-1.5 text-terminal-fg/60 hover:text-terminal-fg rounded transition-colors"
            title="返回"
          >
            <ArrowLeft size={16} />
          </button>
          <span className="font-medium text-terminal-fg">工作日志</span>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={loadLogs}
            className="p-1.5 text-terminal-fg/60 hover:text-terminal-fg rounded transition-colors"
            title="刷新"
          >
            <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
          </button>
          <button
            onClick={clearLogs}
            className="p-1.5 text-terminal-fg/60 hover:text-terminal-red rounded transition-colors"
            title="清空日志"
          >
            <Trash2 size={14} />
          </button>
        </div>
      </div>

      <div className="p-3 border-b border-[#414868] space-y-2">
        <div className="flex items-center gap-2 text-xs text-terminal-fg/60">
          <Filter size={12} />
          <span>筛选</span>
        </div>
        <div className="flex gap-2">
          <input
            value={moduleFilter}
            onChange={(e) => {
              setPage(0)
              setModuleFilter(e.target.value)
            }}
            placeholder="模块，如 AI / TERMINAL"
            className="flex-1 px-2 py-1.5 bg-[#24283b] border border-[#414868] rounded text-xs text-terminal-fg placeholder:text-terminal-fg/40 focus:outline-none focus:border-terminal-blue"
          />
          <select
            value={levelFilter}
            onChange={(e) => {
              setPage(0)
              setLevelFilter(e.target.value)
            }}
            className="px-2 py-1.5 bg-[#24283b] border border-[#414868] rounded text-xs text-terminal-fg focus:outline-none focus:border-terminal-blue"
          >
            {levelOptions.map(item => (
              <option key={item || 'ALL'} value={item}>
                {item || '全部级别'}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-3 space-y-2">
        {error && (
          <div className="text-xs text-terminal-red bg-terminal-red/10 border border-terminal-red/30 rounded p-2">
            {error}
          </div>
        )}
        {!loading && logs.length === 0 && (
          <div className="text-xs text-terminal-fg/50 text-center py-8">暂无日志</div>
        )}
        {logs.map(log => (
          <div key={log.id} className="rounded border border-[#414868] bg-[#24283b] p-2.5 space-y-1.5">
            <div className="flex items-center justify-between text-xs">
              <div className="flex items-center gap-2">
                <span className="text-terminal-cyan">{log.module}</span>
                <span className="text-terminal-fg/60">{log.eventType}</span>
                <span className={`px-1.5 py-0.5 rounded ${
                  log.level === 'ERROR' ? 'bg-terminal-red/20 text-terminal-red' :
                  log.level === 'WARN' ? 'bg-terminal-yellow/20 text-terminal-yellow' :
                  'bg-terminal-green/20 text-terminal-green'
                }`}>{log.level}</span>
              </div>
              <span className="text-terminal-fg/40">{new Date(log.createdAt).toLocaleString()}</span>
            </div>
            <div className="text-sm text-terminal-fg">{log.summary}</div>
            <div className="text-xs text-terminal-fg/60 whitespace-pre-wrap">{log.details}</div>
            <div className="flex items-center gap-3 text-xs text-terminal-fg/50">
              <span>状态: {log.status || '-'}</span>
              <span>耗时: {log.durationMs || 0} ms</span>
              <span>会话: {log.sessionId || '-'}</span>
              {log.provider && <span>Provider: {log.provider}</span>}
            </div>
          </div>
        ))}
      </div>

      <div className="px-3 py-2 border-t border-[#414868] flex items-center justify-between text-xs text-terminal-fg/60">
        <span>第 {page + 1} / {Math.max(1, totalPages)} 页</span>
        <div className="flex gap-2">
          <button
            onClick={() => setPage(p => Math.max(0, p - 1))}
            disabled={page <= 0}
            className="px-2 py-1 border border-[#414868] rounded disabled:opacity-30"
          >
            上一页
          </button>
          <button
            onClick={() => setPage(p => (p + 1 < totalPages ? p + 1 : p))}
            disabled={page + 1 >= totalPages}
            className="px-2 py-1 border border-[#414868] rounded disabled:opacity-30"
          >
            下一页
          </button>
        </div>
      </div>
    </div>
  )
}
