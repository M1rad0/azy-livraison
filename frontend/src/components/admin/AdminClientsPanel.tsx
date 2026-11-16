import { useCallback, useEffect, useMemo, useState } from 'react'
import { ArrowLeft, KeyRound, LoaderCircle, MapPin, PackagePlus, Pencil, Save, Search, ShieldCheck, UserPlus, UserRound, UsersRound, WalletCards } from 'lucide-react'
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
import type { Location } from '../../api/csvImports'
import { ClientDeliveryRequestForm } from '../ClientDeliveryRequestForm'
import { ClientContactNumbersEditor, contactNumberDrafts, type ContactNumberDraft } from '../ClientContactNumbersEditor'
import { ClientPayoutDestinationsEditor, payoutDestinationDrafts, type PayoutDestinationDraft } from '../ClientPayoutDestinationsEditor'
import { LocationPicker } from '../LocationPicker'
import { PhoneInput } from '../PhoneInput'
import { isValidPhoneNumber, normalizePhoneNumber, PHONE_NUMBER_ERROR } from '../../phone'
import { normalizePayoutOperator, PAYOUT_OPERATOR_ERROR } from '../../payoutOperators'
import { errorMessage } from './adminHelpers'
import './AdminClientsPanel.css'

interface ClientDraft {
  username: string
  displayName: string
  enabled: boolean
  expressDeliveryEnabled: boolean
}

const emptyDraft: ClientDraft = {
  username: '', displayName: '', enabled: true, expressDeliveryEnabled: false,
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
  const [payouts, setPayouts] = useState<PayoutDestinationDraft[]>([])
  const [contacts, setContacts] = useState<ContactNumberDraft[]>([])
  const [defaultPickup, setDefaultPickup] = useState<Location>({})
  const [profileSection, setProfileSection] = useState<'information' | 'payouts'>('information')
  const [editing, setEditing] = useState<AdminClient>()
  const [clientEditorOpen, setClientEditorOpen] = useState(false)
  const [deliveryClient, setDeliveryClient] = useState<AdminClient>()
  const [deliveryKind, setDeliveryKind] = useState<DeliveryRequestKind>('NORMAL')
  const [temporaryPassword, setTemporaryPassword] = useState<string>()
  const [message, setMessage] = useState<string>()

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

  function closeClientEditor() {
    if (saving) return
    setEditing(undefined)
    setDraft(emptyDraft)
    setPayouts([])
    setContacts([])
    setDefaultPickup({})
    setTemporaryPassword(undefined)
    setMessage(undefined)
    setClientEditorOpen(false)
  }

  function openCreate() {
    setEditing(undefined)
    setDraft(emptyDraft)
    setPayouts([])
    setContacts(contactNumberDrafts([{ label: 'Principal', number: '', priority: 1, active: true }]))
    setDefaultPickup({})
    setProfileSection('information')
    setTemporaryPassword(undefined)
    setMessage(undefined)
    setClientEditorOpen(true)
  }

  function openEdit(client: AdminClient) {
    setEditing(client)
    setDraft({
      username: client.username, displayName: client.displayName,
      enabled: client.enabled, expressDeliveryEnabled: client.expressDeliveryEnabled,
    })
    setPayouts(payoutDestinationDrafts(client.payoutDestinations))
    setContacts(contactNumberDrafts(client.contactNumbers?.length ? client.contactNumbers
      : [{ label: 'Principal', number: client.phone, priority: 1, active: true }]))
    setDefaultPickup(client.defaultPickup ?? {})
    setProfileSection('information')
    setTemporaryPassword(undefined)
    setMessage(undefined)
    setClientEditorOpen(true)
  }

  async function saveClient() {
    if (saving) return
    if (!draft.username.trim() || !draft.displayName.trim()) {
      setMessage('L’identifiant et le nom affiché sont obligatoires.')
      setProfileSection('information')
      return
    }
    if (!contacts.length || !contacts.some((item) => item.active) || contacts.some((item) => !item.number.trim())) {
      setMessage('Renseignez chaque numéro de contact et conservez au moins un contact actif.')
      setProfileSection('information')
      return
    }
    const normalizedContacts = contacts.map(({ key: _key, ...item }, index) => ({
      ...item, label: item.label?.trim() || null, number: normalizePhoneNumber(item.number), priority: index + 1,
    }))
    if (new Set(normalizedContacts.map((item) => item.number.replace(/\s+/g, ''))).size !== normalizedContacts.length) {
      setMessage('Deux contacts utilisent le même numéro.')
      setProfileSection('information')
      return
    }
    if (normalizedContacts.some((item) => !isValidPhoneNumber(item.number))) {
      setMessage(PHONE_NUMBER_ERROR)
      setProfileSection('information')
      return
    }
    const hasPickup = Number.isFinite(defaultPickup.latitude) && Number.isFinite(defaultPickup.longitude)
    if (!hasPickup && (defaultPickup.label?.trim() || defaultPickup.destinationQuery?.trim())) {
      setMessage('Choisissez le lieu du point de retrait par défaut ou retirez-le.')
      setProfileSection('information')
      return
    }
    if (payouts.some((item) => !item.operator.trim() || !item.accountHolder.trim() || !item.number.trim())) {
      setMessage('Indiquez l’opérateur, le titulaire et le numéro de chaque versement.')
      setProfileSection('payouts')
      return
    }
    if (payouts.some((item) => !normalizePayoutOperator(item.operator))) {
      setMessage(PAYOUT_OPERATOR_ERROR)
      setProfileSection('payouts')
      return
    }
    const destinations = payouts.map(({ key: _key, ...item }, index) => ({
      ...item, operator: normalizePayoutOperator(item.operator), accountHolder: item.accountHolder.trim(),
      number: normalizePhoneNumber(item.number), priority: index + 1,
    }))
    if (destinations.some((item) => !isValidPhoneNumber(item.number))) {
      setMessage(PHONE_NUMBER_ERROR)
      setProfileSection('payouts')
      return
    }
    const identities = destinations.map((item) => `${item.operator.toLocaleLowerCase('fr')}\u0000${item.number.replace(/\s+/g, '')}`)
    if (new Set(identities).size !== identities.length) {
      setMessage('Deux numéros de versement utilisent le même opérateur et le même numéro.')
      setProfileSection('payouts')
      return
    }
    setSaving(true)
    setMessage(undefined)
    try {
      const input = {
        ...draft, username: draft.username.trim(), displayName: draft.displayName.trim(),
        phone: normalizedContacts.find((item) => item.active)!.number,
        contactNumbers: normalizedContacts, payoutDestinations: destinations,
        defaultPickup: hasPickup ? {
          label: defaultPickup.label?.trim() || defaultPickup.destinationQuery?.trim() || 'Point de retrait',
          destinationQuery: defaultPickup.destinationQuery?.trim() || undefined,
          latitude: defaultPickup.latitude as number, longitude: defaultPickup.longitude as number,
        } : null,
        clearDefaultPickup: !hasPickup,
      }
      if (editing) {
        const saved = await updateAdminClient(editing.accountId, { ...input, version: editing.version }, accessToken)
        setClients((current) => current.map((client) => client.accountId === saved.accountId ? saved : client))
        setEditing(saved)
        setPayouts(payoutDestinationDrafts(saved.payoutDestinations))
        setContacts(contactNumberDrafts(saved.contactNumbers?.length ? saved.contactNumbers
          : [{ label: 'Principal', number: saved.phone, priority: 1, active: true }]))
        setDefaultPickup(saved.defaultPickup ?? {})
        setMessage('Le compte client est à jour.')
      } else {
        const created = await createAdminClient(input, accessToken)
        setClients((current) => [...current, created.client].sort((a, b) => a.displayName.localeCompare(b.displayName, 'fr')))
        setEditing(created.client)
        setPayouts(payoutDestinationDrafts(created.client.payoutDestinations))
        setContacts(contactNumberDrafts(created.client.contactNumbers?.length ? created.client.contactNumbers
          : [{ label: 'Principal', number: created.client.phone, priority: 1, active: true }]))
        setDefaultPickup(created.client.defaultPickup ?? {})
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
        clientAccountId={deliveryClient.accountId}
        backLabel="Retour à la liste des clients"
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

  if (clientEditorOpen) {
    return <section className="admin-panel admin-clients admin-client-profile" aria-labelledby="admin-client-profile-title">
      <header className="admin-panel__heading">
        <div><p className="admin-eyebrow">Administration des clients</p><h2 id="admin-client-profile-title">{editing ? editing.displayName : 'Nouveau client'}</h2><p>Informations du compte, retrait habituel et coordonnées de versement.</p></div>
        <button className="admin-button admin-button--ghost" type="button" disabled={saving} onClick={closeClientEditor}><ArrowLeft aria-hidden="true" />Retour aux clients</button>
      </header>
      <div className="client-profile-tabs" role="tablist" aria-label="Coordonnées du client">
        {([{ id: 'information', label: 'Informations', detail: 'Profil, retrait et contacts', icon: UserRound }, { id: 'payouts', label: 'Versements', detail: 'Numéros par ordre de priorité', icon: WalletCards }] as const).map(({ id, label, detail, icon: Icon }) => <button key={id} id={`admin-client-${id}-tab`} role="tab" type="button" tabIndex={profileSection === id ? 0 : -1} aria-label={`${label} ${detail}`} aria-selected={profileSection === id} aria-controls={`admin-client-${id}-panel`} className={profileSection === id ? 'is-active' : undefined} onClick={() => setProfileSection(id)} onKeyDown={(event) => {
          if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
          event.preventDefault()
          const next = event.key === 'Home' ? 'information' : event.key === 'End' ? 'payouts' : profileSection === 'information' ? 'payouts' : 'information'
          setProfileSection(next)
          document.getElementById(`admin-client-${next}-tab`)?.focus()
        }}><Icon aria-hidden="true" /><span><strong>{label}</strong><small>{detail}</small></span></button>)}
      </div>
      <fieldset className="admin-client-profile__fields" disabled={saving}>
        <div id="admin-client-information-panel" role="tabpanel" aria-labelledby="admin-client-information-tab" hidden={profileSection !== 'information'}>
          <section className="client-surface client-profile-card">
            <div className="client-section-heading"><span aria-hidden="true"><UserRound /></span><div><p className="eyebrow">Coordonnées</p><h2>Informations du compte</h2></div></div>
            <div className="client-form-grid">
              <label className="client-field"><span>Nom affiché</span><input value={draft.displayName} maxLength={150} autoComplete="organization" onChange={(event) => setDraft({ ...draft, displayName: event.target.value })} /></label>
              <label className="client-field client-field--readonly"><span>Téléphone principal</span><PhoneInput aria-label="Téléphone principal" value={contacts.find((contact) => contact.active)?.number ?? ''} readOnly /><small>Premier contact actif dans la liste ci-dessous.</small></label>
              <label className="client-field"><span>Identifiant</span><input disabled={Boolean(editing)} value={draft.username} maxLength={100} autoComplete="off" onChange={(event) => setDraft({ ...draft, username: event.target.value })} /></label>
            </div>
            <div className="admin-client-permissions">
              <label><input type="checkbox" checked={draft.enabled} onChange={(event) => setDraft({ ...draft, enabled: event.target.checked })} /><span><strong>Compte actif</strong><small>Le client peut se connecter.</small></span></label>
              <label><input type="checkbox" checked={draft.expressDeliveryEnabled} onChange={(event) => setDraft({ ...draft, expressDeliveryEnabled: event.target.checked })} /><span><strong>Demandes expresses</strong><small>Autorise la création depuis son espace.</small></span></label>
            </div>
          </section>
          <section className="client-surface client-profile-card">
            <div className="client-section-heading"><span aria-hidden="true"><MapPin /></span><div><p className="eyebrow">Adresse favorite</p><h2>Retrait par défaut</h2></div></div>
            <label className="client-field"><span>Libellé du retrait</span><input value={defaultPickup.label ?? ''} maxLength={200} placeholder="Ex. Ma boutique" onChange={(event) => setDefaultPickup({ ...defaultPickup, label: event.target.value })} /></label>
            <LocationPicker accessToken={accessToken} value={defaultPickup} title="Retrait par défaut" description="Le point utilisé automatiquement dans les nouvelles demandes de ce client." onChange={(location) => setDefaultPickup({ ...location, label: defaultPickup.label?.trim() || location.label || location.destinationQuery || 'Point de retrait' })} />
            {(defaultPickup.label || defaultPickup.destinationQuery || Number.isFinite(defaultPickup.latitude)) && <button className="client-text-action" type="button" onClick={() => setDefaultPickup({})}>Retirer le point par défaut</button>}
          </section>
          <section className="client-surface client-priority-card admin-client-contacts" aria-labelledby="admin-client-contacts-title">
            <header><h3 id="admin-client-contacts-title">Numéros de contact</h3><p>Le premier numéro actif est le contact principal. Réordonnez les suivants selon leur priorité.</p></header>
            <ClientContactNumbersEditor items={contacts} onChange={setContacts} disabled={saving} labelLabel="Libellé du contact" numberLabel="Numéro de contact" addLabel="Ajouter un contact" />
          </section>
        </div>
        <div id="admin-client-payouts-panel" role="tabpanel" aria-labelledby="admin-client-payouts-tab" hidden={profileSection !== 'payouts'}>
          <section className="client-surface client-priority-card client-priority-card--payouts admin-client-payouts" aria-labelledby="admin-client-payouts-title">
            <header><h3 id="admin-client-payouts-title">Numéros de versement</h3><p>La priorité 1 est utilisée en premier. Les numéros suivants servent d’alternatives.</p></header>
            <ClientPayoutDestinationsEditor items={payouts} onChange={setPayouts} disabled={saving} defaultOperator="MVola" defaultAccountHolder={draft.displayName.trim()} />
          </section>
        </div>
      </fieldset>
      {temporaryPassword && <div className="admin-temporary-password"><KeyRound aria-hidden="true" /><div><small>Mot de passe temporaire</small><strong>{temporaryPassword}</strong><span>Copiez-le maintenant : il ne sera plus affiché ensuite.</span></div></div>}
      {message && <p className="admin-callout" role="status">{message}</p>}
      <footer className="admin-client-profile__actions">{editing && <button className="admin-button admin-button--ghost" type="button" disabled={saving} onClick={() => void resetPassword()}><KeyRound aria-hidden="true" />Nouveau mot de passe</button>}<button className="admin-button" type="button" disabled={saving} onClick={() => void saveClient()}><Save aria-hidden="true" />{saving ? 'Enregistrement…' : 'Enregistrer'}</button></footer>
    </section>
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
      {message && <p className="admin-callout" role="status">{message}</p>}
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

    </section>
  )
}
