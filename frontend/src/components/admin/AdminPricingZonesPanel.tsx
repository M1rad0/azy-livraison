import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { BadgeDollarSign, Clock3, FileUp, LoaderCircle, MapPinned, Plus, RotateCcw, Save, Scale, Search, Trash2, TriangleAlert, Zap } from 'lucide-react'
import {
  getAdvancedPricingPolicies,
  listAdminClients,
  resetClientPricingPolicy,
  saveAdvancedPricingPolicy,
  type AdvancedPricingPolicy,
  type AdminClient,
  type DeliveryRequestKind,
} from '../../api/admin'
import type { Location } from '../../api/csvImports'
import type { PricingDistanceMode } from '../../api/pricing'
import { MAX_PRICING_KEYWORD_CSV_BYTES, MAX_PRICING_KEYWORD_RULES, mergePricingKeywordRules, normalizePricingKeyword, parsePricingKeywordCsv } from '../../pricingKeywordCsv'
import { LocationPicker } from '../LocationPicker'
import { asConfirmedLocation, asPickerLocation, errorMessage } from './adminHelpers'
import { CourierCombobox, type CourierComboboxOption } from './CourierCombobox'
import './AdminPricingZonesPanel.css'

type ZoneDraft = { id?: string; key: string; name: string; maxDistanceKm: string; fee: string }
type WeightDraft = { id?: string; key: string; maxWeightKg: string; surcharge: string }
type SlotDraft = { id?: string; key: string; label: string; startTime: string; endTime: string; surcharge: string }
type KeywordDraft = { id?: string; key: string; keyword: string; fee: string }
const key = () => globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`
const DEFAULT_CLIENT_ID = '__DEFAULT_PRICING__'

export function AdminPricingZonesPanel({ accessToken }: { accessToken: string }) {
  const [clients, setClients] = useState<AdminClient[]>([])
  const [clientId, setClientId] = useState('')
  const [kind, setKind] = useState<DeliveryRequestKind>('NORMAL')
  const [policy, setPolicy] = useState<AdvancedPricingPolicy>()
  const [center, setCenter] = useState<Location>({})
  const [distanceMode, setDistanceMode] = useState<PricingDistanceMode>('AIR_DISTANCE')
  const [defaultWeight, setDefaultWeight] = useState('1')
  const [zones, setZones] = useState<ZoneDraft[]>([])
  const [weights, setWeights] = useState<WeightDraft[]>([])
  const [slots, setSlots] = useState<SlotDraft[]>([])
  const [morningSurcharge, setMorningSurcharge] = useState('500')
  const [afternoonSurcharge, setAfternoonSurcharge] = useState('500')
  const [keywords, setKeywords] = useState<KeywordDraft[]>([])
  const keywordsRef = useRef(keywords)
  keywordsRef.current = keywords
  const importRun = useRef(0)
  const [importing, setImporting] = useState(false)
  const [importError, setImportError] = useState<string>()
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<string>()

  const hydrate = useCallback((loaded: AdvancedPricingPolicy) => {
    importRun.current++; setImporting(false); setImportError(undefined)
    setPolicy(loaded); setCenter(asPickerLocation(loaded.center)); setDistanceMode(loaded.distanceMode ?? 'AIR_DISTANCE'); setDefaultWeight(String(loaded.defaultWeightKg))
    setZones(loaded.zones.map((zone) => ({ id: zone.id, key: zone.id, name: zone.name, maxDistanceKm: String(zone.maxDistanceMeters / 1000), fee: String(zone.deliveryFeeAriary) })))
    setWeights(loaded.weightTiers.map((tier) => ({ id: tier.id, key: tier.id, maxWeightKg: String(tier.maxWeightKg), surcharge: String(tier.surchargeAriary) })))
    setSlots(loaded.timeSlots.map((slot) => ({ id: slot.id, key: slot.id, label: slot.label, startTime: slot.startTime.slice(0, 5), endTime: slot.endTime.slice(0, 5), surcharge: String(slot.surchargeAriary) })))
    setMorningSurcharge(String(loaded.morningSurchargeAriary ?? 500))
    setAfternoonSurcharge(String(loaded.afternoonSurchargeAriary ?? 500))
    setKeywords((loaded.keywordRules ?? []).map((rule) => ({ id: rule.id, key: rule.id ?? key(), keyword: rule.keyword, fee: String(rule.deliveryFeeAriary) })))
  }, [])

  const load = useCallback(async (signal?: AbortSignal) => {
    importRun.current++; setImporting(false); setImportError(undefined)
    setLoading(true); setMessage(undefined)
    try {
      const policies = await getAdvancedPricingPolicies(accessToken, clientId || undefined, signal)
      hydrate(policies.find((item) => item.requestKind === kind) ?? policies[0])
    } catch (error) { if ((error as { name?: string }).name !== 'AbortError') setMessage(errorMessage(error)) }
    finally { if (!signal?.aborted) setLoading(false) }
  }, [accessToken, clientId, hydrate, kind])

  useEffect(() => { const controller = new AbortController(); void listAdminClients(accessToken, controller.signal).then(setClients).catch(() => undefined); return () => controller.abort() }, [accessToken])
  useEffect(() => { const controller = new AbortController(); void load(controller.signal); return () => controller.abort() }, [load])
  useEffect(() => () => { importRun.current++ }, [])

  const selectedClient = clients.find((client) => client.accountId === clientId)
  const clientOptions = useMemo<CourierComboboxOption[]>(() => [
    {
      id: DEFAULT_CLIENT_ID,
      name: 'Tous les clients · grille par défaut',
      phoneNumber: 'Tarification générale AZY',
      active: true,
    },
    ...clients.map((client) => ({
      id: client.accountId,
      name: client.displayName,
      phoneNumber: `${client.username} · ${client.phone}`,
      active: client.enabled,
    })),
  ], [clients])
  const inherited = Boolean(clientId) && !policy?.clientSpecific
  const maximumCoverage = useMemo(() => Math.max(0, ...zones.map((zone) => Number(zone.maxDistanceKm) || 0)), [zones])

  async function save() {
    const confirmedCenter = asConfirmedLocation(center)
    if (!confirmedCenter || !policy || !zones.length) { setMessage('Confirmez le centre et conservez au moins une zone.'); return }
    const normalizedZones = zones.map((zone) => ({ ...(!inherited && zone.id ? { id: zone.id } : {}), name: zone.name.trim(), maxDistanceMeters: Math.round(Number(zone.maxDistanceKm) * 1000), deliveryFeeAriary: Number(zone.fee) }))
    if (normalizedZones.some((zone, index) => !zone.name || zone.maxDistanceMeters <= 0 || !Number.isInteger(zone.deliveryFeeAriary) || zone.deliveryFeeAriary < 0 || (index > 0 && zone.maxDistanceMeters <= normalizedZones[index - 1].maxDistanceMeters))) { setMessage('Vérifiez les zones : rayons croissants et montants entiers positifs.'); return }
    const normalizedWeights = weights.map((tier) => ({ ...(!inherited && tier.id ? { id: tier.id } : {}), maxWeightKg: Number(tier.maxWeightKg), surchargeAriary: Number(tier.surcharge) }))
    if (normalizedWeights.some((tier, index) => tier.maxWeightKg <= 0 || tier.surchargeAriary < 0 || (index > 0 && tier.maxWeightKg <= normalizedWeights[index - 1].maxWeightKg))) { setMessage('Les paliers de poids doivent être strictement croissants.'); return }
    const normalizedSlots = slots.map((slot) => ({ ...(!inherited && slot.id ? { id: slot.id } : {}), label: slot.label.trim(), startTime: slot.startTime, endTime: slot.endTime, surchargeAriary: Number(slot.surcharge) }))
    if (normalizedSlots.some((slot) => !slot.label || !slot.startTime || !slot.endTime || slot.startTime >= slot.endTime || slot.surchargeAriary < 0)) { setMessage('Vérifiez les créneaux horaires et leurs montants.'); return }
    const validFee = (value: string) => value.trim() !== '' && Number.isSafeInteger(Number(value)) && Number(value) >= 0
    if (!validFee(morningSurcharge) || !validFee(afternoonSurcharge)) { setMessage('Les suppléments Matin et Soir doivent être des entiers positifs ou nuls.'); return }
    const normalizedKeywords = keywords.map((rule) => ({ ...(!inherited && rule.id ? { id: rule.id } : {}), keyword: rule.keyword.trim().replace(/\s+/g, ' '), deliveryFeeAriary: Number(rule.fee) }))
    if (keywords.length > MAX_PRICING_KEYWORD_RULES || keywords.some((rule) => !normalizePricingKeyword(rule.keyword) || rule.keyword.trim().replace(/\s+/g, ' ').length > 200 || !validFee(rule.fee))) { setMessage('Vérifiez les règles par mot-clé : 100 règles maximum, clés de 1 à 200 caractères et tarifs entiers positifs ou nuls.'); return }
    const uniqueKeywords = new Set(normalizedKeywords.map((rule) => normalizePricingKeyword(rule.keyword)))
    if (uniqueKeywords.size !== normalizedKeywords.length) { setMessage('Chaque mot-clé doit être unique, sans tenir compte de la casse, des accents et des espaces superflus.'); return }
    setSaving(true); setMessage(undefined)
    try {
      const saved = await saveAdvancedPricingPolicy({
        ...(clientId ? { clientAccountId: clientId } : {}), requestKind: kind,
        version: inherited ? undefined : policy.version, distanceMode, center: confirmedCenter,
        defaultWeightKg: Number(defaultWeight), zones: normalizedZones,
        weightTiers: normalizedWeights, timeSlots: normalizedSlots,
        morningSurchargeAriary: Number(morningSurcharge), afternoonSurchargeAriary: Number(afternoonSurcharge),
        keywordRules: normalizedKeywords,
      }, accessToken)
      hydrate(saved); setMessage('La grille tarifaire est enregistrée.')
    } catch (error) { setMessage(errorMessage(error)) }
    finally { setSaving(false) }
  }

  async function importKeywords(file: File) {
    const run = ++importRun.current
    setImporting(true); setImportError(undefined); setMessage(undefined)
    try {
      if (!file.name.toLowerCase().endsWith('.csv')) throw new Error('Choisissez un fichier avec l’extension .csv.')
      if (file.size > MAX_PRICING_KEYWORD_CSV_BYTES) throw new Error('Le fichier ne doit pas dépasser 10 Mo.')
      const bytes = await file.arrayBuffer()
      if (run !== importRun.current) return
      const content = new TextDecoder('utf-8', { fatal: true }).decode(bytes)
      const { added, skipped } = mergePricingKeywordRules(keywordsRef.current, parsePricingKeywordCsv(content))
      setKeywords([...keywordsRef.current, ...added.map((rule) => ({ key: key(), keyword: rule.keyword, fee: String(rule.deliveryFeeAriary) }))])
      setMessage(`${added.length} règle(s) ajoutée(s)${skipped ? `, ${skipped} doublon(s) ignoré(s)` : ''}. Les tarifs existants sont conservés. Enregistrez cette grille pour appliquer l’import.`)
    } catch (error) {
      if (run === importRun.current) setImportError(error instanceof TypeError ? 'Enregistrez ce fichier au format CSV UTF-8 avant de l’importer.' : errorMessage(error))
    } finally { if (run === importRun.current) setImporting(false) }
  }

  async function reset() {
    if (!clientId) return
    setSaving(true)
    try { await resetClientPricingPolicy(clientId, kind, accessToken); await load(); setMessage('Le client utilise de nouveau la grille AZY par défaut.') }
    catch (error) { setMessage(errorMessage(error)) }
    finally { setSaving(false) }
  }

  if (loading) return <section className="admin-panel admin-empty"><LoaderCircle className="admin-spin" /><strong>Chargement de la tarification…</strong></section>
  return (
    <section className="admin-panel" aria-labelledby="pricing-title">
      <header className="admin-panel__heading"><div><p className="admin-eyebrow">Grilles versionnées</p><h2 id="pricing-title">Tarification</h2><p>Une grille normale et une grille express, par défaut ou propres à un client.</p></div><div className="admin-pricing-summary"><BadgeDollarSign /><div><small>Couverture maximale</small><strong>{maximumCoverage} km</strong></div></div></header>
      <div className="admin-pricing-targets">
        <label><span>Grille pour</span><CourierCombobox couriers={clientOptions} value={clientId || DEFAULT_CLIENT_ID} ariaLabel="Rechercher le client de cette grille" placeholder="Rechercher un client" optionsAriaLabel="Clients disponibles" emptyMessage="Aucun client ne correspond à cette recherche." clearAriaLabel="Revenir à la grille générale" onChange={(value) => setClientId(value === DEFAULT_CLIENT_ID ? '' : value)} /></label>
        <div className="admin-segmented admin-pricing-kind-tabs" role="tablist" aria-label="Type de livraison"><button type="button" role="tab" aria-selected={kind === 'NORMAL'} className={kind === 'NORMAL' ? 'is-active' : undefined} onClick={() => setKind('NORMAL')}><BadgeDollarSign />Normale</button><button type="button" role="tab" aria-selected={kind === 'EXPRESS'} className={kind === 'EXPRESS' ? 'is-active' : undefined} onClick={() => setKind('EXPRESS')}><Zap />Express</button></div>
        {inherited && <span className="admin-pricing-inherited">Grille par défaut · enregistrez pour personnaliser {selectedClient?.displayName}</span>}
        {clientId && policy?.clientSpecific && <button className="admin-button admin-button--ghost" type="button" onClick={() => void reset()}><RotateCcw />Revenir au défaut</button>}
      </div>

      <div className="admin-pricing-form">
        <section className="admin-settings-card admin-pricing-center"><header><span><MapPinned /></span><div><strong>Point central et poids par défaut</strong><small>Ces valeurs appartiennent uniquement à cette grille.</small></div></header><div className="admin-pricing-distance-setting"><div><strong>Mode de calcul des distances</strong><small>Le mode routier suit le profil AZY privilégiant les axes principaux.</small></div><div className="admin-segmented admin-pricing-distance-mode" role="radiogroup" aria-label="Mode de calcul des distances"><button type="button" role="radio" aria-checked={distanceMode === 'AIR_DISTANCE'} className={distanceMode === 'AIR_DISTANCE' ? 'is-active' : undefined} onClick={() => setDistanceMode('AIR_DISTANCE')}>À vol d’oiseau</button><button type="button" role="radio" aria-checked={distanceMode === 'ROAD_DISTANCE'} className={distanceMode === 'ROAD_DISTANCE' ? 'is-active' : undefined} onClick={() => setDistanceMode('ROAD_DISTANCE')}>Par la route</button></div></div><LocationPicker accessToken={accessToken} value={center} title="Centre tarifaire" description="Point depuis lequel les distances sont calculées." onChange={(location) => setCenter({ ...location, label: location.label || location.destinationQuery })} /><label className="admin-inline-field"><span>Poids utilisé si le client ne le renseigne pas</span><div className="admin-input-unit"><input type="number" min="0.001" step="0.1" value={defaultWeight} onChange={(event) => setDefaultWeight(event.target.value)} /><b>kg</b></div></label></section>

        <PricingCollection title="Zones de distance" icon={<BadgeDollarSign />} addLabel="Ajouter une zone" onAdd={() => setZones([...zones, { key: key(), name: `Zone ${zones.length + 1}`, maxDistanceKm: String(maximumCoverage + 5), fee: '0' }])}>
          {zones.map((zone, index) => <div className="admin-pricing-rule" key={zone.key}><label><span>Nom</span><input value={zone.name} onChange={(event) => setZones(zones.map((item, i) => i === index ? { ...item, name: event.target.value } : item))} /></label><label><span>Rayon max.</span><div className="admin-input-unit"><input type="number" value={zone.maxDistanceKm} onChange={(event) => setZones(zones.map((item, i) => i === index ? { ...item, maxDistanceKm: event.target.value } : item))} /><b>km</b></div></label><label><span>Tarif</span><div className="admin-input-unit"><input type="number" value={zone.fee} onChange={(event) => setZones(zones.map((item, i) => i === index ? { ...item, fee: event.target.value } : item))} /><b>Ar</b></div></label><button className="admin-icon-button" type="button" disabled={zones.length === 1} onClick={() => setZones(zones.filter((_, i) => i !== index))}><Trash2 /></button></div>)}
        </PricingCollection>

        <PricingCollection title="Tarifs par mot-clé" icon={<Search />} addLabel="Ajouter un mot-clé" addDisabled={keywords.length >= MAX_PRICING_KEYWORD_RULES || importing} onAdd={() => setKeywords([...keywords, { key: key(), keyword: '', fee: '0' }])}>
          <p className="admin-pricing-help">Une clé trouvée dans le libellé ou l’adresse remplace le tarif de zone. La casse et les accents sont ignorés ; la clé la plus longue gagne, puis l’ordre de la liste. Les suppléments de poids et d’horaire s’ajoutent ensuite.</p>
          <label className="admin-pricing-import"><span><FileUp />Importer des mots-clés · CSV UTF-8</span><input type="file" accept=".csv,text/csv,application/vnd.ms-excel" disabled={importing || saving} onChange={(event) => { const file = event.target.files?.[0]; event.target.value = ''; if (file) void importKeywords(file) }} /><small>Deux colonnes : clé;tarif (ou keyword;delivery_fee_ariary). Ajout sans remplacement ; les doublons sont ignorés. 100 règles maximum.</small></label>
          {importing && <p className="admin-pricing-help" role="status">Lecture du fichier CSV…</p>}
          {importError && <div className="admin-callout" role="alert"><TriangleAlert /><span>{importError}</span></div>}
          {keywords.length ? keywords.map((rule, index) => <div className="admin-pricing-rule admin-pricing-rule--compact" key={rule.key}><label><span>Mot-clé</span><input maxLength={200} value={rule.keyword} onChange={(event) => setKeywords(keywords.map((item, i) => i === index ? { ...item, keyword: event.target.value } : item))} /></label><label><span>Tarif de base</span><div className="admin-input-unit"><input type="number" min="0" step="1" value={rule.fee} onChange={(event) => setKeywords(keywords.map((item, i) => i === index ? { ...item, fee: event.target.value } : item))} /><b>Ar</b></div></label><button className="admin-icon-button" type="button" aria-label={`Supprimer le mot-clé ${rule.keyword || index + 1}`} onClick={() => setKeywords(keywords.filter((_, i) => i !== index))}><Trash2 /></button></div>) : <p className="admin-muted-empty">Aucun tarif par mot-clé.</p>}
        </PricingCollection>

        <PricingCollection title="Suppléments de poids" icon={<Scale />} addLabel="Ajouter un palier" onAdd={() => setWeights([...weights, { key: key(), maxWeightKg: String((Number(weights.at(-1)?.maxWeightKg) || 0) + 5), surcharge: '0' }])}>
          {weights.length ? weights.map((tier, index) => <div className="admin-pricing-rule admin-pricing-rule--compact" key={tier.key}><label><span>Jusqu’à</span><div className="admin-input-unit"><input type="number" value={tier.maxWeightKg} onChange={(event) => setWeights(weights.map((item, i) => i === index ? { ...item, maxWeightKg: event.target.value } : item))} /><b>kg</b></div></label><label><span>Supplément</span><div className="admin-input-unit"><input type="number" value={tier.surcharge} onChange={(event) => setWeights(weights.map((item, i) => i === index ? { ...item, surcharge: event.target.value } : item))} /><b>Ar</b></div></label><button className="admin-icon-button" type="button" onClick={() => setWeights(weights.filter((_, i) => i !== index))}><Trash2 /></button></div>) : <p className="admin-muted-empty">Aucun supplément de poids.</p>}
        </PricingCollection>

        <section className="admin-settings-card admin-pricing-rules admin-pricing-periods"><header><span><Clock3 /></span><div><strong>Suppléments par période</strong></div></header><div><p className="admin-pricing-help">Matin et Soir sont deux choix exclusifs. Ces suppléments ne s’appliquent pas lorsqu’une heure exacte est demandée ; les créneaux ci-dessous restent distincts.</p><div className="admin-pricing-rule"><label><span>Matin (avant midi)</span><div className="admin-input-unit"><input type="number" min="0" step="1" value={morningSurcharge} onChange={(event) => setMorningSurcharge(event.target.value)} /><b>Ar</b></div></label><label><span>Soir (après midi)</span><div className="admin-input-unit"><input type="number" min="0" step="1" value={afternoonSurcharge} onChange={(event) => setAfternoonSurcharge(event.target.value)} /><b>Ar</b></div></label></div></div></section>

        <PricingCollection title="Suppléments par créneau" icon={<Clock3 />} addLabel="Ajouter un créneau" onAdd={() => setSlots([...slots, { key: key(), label: `Créneau ${slots.length + 1}`, startTime: '18:00', endTime: '19:00', surcharge: '0' }])}>
          {slots.length ? slots.map((slot, index) => <div className="admin-pricing-rule admin-pricing-rule--slot" key={slot.key}><label><span>Libellé</span><input value={slot.label} onChange={(event) => setSlots(slots.map((item, i) => i === index ? { ...item, label: event.target.value } : item))} /></label><label><span>Début</span><input type="time" value={slot.startTime} onChange={(event) => setSlots(slots.map((item, i) => i === index ? { ...item, startTime: event.target.value } : item))} /></label><label><span>Fin</span><input type="time" value={slot.endTime} onChange={(event) => setSlots(slots.map((item, i) => i === index ? { ...item, endTime: event.target.value } : item))} /></label><label><span>Supplément</span><div className="admin-input-unit"><input type="number" value={slot.surcharge} onChange={(event) => setSlots(slots.map((item, i) => i === index ? { ...item, surcharge: event.target.value } : item))} /><b>Ar</b></div></label><button className="admin-icon-button" type="button" onClick={() => setSlots(slots.filter((_, i) => i !== index))}><Trash2 /></button></div>) : <p className="admin-muted-empty">Aucun supplément horaire.</p>}
        </PricingCollection>
      </div>
      {message && <div className="admin-callout" role="status"><TriangleAlert /><span>{message}</span></div>}
      <div className="admin-settings-actions"><button className="admin-button" type="button" disabled={saving || importing} onClick={() => void save()}><Save />{saving ? 'Enregistrement…' : 'Enregistrer cette grille'}</button></div>
    </section>
  )
}

function PricingCollection({ title, icon, addLabel, addDisabled, onAdd, children }: { title: string; icon: ReactNode; addLabel: string; addDisabled?: boolean; onAdd: () => void; children: ReactNode }) {
  return <section className="admin-settings-card admin-pricing-rules"><header><span>{icon}</span><div><strong>{title}</strong></div><button className="admin-button admin-button--ghost" type="button" disabled={addDisabled} onClick={onAdd}><Plus />{addLabel}</button></header><div>{children}</div></section>
}
