import { useEffect, type ReactNode } from 'react'
import { AlertTriangle, X } from 'lucide-react'
import { Button } from './Button'

export function ConfirmDialog({
  title,
  children,
  confirmLabel,
  cancelLabel = 'Annuler',
  busy = false,
  danger = false,
  onConfirm,
  onClose,
}: {
  title: string
  children: ReactNode
  confirmLabel: string
  cancelLabel?: string
  busy?: boolean
  danger?: boolean
  onConfirm: () => void
  onClose: () => void
}) {
  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy) onClose()
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [busy, onClose])

  return (
    <div className="confirm-dialog-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget && !busy) onClose()
    }}>
      <section className="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="confirm-dialog-title">
        <header>
          <span aria-hidden="true"><AlertTriangle /></span>
          <h2 id="confirm-dialog-title">{title}</h2>
          <button type="button" aria-label="Fermer" disabled={busy} onClick={onClose}><X aria-hidden="true" /></button>
        </header>
        <div className="confirm-dialog__content">{children}</div>
        <footer>
          <Button variant="secondary" disabled={busy} onClick={onClose}>{cancelLabel}</Button>
          <Button variant={danger ? 'danger' : 'primary'} disabled={busy} onClick={onConfirm}>{busy ? 'Enregistrement…' : confirmLabel}</Button>
        </footer>
      </section>
    </div>
  )
}
