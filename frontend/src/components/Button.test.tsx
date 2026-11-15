import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Button } from './Button'

describe('Button', () => {
  afterEach(cleanup)

  it('defaults to a non-submitting button and keeps native click behavior', () => {
    const onClick = vi.fn()
    render(<Button onClick={onClick}>Appliquer</Button>)
    const button = screen.getByRole('button', { name: 'Appliquer' })
    expect(button).toHaveAttribute('type', 'button')
    expect(button).toHaveClass('button', 'ui-button', 'ui-button--primary')
    fireEvent.click(button)
    expect(onClick).toHaveBeenCalledOnce()
  })

  it('keeps explicit form submission and disabled behavior', () => {
    const onClick = vi.fn()
    render(<Button type="submit" disabled onClick={onClick}>Enregistrer</Button>)
    const button = screen.getByRole('button', { name: 'Enregistrer' })
    expect(button).toHaveAttribute('type', 'submit')
    expect(button).toBeDisabled()
    fireEvent.click(button)
    expect(onClick).not.toHaveBeenCalled()
  })

  it('reuses the admin theme and merges compact and custom classes', () => {
    render(<Button tone="admin" variant="secondary" size="small" fullWidth className="custom-action" aria-label="Modifier le colis">Modifier</Button>)
    expect(screen.getByRole('button', { name: 'Modifier le colis' })).toHaveClass(
      'admin-button', 'admin-button--ghost', 'ui-button', 'ui-button--small', 'ui-button--wide', 'custom-action',
    )
  })

  it.each(['ghost', 'danger', 'accent'] as const)('provides the %s variant without changing its accessible name', (variant) => {
    render(<Button variant={variant}><svg aria-hidden="true" /><span>Continuer</span></Button>)
    expect(screen.getByRole('button', { name: 'Continuer' })).toHaveClass(`ui-button--${variant}`)
  })
})
