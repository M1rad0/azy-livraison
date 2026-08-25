import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  createDeliveryRequest,
  getClientProfile,
  quoteDeliveryPrice,
  updateClientProfile,
} from './client'

describe('client API', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('loads and updates the authenticated client profile', async () => {
    const profile = {
      accountId: 'account-1',
      username: 'client',
      displayName: 'Boutique Soa',
      phone: '034 00 000 00',
      defaultPickup: null,
      version: 0,
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => profile })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ ...profile, displayName: 'Boutique Soa II' }) })
    vi.stubGlobal('fetch', fetchMock)

    await expect(getClientProfile('token-client')).resolves.toEqual(profile)
    await updateClientProfile({
      displayName: 'Boutique Soa II',
      phone: profile.phone,
      defaultPickup: null,
    }, 'token-client')

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/client/profile', expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer token-client' }),
    }))
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/client/profile', expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({ displayName: 'Boutique Soa II', phone: profile.phone, defaultPickup: null }),
    }))
  })

  it('exposes the daily-capacity confirmation data from a 409 response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      json: async () => ({
        code: 'DAILY_CAPACITY_CONFIRMATION_REQUIRED',
        message: 'La capacité habituelle sera dépassée.',
        limit: 10,
        current: 9,
        requested: 3,
        projected: 12,
      }),
    }))

    const promise = createDeliveryRequest({
      requestedDate: '2026-08-23',
      pickup: { label: 'Analakely', latitude: -18.9089, longitude: 47.5212 },
      saveAsDefaultPickup: false,
      acceptCapacityOverflow: false,
      packages: [{
        recipientName: 'Rakoto',
        faritra: false,
        destination: { label: 'Ampasampito', latitude: -18.8957, longitude: 47.5458 },
        contact: '034 00 000 00',
        amountToCollectAriary: 45_000,
        mobileMoneyPaid: false,
      }],
    }, 'token-client')

    await expect(promise).rejects.toMatchObject({
      code: 'DAILY_CAPACITY_CONFIRMATION_REQUIRED',
      status: 409,
      limit: 10,
      current: 9,
      requested: 3,
      projected: 12,
    })
  })

  it('asks the backend for the delivery price of a confirmed destination', async () => {
    const quote = {
      pricingZoneId: 'zone-centre',
      pricingZoneName: 'Centre-ville',
      deliveryFeeAriary: 3_000,
      distanceFromCenterMeters: 1_200,
      pricingZoneMaxDistanceMeters: 4_000,
      pricingPolicyVersion: 2,
    }
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => quote })
    vi.stubGlobal('fetch', fetchMock)
    const destination = { label: 'Analakely', query: 'Analakely', latitude: -18.9089, longitude: 47.5212 }

    await expect(quoteDeliveryPrice(destination, 'token-client')).resolves.toEqual(quote)
    expect(fetchMock).toHaveBeenCalledWith('/api/client/delivery-pricing/quote', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ destination }),
      headers: expect.objectContaining({ Authorization: 'Bearer token-client' }),
    }))
  })
})
