import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PortalShell, usePortalNavigation } from './PortalShell'
import type { AccountRole } from '../api/auth'

function CurrentSection() {
  return <output aria-label="Section ouverte">{usePortalNavigation()?.section}</output>
}
function shell(role: AccountRole = 'ADMIN') {
  return <PortalShell account={{ id: 'account', username: 'test', displayName: 'Test', role, phone: null }} onLogout={vi.fn()}><CurrentSection /></PortalShell>
}
describe('Routes financières non listées', () => {
  afterEach(() => { cleanup(); window.history.replaceState(null, '', '/'); vi.restoreAllMocks() })
  it('place Versements entre Dispatch et Briefing et ouvre son URL dédiée', () => {
    render(shell())
    const links = Array.from(document.querySelectorAll('.portal-sidebar__nav a'))
    const index = links.findIndex((link) => link.getAttribute('href') === '#/admin/dispatch')
    expect(links[index + 1]).toHaveAttribute('href', '#/admin/payouts')
    expect(links[index + 2]).toHaveAttribute('href', '#/admin/briefing')
    fireEvent.click(screen.getByRole('link', { name: /^Versements/ }))
    expect(screen.getByLabelText('Section ouverte')).toHaveTextContent(/^payouts$/)
    expect(window.location.hash).toBe('#/admin/payouts')
  })
  it.each(['finance-legacy-clients', 'finance-legacy-couriers'])('ouvre %s par URL sans lien dans la navigation', (route) => {
    window.history.replaceState(null, '', `/#/admin/${route}`)
    render(shell())
    expect(screen.getByLabelText('Section ouverte')).toHaveTextContent(route)
    expect(screen.queryByRole('link', { name: /Historique clients|Historique livreurs/ })).not.toBeInTheDocument()
    expect(document.querySelector(`a[href="#/admin/${route}"]`)).toBeNull()
    fireEvent.click(screen.getByRole('link', { name: /Finances/ }))
    expect(screen.getByLabelText('Section ouverte')).toHaveTextContent(/^finance$/)
    expect(window.location.hash).toBe('#/admin/finance')
  })
  it('ne donne pas accès aux routes administrateur à un compte client', () => {
    window.history.replaceState(null, '', '/#/admin/finance-legacy-clients')
    render(shell('CLIENT'))
    expect(screen.getByLabelText('Section ouverte')).toHaveTextContent('history')
    expect(window.location.hash).toBe('#/client/history')
  })
})
