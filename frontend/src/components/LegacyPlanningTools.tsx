import { useState } from 'react'
import { ArrowLeft, ArrowRight, CheckCircle2, Layers3, Route } from 'lucide-react'
import type { PlanningPattern } from '../api/plans'
import { MultiClientPlanningWorkspace } from './MultiClientPlanningWorkspace'
import { PlanningWorkspace } from './PlanningWorkspace'

interface LegacyPlanningToolsProps {
  accessToken: string
  onClose: () => void
}

export function LegacyPlanningTools({ accessToken, onClose }: LegacyPlanningToolsProps) {
  const [selectedPattern, setSelectedPattern] = useState<PlanningPattern>()

  return (
    <>
      <nav className="legacy-tools-bar" aria-label="Outils historiques">
        <div>
          <span className="legacy-tools-bar__icon" aria-hidden="true"><Layers3 /></span>
          <span>
            <small>Back-office précédent</small>
            <strong>Outils historiques</strong>
          </span>
        </div>
        <button className="button button--secondary" type="button" onClick={onClose}>
          <ArrowLeft aria-hidden="true" />Tableau de bord
        </button>
      </nav>

      {selectedPattern && (
        <nav className="workspace-context-bar" aria-label="Mode de planification actif">
          <div className="mode-context">
            <span className="mode-context__icon" aria-hidden="true"><Route /></span>
            <div>
              <small>Mode actif</small>
              <strong>{selectedPattern === 'SINGLE_PICKUP_MULTI_DELIVERY'
                ? 'Un retrait · plusieurs livraisons'
                : 'Plusieurs retraits · plusieurs livraisons'}</strong>
            </div>
            <button type="button" onClick={() => setSelectedPattern(undefined)}>
              <ArrowLeft aria-hidden="true" />Changer de mode
            </button>
          </div>
        </nav>
      )}

      {selectedPattern ? (
        selectedPattern === 'SINGLE_PICKUP_MULTI_DELIVERY'
          ? <PlanningWorkspace accessToken={accessToken} />
          : <MultiClientPlanningWorkspace accessToken={accessToken} />
      ) : (
        <>
          <section className="hero pattern-hero" aria-labelledby="page-title">
            <p className="eyebrow">Outils historiques</p>
            <h1 id="page-title">Quel trajet préparez-vous ?</h1>
            <p className="hero__intro">
              Ces outils locaux restent accessibles pendant la transition vers le nouveau suivi multi-utilisateur.
            </p>
          </section>

          <section className="pattern-picker" aria-labelledby="pattern-title">
            <div className="section-heading">
              <div>
                <p className="eyebrow">Modes disponibles</p>
                <h2 id="pattern-title">Commencer une préparation</h2>
              </div>
              <p>Choisissez le mode qui correspond à la manière dont vos colis seront récupérés et livrés.</p>
            </div>

            <div className="pattern-grid">
              <article className="pattern-card pattern-card--active">
                <div className="pattern-card__visual" aria-hidden="true">
                  <span className="pattern-node pattern-node--pickup">A</span>
                  <i /><i /><i />
                  <span className="pattern-node">1</span>
                  <span className="pattern-node">2</span>
                  <span className="pattern-node">3</span>
                </div>
                <div className="pattern-card__content">
                  <span className="section-badge"><CheckCircle2 aria-hidden="true" />Disponible</span>
                  <h3>Un retrait, plusieurs livraisons</h3>
                  <p>Un point de récupération commun, plusieurs colis, plusieurs livreurs et des destinations réparties.</p>
                  <button className="button button--wide" type="button" onClick={() => setSelectedPattern('SINGLE_PICKUP_MULTI_DELIVERY')}>
                    <Route aria-hidden="true" />Choisir ce mode <ArrowRight aria-hidden="true" />
                  </button>
                </div>
              </article>

              <article className="pattern-card pattern-card--active pattern-card--multi">
                <div className="pattern-card__visual pattern-card__visual--multi" aria-hidden="true">
                  <span className="pattern-node pattern-node--pickup">A</span><i /><span className="pattern-node">1</span>
                  <span className="pattern-node pattern-node--pickup">B</span><i /><span className="pattern-node">2</span>
                </div>
                <div className="pattern-card__content">
                  <span className="section-badge"><CheckCircle2 aria-hidden="true" />Disponible</span>
                  <h3>Plusieurs retraits</h3>
                  <p>Ajoutez chaque point de récupération et ses colis, puis répartissez l’ensemble entre les livreurs.</p>
                  <button className="button button--wide" type="button" onClick={() => setSelectedPattern('MULTI_CLIENT_MULTI_PICKUP_DELIVERY')}>
                    <Layers3 aria-hidden="true" />Choisir le mode plusieurs retraits <ArrowRight aria-hidden="true" />
                  </button>
                </div>
              </article>
            </div>
          </section>
        </>
      )}
    </>
  )
}
