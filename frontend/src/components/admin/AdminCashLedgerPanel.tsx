import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { ArrowLeftRight, Download, History, RefreshCw, Search, SlidersHorizontal, TriangleAlert, WalletCards, XCircle } from 'lucide-react'
import {
  adjustCashAccount, createCashTransfer, downloadCashMovementProof, getCashLedger, listCashMovements, listCashObligations, voidCashMovement,
  type CashAccount, type CashLedger, type CashMovement, type CashTransferInput,
} from '../../api/cashLedger'
import { Button } from '../Button'
import { ConfirmDialog } from '../ConfirmDialog'
import { PaymentProofField } from '../PaymentProofField'
import { normalizePhoneNumber } from '../../phone'
import { CashBriefingFinancePanel } from './CashBriefingFinancePanel'
import { FinanceListFilters, FinanceListPagination, useFinanceList } from './FinanceListControls'
import { errorMessage, formatAriary, formatDate, formatDateTime } from './adminHelpers'
import './AdminCashLedgerPanel.css'

const typeLabel = { AZY: 'Caisse AZY', COURIER: 'Caisse livreur', SELLER: 'Compte vendeur' }
const key = () => crypto.randomUUID()
const searchable = (account: CashAccount) => `${account.name} ${account.payoutNumber ?? ''} ${normalizePhoneNumber(account.payoutNumber ?? '')} ${typeLabel[account.type]}`.toLocaleLowerCase('fr')
const paymentLabel: Record<string, string> = { ESPECES: 'Espèces', MVOLA: 'MVola', ORANGE_MONEY: 'Orange Money', AIRTEL_MONEY: 'Airtel Money', VIREMENT: 'Virement bancaire', AUTRE: 'Autre' }
const movementLabel: Record<string, string> = { TRANSFER: 'Transfert', ADJUSTMENT: 'Régularisation', COLLECTION: 'Encaissement de colis', SELLER_OBLIGATION: 'Solde du rapport vendeur', REVERSAL: 'Contre-écriture' }

/** Keep keyboard navigation inside these forms without changing the shared dialog. */
function useCashDialogFocus() {
  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const dialog = document.querySelector<HTMLElement>('[role="dialog"]')
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const focusable = () => Array.from(dialog?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex="0"]') ?? [])
    const fields = focusable()
    ;(fields.find((item) => item.tagName === 'INPUT' || item.tagName === 'TEXTAREA') ?? fields[0])?.focus()
    const trap = (event: KeyboardEvent) => {
      if (event.key !== 'Tab') return
      const items = focusable(); const first = items[0]; const last = items.at(-1)
      if (!first || !last) return
      if (event.shiftKey && (document.activeElement === first || !dialog?.contains(document.activeElement))) { event.preventDefault(); last.focus() }
      else if (!event.shiftKey && (document.activeElement === last || !dialog?.contains(document.activeElement))) { event.preventDefault(); first.focus() }
    }
    document.addEventListener('keydown', trap)
    return () => { document.removeEventListener('keydown', trap); document.body.style.overflow = overflow; if (previous?.isConnected) previous.focus() }
  }, [])
}

function AccountPicker({ label, accounts, value, onChange, disabled = false }: {
  label: string; accounts: CashAccount[]; value: string; onChange: (value: string) => void; disabled?: boolean
}) {
  const id = useId()
  const selected = accounts.find((account) => account.key === value)
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const options = accounts.filter((account) => searchable(account).includes(query.trim().toLocaleLowerCase('fr'))).slice(0, 12)
  function choose(account: CashAccount) { onChange(account.key); setQuery(''); setOpen(false) }
  return <div className="cash-ledger-picker" onBlur={(event) => { if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false) }}>
    <label htmlFor={id}>{label}</label>
    <input id={id} role="combobox" aria-autocomplete="list" aria-expanded={open} aria-controls={`${id}-options`}
      aria-activedescendant={open && options[active] ? `${id}-${active}` : undefined}
      disabled={disabled} value={open ? query : selected?.name ?? query} placeholder="Rechercher un nom ou un numéro…"
      onFocus={() => { setOpen(true); setQuery(''); setActive(0) }}
      onChange={(event) => { setQuery(event.target.value); onChange(''); setActive(0); setOpen(true) }}
      onKeyDown={(event) => {
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
          event.preventDefault(); setOpen(true)
          setActive((index) => Math.max(0, Math.min(options.length - 1, index + (event.key === 'ArrowDown' ? 1 : -1))))
        } else if (event.key === 'Enter' && open && options[active]) { event.preventDefault(); choose(options[active]) }
        else if (event.key === 'Escape' && open) { event.stopPropagation(); setOpen(false) }
      }} />
    {selected && <small>{typeLabel[selected.type]} · {formatAriary(selected.balanceAriary)}{selected.payoutNumber ? ` · ${selected.payoutNumber}` : ''}</small>}
    {open && <div id={`${id}-options`} role="listbox" aria-label={label} className="cash-ledger-picker__options">
      {options.map((account, index) => <button key={account.key} id={`${id}-${index}`} type="button" role="option" aria-selected={account.key === value}
        className={index === active ? 'is-highlighted' : undefined} onMouseDown={(event) => event.preventDefault()} onClick={() => choose(account)}>
        <strong>{account.name}</strong><small>{typeLabel[account.type]}{account.payoutNumber ? ` · ${account.payoutNumber}` : ''}</small>
      </button>)}
      {options.length === 0 && <p>Aucun compte trouvé.</p>}
      {options.length === 12 && <p>Affinez la recherche pour voir les autres personnes.</p>}
    </div>}
  </div>
}

function projected(account: CashAccount, amount: number, direction: 'FROM' | 'TO') {
  const sign = (account.type === 'SELLER' ? -1 : 1) * (direction === 'FROM' ? -1 : 1)
  return account.balanceAriary + sign * amount
}

function TransferDialog({ ledger, initialFrom, onClose, onSave }: {
  ledger: CashLedger; initialFrom?: string; onClose: () => void
  onSave: (input: CashTransferInput, proof: File | null) => Promise<void>
}) {
  useCashDialogFocus()
  const [from, setFrom] = useState(initialFrom ?? 'AZY')
  const [to, setTo] = useState('')
  const [amount, setAmount] = useState('')
  const [method, setMethod] = useState('MVOLA')
  const [reference, setReference] = useState('')
  const [note, setNote] = useState('')
  const [proof, setProof] = useState<File | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const attempt = useRef<{ fingerprint: string; id: string } | null>(null)
  const source = ledger.accounts.find((account) => account.key === from)
  const target = ledger.accounts.find((account) => account.key === to)
  const numericAmount = Number(amount)
  const warning = numericAmount > 0 && ((source && projected(source, numericAmount, 'FROM') < 0)
    || (target && projected(target, numericAmount, 'TO') < 0)
    || (source?.type !== 'SELLER' && source && numericAmount > source.availableAriary))

  async function submit() {
    if (busy) return
    if (!source || !target || source.key === target.key) { setError('Choisissez deux comptes distincts.'); return }
    if (!Number.isSafeInteger(numericAmount) || numericAmount <= 0) { setError('Indiquez un montant entier strictement positif en ariary.'); return }
    if (!method.trim()) { setError('Indiquez le moyen de paiement.'); return }
    const body = { fromKey: from, toKey: to, amountAriary: numericAmount, paymentMethod: method.trim(),
      reference: reference.trim() || undefined, note: note.trim() || undefined, expectedRevision: ledger.revision }
    const fingerprint = JSON.stringify(body) + (proof ? `${proof.name}:${proof.size}:${proof.lastModified}` : '')
    if (attempt.current?.fingerprint !== fingerprint) attempt.current = { fingerprint, id: key() }
    setBusy(true); setError(undefined)
    try { await onSave({ ...body, idempotencyKey: attempt.current.id }, proof); onClose() }
    catch (reason) { setError(errorMessage(reason)) }
    finally { setBusy(false) }
  }

  return <ConfirmDialog title="Nouveau transfert" confirmLabel="Confirmer le transfert" busy={busy} onClose={() => { if (!busy) onClose() }} onConfirm={() => void submit()}>
    <p>Enregistrez un mouvement réel entre deux comptes. Les réservations restent distinctes des paiements confirmés.</p>
    <fieldset className="cash-ledger-form" disabled={busy}>
      <AccountPicker label="Départ — caisse ou personne" accounts={ledger.accounts} value={from} onChange={setFrom} />
      <AccountPicker label="Arrivée — caisse ou personne" accounts={ledger.accounts} value={to} onChange={setTo} />
      <label>Montant en ariary<input type="number" min="1" step="1" inputMode="numeric" value={amount} onChange={(event) => setAmount(event.target.value)} /></label>
      <label>Moyen de paiement<select value={method} onChange={(event) => setMethod(event.target.value)}><option value="ESPECES">Espèces</option><option value="MVOLA">MVola</option><option value="ORANGE_MONEY">Orange Money</option><option value="AIRTEL_MONEY">Airtel Money</option><option value="VIREMENT">Virement bancaire</option><option value="AUTRE">Autre</option></select></label>
      <label>Référence <small>facultative</small><input maxLength={200} value={reference} onChange={(event) => setReference(event.target.value)} /></label>
      <label>Note / motif <small>facultatif</small><textarea rows={2} maxLength={1000} value={note} onChange={(event) => setNote(event.target.value)} /></label>
      <PaymentProofField value={proof} onChange={setProof} disabled={busy} />
    </fieldset>
    {warning && <p className="cash-ledger-warning" role="status"><TriangleAlert aria-hidden="true" />Ce transfert dépasse le disponible ou produit un solde négatif. Il reste autorisé ; vérifiez les comptes et les réservations avant de confirmer.</p>}
    {source && target && numericAmount > 0 && <p className="cash-ledger-preview">Après transfert : {source.name} {formatAriary(projected(source, numericAmount, 'FROM'))} · {target.name} {formatAriary(projected(target, numericAmount, 'TO'))}.</p>}
    {error && <p role="alert" className="admin-form-error">{error}</p>}
  </ConfirmDialog>
}

type Correction = { kind: 'ADJUST' | 'ZERO'; account?: CashAccount } | { kind: 'VOID'; movement: CashMovement }

function CorrectionDialog({ action, ledger, onClose, onSave }: {
  action: Correction; ledger: CashLedger; onClose: () => void
  onSave: (action: Correction, accountKey: string, delta: number, reason: string, idempotencyKey: string) => Promise<void>
}) {
  useCashDialogFocus()
  const [accountKey, setAccountKey] = useState(action.kind === 'VOID' ? '' : action.account?.key ?? '')
  const [delta, setDelta] = useState('')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const attempt = useRef<{ fingerprint: string; id: string } | null>(null)
  const account = ledger.accounts.find((item) => item.key === accountKey)
  const actualDelta = action.kind === 'ZERO' ? -(account?.balanceAriary ?? 0) : Number(delta)
  async function submit() {
    if (busy) return
    if (!reason.trim()) { setError('Le motif est obligatoire.'); return }
    if (action.kind !== 'VOID' && (!account || !Number.isSafeInteger(actualDelta) || actualDelta === 0)) {
      setError('Choisissez un compte et une variation entière non nulle.'); return
    }
    const fingerprint = JSON.stringify([action, accountKey, actualDelta, reason.trim(), ledger.revision])
    if (attempt.current?.fingerprint !== fingerprint) attempt.current = { fingerprint, id: key() }
    setBusy(true); setError(undefined)
    try { await onSave(action, accountKey, actualDelta, reason.trim(), attempt.current.id); onClose() }
    catch (failure) { setError(errorMessage(failure)) }
    finally { setBusy(false) }
  }
  const title = action.kind === 'VOID' ? 'Annuler ce mouvement ?' : action.kind === 'ZERO' ? 'Remettre ce compte à zéro ?' : 'Régulariser un compte'
  return <ConfirmDialog title={title} confirmLabel={action.kind === 'VOID' ? 'Confirmer l’annulation' : 'Enregistrer la régularisation'} busy={busy} danger={action.kind !== 'ADJUST'} onClose={() => { if (!busy) onClose() }} onConfirm={() => void submit()}>
    <p>{action.kind === 'VOID' ? 'Le mouvement original restera visible. Son effet sera compensé par une contre-écriture.' : action.kind === 'ZERO' ? 'Le solde sera compensé par une nouvelle écriture. Aucun mouvement ni justificatif ne sera supprimé.' : 'Corrigez un solde sans inventer de transfert et sans modifier les anciennes écritures. Une variation positive augmente le solde affiché.'}</p>
    <fieldset className="cash-ledger-form" disabled={busy}>
      {action.kind === 'ADJUST' && <><AccountPicker label="Compte à régulariser" accounts={ledger.accounts} value={accountKey} onChange={setAccountKey} /><label>Variation en ariary (positive ou négative)<input type="number" step="1" value={delta} onChange={(event) => setDelta(event.target.value)} /></label></>}
      {action.kind === 'ZERO' && account && <p><strong>{account.name}</strong> : {formatAriary(account.balanceAriary)} → {formatAriary(0)} (variation {formatAriary(actualDelta)}).</p>}
      <label>Motif obligatoire<textarea rows={3} maxLength={1000} value={reason} onChange={(event) => setReason(event.target.value)} /></label>
    </fieldset>
    {error && <p className="admin-form-error" role="alert">{error}</p>}
  </ConfirmDialog>
}

export function AdminCashLedgerPanel({ accessToken }: { accessToken: string }) {
  const [view, setView] = useState<'CASH' | 'SELLERS' | 'PAYOUTS' | 'HISTORY'>('CASH')
  const [ledger, setLedger] = useState<CashLedger>()
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string>()
  const [notice, setNotice] = useState<string>()
  const [query, setQuery] = useState('')
  const [transfer, setTransfer] = useState<{ from?: string }>()
  const [correction, setCorrection] = useState<Correction>()
  const [downloading, setDownloading] = useState<string>()
  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true); setError(undefined)
    try { const value = await getCashLedger(accessToken, signal, false); if (!signal?.aborted) setLedger(value) }
    catch (failure) { if ((failure as { name?: string }).name !== 'AbortError') setError(errorMessage(failure)) }
    finally { if (!signal?.aborted) setLoading(false) }
  }, [accessToken])
  useEffect(() => { const controller = new AbortController(); void load(controller.signal); return () => controller.abort() }, [load])
  const loadMovements = useCallback((filters: Parameters<typeof listCashMovements>[1], signal: AbortSignal) => listCashMovements(accessToken, filters, signal), [accessToken, ledger])
  const loadObligations = useCallback((filters: Parameters<typeof listCashObligations>[1], signal: AbortSignal) => listCashObligations(accessToken, filters, signal), [accessToken, ledger])
  const movementList = useFinanceList(loadMovements, view === 'HISTORY' && Boolean(ledger))
  const obligationList = useFinanceList(loadObligations, view === 'SELLERS' && Boolean(ledger))
  const accounts = ledger?.accounts ?? []
  const visible = accounts.filter((account) => searchable(account).includes(query.trim().toLocaleLowerCase('fr')))
  const names = useMemo(() => new Map(accounts.map((account) => [account.key, account.name])), [accounts])
  const accountName = (accountKey?: string | null) => accountKey ? names.get(accountKey) ?? accountKey : 'Régularisation'
  const movements = movementList.items
  const obligations = obligationList.items
  async function saveTransfer(input: CashTransferInput, proof: File | null) {
    await createCashTransfer(input, proof, accessToken); setNotice('Transfert confirmé.'); await load()
  }
  async function saveCorrection(action: Correction, accountKey: string, delta: number, reason: string, idempotencyKey: string) {
    if (!ledger) return
    if (action.kind === 'VOID') await voidCashMovement(action.movement.id, reason, ledger.revision, accessToken)
    else await adjustCashAccount({ idempotencyKey, accountKey, deltaAriary: delta, reason, expectedRevision: ledger.revision }, accessToken)
    setNotice(action.kind === 'VOID' ? 'Annulation enregistrée sans supprimer l’historique.' : 'Régularisation enregistrée.'); await load()
  }
  async function proof(id: string) {
    setDownloading(id); setError(undefined)
    try {
      const file = await downloadCashMovementProof(id, accessToken)
      const url = URL.createObjectURL(file.blob); const anchor = document.createElement('a')
      anchor.href = url; anchor.download = file.filename; document.body.append(anchor); anchor.click(); anchor.remove(); URL.revokeObjectURL(url)
    } catch (failure) { setError(errorMessage(failure)) }
    finally { setDownloading(undefined) }
  }
  function card(account: CashAccount) {
    const seller = account.type === 'SELLER'
    return <article key={account.key} className={`cash-ledger-account${account.type === 'AZY' ? ' cash-ledger-account--azy' : ''}`}>
      <header><span><small>{typeLabel[account.type]}</small><h4>{account.name}</h4></span><WalletCards aria-hidden="true" /></header>
      <strong className={account.balanceAriary < 0 ? 'cash-ledger-negative' : undefined}>{formatAriary(account.balanceAriary)}</strong>
      <p>{seller ? account.balanceAriary < 0 ? 'Le vendeur doit cette somme à AZY.' : account.balanceAriary > 0 ? 'AZY doit cette somme au vendeur.' : 'Compte vendeur soldé.' : account.balanceAriary < 0 ? account.type === 'COURIER' ? 'Avance personnelle : AZY doit cette somme au livreur, hors salaire.' : 'Solde négatif : dette ou avance à régulariser.' : 'Argent détenu, distinct des rémunérations.'}</p>
      {account.payoutNumber && <small>Numéro de versement : {account.payoutNumber}</small>}
      {!seller && <dl><div><dt>Réservé</dt><dd>{formatAriary(account.reservedAriary)}</dd></div><div><dt>Disponible</dt><dd>{formatAriary(account.availableAriary)}</dd></div></dl>}
      <footer><Button tone="admin" variant="secondary" size="small" onClick={() => setTransfer({ from: account.key })}>Transférer</Button><Button tone="admin" variant="ghost" size="small" aria-label={`Régulariser ${account.name}`} onClick={() => setCorrection({ kind: 'ADJUST', account })}><SlidersHorizontal aria-hidden="true" /></Button><Button tone="admin" variant="ghost" size="small" disabled={account.balanceAriary === 0} aria-label={`Remettre ${account.name} à zéro`} onClick={() => setCorrection({ kind: 'ZERO', account })}>À zéro</Button></footer>
    </article>
  }
  return <div className="cash-ledger" aria-label="Suivi des caisses">
    <div className="admin-finance-tabs" role="tablist" aria-label="Suivi financier">
      {([['CASH', 'Caisses'], ['SELLERS', 'Vendeurs'], ['PAYOUTS', 'Versements'], ['HISTORY', 'Mouvements']] as const).map(([id, label]) => <button key={id} id={`cash-tab-${id}`} type="button" role="tab" aria-selected={view === id} aria-controls="cash-finance-content" className={view === id ? 'is-active' : undefined} onClick={() => setView(id)}>{label}</button>)}
    </div>
    <div className="cash-ledger-toolbar">{(view === 'CASH' || view === 'SELLERS') && <label><Search aria-hidden="true" /><span className="sr-only">Rechercher une caisse ou une personne</span><input type="search" placeholder="Nom, livreur, vendeur ou numéro…" value={query} onChange={(event) => { setQuery(event.target.value); obligationList.change({ q: event.target.value }) }} /></label>}<Button tone="admin" disabled={!ledger || loading} onClick={() => { setNotice(undefined); setTransfer({}) }}><ArrowLeftRight aria-hidden="true" />Nouveau transfert</Button><Button tone="admin" variant="secondary" disabled={!ledger || loading} onClick={() => setCorrection({ kind: 'ADJUST' })}>Régularisation</Button><Button tone="admin" variant="ghost" disabled={loading} aria-label="Actualiser les caisses" onClick={() => void load()}><RefreshCw className={loading ? 'admin-spin' : undefined} aria-hidden="true" /></Button></div>
    {error && <div className="admin-callout admin-callout--error" role="alert"><TriangleAlert aria-hidden="true" /><div><strong>Opération impossible</strong><span>{error}</span></div></div>}
    {notice && <p className="cash-ledger-notice" role="status">{notice}</p>}
    <div id="cash-finance-content" role="tabpanel" aria-labelledby={`cash-tab-${view}`}>
    {loading && !ledger ? <p role="status">Chargement des caisses…</p> : ledger && <>
      {view === 'CASH' && <section aria-labelledby="physical-cash-title"><h3 id="physical-cash-title">Argent détenu <small>AZY et livreurs · espèces et mobile money</small></h3><div className="cash-ledger-accounts">{visible.filter((account) => account.type !== 'SELLER').map(card)}</div>{!visible.some((account) => account.type !== 'SELLER') && <p className="cash-ledger-empty">Aucune caisse correspondant à la recherche.</p>}</section>}
      {view === 'SELLERS' && <section aria-labelledby="seller-cash-title"><h3 id="seller-cash-title">Comptes vendeurs</h3><p className="cash-ledger-help">Solde positif : montant à verser au vendeur. Solde négatif : montant à récupérer auprès du vendeur.</p><div className="cash-ledger-accounts">{visible.filter((account) => account.type === 'SELLER').map(card)}</div>{!visible.some((account) => account.type === 'SELLER') && <p className="cash-ledger-empty">Aucun vendeur correspondant à la recherche.</p>}</section>}
      {view === 'PAYOUTS' && <CashBriefingFinancePanel accessToken={accessToken} ledgerRevision={ledger.revision} onChanged={() => void load()} />}
      {view === 'SELLERS' && <section aria-labelledby="cash-obligations-title" aria-busy={obligationList.loading}><h3 id="cash-obligations-title">Règlements par compte rendu</h3>
        <FinanceListFilters filters={obligationList.filters} onChange={obligationList.change} />
        {obligationList.error && <p role="alert" className="admin-form-error">{obligationList.error}{!obligationList.invalidRange && <Button tone="admin" variant="ghost" size="small" onClick={obligationList.refresh}>Réessayer</Button>}</p>}
        <div className="cash-ledger-obligations">{obligations.map((obligation) => <article key={obligation.id}><div><strong>{accountName(`SELLER:${obligation.sellerAccountId}`)}</strong><small>Rapport du {formatDate(obligation.serviceDate)}</small></div><div><small>Montant dû</small><strong>{formatAriary(obligation.amountDueAriary)}</strong></div><div><small>Réglé</small><strong>{formatAriary(obligation.paidAriary)}</strong></div><div><small>Reste à régler</small><strong>{formatAriary(obligation.remainingAriary)}</strong></div></article>)}{!obligationList.loading && !obligationList.error && obligations.length === 0 && <p className="cash-ledger-empty">Aucun rapport correspondant.</p>}</div>
        <FinanceListPagination label="Pagination des règlements par compte rendu" filters={obligationList.filters} onChange={obligationList.change} data={obligationList.error ? undefined : obligationList.data} disabled={obligationList.loading || Boolean(obligationList.error)} loading={obligationList.loading && !obligationList.invalidRange} />
      </section>}
      {view === 'HISTORY' && <section aria-labelledby="cash-history-title">
        <div className="cash-ledger-history-heading"><h3 id="cash-history-title"><History aria-hidden="true" />Historique des mouvements</h3></div>
        <FinanceListFilters filters={movementList.filters} onChange={movementList.change} searchLabel="Rechercher un mouvement" placeholder="Nom, référence ou motif…" />
        {movementList.error && <p role="alert" className="admin-form-error">{movementList.error}{!movementList.invalidRange && <Button tone="admin" variant="ghost" size="small" onClick={movementList.refresh}>Réessayer</Button>}</p>}
        <div className="cash-ledger-history">{movements.map((movement) => (
          <article key={movement.id} className={movement.status === 'VOIDED' ? 'is-voided' : undefined}>
            <div>
              <strong>{movement.kind === 'TRANSFER' ? `${accountName(movement.fromKey)} → ${accountName(movement.toKey)}` : movement.kind === 'ADJUSTMENT' ? `Régularisation · ${accountName(movement.toKey ?? movement.fromKey)}` : movementLabel[movement.kind] ?? 'Mouvement de caisse'}</strong>
              <small>{formatDateTime(movement.createdAt)} · {paymentLabel[movement.paymentMethod ?? ''] ?? movement.paymentMethod ?? movementLabel[movement.kind] ?? 'Mouvement'}{movement.reference ? ` · ${movement.reference}` : ''}</small>
              {movement.kind !== 'TRANSFER' && movement.kind !== 'ADJUSTMENT' && <small>{[movement.fromKey, movement.toKey].filter(Boolean).map((accountKey) => accountName(accountKey)).join(' → ')}</small>}
              {movement.note && <p>{movement.note}</p>}
              {movement.status === 'VOIDED' && <em>Annulé{movement.voidReason ? ` : ${movement.voidReason}` : ''} · conservé dans l’historique</em>}
            </div>
            <strong>{formatAriary(movement.deltaAriary != null && !(movement.kind === 'COLLECTION' && movement.fromKey && movement.toKey) ? movement.deltaAriary : movement.amountAriary)}</strong>
            <div className="cash-ledger-history__actions">
              {movement.proofAvailable && <Button tone="admin" variant="secondary" size="small" disabled={Boolean(downloading)} onClick={() => void proof(movement.id)}><Download aria-hidden="true" />Justificatif</Button>}
              {movement.status === 'CONFIRMED' && !movement.reversalOf && ['TRANSFER', 'ADJUSTMENT'].includes(movement.kind) && <Button tone="admin" variant="ghost" size="small" aria-label={`Annuler le mouvement ${movement.id}`} onClick={() => setCorrection({ kind: 'VOID', movement })}><XCircle aria-hidden="true" />Annuler</Button>}
            </div>
          </article>
        ))}{!movementList.loading && !movementList.error && movements.length === 0 && <p className="cash-ledger-empty">Aucun mouvement à afficher.</p>}</div>
        <FinanceListPagination label="Pagination des mouvements" filters={movementList.filters} onChange={movementList.change} data={movementList.error ? undefined : movementList.data} disabled={movementList.loading || Boolean(movementList.error)} loading={movementList.loading && !movementList.invalidRange} />
      </section>}
    </>}
    </div>
    {ledger && transfer && <TransferDialog ledger={ledger} initialFrom={transfer.from} onClose={() => setTransfer(undefined)} onSave={saveTransfer} />}
    {ledger && correction && <CorrectionDialog action={correction} ledger={ledger} onClose={() => setCorrection(undefined)} onSave={saveCorrection} />}
  </div>
}
