import { AdminCashLedgerPanel } from './AdminCashLedgerPanel'
import type { Courier } from '../../api/admin'
import './AdminFinancePanel.css'

/** Current finances only. Historical systems have unlisted administration routes. */
export function AdminFinancePanel({ accessToken }: {
  accessToken: string
  couriers: Courier[]
  initialDate?: string
}) {
  return <section className="admin-panel admin-finance" aria-labelledby="finance-title">
    <header className="admin-panel__heading"><div><h2 id="finance-title">Finances</h2><p>Caisses, comptes vendeurs et transferts.</p></div></header>
    <AdminCashLedgerPanel accessToken={accessToken} />
  </section>
}
