import {
  Bike,
  Settings2,
} from 'lucide-react'
import type { AuthSession } from '../api/auth'
import { AdminPortal } from './admin/AdminPortal'
import { ClientPortal } from './ClientPortal'

interface DashboardProps {
  session: AuthSession
}

export function ClientDashboard({ session, welcome = false, onWelcomeDismiss }: DashboardProps & { welcome?: boolean; onWelcomeDismiss?: () => void }) {
  return <ClientPortal accessToken={session.accessToken} welcome={welcome} onWelcomeDismiss={onWelcomeDismiss} />
}

export function AdminDashboard({ session }: DashboardProps) {
  return <div className="role-dashboard"><AdminPortal accessToken={session.accessToken} /></div>
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
