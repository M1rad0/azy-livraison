import { PackageCheck } from 'lucide-react'

export function Brand() {
  return (
    <div className="brand-lockup">
      <div className="brand-mark" aria-hidden="true">
        <PackageCheck />
      </div>
      <div>
        <strong>AZY Livraison</strong>
        <span>Antananarivo · Madagascar</span>
      </div>
    </div>
  )
}
