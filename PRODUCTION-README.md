# AZY Livraison — production Docker

Ces fichiers sont des overrides. Les fichiers locaux `compose.yaml` et
`compose.geography.yaml` restent inchangés.

## 1. Installer les fichiers

Copier :

- `.env.production.example` à la racine puis le dupliquer en `.env.production` ;
- `compose.production.yaml` à la racine ;
- `compose.geography.production.yaml` à la racine ;
- le dossier `infrastructure/nginx/` dans le projet.

Remplacer ensuite toutes les valeurs `CHANGE_ME` et `example.com`.

Le DNS de `APP_DOMAIN` doit pointer vers le VPS et les ports TCP 80/443 doivent
être autorisés avant de demander le certificat.

## 2. Vérifier la fusion Compose

Cette configuration utilise le tag Compose `!reset` pour retirer les ports
publiés par les fichiers locaux.

```bash
docker compose \
  --env-file .env.production \
  -f compose.yaml \
  -f compose.geography.yaml \
  -f compose.production.yaml \
  -f compose.geography.production.yaml \
  --profile geography \
  config
```

Le résultat ne doit publier que `edge` sur 80 et 443.

## 3. Démarrer l'application en mode bootstrap TLS

```bash
docker compose \
  --env-file .env.production \
  -f compose.yaml \
  -f compose.geography.yaml \
  -f compose.production.yaml \
  -f compose.geography.production.yaml \
  --profile geography \
  up -d --build
```

Sans certificat, `edge` sert uniquement le challenge ACME et répond 503 sur le
reste. Aucun trafic applicatif n'est servi en HTTP clair.

## 4. Obtenir le premier certificat Let's Encrypt

```bash
docker compose \
  --env-file .env.production \
  -f compose.yaml \
  -f compose.geography.yaml \
  -f compose.production.yaml \
  -f compose.geography.production.yaml \
  --profile geography \
  --profile tls-bootstrap \
  run --rm certbot-bootstrap
```

Le container `edge` détecte le nouveau certificat sous environ une minute,
active automatiquement la configuration HTTPS et recharge Nginx.

## 5. Vérifications

```bash
docker compose \
  --env-file .env.production \
  -f compose.yaml \
  -f compose.geography.yaml \
  -f compose.production.yaml \
  -f compose.geography.production.yaml \
  --profile geography \
  ps
```

Sur le VPS, les seuls ports applicatifs publics attendus sont :

- 80/tcp — redirection + ACME ;
- 443/tcp — HTTPS.

SSH reste évidemment à gérer séparément sur le port choisi par le serveur.

`postgres`, `backend`, `frontend`, `nominatim` et `osrm` ne doivent pas avoir de
port publié sur l'hôte.
