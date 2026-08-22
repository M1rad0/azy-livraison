import { useState } from 'react'
import {
  ArrowRight,
  Bike,
  History,
  Settings2,
} from 'lucide-react'
import type { AuthSession } from '../api/auth'
import { AdminPortal } from './admin/AdminPortal'
import { ClientPortal } from './ClientPortal'
import { LegacyPlanningTools } from './LegacyPlanningTools'

interface DashboardProps {
  session: AuthSession
}

export function ClientDashboard({ session }: DashboardProps) {
  return <ClientPortal accessToken={session.accessToken} />
}

export function AdminDashboard({ session }: DashboardProps) {
  const [showLegacyTools, setShowLegacyTools] = useState(false)

  if (showLegacyTools) {
    return <LegacyPlanningTools accessToken={session.accessToken} onClose={() => setShowLegacyTools(false)} />
  }

  return (
    <div className="role-dashboard">
      <AdminPortal accessToken={session.accessToken} />
      <aside className="legacy-access" aria-labelledby="legacy-access-title">
        <span className="legacy-access__icon" aria-hidden="true"><History /></span>
        <div>
          <p className="eyebrow">Transition</p>
          <h2 id="legacy-access-title">Outils historiques</h2>
          <p>Retrouvez temporairement la planification locale et les fichiers de l’ancien back-office.</p>
        </div>
        <button className="button button--secondary" type="button" onClick={() => setShowLegacyTools(true)}>
          Ouvrir les outils historiques<ArrowRight aria-hidden="true" />
        </button>
      </aside>
    </div>
  )
}

export function CourierDashboard({ session }: DashboardProps) {
  return (
    <div className="role-dashboard">
      <section className="role-hero role-hero--courier" aria-labelledby="courier-dashboard-title">
        <div>
          <p className="eyebrow">Espace livreur</p>
          <h1 id="courier-dashboard-title">Bienvenue, {session.account.displayName || session.account.username}.</h1>
          <p>L’accès livreur sera activé lorsque le suivi des tournées sera prêt.</p>
        </div>
        <span className="role-hero__illustration" aria-hidden="true"><Bike /></span>
      </section>
      <section className="role-dashboard__content courier-coming-soon">
        <Settings2 aria-hidden="true" />
        <div>
          <h2>Espace en préparation</h2>
          <p>Votre compte est bien reconnu et reste séparé des espaces client et administrateur.</p>
        </div>
      </section>
    </div>
  )
}
