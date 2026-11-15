import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { quoteAdminDeliveryPrice, rescheduleAdminDeliveryPackage, updateAdminDeliveryPackage } from '../api/admin'
import { quoteDeliveryPrice, rescheduleDeliveryPackage, updateDeliveryPackage, type DeliveryPricingOptions, type DeliveryPricingQuote, type DeliveryRequestKind } from '../api/client'
import { ClientPackageDialog, packageWindowInput, type ClientPackageDraft } from './ClientPackageDialog'
import { ExistingPackageActions, type EditablePackageContext } from './ExistingPackageActions'

vi.mock('../api/admin', async (importOriginal) => ({
  ...await importOriginal<typeof import('../api/admin')>(),
  quoteAdminDeliveryPrice: vi.fn(), rescheduleAdminDeliveryPackage: vi.fn(), updateAdminDeliveryPackage: vi.fn(),
}))
vi.mock('../api/client', async (importOriginal) => ({
  ...await importOriginal<typeof import('../api/client')>(),
  quoteDeliveryPrice: vi.fn(), rescheduleDeliveryPackage: vi.fn(), updateDeliveryPackage: vi.fn(),
}))
vi.mock('./ClientPackageDialog', async (importOriginal) => ({
  ...await importOriginal<typeof import('./ClientPackageDialog')>(),
  ClientPackageDialog: vi.fn(() => <div>Formulaire de modification du colis</div>),
}))

function context(action: EditablePackageContext['action'] = 'EDIT'): EditablePackageContext {
  return {
    action, ownerAccountId: 'client-42', requestedDate: '2099-11-15', requestKind: 'NORMAL',
    item: {
      id: 'package-1', version: 7, sequenceNumber: 3, recipientName: 'Miora', faritra: false,
      destination: { label: 'Bureau', query: 'Ivandry, Antananarivo', latitude: -18.88, longitude: 47.52 },
      contact: '0340000000', amountToCollectAriary: 25000, mobileMoneyPaid: false,
      effectiveWeightKg: 2.5, weightSource: 'PROVIDED', desiredDeliveryTime: '14:00:00', desiredDeliveryEndTime: '16:30:00', deliveryTimePreference: 'CUSTOM',
    },
  }
}

function dialogProps() { return vi.mocked(ClientPackageDialog).mock.calls.at(-1)![0] }

const currentQuote: DeliveryPricingQuote = {
  pricingZoneId: 'current-zone', pricingZoneName: 'Tarif actuel', deliveryFeeAriary: 9000,
  distanceFromCenterMeters: 1500, pricingZoneMaxDistanceMeters: 10000, pricingPolicyVersion: 9,
  distanceMode: 'ROAD_DISTANCE', baseDeliveryFeeAriary: 8000, weightSurchargeAriary: 500, timeSurchargeAriary: 500,
}

function pricedContext(): EditablePackageContext {
  const source = context()
  Object.assign(source.item, {
    pricingZoneId: 'historical-zone', pricingZoneName: 'Ancienne zone', deliveryFeeAriary: 4000,
    calculatedDeliveryFeeAriary: 4000, finalDeliveryFeeAriary: 4000,
    distanceFromCenterMeters: 1000, pricingDistanceMode: 'AIR_DISTANCE', pricingZoneMaxDistanceMeters: 5000,
    pricingPolicyVersion: 1, pricingPolicyId: 12, baseDeliveryFeeAriary: 3000, weightSurchargeAriary: 500, timeSurchargeAriary: 500,
  })
  return source
}

function initialQuoteInput() {
  const props = dialogProps()
  const value = props.initialValue
  return {
    destination: { label: value.destination.label!, query: value.destination.destinationQuery,
      latitude: value.destination.latitude!, longitude: value.destination.longitude! },
    options: { requestKind: props.requestKind ?? 'NORMAL', ...(value.weightKg ? { weightKg: Number(value.weightKg) } : {}),
      ...packageWindowInput(value) } as DeliveryPricingOptions & { requestKind: DeliveryRequestKind },
  }
}

describe('ExistingPackageActions', () => {
  beforeEach(() => {
    vi.mocked(updateDeliveryPackage).mockResolvedValue({} as Awaited<ReturnType<typeof updateDeliveryPackage>>)
    vi.mocked(updateAdminDeliveryPackage).mockResolvedValue({} as Awaited<ReturnType<typeof updateAdminDeliveryPackage>>)
    vi.mocked(rescheduleDeliveryPackage).mockResolvedValue({} as Awaited<ReturnType<typeof rescheduleDeliveryPackage>>)
    vi.mocked(rescheduleAdminDeliveryPackage).mockResolvedValue({} as Awaited<ReturnType<typeof rescheduleAdminDeliveryPackage>>)
    vi.mocked(quoteDeliveryPrice).mockResolvedValue(currentQuote)
    vi.mocked(quoteAdminDeliveryPrice).mockResolvedValue(currentQuote)
  })
  afterEach(() => { cleanup(); vi.resetAllMocks() })

  it('modifie un colis client avec sa version attendue et conserve la fenêtre personnalisée libre', async () => {
    const onSaved = vi.fn()
    const onClose = vi.fn()
    render(<ExistingPackageActions context={context()} accessToken="client-token" onSaved={onSaved} onClose={onClose} />)
    const props = dialogProps()
    expect(props.initialValue).toMatchObject({
      desiredDeliveryTime: '14:00', desiredDeliveryEndTime: '16:30', deliveryTimePreference: 'CUSTOM', weightKg: '2.5',
      destination: { destinationQuery: 'Ivandry, Antananarivo' },
    })
    expect(props.geocodingClientAccountId).toBeUndefined()
    expect(props.quotePrice).toBeTypeOf('function')
    const edited: ClientPackageDraft = { ...props.initialValue, recipientName: 'Miora modifiée', mobileMoneyPaid: true,
      amountToCollectAriary: '30000', desiredDeliveryTime: '22:10', desiredDeliveryEndTime: '01:15' }

    await act(async () => { await props.onSave(edited) })

    expect(updateDeliveryPackage).toHaveBeenCalledExactlyOnceWith('package-1', 7, {
      recipientName: 'Miora modifiée', faritra: false,
      destination: { label: 'Bureau', query: 'Ivandry, Antananarivo', latitude: -18.88, longitude: 47.52 },
      contact: '0340000000', amountToCollectAriary: 30000, mobileMoneyPaid: true, weightKg: 2.5,
      deliveryTimePreference: 'CUSTOM', desiredDeliveryTime: '22:10', desiredDeliveryEndTime: '01:15',
    }, 'client-token')
    expect(updateAdminDeliveryPackage).not.toHaveBeenCalled()
    expect(onSaved).toHaveBeenCalledOnce()
    expect(onClose).toHaveBeenCalledOnce()
    expect(onSaved.mock.invocationCallOrder[0]).toBeLessThan(onClose.mock.invocationCallOrder[0])
  })

  it('utilise les API admin pour le propriétaire et transmet une période seule sans heures résiduelles', async () => {
    const onSaved = vi.fn()
    const onClose = vi.fn()
    const adminContext = context()
    adminContext.requestKind = 'EXPRESS'
    adminContext.item.weightSource = 'DEFAULT_SETTING'
    render(<ExistingPackageActions context={adminContext} accessToken="admin-token" administration onSaved={onSaved} onClose={onClose} />)
    const props = dialogProps()
    expect(props.geocodingClientAccountId).toBe('client-42')
    expect(props.initialValue.weightKg).toBe('')
    const destination = { label: 'Bureau', query: 'Ivandry', latitude: -18.88, longitude: 47.52 }
    const signal = new AbortController().signal
    await props.quotePrice!(destination, signal, { requestKind: 'EXPRESS', deliveryTimePreference: 'AFTERNOON' })
    expect(quoteAdminDeliveryPrice).toHaveBeenCalledExactlyOnceWith('client-42', destination, 'EXPRESS', 'admin-token', signal,
      { requestKind: 'EXPRESS', deliveryTimePreference: 'AFTERNOON' })

    await act(async () => { await props.onSave({ ...props.initialValue, deliveryTimePreference: 'AFTERNOON' }) })

    expect(updateAdminDeliveryPackage).toHaveBeenCalledExactlyOnceWith('package-1', 7, {
      recipientName: 'Miora', faritra: false,
      destination: { label: 'Bureau', query: 'Ivandry, Antananarivo', latitude: -18.88, longitude: 47.52 },
      contact: '0340000000', amountToCollectAriary: 25000, mobileMoneyPaid: false, deliveryTimePreference: 'AFTERNOON',
    }, 'admin-token')
    expect(updateDeliveryPackage).not.toHaveBeenCalled()
    expect(onSaved).toHaveBeenCalledOnce()
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('laisse remonter un refus de modification sans annoncer de réussite ni fermer le formulaire', async () => {
    vi.mocked(updateDeliveryPackage).mockRejectedValue(new Error('Ce colis est déjà affecté.'))
    const onSaved = vi.fn()
    const onClose = vi.fn()
    render(<ExistingPackageActions context={context()} accessToken="token" onSaved={onSaved} onClose={onClose} />)

    await expect(dialogProps().onSave(dialogProps().initialValue)).rejects.toThrow('déjà affecté')

    expect(onSaved).not.toHaveBeenCalled()
    expect(onClose).not.toHaveBeenCalled()
    expect(updateAdminDeliveryPackage).not.toHaveBeenCalled()
  })

  it.each([false, true])('reprogramme avec la bonne API (administration=%s) et ferme après le rafraîchissement réussi', async (administration) => {
    let resolveRefresh!: () => void
    const refresh = new Promise<void>((resolve) => { resolveRefresh = resolve })
    const onSaved = vi.fn().mockReturnValue(refresh)
    const onClose = vi.fn()
    const rescheduledContext = { ...context('RESCHEDULE'), requestKind: 'EXPRESS' as const }
    render(<ExistingPackageActions context={rescheduledContext} accessToken="token" administration={administration} onSaved={onSaved} onClose={onClose} />)
    expect(screen.getByRole('dialog', { name: 'Reprogrammer le colis' })).toBeInTheDocument()
    expect(screen.getByText(/traité comme une livraison normale/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Nouvelle date'), { target: { value: '2099-11-16' } })
    fireEvent.click(screen.getByRole('button', { name: 'Reprogrammer' }))

    await waitFor(() => expect(onSaved).toHaveBeenCalledOnce())
    const usedApi = administration ? rescheduleAdminDeliveryPackage : rescheduleDeliveryPackage
    const unusedApi = administration ? rescheduleDeliveryPackage : rescheduleAdminDeliveryPackage
    expect(usedApi).toHaveBeenCalledExactlyOnceWith('package-1', 7, '2099-11-16', 'token')
    expect(unusedApi).not.toHaveBeenCalled()
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Enregistrement…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Annuler' })).toBeDisabled()

    await act(async () => { resolveRefresh(); await refresh })

    expect(onClose).toHaveBeenCalledOnce()
    expect(updateDeliveryPackage).not.toHaveBeenCalled()
    expect(updateAdminDeliveryPackage).not.toHaveBeenCalled()
  })

  it('affiche le refus de reprogrammation d’un colis affecté sans fermer ni modifier les données', async () => {
    vi.mocked(rescheduleDeliveryPackage).mockRejectedValue(new Error('Ce colis est déjà affecté à un livreur.'))
    const onSaved = vi.fn()
    const onClose = vi.fn()
    render(<ExistingPackageActions context={context('RESCHEDULE')} accessToken="token" onSaved={onSaved} onClose={onClose} />)
    fireEvent.change(screen.getByLabelText('Nouvelle date'), { target: { value: '2099-11-16' } })
    fireEvent.click(screen.getByRole('button', { name: 'Reprogrammer' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('déjà affecté à un livreur')
    expect(screen.getByRole('button', { name: 'Reprogrammer' })).toBeEnabled()
    expect(screen.getByLabelText('Nouvelle date')).toHaveValue('2099-11-16')
    expect(onSaved).not.toHaveBeenCalled()
    expect(onClose).not.toHaveBeenCalled()
    expect(rescheduleAdminDeliveryPackage).not.toHaveBeenCalled()
    expect(updateDeliveryPackage).not.toHaveBeenCalled()
  })

  it('refuse la date inchangée ou vide sans appeler une API', async () => {
    const onSaved = vi.fn()
    const onClose = vi.fn()
    render(<ExistingPackageActions context={context('RESCHEDULE')} accessToken="token" onSaved={onSaved} onClose={onClose} />)
    fireEvent.click(screen.getByRole('button', { name: 'Reprogrammer' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Choisissez une nouvelle date.')
    fireEvent.change(screen.getByLabelText('Nouvelle date'), { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: 'Reprogrammer' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Choisissez une nouvelle date.')
    expect(rescheduleDeliveryPackage).not.toHaveBeenCalled()
    expect(rescheduleAdminDeliveryPackage).not.toHaveBeenCalled()
    expect(onSaved).not.toHaveBeenCalled()
    expect(onClose).not.toHaveBeenCalled()
  })

  it.each([false, true])('conserve le snapshot tarifaire pour une correction de contact (administration=%s)', async (administration) => {
    render(<ExistingPackageActions context={pricedContext()} accessToken="token" administration={administration} onSaved={vi.fn()} onClose={vi.fn()} />)
    const props = dialogProps()
    const { destination, options } = initialQuoteInput()

    const quote = await props.quotePrice!(destination, new AbortController().signal, options)
    await act(async () => { await props.onSave({ ...props.initialValue, contact: '0321111111', amountToCollectAriary: '28000' }) })

    expect(quote).toMatchObject({
      pricingZoneId: 'historical-zone', pricingZoneName: 'Ancienne zone', deliveryFeeAriary: 4000,
      distanceFromCenterMeters: 1000, pricingZoneMaxDistanceMeters: 5000, pricingPolicyVersion: 1, pricingPolicyId: 12,
      distanceMode: 'AIR_DISTANCE', baseDeliveryFeeAriary: 3000, weightSurchargeAriary: 500, timeSurchargeAriary: 500,
    })
    expect(quoteDeliveryPrice).not.toHaveBeenCalled()
    expect(quoteAdminDeliveryPrice).not.toHaveBeenCalled()
    const save = administration ? updateAdminDeliveryPackage : updateDeliveryPackage
    expect(save).toHaveBeenCalledWith('package-1', 7, expect.objectContaining({ contact: '0321111111', amountToCollectAriary: 28000 }), 'token')
  })

  it.each(['libellé', 'adresse', 'latitude', 'longitude', 'poids', 'poids par défaut', 'début', 'fin', 'période'])('recalcule le devis client quand le champ financier %s change', async (field) => {
    render(<ExistingPackageActions context={pricedContext()} accessToken="token" onSaved={vi.fn()} onClose={vi.fn()} />)
    const { destination, options } = initialQuoteInput()
    if (field === 'libellé') destination.label = 'Autre bureau'
    if (field === 'adresse') destination.query = 'Analakely'
    if (field === 'latitude') destination.latitude = -18.9
    if (field === 'longitude') destination.longitude = 47.6
    if (field === 'poids') options.weightKg = 3
    if (field === 'poids par défaut') delete options.weightKg
    if (field === 'début') options.desiredDeliveryTime = '15:00'
    if (field === 'fin') options.desiredDeliveryEndTime = '17:00'
    if (field === 'période') { options.deliveryTimePreference = 'MORNING'; delete options.desiredDeliveryTime; delete options.desiredDeliveryEndTime }
    const signal = new AbortController().signal

    expect(await dialogProps().quotePrice!(destination, signal, options)).toEqual(currentQuote)

    expect(quoteDeliveryPrice).toHaveBeenCalledExactlyOnceWith(destination, 'token', signal, options)
    expect(quoteAdminDeliveryPrice).not.toHaveBeenCalled()
  })

  it('conserve le poids par défaut historique et revient au snapshot après annulation d’un changement', async () => {
    const source = pricedContext()
    source.item.weightSource = 'DEFAULT_SETTING'
    render(<ExistingPackageActions context={source} accessToken="token" onSaved={vi.fn()} onClose={vi.fn()} />)
    const { destination, options } = initialQuoteInput()
    const signal = new AbortController().signal
    expect((await dialogProps().quotePrice!(destination, signal, options)).deliveryFeeAriary).toBe(4000)
    expect(await dialogProps().quotePrice!(destination, signal, { ...options, weightKg: 2.5 })).toEqual(currentQuote)
    expect((await dialogProps().quotePrice!(destination, signal, options)).deliveryFeeAriary).toBe(4000)
    expect(quoteDeliveryPrice).toHaveBeenCalledOnce()
  })

  it('reconnaît une période stockée avec ses heures normalisées sans la recalculer', async () => {
    const source = pricedContext()
    source.item.deliveryTimePreference = 'MORNING'
    source.item.desiredDeliveryTime = '08:00:00'
    source.item.desiredDeliveryEndTime = '12:00:00'
    render(<ExistingPackageActions context={source} accessToken="token" onSaved={vi.fn()} onClose={vi.fn()} />)
    const { destination, options } = initialQuoteInput()
    expect(options).toEqual({ requestKind: 'NORMAL', weightKg: 2.5, deliveryTimePreference: 'MORNING' })

    expect((await dialogProps().quotePrice!(destination, new AbortController().signal, options)).deliveryFeeAriary).toBe(4000)
    expect(quoteDeliveryPrice).not.toHaveBeenCalled()
  })

  it.each([0, 2500])('préserve le tarif manuel final %s Ar du snapshot, même sans métadonnée admin', async (finalFee) => {
    const source = pricedContext()
    source.item.finalDeliveryFeeAriary = finalFee
    render(<ExistingPackageActions context={source} accessToken="token" onSaved={vi.fn()} onClose={vi.fn()} />)
    const { destination, options } = initialQuoteInput()

    expect(await dialogProps().quotePrice!(destination, new AbortController().signal, options)).toMatchObject({ deliveryFeeAriary: finalFee, finalFeeOverridden: true })
    expect(quoteDeliveryPrice).not.toHaveBeenCalled()
  })

  it('recalcule les composantes admin modifiées tout en conservant le tarif manuel final convenu', async () => {
    const source = pricedContext()
    source.item.deliveryFeeOverriddenAt = '2026-11-15T08:00:00Z'
    source.item.finalDeliveryFeeAriary = 4000 // An explicit override can equal the old calculated fee.
    render(<ExistingPackageActions context={source} accessToken="admin-token" administration onSaved={vi.fn()} onClose={vi.fn()} />)
    const { destination, options } = initialQuoteInput()
    options.desiredDeliveryEndTime = '18:00'
    const signal = new AbortController().signal

    expect(await dialogProps().quotePrice!(destination, signal, options)).toEqual({ ...currentQuote, deliveryFeeAriary: 4000, finalFeeOverridden: true })
    expect(quoteAdminDeliveryPrice).toHaveBeenCalledExactlyOnceWith('client-42', destination, 'NORMAL', 'admin-token', signal, options)
    expect(quoteDeliveryPrice).not.toHaveBeenCalled()
  })

  it('utilise le calcul API si le snapshot est incomplet plutôt que d’inventer ses champs manquants', async () => {
    const source = pricedContext()
    delete source.item.distanceFromCenterMeters
    render(<ExistingPackageActions context={source} accessToken="token" onSaved={vi.fn()} onClose={vi.fn()} />)
    const { destination, options } = initialQuoteInput()
    const signal = new AbortController().signal

    expect(await dialogProps().quotePrice!(destination, signal, options)).toEqual(currentQuote)
    expect(quoteDeliveryPrice).toHaveBeenCalledExactlyOnceWith(destination, 'token', signal, options)
  })

  it('respecte l’annulation du calcul même lorsqu’un snapshot est immédiatement disponible', async () => {
    render(<ExistingPackageActions context={pricedContext()} accessToken="token" onSaved={vi.fn()} onClose={vi.fn()} />)
    const { destination, options } = initialQuoteInput()
    const abort = new AbortController()
    abort.abort()

    await expect(dialogProps().quotePrice!(destination, abort.signal, options)).rejects.toMatchObject({ name: 'AbortError' })
    expect(quoteDeliveryPrice).not.toHaveBeenCalled()
  })
})
