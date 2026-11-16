import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ConfirmDialog } from './ConfirmDialog'

afterEach(cleanup)

describe('ConfirmDialog', () => {
  it('affiche la fenêtre dans le body avec un contenu séparé des actions', () => {
    const onConfirm = vi.fn()
    const { container } = render(<ConfirmDialog title="Transfert" confirmLabel="Confirmer" onConfirm={onConfirm} onClose={vi.fn()}><p>Formulaire</p></ConfirmDialog>)
    const dialog = screen.getByRole('dialog', { name: 'Transfert' })
    expect(container).not.toContainElement(dialog)
    expect(dialog.parentElement?.parentElement).toBe(document.body)
    expect(within(dialog).getByText('Formulaire').parentElement).toHaveClass('confirm-dialog__content')
    expect(within(dialog).getByRole('button', { name: 'Confirmer' }).parentElement?.tagName).toBe('FOOTER')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirmer' }))
    expect(onConfirm).toHaveBeenCalledOnce()
  })

  it('ferme avec Échap ou le fond, mais pas en cliquant dans le formulaire', () => {
    const onClose = vi.fn()
    render(<ConfirmDialog title="Transfert" confirmLabel="Confirmer" onConfirm={vi.fn()} onClose={onClose}><p>Formulaire</p></ConfirmDialog>)
    fireEvent.mouseDown(screen.getByText('Formulaire'))
    expect(onClose).not.toHaveBeenCalled()
    fireEvent.keyDown(window, { key: 'Escape' })
    fireEvent.mouseDown(screen.getByRole('dialog').parentElement!)
    expect(onClose).toHaveBeenCalledTimes(2)
  })

  it('empêche la fermeture et la double confirmation pendant l’enregistrement', () => {
    const onClose = vi.fn()
    const onConfirm = vi.fn()
    render(<ConfirmDialog title="Transfert" confirmLabel="Confirmer" busy onConfirm={onConfirm} onClose={onClose}><p>Formulaire</p></ConfirmDialog>)
    fireEvent.keyDown(window, { key: 'Escape' })
    fireEvent.mouseDown(screen.getByRole('dialog').parentElement!)
    for (const button of screen.getAllByRole('button')) {
      expect(button).toBeDisabled()
      fireEvent.click(button)
    }
    expect(onClose).not.toHaveBeenCalled()
    expect(onConfirm).not.toHaveBeenCalled()
  })
})
