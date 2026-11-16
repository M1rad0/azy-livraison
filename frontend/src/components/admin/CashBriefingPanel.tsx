import { useEffect, useMemo, useRef, useState } from 'react'
import { Banknote, ChevronLeft, ChevronRight, Plus, RefreshCw, Save, Search, Sparkles, Trash2 } from 'lucide-react'
import { getCashBriefingPlanning, prepareCashBriefing, revalidateCashBriefing, saveManualCashBriefing, type CashBriefingBatch, type CashBriefingPlanning } from '../../api/cashBriefing'
import { Button } from '../Button'
import { ConfirmDialog } from '../ConfirmDialog'
import { CourierCombobox } from './CourierCombobox'
import { PhoneInput } from '../PhoneInput'
import { isPhonePayoutOperator, isValidPhoneNumber, normalizePhoneNumber, PHONE_NUMBER_ERROR } from '../../phone'
import { PAYOUT_OPERATORS, PAYOUT_OPERATOR_ERROR, normalizePayoutOperator } from '../../payoutOperators'
import { errorMessage, formatAriary } from './adminHelpers'
import './CashBriefingPanel.css'

type DraftTransfer = {
  key: string; sellerAccountId: string; courierAccountId: string; amount: string
  destination: string; customOperator: string; customNumber: string; customHolder: string
  savedNumber?: string | null; savedHolder?: string | null; savedOperator?: string | null
}
type PlanningSeller = CashBriefingPlanning['sellers'][number]
const CUSTOM_DESTINATION = 'custom'
const activeDestinations = (seller?: PlanningSeller) => [...(seller?.payoutDestinations ?? [])]
  .filter((item) => item.active && Boolean(normalizePayoutOperator(item.operator)))
  .sort((a, b) => a.priority - b.priority)
const defaultDestination = (seller?: PlanningSeller) => activeDestinations(seller)[0]?.id ?? CUSTOM_DESTINATION
type Confirmation = 'refresh' | 'automatic'
const normalize = (value: string) => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('fr')
const editable = (batch: CashBriefingBatch | null | undefined, sellers: CashBriefingPlanning['sellers']): DraftTransfer[] => batch?.transfers.filter((transfer) => transfer.status === 'PENDING').map((transfer) => ({
  key: transfer.id, sellerAccountId: transfer.sellerAccountId, courierAccountId: transfer.courierAccountId, amount: String(transfer.amountAriary),
  destination: transfer.sellerPayoutNumberOverride ? CUSTOM_DESTINATION : transfer.sellerPayoutDestinationId ?? defaultDestination(sellers.find((seller) => seller.sellerAccountId === transfer.sellerAccountId)),
  customOperator: transfer.sellerPayoutNumberOverride ? normalizePayoutOperator(transfer.sellerPayoutOperator ?? '') : 'MVola',
  customNumber: transfer.sellerPayoutNumberOverride ? transfer.sellerPayoutNumber ?? '' : '',
  customHolder: transfer.sellerPayoutNumberOverride ? transfer.sellerPayoutAccountHolder ?? '' : '',
  savedNumber: transfer.sellerPayoutNumber, savedHolder: transfer.sellerPayoutAccountHolder, savedOperator: transfer.sellerPayoutOperator,
})) ?? []

export function CashBriefingPanel({ accessToken, planId, onBatchChange, onDirtyChange, onBlockedChange }: { accessToken: string; planId: string; onBatchChange?: (batch: CashBriefingBatch | undefined) => void; onDirtyChange?: (dirty: boolean) => void; onBlockedChange?: (blocked: boolean) => void }) {
  const [planning, setPlanning] = useState<CashBriefingPlanning>()
  const [draft, setDraft] = useState<DraftTransfer[]>([])
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [message, setMessage] = useState<string>()
  const [notice, setNotice] = useState<string>()
  const [uncertain, setUncertain] = useState(false)
  const [search, setSearch] = useState('')
  const [confirmation, setConfirmation] = useState<Confirmation>()
  const callback = useRef(onBatchChange)
  callback.current = onBatchChange
  const saveAttempt = useRef<{ signature: string; key: string } | undefined>(undefined)
  const autoAttempt = useRef<{ signature: string; key: string } | undefined>(undefined)
  const batch = planning?.batch
  const locked = busy || loading || !planning
  const stale = batch?.status === 'STALE'

  useEffect(() => { onDirtyChange?.(dirty) }, [dirty, onDirtyChange])

  function applyPlanning(result: CashBriefingPlanning) {
    setPlanning(result); setDraft(editable(result.batch, result.sellers)); setDirty(false); setUncertain(false)
    callback.current?.(result.batch ?? undefined)
  }

  useEffect(() => {
    const controller = new AbortController()
    setPlanning(undefined); setDraft([]); setDirty(false); setLoading(true); setMessage(undefined); setNotice(undefined); setUncertain(false)
    saveAttempt.current = undefined; autoAttempt.current = undefined
    callback.current?.(undefined)
    void getCashBriefingPlanning(planId, accessToken, controller.signal).then((result) => {
      if (!controller.signal.aborted) applyPlanning(result)
    }).catch((error) => { if (!controller.signal.aborted) setMessage(errorMessage(error)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [accessToken, planId])

  async function refresh() {
    setBusy(true); setMessage(undefined); setNotice(undefined)
    try { applyPlanning(await getCashBriefingPlanning(planId, accessToken)) }
    catch (error) { setMessage(errorMessage(error)); setUncertain(true) }
    finally { setBusy(false); setConfirmation(undefined) }
  }

  function update(key: string, patch: Partial<DraftTransfer>) {
    if (locked) return
    setDraft((rows) => rows.map((row) => row.key === key ? { ...row, ...patch } : row)); setDirty(true); setNotice(undefined)
  }

  const totals = useMemo(() => {
    const couriers = new Map<string, number>(), sellers = new Map<string, number>()
    for (const row of draft) {
      const amount = Number(row.amount)
      if (Number.isSafeInteger(amount) && amount > 0) {
        couriers.set(row.courierAccountId, (couriers.get(row.courierAccountId) ?? 0) + amount)
        sellers.set(row.sellerAccountId, (sellers.get(row.sellerAccountId) ?? 0) + amount)
      }
    }
    return { couriers, sellers }
  }, [draft])
  const validation = useMemo(() => {
    if (!planning) return undefined
    for (const row of draft) {
      if (!planning.sellers.some((seller) => seller.sellerAccountId === row.sellerAccountId) || !planning.couriers.some((courier) => courier.courierAccountId === row.courierAccountId)) return 'Choisissez un vendeur et un livreur actif pour chaque versement.'
      const selected = activeDestinations(planning.sellers.find((seller) => seller.sellerAccountId === row.sellerAccountId)).find((item) => item.id === row.destination)
      if (row.destination !== CUSTOM_DESTINATION && !selected) return 'Un numéro du compte n’est plus disponible. Choisissez un autre numéro de versement.'
      if (row.destination === CUSTOM_DESTINATION && !normalizePayoutOperator(row.customOperator)) return PAYOUT_OPERATOR_ERROR
      const number = row.destination === CUSTOM_DESTINATION ? row.customNumber : selected?.number ?? ''
      if (!number.trim()) return 'Renseignez un numéro de versement pour chaque versement prévu.'
      if (number.trim().length > 100 || /[\u0000-\u001f\u007f]/.test(number)) return 'Le numéro de versement contient des caractères non valides.'
      if (row.destination === CUSTOM_DESTINATION && !isValidPhoneNumber(number)) return PHONE_NUMBER_ERROR
      if (row.destination === CUSTOM_DESTINATION && !row.customHolder.trim()) return 'Renseignez le titulaire de chaque numéro de versement personnalisé.'
      if (row.destination === CUSTOM_DESTINATION && (row.customHolder.trim().length > 150 || /[\u0000-\u001f\u007f]/.test(row.customHolder))) return 'Le titulaire du versement contient des caractères non valides.'
      if (!Number.isSafeInteger(Number(row.amount)) || Number(row.amount) <= 0) return 'Chaque montant doit être un nombre entier supérieur à zéro.'
    }
    if (planning.couriers.some((courier) => (totals.couriers.get(courier.courierAccountId) ?? 0) > courier.capacityAriary)) return 'Les versements prévus dépassent la caisse disponible d’un livreur.'
    if (planning.sellers.some((seller) => (totals.sellers.get(seller.sellerAccountId) ?? 0) > Math.max(0, seller.availableAriary))) return 'Les versements prévus dépassent le solde disponible à verser à un vendeur.'
    return undefined
  }, [draft, planning, totals])

  useEffect(() => { onBlockedChange?.(locked || stale || uncertain || Boolean(validation)) }, [locked, stale, uncertain, validation, onBlockedChange])

  async function save() {
    if (!planning || validation) return
    const input = { planId, expectedLedgerRevision: planning.ledgerRevision, expectedBatchId: batch?.id ?? null, transfers: draft.map((row) => ({ sellerAccountId: row.sellerAccountId, courierAccountId: row.courierAccountId, amountAriary: Number(row.amount), ...(row.destination === CUSTOM_DESTINATION ? { sellerPayoutOperator: normalizePayoutOperator(row.customOperator), sellerPayoutNumber: normalizePhoneNumber(row.customNumber), sellerPayoutAccountHolder: row.customHolder.trim() } : { sellerPayoutDestinationId: row.destination }) })) }
    const signature = JSON.stringify(input)
    if (saveAttempt.current?.signature !== signature) saveAttempt.current = { signature, key: crypto.randomUUID() }
    setBusy(true); setMessage(undefined); setNotice(undefined)
    try {
      const result = await saveManualCashBriefing({ ...input, idempotencyKey: saveAttempt.current.key }, accessToken)
      callback.current?.(result)
      applyPlanning(await getCashBriefingPlanning(planId, accessToken))
      saveAttempt.current = undefined; setNotice('Les versements à faire sont enregistrés pour le briefing.')
    } catch (error) { setMessage(errorMessage(error)); setUncertain(true) }
    finally { setBusy(false) }
  }

  async function automatic() {
    if (!planning) return
    setBusy(true); setMessage(undefined); setNotice(undefined)
    try {
      const replacement = { replaceExisting: true as const, expectedLedgerRevision: planning.ledgerRevision, expectedBatchId: batch?.id ?? null }
      const signature = JSON.stringify({ planId, ...replacement })
      if (autoAttempt.current?.signature !== signature) autoAttempt.current = { signature, key: crypto.randomUUID() }
      const result = await prepareCashBriefing(planId, accessToken, autoAttempt.current.key, replacement)
      callback.current?.(result)
      applyPlanning(await getCashBriefingPlanning(planId, accessToken))
      autoAttempt.current = undefined
      setNotice('La répartition automatique est prête. Vous pouvez encore la modifier.')
    } catch (error) { setMessage(errorMessage(error)); setUncertain(true) }
    finally { setBusy(false); setConfirmation(undefined) }
  }

  async function revalidate() {
    if (!batch || !planning) return
    setBusy(true); setMessage(undefined)
    try {
      const result = await revalidateCashBriefing(batch.id, accessToken)
      const latest = await getCashBriefingPlanning(planId, accessToken)
      setPlanning(latest); setUncertain(false); callback.current?.(result)
      if (!dirty) setDraft(editable(latest.batch, latest.sellers))
    } catch (error) { setMessage(errorMessage(error)); setUncertain(true) }
    finally { setBusy(false) }
  }

  // Keep an existing draft visible for correction even if its balance has since changed.
  const payableSellers = planning?.sellers.filter((seller) => seller.availableAriary > 0 || draft.some((row) => row.sellerAccountId === seller.sellerAccountId)) ?? []
  const visibleSellers = payableSellers.filter((seller) => normalize(`${seller.sellerName} ${seller.sellerPayoutNumber ?? ''}`).includes(normalize(search)))
  return <section className="cash-briefing-panel" aria-label="Préparation des versements">
    <header><div><h3><Banknote aria-hidden="true" />Versements à faire</h3><p>Préparez les versements des livreurs actifs avant d’envoyer leurs briefings.</p></div><Button tone="admin" variant="secondary" disabled={busy || loading} onClick={() => dirty ? setConfirmation('refresh') : void refresh()} aria-label="Actualiser les versements"><RefreshCw aria-hidden="true" /></Button></header>
    {loading && <p role="status">Chargement des soldes…</p>}
    {message && <p role="alert" className="admin-callout admin-callout--warning">{message}</p>}
    {notice && <p role="status" className="admin-callout">{notice}</p>}
    {stale && <div className="cash-planning-warning" role="alert"><p>Les caisses ont changé. Revalidez les versements ou corrigez-les puis enregistrez-les avant d’envoyer le briefing.</p><Button tone="admin" variant="secondary" disabled={locked} onClick={() => void revalidate()}><RefreshCw aria-hidden="true" />Revalider les caisses</Button></div>}
    {planning && <>
      <section className="cash-planning-section" aria-labelledby="cash-planning-sellers">
        <div className="cash-planning-heading"><h4 id="cash-planning-sellers">Soldes des vendeurs</h4><label className="cash-planning-search"><Search aria-hidden="true" /><input aria-label="Rechercher un vendeur" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Rechercher un vendeur" /></label></div>
        <div className="cash-planning-table"><table><thead><tr><th>Vendeur</th><th>Solde à verser</th><th>Prévu ici</th><th>Restant disponible</th></tr></thead><tbody>{visibleSellers.map((seller) => {
          const planned = totals.sellers.get(seller.sellerAccountId) ?? 0
          const preferred = activeDestinations(seller)[0]
          return <tr key={seller.sellerAccountId}><td><strong>{seller.sellerName}</strong>{(preferred?.number || seller.sellerPayoutNumber) && <><small>{preferred?.number ?? seller.sellerPayoutNumber}</small><small>{preferred?.accountHolder || 'Titulaire non renseigné'}{preferred?.operator && ` · ${preferred.operator}`}</small></>}</td><td>{formatAriary(seller.balanceAriary)}</td><td>{formatAriary(planned)}</td><td>{formatAriary(seller.availableAriary - planned)}</td></tr>
        })}</tbody></table>{visibleSellers.length === 0 && <p className="cash-planning-empty">{search.trim() ? 'Aucun vendeur ne correspond à cette recherche.' : 'Aucun versement à prévoir.'}</p>}</div>
      </section>
      <section className="cash-planning-section" aria-labelledby="cash-planning-couriers"><h4 id="cash-planning-couriers">Caisses des livreurs actifs</h4><div className="cash-planning-couriers">{planning.couriers.map((courier) => {
        const planned = totals.couriers.get(courier.courierAccountId) ?? 0
        return <article key={courier.courierAccountId}><h5>{courier.courierName}</h5><dl><div><dt>Disponible</dt><dd>{formatAriary(courier.capacityAriary)}</dd></div><div><dt>Prévu ici</dt><dd>{formatAriary(planned)}</dd></div><div><dt>Restant</dt><dd className={courier.capacityAriary < planned ? 'cash-planning-negative' : undefined}>{formatAriary(courier.capacityAriary - planned)}</dd></div></dl>{courier.reservedElsewhereAriary > 0 && <small>{formatAriary(courier.reservedElsewhereAriary)} déjà réservé ailleurs</small>}</article>
      })}{planning.couriers.length === 0 && <p>Aucun livreur actif dans ce dispatch.</p>}</div></section>
      <section className="cash-planning-section" aria-labelledby="cash-planning-transfers">
        <div className="cash-planning-heading"><div><h4 id="cash-planning-transfers">Répartition des versements</h4><p>Ces prévisions seront reprises dans les briefings. Les versements réels restent à confirmer dans Finance.</p></div><Button tone="admin" variant="secondary" disabled={locked || planning.couriers.length === 0 || payableSellers.length === 0} onClick={() => { setDraft((rows) => [...rows, { key: crypto.randomUUID(), sellerAccountId: '', courierAccountId: '', amount: '', destination: CUSTOM_DESTINATION, customOperator: 'MVola', customNumber: '', customHolder: '' }]); setDirty(true) }}><Plus aria-hidden="true" />Ajouter un versement</Button></div>
        {draft.length === 0 && <p className="cash-planning-empty">Aucun versement prévu. Ajoutez-en un ou lancez la répartition automatique.</p>}
        <fieldset className="cash-planning-rows" disabled={locked}>{draft.map((row, index) => {
          const seller = planning.sellers.find((item) => item.sellerAccountId === row.sellerAccountId)
          const destinations = activeDestinations(seller)
          const unavailable = row.destination !== CUSTOM_DESTINATION && !destinations.some((item) => item.id === row.destination)
          const choices = [...destinations.map((item) => item.id), ...(unavailable ? [row.destination] : []), CUSTOM_DESTINATION]
          const selectedIndex = choices.indexOf(row.destination)
          const selected = destinations.find((item) => item.id === row.destination)
          const custom = row.destination === CUSTOM_DESTINATION
          return <div className="cash-planning-row" key={row.key}>
          <label>Vendeur<CourierCombobox couriers={payableSellers.map((seller) => ({ id: seller.sellerAccountId, name: seller.sellerName, contact: seller.sellerPayoutNumber, active: true }))} value={row.sellerAccountId} ariaLabel={`Vendeur du versement ${index + 1}`} placeholder="Rechercher un vendeur" optionsAriaLabel="Vendeurs disponibles" emptyMessage="Aucun vendeur ne correspond à cette recherche." clearAriaLabel="Effacer le vendeur choisi" onChange={(sellerAccountId) => update(row.key, { sellerAccountId, destination: defaultDestination(planning.sellers.find((item) => item.sellerAccountId === sellerAccountId)), customOperator: 'MVola', customNumber: '', customHolder: '', savedNumber: undefined, savedHolder: undefined, savedOperator: undefined })} /></label>
          <label>Livreur<CourierCombobox couriers={planning.couriers.map((courier) => ({ id: courier.courierAccountId, name: courier.courierName, contact: `Disponible : ${formatAriary(courier.capacityAriary)}`, active: true }))} value={row.courierAccountId} ariaLabel={`Livreur du versement ${index + 1}`} onChange={(courierAccountId) => update(row.key, { courierAccountId })} /></label>
          <label>Montant (Ar)<input aria-label={`Montant du versement ${index + 1}`} type="number" min="1" step="1" inputMode="numeric" value={row.amount} onChange={(event) => update(row.key, { amount: event.target.value })} /></label>
          <Button tone="admin" variant="ghost" aria-label={`Retirer le versement ${index + 1}`} onClick={() => { setDraft((rows) => rows.filter((item) => item.key !== row.key)); setDirty(true) }}><Trash2 aria-hidden="true" /></Button>
          {seller && <section className="cash-planning-destination" aria-label={`Coordonnées du versement ${index + 1}`}>
            <header><span>Coordonnées de versement</span><div className="cash-planning-destination__navigation">
              <Button tone="admin" variant="ghost" size="small" disabled={selectedIndex <= 0} aria-label={`Numéro précédent du versement ${index + 1}`} onClick={() => update(row.key, { destination: choices[selectedIndex - 1] })}><ChevronLeft aria-hidden="true" /></Button>
              <strong aria-live="polite">{custom ? 'Personnalisé' : unavailable ? 'Numéro indisponible' : `Numéro du compte · priorité ${selected?.priority}`}</strong>
              <Button tone="admin" variant="ghost" size="small" disabled={selectedIndex >= choices.length - 1} aria-label={`Numéro suivant du versement ${index + 1}`} onClick={() => update(row.key, { destination: choices[selectedIndex + 1] })}><ChevronRight aria-hidden="true" /></Button>
            </div></header>
            {!custom && (selected?.operator || row.savedOperator) && <small className="cash-planning-destination__operator">{selected?.operator ?? row.savedOperator}</small>}
            <div className="cash-planning-destination__fields">
              {custom && <label>Opérateur<select aria-label={`Opérateur du versement ${index + 1}`} value={row.customOperator} onChange={(event) => update(row.key, { customOperator: event.target.value })}><option value="">Choisir un opérateur</option>{PAYOUT_OPERATORS.map((operator) => <option key={operator} value={operator}>{operator}</option>)}</select></label>}
              <label>Numéro de versement{custom || isPhonePayoutOperator(selected?.operator ?? row.savedOperator ?? '')
                ? <PhoneInput autoComplete="off" readOnly={!custom} aria-label={`Numéro du versement ${index + 1}`} value={custom ? row.customNumber : selected?.number ?? row.savedNumber ?? ''} onChange={custom ? (event) => update(row.key, { customNumber: event.target.value }) : undefined} />
                : <input readOnly aria-label={`Numéro du versement ${index + 1}`} value={selected?.number ?? row.savedNumber ?? ''} />}</label>
              <label>Titulaire<input maxLength={150} autoComplete="off" readOnly={!custom} aria-label={`Titulaire du versement ${index + 1}`} value={custom ? row.customHolder : selected?.accountHolder ?? row.savedHolder ?? ''} onChange={custom ? (event) => update(row.key, { customHolder: event.target.value }) : undefined} /></label>
            </div>
            {unavailable && <p className="cash-planning-negative">Ce numéro du compte n’est plus disponible. Choisissez un autre numéro.</p>}
            {custom && <p>Ces coordonnées ne sont utilisées que pour ce versement et ne modifient pas le compte vendeur.</p>}
          </section>}
        </div>})}</fieldset>
        {dirty && validation && <p role="alert" className="cash-planning-negative">{validation}</p>}
        {batch?.transfers.some((transfer) => transfer.status === 'CONFIRMED') && <div className="cash-planning-confirmed"><h5>Déjà confirmés dans Finance</h5><ul>{batch.transfers.filter((transfer) => transfer.status === 'CONFIRMED').map((transfer) => <li key={transfer.id}><span>{transfer.courierName} → {transfer.sellerName}</span><strong>{formatAriary(transfer.amountAriary)}</strong></li>)}</ul></div>}
      </section>
    </>}
    <footer><Button tone="admin" variant="secondary" disabled={locked || !planning?.couriers.length} onClick={() => dirty || draft.length > 0 ? setConfirmation('automatic') : void automatic()}><Sparkles aria-hidden="true" />Répartir automatiquement</Button><Button tone="admin" disabled={locked || !dirty || Boolean(validation)} onClick={() => void save()}><Save aria-hidden="true" />Enregistrer les versements</Button></footer>
    {confirmation && <ConfirmDialog title={confirmation === 'refresh' ? 'Actualiser les versements ?' : 'Répartir automatiquement ?'} confirmLabel={confirmation === 'refresh' ? 'Actualiser et remplacer' : 'Générer la répartition'} busy={busy} onClose={() => setConfirmation(undefined)} onConfirm={() => void (confirmation === 'refresh' ? refresh() : automatic())}><p>{confirmation === 'refresh' ? 'Les modifications non enregistrées seront remplacées par les versements enregistrés.' : 'La répartition automatique remplacera les versements prévus et les modifications non enregistrées. Les versements déjà confirmés sont conservés.'}</p></ConfirmDialog>}
  </section>
}
