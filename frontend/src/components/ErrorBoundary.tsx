import { Component, ErrorInfo, ReactNode } from 'react'
import { AlertTriangle, RefreshCcw } from 'lucide-react'

interface Props {
  children: ReactNode
  fallback?: ReactNode
}

interface State {
  hasError: boolean
  error: Error | null
  errorInfo: ErrorInfo | null
}

/**
 * Error boundary component to catch and handle React errors gracefully.
 * Prevents the entire app from crashing when a component throws an error.
 */
export default class ErrorBoundary extends Component<Props, State> {
  public state: State = {
    hasError: false,
    error: null,
    errorInfo: null
  }

  public static getDerivedStateFromError(error: Error): Partial<State> {
    return { hasError: true, error }
  }

  public componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('ErrorBoundary caught an error:', error, errorInfo)
    this.setState({ errorInfo })
  }

  private handleReload = () => {
    window.location.reload()
  }

  private handleReset = () => {
    this.setState({ hasError: false, error: null, errorInfo: null })
  }

  public render() {
    if (this.state.hasError) {
      if (this.props.fallback) {
        return this.props.fallback
      }

      return (
        <div className="flex flex-col items-center justify-center h-full min-h-[200px] p-8 bg-[#1a1b26] text-terminal-fg">
          <div className="flex items-center gap-3 mb-4">
            <AlertTriangle className="text-terminal-red" size={32} />
            <h2 className="text-xl font-semibold text-terminal-red">Something went wrong</h2>
          </div>
          
          <p className="text-terminal-fg/70 text-center mb-4 max-w-md">
            An unexpected error occurred. You can try refreshing the page or resetting this component.
          </p>
          
          {this.state.error && (
            <details className="mb-4 max-w-md w-full">
              <summary className="cursor-pointer text-sm text-terminal-fg/60 hover:text-terminal-fg">
                Error details
              </summary>
              <pre className="mt-2 p-3 bg-[#24283b] rounded text-xs text-terminal-red overflow-auto max-h-40">
                {this.state.error.message}
                {this.state.errorInfo && (
                  <>
                    {'\n\nComponent Stack:'}
                    {this.state.errorInfo.componentStack}
                  </>
                )}
              </pre>
            </details>
          )}
          
          <div className="flex gap-3">
            <button
              onClick={this.handleReset}
              className="flex items-center gap-2 px-4 py-2 bg-[#292e42] hover:bg-[#343b58] rounded transition-colors"
            >
              <RefreshCcw size={16} />
              Try Again
            </button>
            <button
              onClick={this.handleReload}
              className="flex items-center gap-2 px-4 py-2 bg-terminal-blue/20 text-terminal-blue hover:bg-terminal-blue/30 rounded transition-colors"
            >
              Reload Page
            </button>
          </div>
        </div>
      )
    }

    return this.props.children
  }
}
