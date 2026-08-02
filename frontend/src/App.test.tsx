import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'

describe('App', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({
          service: 'UP',
          database: 'UP',
          checkedAt: '2026-08-02T12:00:00Z',
        }),
      }),
    )
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('confirms that the API and database are operational', async () => {
    render(<App />)

    expect(screen.getByRole('heading', { name: 'AZY Livraison' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getAllByText('Opérationnel')).toHaveLength(3))
    expect(fetch).toHaveBeenCalledWith(
      '/api/system/status',
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    )
  })
})
