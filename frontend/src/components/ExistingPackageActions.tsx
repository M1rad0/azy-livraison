import { useCallback, useMemo, useState } from 'react'
import { quoteAdminDeliveryPrice, rescheduleAdminDeliveryPackage, updateAdminDeliveryPackage } from '../api/admin'
import { quoteDeliveryPrice, rescheduleDeliveryPackage, updateDeliveryPackage, type ClientLocation, type DeliveryPackageRecord, type DeliveryPricingOptions, type DeliveryPricingQuote, type DeliveryRequestKind } from '../api/client'
import { ClientPackageDialog, packageWindowInput, type ClientPackageDraft } from './ClientPackageDialog'
import { ConfirmDialog } from './ConfirmDialog'

export interface EditablePackageContext {
  item: Pick<DeliveryPackageRecord, 'id' | 'sequenceNumber' | 'recipientName' | 'faritra' | 'contact' | 'amountToCollectAriary' | 'mobileMoneyPaid' | 'effectiveWeightKg' | 'weightSource' | 'desiredDeliveryTime' | 'desiredDeliveryEndTime' | 'deliveryTimePreference' | 'version'
    | 'pricingZoneId' | 'pricingZoneName' | 'deliveryFeeAriary' | 'calculatedDeliveryFeeAriary' | 'finalDeliveryFeeAriary'
    | 'distanceFromCenterMeters' | 'pricingDistanceMode' | 'pricingZoneMaxDistanceMeters' | 'pricingPolicyVersion' | 'pricingPolicyId'
    | 'baseDeliveryFeeAriary' | 'weightSurchargeAriary' | 'timeSurchargeAriary' | 'desiredDeliveryEndsNextDay'> & {
    destination: { label?: string; query?: string; destinationQuery?: string; latitude: number; longitude: number }
    deliveryFeeOverriddenAt?: string | null
  }
  ownerAccountId: string
  requestedDate: string
  requestKind?: DeliveryRequestKind
  action: 'EDIT' | 'RESCHEDULE'
}

function validAmount(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function hasFeeOverride(item: EditablePackageContext['item']): boolean {
  const calculatedFee = item.calculatedDeliveryFeeAriary ?? item.deliveryFeeAriary
  return validAmount(item.finalDeliveryFeeAriary) && (Boolean(item.deliveryFeeOverriddenAt)
    || (validAmount(calculatedFee) && item.finalDeliveryFeeAriary !== calculatedFee))
}

function snapshotQuote(item: EditablePackageContext['item'], requestKind: DeliveryRequestKind): DeliveryPricingQuote | undefined {
  const calculatedFee = item.calculatedDeliveryFeeAriary ?? item.deliveryFeeAriary
  const finalFee = item.finalDeliveryFeeAriary ?? calculatedFee
  if (!item.pricingZoneId || !item.pricingZoneName || !validAmount(calculatedFee) || !validAmount(finalFee)
    || item.distanceFromCenterMeters == null || !Number.isFinite(item.distanceFromCenterMeters) || item.distanceFromCenterMeters < 0
    || item.pricingZoneMaxDistanceMeters == null || !Number.isFinite(item.pricingZoneMaxDistanceMeters) || item.pricingZoneMaxDistanceMeters < 0
    || !validAmount(item.pricingPolicyVersion)
    || (item.pricingDistanceMode !== 'AIR_DISTANCE' && item.pricingDistanceMode !== 'ROAD_DISTANCE')) return undefined
  return {
    pricingZoneId: item.pricingZoneId, pricingZoneName: item.pricingZoneName, deliveryFeeAriary: finalFee,
    distanceFromCenterMeters: item.distanceFromCenterMeters, pricingZoneMaxDistanceMeters: item.pricingZoneMaxDistanceMeters,
    pricingPolicyVersion: item.pricingPolicyVersion, distanceMode: item.pricingDistanceMode, requestKind,
    ...(item.pricingPolicyId != null ? { pricingPolicyId: item.pricingPolicyId } : {}),
    ...(validAmount(item.baseDeliveryFeeAriary) ? { baseDeliveryFeeAriary: item.baseDeliveryFeeAriary } : {}),
    ...(validAmount(item.weightSurchargeAriary) ? { weightSurchargeAriary: item.weightSurchargeAriary } : {}),
    ...(validAmount(item.timeSurchargeAriary) ? { timeSurchargeAriary: item.timeSurchargeAriary } : {}),
    effectiveWeightKg: item.effectiveWeightKg, desiredDeliveryTime: item.desiredDeliveryTime,
    desiredDeliveryEndTime: item.desiredDeliveryEndTime, desiredDeliveryEndsNextDay: item.desiredDeliveryEndsNextDay,
    deliveryTimePreference: item.deliveryTimePreference,
    ...(hasFeeOverride(item) ? { finalFeeOverridden: true } : {}),
  }
}

export function ExistingPackageActions({ context, accessToken, administration = false, onSaved, onClose }: {
  context: EditablePackageContext; accessToken: string; administration?: boolean; onSaved: () => void | Promise<void>; onClose: () => void
}) {
  const [date, setDate] = useState(context.requestedDate)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const { item } = context
  const initial = useMemo<ClientPackageDraft>(() => ({ localId: item.id, recipientName: item.recipientName, faritra: item.faritra,
    destination: { ...item.destination, destinationQuery: item.destination.query ?? item.destination.destinationQuery },
    contact: item.contact, amountToCollectAriary: String(item.amountToCollectAriary), mobileMoneyPaid: item.mobileMoneyPaid,
    weightKg: item.weightSource === 'PROVIDED' ? String(item.effectiveWeightKg) : '', desiredDeliveryTime: item.desiredDeliveryTime?.slice(0, 5) ?? '',
    desiredDeliveryEndTime: item.desiredDeliveryEndTime?.slice(0, 5) ?? '', deliveryTimePreference: item.deliveryTimePreference }), [item])
  const quotePrice = useCallback(async (destination: ClientLocation, signal: AbortSignal,
    options: DeliveryPricingOptions & { requestKind: DeliveryRequestKind }): Promise<DeliveryPricingQuote> => {
    signal.throwIfAborted()
    const sourceWindow = packageWindowInput(initial)
    const requestedWindow = packageWindowInput({ ...initial, desiredDeliveryTime: options.desiredDeliveryTime ?? '',
      desiredDeliveryEndTime: options.desiredDeliveryEndTime ?? '', deliveryTimePreference: options.deliveryTimePreference })
    const sourceQuery = initial.destination.destinationQuery?.trim() || undefined
    const sourceLabel = initial.destination.label?.trim() || sourceQuery || `Livraison ${item.sequenceNumber}`
    const unchanged = destination.label === sourceLabel && destination.query === sourceQuery
      && destination.latitude === item.destination.latitude && destination.longitude === item.destination.longitude
      && options.weightKg === (item.weightSource === 'PROVIDED' ? item.effectiveWeightKg : undefined)
      && sourceWindow.deliveryTimePreference === requestedWindow.deliveryTimePreference
      && sourceWindow.desiredDeliveryTime === requestedWindow.desiredDeliveryTime
      && sourceWindow.desiredDeliveryEndTime === requestedWindow.desiredDeliveryEndTime
      && options.requestKind === (context.requestKind ?? 'NORMAL')
    const snapshot = unchanged ? snapshotQuote(item, context.requestKind ?? 'NORMAL') : undefined
    if (snapshot) return snapshot
    const calculated = administration
      ? await quoteAdminDeliveryPrice(context.ownerAccountId, destination, context.requestKind ?? 'NORMAL', accessToken, signal, options)
      : await quoteDeliveryPrice(destination, accessToken, signal, options)
    // A manual fee survives repricing on the server; the preview must do so too.
    return hasFeeOverride(item) ? { ...calculated, deliveryFeeAriary: item.finalDeliveryFeeAriary!, finalFeeOverridden: true } : calculated
  }, [accessToken, administration, context.ownerAccountId, context.requestKind, initial, item])
  async function reschedule() {
    if (busy) return
    if (!date || date === context.requestedDate) { setError('Choisissez une nouvelle date.'); return }
    setBusy(true); setError(undefined)
    try {
      if (administration) await rescheduleAdminDeliveryPackage(item.id, item.version, date, accessToken)
      else await rescheduleDeliveryPackage(item.id, item.version, date, accessToken)
      await onSaved(); onClose()
    } catch (failure) { setError(failure instanceof Error ? failure.message : 'Le colis n’a pas pu être reprogrammé.') }
    finally { setBusy(false) }
  }
  if (context.action === 'RESCHEDULE') return <ConfirmDialog title="Reprogrammer le colis" confirmLabel="Reprogrammer" busy={busy}
    onClose={onClose} onConfirm={() => void reschedule()}>
    <p>Choisissez une nouvelle date pour le colis de <strong>{item.recipientName}</strong>. Le point de retrait et le tarif sont conservés.</p>
    {context.requestKind === 'EXPRESS' && <p>Ce colis sera traité comme une livraison normale à la date choisie.</p>}
    <label className="package-reschedule-date">Nouvelle date<input type="date" value={date} disabled={busy} min={new Intl.DateTimeFormat('en-CA', { timeZone: 'Indian/Antananarivo', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())} onChange={(event) => setDate(event.target.value)} /></label>
    {error && <p className="client-alert client-alert--error" role="alert">{error}</p>}
  </ConfirmDialog>
  return <ClientPackageDialog accessToken={accessToken} initialValue={initial} packageNumber={item.sequenceNumber} requestKind={context.requestKind}
    geocodingClientAccountId={administration ? context.ownerAccountId : undefined}
    quotePrice={quotePrice}
    onClose={onClose} onSave={async (value) => {
      const input = { recipientName: value.recipientName, faritra: value.faritra, destination: { label: value.destination.label!, query: value.destination.destinationQuery,
        latitude: value.destination.latitude!, longitude: value.destination.longitude! }, contact: value.contact, amountToCollectAriary: Number(value.amountToCollectAriary),
        mobileMoneyPaid: value.mobileMoneyPaid, ...(value.weightKg ? { weightKg: Number(value.weightKg) } : {}), ...packageWindowInput(value) }
      if (administration) await updateAdminDeliveryPackage(item.id, item.version, input, accessToken)
      else await updateDeliveryPackage(item.id, item.version, input, accessToken)
      await onSaved(); onClose()
    }} />
}
