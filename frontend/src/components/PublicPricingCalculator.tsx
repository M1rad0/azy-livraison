import { useEffect, useRef, useState } from 'react'
import { BadgeDollarSign, Calculator, LoaderCircle, X, Zap } from 'lucide-react'
import { quotePublicDeliveryPrice, type DeliveryPricingQuote, type DeliveryRequestKind } from '../api/client'
import type { Location } from '../api/csvImports'
import { LocationPicker } from './LocationPicker'

export function PublicPricingCalculator({ onClose }: { onClose: () => void }) {
  const [kind, setKind] = useState<DeliveryRequestKind>('NORMAL')
  const [destination, setDestination] = useState<Location>({})
  const [quote, setQuote] = useState<DeliveryPricingQuote>()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string>()
  const closeButtonRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    const previousOverflow = document.body.style.overflow
    const previouslyFocused = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !document.querySelector('.location-modal-overlay')) onClose()
    }
    document.body.style.overflow = 'hidden'
    window.addEventListener('keydown', closeOnEscape)
    closeButtonRef.current?.focus()
    return () => {
      document.body.style.overflow = previousOverflow
      window.removeEventListener('keydown', closeOnEscape)
      previouslyFocused?.focus()
    }
  }, [onClose])

  useEffect(() => {
    if (!Number.isFinite(destination.latitude) || !Number.isFinite(destination.longitude)) { setQuote(undefined); return }
    const controller = new AbortController()
    setLoading(true); setError(undefined)
    void quotePublicDeliveryPrice({
      label: destination.label || destination.destinationQuery || 'Destination',
      query: destination.destinationQuery,
      latitude: destination.latitude as number,
      longitude: destination.longitude as number,
    }, kind, controller.signal).then(setQuote).catch((failure) => {
      if ((failure as { name?: string }).name !== 'AbortError') setError(failure instanceof Error ? failure.message : 'Tarif indisponible.')
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [destination.latitude, destination.longitude, destination.label, destination.destinationQuery, kind])

  return (
    <div className="client-modal-overlay public-pricing-overlay" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose() }}>
      <section className="client-package-dialog public-pricing-dialog" role="dialog" aria-modal="true" aria-label="Calculer un tarif">
        <header className="client-modal-header"><div className="client-modal-title"><span><Calculator /></span><div><p className="eyebrow">Sans connexion</p><h2>Calculer un tarif</h2></div></div><button ref={closeButtonRef} className="icon-button" type="button" aria-label="Fermer" onClick={onClose}><X /></button></header>
        <div className="client-package-dialog__body">
          <div className="request-kind-tabs public-pricing-kind" role="tablist" aria-label="Type de livraison">
            <button className={kind === 'NORMAL' ? 'is-active' : undefined} type="button" role="tab" aria-selected={kind === 'NORMAL'} onClick={() => setKind('NORMAL')}><BadgeDollarSign /><span><strong>Normale</strong><small>Livraison planifiée</small></span></button>
            <button className={kind === 'EXPRESS' ? 'is-active' : undefined} type="button" role="tab" aria-selected={kind === 'EXPRESS'} onClick={() => setKind('EXPRESS')}><Zap /><span><strong>Express</strong><small>Traitement immédiat</small></span></button>
          </div>
          <LocationPicker accessToken="" value={destination} title="Destination" description="Recherchez le quartier ou placez précisément le point sur la carte." onChange={setDestination} />
          {loading && <div className="client-pricing-preview is-loading"><LoaderCircle className="spin" /><strong>Calcul du tarif…</strong></div>}
          {quote && !loading && <div className="public-pricing-result"><span>Zone {quote.pricingZoneName}</span><strong>{quote.deliveryFeeAriary.toLocaleString('fr-MG')} Ar</strong></div>}
          {error && <p className="client-alert client-alert--error">{error}</p>}
        </div>
      </section>
    </div>
  )
}
