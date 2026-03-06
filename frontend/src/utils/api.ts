/**
 * Shared API utilities with URL caching for optimal performance.
 */

// Extend Window interface for Electron API
declare global {
  interface Window {
    electronAPI?: {
      getBackendUrl: () => Promise<string>
      getWsUrl: () => Promise<string>
      platform: string
      minimize: () => void
      maximize: () => void
      close: () => void
    }
  }
}

// Cached backend URL
let cachedBackendUrl: string | undefined = undefined

/**
 * Get the backend URL, caching it after first resolution.
 * In Electron, this calls the main process; in browser, uses localhost.
 */
export async function getBackendUrl(): Promise<string> {
  if (cachedBackendUrl) {
    return cachedBackendUrl
  }
  
  if (window.electronAPI) {
    cachedBackendUrl = await window.electronAPI.getBackendUrl()
  } else {
    cachedBackendUrl = 'http://localhost:8080'
  }
  
  return cachedBackendUrl
}

/**
 * Clear the cached backend URL (call when backend restarts or config changes).
 */
export function clearBackendUrlCache(): void {
  cachedBackendUrl = undefined
}

/**
 * Get the LLM API base URL.
 */
export async function getLLMApiBase(): Promise<string> {
  const backendUrl = await getBackendUrl()
  return `${backendUrl}/api/llm`
}

/**
 * Fetch with automatic backend URL resolution.
 */
export async function apiFetch(
  path: string, 
  options?: RequestInit
): Promise<Response> {
  const backendUrl = await getBackendUrl()
  return fetch(`${backendUrl}${path}`, options)
}

/**
 * POST JSON to backend API.
 */
export async function apiPost<T>(
  path: string, 
  body: unknown
): Promise<T> {
  const response = await apiFetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  
  if (!response.ok) {
    const text = await response.text()
    throw new Error(`API error ${response.status}: ${text}`)
  }
  
  return response.json()
}

/**
 * GET from backend API.
 */
export async function apiGet<T>(path: string): Promise<T> {
  const response = await apiFetch(path)
  
  if (!response.ok) {
    const text = await response.text()
    throw new Error(`API error ${response.status}: ${text}`)
  }
  
  return response.json()
}

/**
 * PUT JSON to backend API.
 */
export async function apiPut<T>(
  path: string, 
  body: unknown
): Promise<T> {
  const response = await apiFetch(path, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  
  if (!response.ok) {
    const text = await response.text()
    throw new Error(`API error ${response.status}: ${text}`)
  }
  
  return response.json()
}
