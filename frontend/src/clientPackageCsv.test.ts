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
// Keep exercising files generated before the window/preference columns existed.
const headers = CLIENT_PACKAGE_CSV_COLUMNS.filter((column) => column !== 'desired_delivery_end_time' && column !== 'delivery_time_preference').join(';')
const windowHeaders = 'recipient_name;contact;amount_to_collect_ariary;destination_query;desired_delivery_time;desired_delivery_end_time;delivery_time_preference'

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
      faritra: false, mobileMoneyPaid: false, weightKg: '1.5', desiredDeliveryTime: '14:00', desiredDeliveryEndTime: '15:30', deliveryTimePreference: 'CUSTOM',
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
    expect(quotePrice).toHaveBeenCalledWith(expect.objectContaining({ latitude: candidate.latitude }), signal, { requestKind: 'NORMAL', weightKg: 1.5, desiredDeliveryTime: '14:00', desiredDeliveryEndTime: '15:30', deliveryTimePreference: 'CUSTOM' })
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

  it('conserve les anciens CSV et complète une heure de début seule par une fenêtre d’une heure', () => {
    const rows = parseClientPackageCsv(`${headers}\nMiora;Bureau;Ankorondrano;;;034;0;non;non;;9:05\nRakoto;Maison;Analakely;;;032;0;non;non;;23:30`)
    expect(rows[0].value).toMatchObject({ desiredDeliveryTime: '09:05', desiredDeliveryEndTime: '10:05', deliveryTimePreference: 'CUSTOM' })
    expect(rows[1].value).toMatchObject({ desiredDeliveryTime: '23:30', desiredDeliveryEndTime: '00:30', deliveryTimePreference: 'CUSTOM' })
  })

  it('lit des créneaux personnalisés libres, y compris une fin le lendemain', () => {
    const rows = parseClientPackageCsv(`${windowHeaders}\nMiora;034;0;Analakely;9:00;11:45;CUSTOM\nRakoto;032;0;Analakely;22:00:00;01:15:00;custom\nJo;033;0;Analakely;;;NONE`)
    expect(rows.map((row) => ({ start: row.value.desiredDeliveryTime, end: row.value.desiredDeliveryEndTime, mode: row.value.deliveryTimePreference }))).toEqual([
      { start: '09:00', end: '11:45', mode: 'CUSTOM' }, { start: '22:00', end: '01:15', mode: 'CUSTOM' }, { start: '', end: '', mode: 'NONE' },
    ])
  })

  it.each(['MORNING', 'MATIN', 'matin', 'AFTERNOON', 'SOIR', 'soir'])('accepte la période %s sans envoyer d’heures au calcul tarifaire', async (mode) => {
    const signal = new AbortController().signal
    const quotePrice = vi.fn().mockResolvedValue(quote)
    const rows = await prepareClientPackageCsv(`${windowHeaders}\nMiora;034;0;Analakely;;;${mode}`, { accessToken: 'token', requestKind: 'NORMAL', signal, quotePrice })
    const preference = ['MORNING', 'MATIN'].includes(mode.toUpperCase()) ? 'MORNING' : 'AFTERNOON'
    expect(rows[0]).toMatchObject({ deliveryTimePreference: preference, desiredDeliveryTime: '', desiredDeliveryEndTime: '' })
    expect(quotePrice).toHaveBeenCalledExactlyOnceWith(expect.any(Object), signal, { requestKind: 'NORMAL', deliveryTimePreference: preference })
  })

  it('transmet le début, la fin libre et le mode CUSTOM au calcul tarifaire', async () => {
    const signal = new AbortController().signal
    await prepareClientPackageCsv(`${windowHeaders}\nMiora;034;0;Analakely;21:30;02:00;CUSTOM`, { accessToken: 'token', requestKind: 'NORMAL', signal })
    expect(quoteDeliveryPrice).toHaveBeenCalledExactlyOnceWith(expect.any(Object), 'token', signal, {
      requestKind: 'NORMAL', desiredDeliveryTime: '21:30', desiredDeliveryEndTime: '02:00', deliveryTimePreference: 'CUSTOM',
    })
  })

  it.each([
    ['début personnalisé manquant', ';15:00;CUSTOM', 'desired_delivery_time'],
    ['fin personnalisée manquante', '14:00;;CUSTOM', 'desired_delivery_end_time'],
    ['heures égales', '14:00;14:00;CUSTOM', 'desired_delivery_end_time'],
    ['fin invalide', '14:00;25:00;CUSTOM', 'desired_delivery_end_time'],
    ['préférence inconnue', ';;NUIT', 'delivery_time_preference'],
    ['période avec heures', '09:00;10:00;MORNING', 'delivery_time_preference'],
    ['mode NONE avec heures', '09:00;10:00;NONE', 'delivery_time_preference'],
    ['fin seule sans préférence', ';15:00;', 'desired_delivery_time'],
  ])('rejette %s avant toute recherche ou cotation', async (_name, cells, field) => {
    await expect(prepareClientPackageCsv(`${windowHeaders}\nMiora;034;0;Analakely;${cells}`, {
      accessToken: 'token', requestKind: 'NORMAL', signal: new AbortController().signal,
    })).rejects.toMatchObject({ errors: expect.arrayContaining([expect.objectContaining({ line: 2, field })]) })
    expect(searchAddress).not.toHaveBeenCalled()
    expect(quoteDeliveryPrice).not.toHaveBeenCalled()
  })

  it('interdit le Matin express à partir de midi, sans bloquer Soir ni un début CUSTOM futur', () => {
    vi.useFakeTimers()
    try {
      vi.setSystemTime(new Date('2026-11-04T11:59:00+03:00'))
      expect(parseClientPackageCsv(`${windowHeaders}\nMiora;034;0;Analakely;;;MATIN`, 'EXPRESS')[0].value.deliveryTimePreference).toBe('MORNING')
      vi.setSystemTime(new Date('2026-11-04T12:00:00+03:00'))
      expect(() => parseClientPackageCsv(`${windowHeaders}\nMiora;034;0;Analakely;;;MATIN`, 'EXPRESS')).toThrow('Matin est indisponible')
      expect(parseClientPackageCsv(`${windowHeaders}\nMiora;034;0;Analakely;;;SOIR`, 'EXPRESS')[0].value.deliveryTimePreference).toBe('AFTERNOON')
      expect(parseClientPackageCsv(`${windowHeaders}\nMiora;034;0;Analakely;22:00;01:00;CUSTOM`, 'EXPRESS')[0].value.desiredDeliveryEndTime).toBe('01:00')
      expect(() => parseClientPackageCsv(`${windowHeaders}\nMiora;034;0;Analakely;12:00;15:00;CUSTOM`, 'EXPRESS')).toThrow('postérieure à maintenant')
    } finally { vi.useRealTimers() }
  })
})
