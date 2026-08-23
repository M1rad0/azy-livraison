import { useEffect, useMemo, useState, type FormEvent } from 'react'
import {
  BadgeDollarSign,
  CircleDot,
  LoaderCircle,
  MapPinned,
  Plus,
  Save,
  Trash2,
  TriangleAlert,
} from 'lucide-react'
import {
  getDeliveryPricingPolicy,
  updateDeliveryPricingPolicy,
  type DeliveryPricingPolicy,
} from '../../api/admin'
import type { Location } from '../../api/csvImports'
import { LocationPicker } from '../LocationPicker'
import { asConfirmedLocation, asPickerLocation, errorMessage, formatAriary, locationLabel } from './adminHelpers'

interface PricingZoneDraft {
  localId: string
  id?: string
  name: string
  maxDistanceKm: string
  deliveryFeeAriary: string
}

function localId() {
  return globalThis.crypto?.randomUUID?.() ?? `pricing-zone-${Date.now()}-${Math.random()}`
}

function zoneDrafts(policy: DeliveryPricingPolicy): PricingZoneDraft[] {
  return policy.zones.map((zone) => ({
    localId: zone.id,
    id: zone.id,
    name: zone.name,
    maxDistanceKm: String(zone.maxDistanceMeters / 1000),
    deliveryFeeAriary: String(zone.deliveryFeeAriary),
  }))
}

export function AdminPricingZonesPanel({ accessToken }: { accessToken: string }) {
  const [policy, setPolicy] = useState<DeliveryPricingPolicy>()
  const [center, setCenter] = useState<Location>({})
  const [zones, setZones] = useState<PricingZoneDraft[]>([])
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<string>()
  const [saved, setSaved] = useState(false)

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    getDeliveryPricingPolicy(accessToken, controller.signal).then((loaded) => {
      setPolicy(loaded)
      setCenter(asPickerLocation(loaded.center))
      setZones(zoneDrafts(loaded))
    }).catch((error: unknown) => {
      if ((error as { name?: string }).name !== 'AbortError') setMessage(errorMessage(error))
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false)
    })
    return () => controller.abort()
  }, [accessToken])

  const maximumCoverage = useMemo(() => zones.reduce(
    (maximum, zone) => Math.max(maximum, Number(zone.maxDistanceKm) || 0),
    0,
  ), [zones])

  function updateZone(index: number, update: Partial<PricingZoneDraft>) {
    setZones((current) => current.map((zone, zoneIndex) => zoneIndex === index ? { ...zone, ...update } : zone))
    setSaved(false)
  }

  function addZone() {
    const previous = zones.at(-1)
    setZones((current) => [...current, {
      localId: localId(),
      name: `Nouveau palier ${current.length + 1}`,
      maxDistanceKm: String((Number(previous?.maxDistanceKm) || 0) + 5),
      deliveryFeeAriary: String((Number(previous?.deliveryFeeAriary) || 0) + 1000),
    }])
    setSaved(false)
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setMessage(undefined)
    setSaved(false)
    const confirmedCenter = asConfirmedLocation(center)
    if (!policy || !confirmedCenter) {
      setMessage('Confirmez le point central sur la carte.')
      return
    }
    if (zones.length === 0) {
      setMessage('Ajoutez au moins un palier tarifaire.')
      return
    }

    const normalizedNames = zones.map((zone) => zone.name.trim().toLocaleLowerCase('fr'))
    if (normalizedNames.some((name) => !name) || new Set(normalizedNames).size !== normalizedNames.length) {
      setMessage('Chaque palier doit avoir un nom unique.')
      return
    }

    let previousDistance = 0
    let previousFee = -1
    const inputZones = []
    for (const zone of zones) {
      const maxDistanceMeters = Math.round(Number(zone.maxDistanceKm) * 1000)
      const deliveryFeeAriary = Number(zone.deliveryFeeAriary)
      if (!Number.isInteger(maxDistanceMeters) || maxDistanceMeters <= previousDistance) {
        setMessage('Les rayons doivent être positifs et strictement croissants.')
        return
      }
      if (!Number.isInteger(deliveryFeeAriary) || deliveryFeeAriary < previousFee || deliveryFeeAriary < 0) {
        setMessage('Les prix doivent être des montants entiers et ne pas diminuer avec la distance.')
        return
      }
      inputZones.push({
        ...(zone.id ? { id: zone.id } : {}),
        name: zone.name.trim(),
        maxDistanceMeters,
        deliveryFeeAriary,
      })
      previousDistance = maxDistanceMeters
      previousFee = deliveryFeeAriary
    }

    setSaving(true)
    try {
      const updated = await updateDeliveryPricingPolicy({
        version: policy.version,
        center: confirmedCenter,
        zones: inputZones,
      }, accessToken)
      setPolicy(updated)
      setCenter(asPickerLocation(updated.center))
      setZones(zoneDrafts(updated))
      setSaved(true)
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return <section className="admin-panel admin-empty" aria-live="polite"><LoaderCircle className="admin-spin" aria-hidden="true" /><strong>Chargement des zones tarifaires…</strong></section>
  }

  return (
    <section className="admin-panel" aria-labelledby="pricing-title">
      <header className="admin-panel__heading">
        <div>
          <p className="admin-eyebrow">Couverture et prix</p>
          <h2 id="pricing-title">Tarification des livraisons</h2>
          <p>Chaque destination reçoit le premier palier dont le rayon la couvre. Au-delà du dernier rayon, la zone est considérée comme non couverte.</p>
        </div>
        <div className="admin-pricing-summary"><CircleDot aria-hidden="true" /><div><small>Couverture maximale</small><strong>{maximumCoverage.toLocaleString('fr-MG')} km</strong></div></div>
      </header>

      <form className="admin-pricing-form" onSubmit={save}>
        <section className="admin-settings-card admin-pricing-center">
          <header><span><MapPinned aria-hidden="true" /></span><div><strong>Point central commun</strong><small>Les rayons sont mesurés à vol d’oiseau depuis ce point</small></div></header>
          <LocationPicker
            accessToken={accessToken}
            value={center}
            title="Centre des zones tarifaires"
            description="Choisissez le point de référence utilisé pour calculer tous les paliers."
            onChange={(location) => {
              setCenter({
                ...location,
                label: location.destinationQuery?.trim() || location.label,
              })
              setSaved(false)
            }}
          />
          {asConfirmedLocation(center) && <p className="admin-settings-hint"><MapPinned aria-hidden="true" />Centre actuel : {locationLabel(center)}</p>}
        </section>

        <section className="admin-settings-card admin-pricing-zones">
          <header>
            <span><BadgeDollarSign aria-hidden="true" /></span>
            <div><strong>Paliers concentriques</strong><small>Rayons et tarifs issus de la répartition initiale, entièrement modifiables</small></div>
            <button className="admin-button admin-button--ghost" type="button" onClick={addZone}><Plus aria-hidden="true" />Ajouter un palier</button>
          </header>
          <div className="admin-pricing-zone-list">
            <div className="admin-pricing-zone-row admin-pricing-zone-row--header" aria-hidden="true"><span>Palier</span><span>Rayon maximal</span><span>Prix</span><span /></div>
            {zones.map((zone, index) => (
              <div className="admin-pricing-zone-row" key={zone.localId}>
                <label><span>Nom du palier {index + 1}</span><input required maxLength={100} value={zone.name} onChange={(event) => updateZone(index, { name: event.target.value })} /></label>
                <label><span>Rayon maximal</span><div className="admin-input-unit"><input required type="number" min="0.1" step="0.1" value={zone.maxDistanceKm} onChange={(event) => updateZone(index, { maxDistanceKm: event.target.value })} /><b>km</b></div></label>
                <label><span>Prix de livraison</span><div className="admin-input-unit"><input required type="number" min="0" step="1" value={zone.deliveryFeeAriary} onChange={(event) => updateZone(index, { deliveryFeeAriary: event.target.value })} /><b>Ar</b></div></label>
                <button className="admin-icon-button admin-button--danger-ghost" type="button" aria-label={`Supprimer le palier ${zone.name}`} disabled={zones.length === 1} onClick={() => {
                  setZones((current) => current.filter((item) => item.localId !== zone.localId))
                  setSaved(false)
                }}><Trash2 aria-hidden="true" /></button>
                <small className="admin-pricing-zone-row__range">{index === 0 ? 'De 0' : `Après ${zones[index - 1]?.maxDistanceKm || '—'} km`} à {zone.maxDistanceKm || '—'} km · {formatAriary(Number(zone.deliveryFeeAriary) || 0)}</small>
              </div>
            ))}
          </div>
        </section>

        {message && <div className="admin-callout admin-callout--error" role="alert"><TriangleAlert aria-hidden="true" /><div><strong>Enregistrement impossible</strong><span>{message}</span></div></div>}
        {saved && <p className="admin-settings-success" role="status">Les zones et leurs tarifs sont à jour.</p>}
        <div className="admin-settings-actions"><button className="admin-button" type="submit" disabled={saving}><Save aria-hidden="true" />{saving ? 'Enregistrement…' : 'Enregistrer la tarification'}</button></div>
      </form>
    </section>
  )
}
