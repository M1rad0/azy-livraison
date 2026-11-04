import { beforeEach, describe, expect, it, vi } from 'vitest'
import { quoteDeliveryPrice } from './api/client'
import { searchAddress } from './api/geocoding'
import {
  CLIENT_PACKAGE_CSV_COLUMNS,
  CLIENT_PACKAGE_CSV_TEMPLATE,
  ClientPackageCsvFailure,
  parseClientPackageCsv,
  prepareClientPackageCsv,
} from './clientPackageCsv'

vi.mock('./api/client', async (importOriginal) => ({
  ...await importOriginal<typeof import('./api/client')>(), quoteDeliveryPrice: vi.fn(),
}))
vi.mock('./api/geocoding', () => ({ searchAddress: vi.fn() }))

const quote = {
  pricingZoneId: 'centre', pricingZoneName: 'Centre', deliveryFeeAriary: 3000,
  distanceFromCenterMeters: 1000, pricingZoneMaxDistanceMeters: 5000,
  pricingPolicyVersion: 1, distanceMode: 'AIR_DISTANCE' as const,
}
const candidate = {
  id: 'osm-1', osmType: 'node', osmId: 1,
  displayName: 'Ankorondrano, Antananarivo, Madagascar', latitude: -18.88, longitude: 47.52,
}
const headers = CLIENT_PACKAGE_CSV_COLUMNS.join(';')

describe('CSV des demandes clients', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(quoteDeliveryPrice).mockResolvedValue(quote)
    vi.mocked(searchAddress).mockResolvedValue({ query: 'Ankorondrano', provider: 'NOMINATIM', cached: false, resultCount: 1, candidates: [candidate] })
  })

  it('lit le modèle complet avec les montants, téléphones, options et virgules décimales', () => {
    const rows = parseClientPackageCsv(`\uFEFF${CLIENT_PACKAGE_CSV_TEMPLATE.replaceAll('\n', '\r\n')}`)
    expect(rows).toHaveLength(2)
    expect(rows[0].value).toEqual({
      recipientName: 'Miora', contact: '0340000000', amountToCollectAriary: '25000',
      destination: { label: 'Bureau Ankorondrano', destinationQuery: 'Ankorondrano, Antananarivo' },
      faritra: false, mobileMoneyPaid: false, weightKg: '1.5', desiredDeliveryTime: '14:00',
    })
    expect(rows[1].value.destination).toEqual({ label: 'Boutique Analakely', latitude: -18.9089, longitude: 47.5212 })
    expect(rows[1].value.mobileMoneyPaid).toBe(true)
    expect(rows[1].value.weightKg).toBe('')
  })

  it('accepte un CSV séparé par virgules, cellules multilignes et guillemets échappés', () => {
    const rows = parseClientPackageCsv('recipient_name,contact,amount_to_collect_ariary,destination_query,destination_label,faritra,mobile_money_paid\n"Rakoto, Jo",0320123456,0,"Analakely, Antananarivo","Portail \"\"bleu\"\"\n2e étage",true,0\n')
    expect(rows[0].value).toMatchObject({ recipientName: 'Rakoto, Jo', contact: '0320123456', faritra: true, mobileMoneyPaid: false })
    expect(rows[0].value.destination.label).toBe('Portail "bleu"\n2e étage')
    expect(rows[0].line).toBe(2)
  })

  it('valide toutes les lignes avant le moindre appel et indique les champs invalides', async () => {
    const content = `${headers}\nMiora;Bureau;Ankorondrano;;;0340000000;25000;oui;non;1;\nRakoto;Maison;Analakely;;;0320000000;-2;peut-être;non;-1;25:00\n`
    await expect(prepareClientPackageCsv(content, { accessToken: 'token', requestKind: 'NORMAL', signal: new AbortController().signal })).rejects.toMatchObject({
      errors: expect.arrayContaining([
        expect.objectContaining({ line: 3, field: 'amount_to_collect_ariary' }),
        expect.objectContaining({ line: 3, field: 'faritra' }),
        expect.objectContaining({ line: 3, field: 'weight_kg' }),
        expect.objectContaining({ line: 3, field: 'desired_delivery_time' }),
      ]),
    })
    expect(searchAddress).not.toHaveBeenCalled()
    expect(quoteDeliveryPrice).not.toHaveBeenCalled()
  })

  it.each([
    ['latitude incomplète', `${headers}\nMiora;Maison;;-18.9;;0340000000;0;non;non;;\n`],
    ['colonne inconnue', 'recipient_name;contact;amount_to_collect_ariary;destination_query;intrus\nMiora;034;0;Analakely;non'],
    ['guillemets non fermés', `${headers}\n"Miora;Maison;Analakely;;;034;0;non;non;;`],
    ['colonnes incohérentes', `${headers}\nMiora;Maison;Analakely;;;034;0;non`],
  ])('rejette %s sans perdre silencieusement de ligne', (_name, content) => {
    expect(() => parseClientPackageCsv(content)).toThrow(ClientPackageCsvFailure)
  })

  it('utilise le premier résultat couvert et la tarification du client sélectionné, poids et créneau inclus', async () => {
    const quotePrice = vi.fn().mockResolvedValue(quote)
    const signal = new AbortController().signal
    vi.mocked(searchAddress).mockResolvedValue({ query: 'Ankorondrano', provider: 'NOMINATIM', cached: false, resultCount: 2, candidates: [candidate, { ...candidate, id: 'second', latitude: 12 }] })
    const rows = await prepareClientPackageCsv(CLIENT_PACKAGE_CSV_TEMPLATE, { accessToken: 'admin-token', requestKind: 'NORMAL', clientAccountId: 'client-42', quotePrice, signal })
    expect(searchAddress).toHaveBeenCalledExactlyOnceWith('Ankorondrano, Antananarivo', 'admin-token', signal, { purpose: 'DESTINATION', requestKind: 'NORMAL', clientAccountId: 'client-42' })
    expect(rows[0].destination).toEqual({ label: 'Bureau Ankorondrano', destinationQuery: candidate.displayName, latitude: candidate.latitude, longitude: candidate.longitude })
    expect(rows[0].pricingQuote).toEqual(quote)
    expect(quotePrice).toHaveBeenCalledWith(expect.objectContaining({ latitude: candidate.latitude }), signal, { requestKind: 'NORMAL', weightKg: 1.5, desiredDeliveryTime: '14:00' })
    expect(quotePrice).toHaveBeenCalledWith(expect.objectContaining({ latitude: -18.9089, longitude: 47.5212 }), signal, { requestKind: 'NORMAL' })
    expect(quoteDeliveryPrice).not.toHaveBeenCalled()
  })

  it('conserve tous les colis introuvables ou sans tarif pour correction humaine', async () => {
    vi.mocked(searchAddress).mockResolvedValue({ query: 'Introuvable', provider: 'NOMINATIM', cached: false, resultCount: 0, candidates: [] })
    vi.mocked(quoteDeliveryPrice).mockRejectedValue(new Error('Service tarif indisponible'))
    const rows = await prepareClientPackageCsv(CLIENT_PACKAGE_CSV_TEMPLATE, { accessToken: 'token', requestKind: 'NORMAL', signal: new AbortController().signal })
    expect(rows).toHaveLength(2)
    expect(rows[0].destination).toEqual({ label: 'Bureau Ankorondrano', destinationQuery: 'Ankorondrano, Antananarivo' })
    expect(rows[0].importWarning).toContain('Aucun lieu trouvé')
    expect(rows[1].importWarning).toBe('Service tarif indisponible')
    expect(rows.every((row) => row.pricingQuote === undefined)).toBe(true)
  })

  it('réutilise la recherche identique pour plusieurs colis et respecte les coordonnées explicites', async () => {
    const rows = await prepareClientPackageCsv(`${headers}\nMiora;Bureau;Ankorondrano;;;034;0;non;non;;\nRakoto;Boutique;Ankorondrano;;;032;0;non;non;;\nJo;Maison;Recherche ignorée;-18.9;47.5;033;0;non;non;;`, { accessToken: 'token', requestKind: 'NORMAL', signal: new AbortController().signal })
    expect(rows).toHaveLength(3)
    expect(searchAddress).toHaveBeenCalledTimes(1)
    expect(rows[2].destination.latitude).toBe(-18.9)
    expect(new Set(rows.map((row) => row.localId)).size).toBe(3)
  })

  it('fonctionne aussi en HTTP local sans crypto.randomUUID', async () => {
    vi.stubGlobal('crypto', undefined)
    try {
      const rows = await prepareClientPackageCsv(CLIENT_PACKAGE_CSV_TEMPLATE, { accessToken: 'token', requestKind: 'NORMAL', signal: new AbortController().signal })
      expect(rows).toHaveLength(2)
      expect(rows.every((row) => row.localId.startsWith('package-'))).toBe(true)
      expect(rows[0].localId).not.toBe(rows[1].localId)
    } finally { vi.unstubAllGlobals() }
  })

  it('refuse un créneau express passé et conserve la règle normale', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-11-04T12:00:00+03:00'))
    try {
      const csv = `${headers}\nMiora;Bureau;Ankorondrano;;;034;0;non;non;;11:00`
      expect(() => parseClientPackageCsv(csv, 'EXPRESS')).toThrow('L’heure souhaitée doit être postérieure à maintenant')
      expect(parseClientPackageCsv(csv, 'NORMAL')[0].value.desiredDeliveryTime).toBe('11:00')
    } finally { vi.useRealTimers() }
  })
})
