import { afterEach, describe, expect, it, vi } from 'vitest'
import { calculateRoutingMatrix } from './routing'

describe('distances routières pour une proposition de dispatch', () => {
  afterEach(() => vi.unstubAllGlobals())
  const points = [{ id: 'a', latitude: -18.9, longitude: 47.5 }, { id: 'b', latitude: -19, longitude: 47.6 }]

  it('transmet les points, le jeton et le signal et conserve les sens uniques', async () => {
    const payload = { pointCount: 2, distancesMeters: [[0, 1000], [1800, 0]] }
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => payload })
    vi.stubGlobal('fetch', fetchMock)
    const signal = new AbortController().signal
    expect(await calculateRoutingMatrix(points, 'token', signal)).toEqual(payload)
    expect(fetchMock).toHaveBeenCalledWith('/api/routing/matrix', expect.objectContaining({ method: 'POST',
      headers: expect.objectContaining({ Authorization: 'Bearer token' }), body: JSON.stringify({ points }), signal }))
  })

  it('ne remplace pas une route impossible par du vol d’oiseau', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ pointCount: 2, distancesMeters: [[0, null], [50, 0]] }) }))
    expect((await calculateRoutingMatrix(points, 'token')).distancesMeters[0][1]).toBeNull()
  })

  it.each([
    { pointCount: 1, distancesMeters: [[0]] },
    { pointCount: 2, distancesMeters: [[0, 1], [2]] },
    { pointCount: 2, distancesMeters: [[0, -1], [2, 0]] },
    { pointCount: 2, distancesMeters: [[0, '1'], [2, 0]] },
  ])('refuse une matrice invalide : %j', async (payload) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => payload }))
    await expect(calculateRoutingMatrix(points, 'token')).rejects.toThrow('incomplètes')
  })

  it('remonte l’indisponibilité du service sans autre appel', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, json: async () => ({ message: 'Routes indisponibles' }) })
    vi.stubGlobal('fetch', fetchMock)
    await expect(calculateRoutingMatrix(points, 'token')).rejects.toThrow('Routes indisponibles')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
