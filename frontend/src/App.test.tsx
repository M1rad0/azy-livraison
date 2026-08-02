import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'

const session = {
  tokenType: 'Bearer',
  accessToken: 'test-access-token',
  expiresIn: 900,
  account: { id: '9ac90f82-9f3e-4ccb-b11f-d2cf804f3a1e', username: 'admin' },
}

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  }
}

describe('App', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('restores the session and confirms that the platform is operational', async () => {
    const fetchMock = vi.fn().mockImplementation((input: string) => {
      if (input === '/api/auth/refresh') return Promise.resolve(jsonResponse(session))
      if (input === '/api/system/status') {
        return Promise.resolve(jsonResponse({
          service: 'UP',
          database: 'UP',
          checkedAt: '2026-08-02T12:00:00Z',
        }))
      }
      throw new Error(`Unexpected request: ${input}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)

    await waitFor(() => expect(screen.getAllByText('Opérationnel')).toHaveLength(3))
    expect(screen.getByText(/Connecté/)).toHaveTextContent('admin')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/system/status',
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer test-access-token' }),
      }),
    )
  })

  it('shows the login form when no refresh session exists', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      code: 'INVALID_REFRESH_TOKEN',
      message: 'Session expirée.',
    }, 401)))

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Bienvenue' })).toBeInTheDocument()
    expect(screen.getByLabelText('Identifiant')).toBeInTheDocument()
    expect(screen.getByLabelText('Mot de passe')).toHaveAttribute('type', 'password')
  })

  it('logs in from the form', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ code: 'INVALID_REFRESH_TOKEN' }, 401))
      .mockResolvedValueOnce(jsonResponse(session))
      .mockResolvedValueOnce(jsonResponse({
        service: 'UP',
        database: 'UP',
        checkedAt: '2026-08-02T12:00:00Z',
      }))
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    fireEvent.change(await screen.findByLabelText('Identifiant'), { target: { value: 'admin' } })
    fireEvent.change(screen.getByLabelText('Mot de passe'), { target: { value: 'secret-password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Se connecter' }))

    await screen.findByRole('heading', { name: 'La base est prête.' })
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/auth/login',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        body: JSON.stringify({ username: 'admin', password: 'secret-password' }),
      }),
    )
  })
})
