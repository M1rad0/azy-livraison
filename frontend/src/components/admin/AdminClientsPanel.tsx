import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { KeyRound, LoaderCircle, PackagePlus, Pencil, Search, ShieldCheck, UserPlus, UsersRound, X } from 'lucide-react'
import {
  createAdminClient,
  createAdminDeliveryRequest,
  listAdminClients,
  quoteAdminDeliveryPrice,
  resetAdminClientPassword,
  updateAdminClient,
  type AdminClient,
  type DeliveryRequestKind,
} from '../../api/admin'
import type { DeliveryRequestRecord } from '../../api/client'
import { ClientDeliveryRequestForm } from '../ClientDeliveryRequestForm'
import { errorMessage } from './adminHelpers'

interface ClientDraft {
  username: string
  displayName: string
  phone: string
  enabled: boolean
  expressDeliveryEnabled: boolean
}

const emptyDraft: ClientDraft = {
  username: '', displayName: '', phone: '', enabled: true, expressDeliveryEnabled: false,
}

export function AdminClientsPanel({ accessToken, onExpressCreated }: {
  accessToken: string
  onExpressCreated?: () => void
}) {
  const [clients, setClients] = useState<AdminClient[]>([])
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [draft, setDraft] = useState<ClientDraft>(emptyDraft)
  const [editing, setEditing] = useState<AdminClient>()
  const [clientDialogOpen, setClientDialogOpen] = useState(false)
  const [deliveryClient, setDeliveryClient] = useState<AdminClient>()
  const [deliveryKind, setDeliveryKind] = useState<DeliveryRequestKind>('NORMAL')
  const [temporaryPassword, setTemporaryPassword] = useState<string>()
  const [message, setMessage] = useState<string>()
  const closeDialogButtonRef = useRef<HTMLButtonElement>(null)

  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true)
    try { setClients(await listAdminClients(accessToken, signal)) }
    catch (error) { if ((error as { name?: string }).name !== 'AbortError') setMessage(errorMessage(error)) }
    finally { if (!signal?.aborted) setLoading(false) }
  }, [accessToken])

  useEffect(() => {
    const controller = new AbortController()
    void load(controller.signal)
    return () => controller.abort()
  }, [load])

  const visible = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase('fr')
    if (!normalized) return clients
    return clients.filter((client) => [client.displayName, client.username, client.phone]
      .some((value) => value.toLocaleLowerCase('fr').includes(normalized)))
  }, [clients, query])

  function closeClientDialog() {
    setEditing(undefined)
    setDraft(emptyDraft)
    setTemporaryPassword(undefined)
    setMessage(undefined)
    setClientDialogOpen(false)
  }

  useEffect(() => {
    if (!clientDialogOpen) return
    const previousOverflow = document.body.style.overflow
    const previouslyFocused = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closeClientDialog()
    }
    document.body.style.overflow = 'hidden'
    window.addEventListener('keydown', closeOnEscape)
    closeDialogButtonRef.current?.focus()
    return () => {
      document.body.style.overflow = previousOverflow
      window.removeEventListener('keydown', closeOnEscape)
      previouslyFocused?.focus()
    }
  }, [clientDialogOpen])

  function openCreate() {
    setEditing(undefined)
    setDraft(emptyDraft)
    setTemporaryPassword(undefined)
    setMessage(undefined)
    setClientDialogOpen(true)
  }

  function openEdit(client: AdminClient) {
    setEditing(client)
    setDraft({
      username: client.username, displayName: client.displayName, phone: client.phone,
      enabled: client.enabled, expressDeliveryEnabled: client.expressDeliveryEnabled,
    })
    setTemporaryPassword(undefined)
    setMessage(undefined)
    setClientDialogOpen(true)
  }

  async function saveClient() {
    if (!draft.username.trim() || !draft.displayName.trim() || !draft.phone.trim()) {
      setMessage('Identifiant, nom et téléphone sont obligatoires.')
      return
    }
    setSaving(true)
    setMessage(undefined)
    try {
      if (editing) {
        const saved = await updateAdminClient(editing.accountId, { ...draft, version: editing.version }, accessToken)
        setClients((current) => current.map((client) => client.accountId === saved.accountId ? saved : client))
        setEditing(saved)
        setMessage('Le compte client est à jour.')
      } else {
        const created = await createAdminClient(draft, accessToken)
        setClients((current) => [...current, created.client].sort((a, b) => a.displayName.localeCompare(b.displayName, 'fr')))
        setEditing(created.client)
        setTemporaryPassword(created.temporaryPassword)
      }
    } catch (error) { setMessage(errorMessage(error)) }
    finally { setSaving(false) }
  }

  async function resetPassword() {
    if (!editing) return
    setSaving(true)
    try { setTemporaryPassword((await resetAdminClientPassword(editing.accountId, accessToken)).temporaryPassword) }
    catch (error) { setMessage(errorMessage(error)) }
    finally { setSaving(false) }
  }

  if (deliveryClient) {
    return (
      <ClientDeliveryRequestForm
        accessToken={accessToken}
        defaultPickup={deliveryClient.defaultPickup}
        requestKind={deliveryKind}
        createRequest={async (input) => await createAdminDeliveryRequest(
          deliveryClient.accountId,
          { ...input, requestKind: deliveryKind },
          accessToken,
        ) as unknown as DeliveryRequestRecord}
        quotePrice={(destination, signal, options) => quoteAdminDeliveryPrice(
          deliveryClient.accountId, destination, deliveryKind, accessToken, signal, options,
        )}
        onCreated={(_request, savedDefaultPickup) => {
          setMessage(`${deliveryKind === 'EXPRESS' ? 'Livraison express' : 'Demande'} enregistrée pour ${deliveryClient.displayName}.`)
          setDeliveryClient(undefined)
          if (savedDefaultPickup) void load()
          if (deliveryKind === 'EXPRESS') onExpressCreated?.()
        }}
        onCancel={() => setDeliveryClient(undefined)}
      />
    )
  }

  return (
    <section className="admin-panel admin-clients" aria-labelledby="admin-clients-title">
      <header className="admin-panel__heading">
        <div><p className="admin-eyebrow">Comptes et demandes</p><h2 id="admin-clients-title">Clients</h2><p>Gérez les accès et enregistrez une livraison sans changer de session.</p></div>
        <button className="admin-button" type="button" onClick={openCreate}><UserPlus aria-hidden="true" />Nouveau client</button>
      </header>

      <div className="admin-client-toolbar">
        <label><Search aria-hidden="true" /><input value={query} placeholder="Rechercher un client…" onChange={(event) => setQuery(event.target.value)} /></label>
        <span>{visible.length} client{visible.length > 1 ? 's' : ''}</span>
      </div>
      {message && !clientDialogOpen && <p className="admin-callout" role="status">{message}</p>}
      {loading ? <div className="admin-empty"><LoaderCircle className="admin-spin" /><strong>Chargement des clients…</strong></div> : (
        <div className="admin-client-grid">
          {visible.map((client) => (
            <article key={client.accountId} className={!client.enabled ? 'is-disabled' : undefined}>
              <span className="admin-client-avatar" aria-hidden="true">{client.displayName.slice(0, 2).toUpperCase()}</span>
              <div className="admin-client-card__identity"><strong>{client.displayName}</strong><small>{client.username} · {client.phone}</small></div>
              <div className="admin-client-card__badges"><span>{client.enabled ? 'Actif' : 'Désactivé'}</span>{client.expressDeliveryEnabled && <span className="is-express">Express autorisé</span>}</div>
              <div className="admin-client-card__actions">
                <button type="button" className="admin-button admin-button--ghost" onClick={() => openEdit(client)}><Pencil aria-hidden="true" />Gérer</button>
                <button type="button" className="admin-button" onClick={() => { setDeliveryClient(client); setDeliveryKind('NORMAL') }}><PackagePlus aria-hidden="true" />Demande</button>
                <button type="button" className="admin-button admin-button--express" onClick={() => { setDeliveryClient(client); setDeliveryKind('EXPRESS') }}><ShieldCheck aria-hidden="true" />Express</button>
              </div>
            </article>
          ))}
          {!visible.length && <div className="admin-empty"><UsersRound aria-hidden="true" /><strong>Aucun client trouvé</strong></div>}
        </div>
      )}

      {clientDialogOpen && (
        <div className="client-modal-overlay" role="presentation" onMouseDown={(event) => {
          if (event.target === event.currentTarget) closeClientDialog()
        }}>
          <section className="client-package-dialog admin-client-dialog" role="dialog" aria-modal="true" aria-label={editing ? 'Modifier le client' : 'Créer un client'}>
            <header className="client-modal-header"><div className="client-modal-title"><span><UsersRound /></span><div><p className="admin-eyebrow">Administration</p><h2>{editing ? editing.displayName : 'Nouveau client'}</h2></div></div><button ref={closeDialogButtonRef} className="icon-button" type="button" aria-label="Fermer" onClick={closeClientDialog}><X /></button></header>
            <div className="client-package-dialog__body">
              <div className="client-form-grid">
                <label className="client-field"><span>Nom affiché</span><input value={draft.displayName} onChange={(event) => setDraft({ ...draft, displayName: event.target.value })} /></label>
                <label className="client-field"><span>Téléphone</span><input value={draft.phone} onChange={(event) => setDraft({ ...draft, phone: event.target.value })} /></label>
                <label className="client-field"><span>Identifiant</span><input disabled={Boolean(editing)} value={draft.username} onChange={(event) => setDraft({ ...draft, username: event.target.value })} /></label>
              </div>
              <div className="admin-client-permissions">
                <label><input type="checkbox" checked={draft.enabled} onChange={(event) => setDraft({ ...draft, enabled: event.target.checked })} /><span><strong>Compte actif</strong><small>Le client peut se connecter.</small></span></label>
                <label><input type="checkbox" checked={draft.expressDeliveryEnabled} onChange={(event) => setDraft({ ...draft, expressDeliveryEnabled: event.target.checked })} /><span><strong>Demandes expresses</strong><small>Autorise la création depuis son espace.</small></span></label>
              </div>
              {temporaryPassword && <div className="admin-temporary-password"><KeyRound /><div><small>Mot de passe temporaire</small><strong>{temporaryPassword}</strong><span>Copiez-le maintenant : il ne sera plus affiché ensuite.</span></div></div>}
              {message && <p className="admin-callout" role="status">{message}</p>}
            </div>
            <footer className="client-modal-footer">{editing && <button className="button button--secondary" type="button" disabled={saving} onClick={() => void resetPassword()}><KeyRound />Nouveau mot de passe</button>}<button className="button" type="button" disabled={saving} onClick={() => void saveClient()}>{saving ? 'Enregistrement…' : 'Enregistrer'}</button></footer>
          </section>
        </div>
      )}
    </section>
  )
}
