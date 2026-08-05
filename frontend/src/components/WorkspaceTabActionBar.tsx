import type { ReactNode } from 'react'

interface WorkspaceTabActionBarProps {
  children?: ReactNode
  description: string
  title: string
}

export function WorkspaceTabActionBar({ children, description, title }: WorkspaceTabActionBarProps) {
  return (
    <header className="workspace-tab-actions">
      <div>
        <strong>{title}</strong>
        <small>{description}</small>
      </div>
      {children && <div className="workspace-tab-actions__buttons">{children}</div>}
    </header>
  )
}
