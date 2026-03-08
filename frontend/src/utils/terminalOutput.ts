const ANSI_PATTERN = /\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~]|\][^\x07\x1B]*(?:\x07|\x1B\\))/g
const CONTROL_PATTERN = /[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g
const MARKER_OUTPUT_PATTERN = /^~\d{3,}~:-?\d+$/
const MARKER_COMMAND_PATTERN = /(?:printf '\\n~\d+~:%s\\n' "\$\?"|__nexus_exit=\$\?|\\033]9;~\d+~|`e]9;~\d+~|\$nexusExit\s*=|Write-Output\s+"~\d+~:\$nexusExit")/

function applyBackspaces(input: string): string {
  const chars: string[] = []
  for (const char of input) {
    if (char === '\b') {
      chars.pop()
    } else {
      chars.push(char)
    }
  }
  return chars.join('')
}

export function cleanTerminalText(input: string): string {
  const noAnsi = input.replace(ANSI_PATTERN, '')
  const noBackspaces = applyBackspaces(noAnsi)
  const noControl = noBackspaces.replace(CONTROL_PATTERN, '')
  return noControl
}

export function normalizeTerminalLine(line: string): string {
  const clean = cleanTerminalText(line)
  const carriageNormalized = clean.includes('\r') ? clean.slice(clean.lastIndexOf('\r') + 1) : clean
  return carriageNormalized.trimEnd()
}

export function isNoiseTerminalLine(line: string): boolean {
  const trimmed = line.trim()
  if (!trimmed) {
    return true
  }
  if (MARKER_OUTPUT_PATTERN.test(trimmed)) {
    return true
  }
  if (MARKER_COMMAND_PATTERN.test(trimmed)) {
    return true
  }
  return false
}
