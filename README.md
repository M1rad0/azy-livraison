# AZY Livraison

Application web locale-first de préparation, répartition et visualisation de tournées de livraison à Antananarivo, Madagascar.

> **État du projet : socle, authentification et imports CSV en place.** Le frontend React, l'API Spring Boot, PostgreSQL, Flyway et leur orchestration Docker Compose sont opérationnels. Le plan JSON `1.0` peut être validé et normalisé, et les listes de colis/livreurs peuvent être importées ; la géographie et l'optimisation restent à implémenter.

## Objectif

AZY Livraison transforme une liste de colis et une liste de livreurs en tournées cohérentes, en tenant compte des routes et de la capacité réelle de chaque livreur.

Le premier scénario pris en charge est :

- un point unique de récupération ;
- `n` colis à livrer ;
- `m` livreurs disponibles ;
- une capacité maximale et un véhicule pour chaque livreur ;
- une date et une heure de départ communes ;
- une fin de tournée configurable : dernier arrêt ou retour au dépôt.

L'application doit fonctionner principalement en local, tout en restant déployable en ligne. Les plans et les résultats sont d'abord des fichiers partageables ; PostgreSQL ne remplace pas ces fichiers.

## Fonctionnalités du MVP

### Préparer un plan

L'utilisateur peut :

- saisir le dépôt de départ ;
- saisir ou importer les colis ;
- saisir ou importer les livreurs ;
- choisir la date et l'heure de départ ;
- choisir si les livreurs retournent au dépôt ;
- enregistrer le plan dans un fichier JSON modifiable et partageable ;
- recharger un plan JSON pour le modifier dans le formulaire.

Chaque colis contient au minimum :

- un identifiant ;
- un libellé de reconnaissance ;
- un poids en kilogrammes ;
- une destination ;
- un numéro de téléphone facultatif.

Chaque livreur contient au minimum :

- un identifiant et un nom ;
- un type de véhicule : `MOTO` ou `BICYCLE` dans le MVP ;
- éventuellement une capacité maximale personnalisée ;
- éventuellement une vitesse moyenne personnalisée.

Le type de véhicule fournit une capacité et une vitesse par défaut. Les valeurs renseignées sur un livreur les remplacent uniquement pour ce livreur.

### Définir une destination

Trois méthodes sont prévues, dans cet ordre de priorité fonctionnelle :

1. recherche textuelle d'une adresse ou d'un lieu ;
2. saisie directe de latitude et longitude ;
3. sélection par clic dans une fenêtre cartographique ouverte avec un bouton carte.

La règle d'interprétation est déterministe :

- si `latitude` et `longitude` sont présentes et valides, elles font foi ;
- sinon, le backend géocode `destinationQuery` et complète les coordonnées ;
- si plusieurs résultats sont plausibles, l'utilisateur doit en confirmer un ;
- si aucun résultat fiable n'est obtenu, le colis reste invalide et ne part pas à l'optimisation.

Cette règle s'applique aussi aux imports CSV. Un fichier produit à partir d'un fichier client, y compris avec l'aide d'un LLM, peut donc contenir soit des coordonnées, soit une destination textuelle. Les coordonnées résolues sont intégrées au plan JSON exporté afin que les recherches suivantes soient reproductibles.

### Générer et examiner le résultat

Le bouton **Générer le résultat** :

1. valide et géocode les destinations ;
2. calcule une matrice routière à partir des routes OpenStreetMap ;
3. affecte et ordonne les colis avec OR-Tools ;
4. affiche les tournées en HTML ;
5. dessine chaque tournée avec une couleur distincte sur la carte ;
6. permet d'exporter le résultat en JSON, CSV et PDF.

L'écran de résultat doit afficher au minimum :

- le livreur et ses paramètres effectifs ;
- l'ordre des arrêts ;
- les colis et contacts associés à chaque arrêt ;
- le poids chargé, la capacité restante et le taux d'utilisation ;
- la distance routière et la durée estimée par tronçon et par tournée ;
- l'heure de passage estimée ;
- les totaux globaux ;
- les colis non affectés et une raison exploitable ;
- les avertissements de géocodage ou de routage ;
- le statut et le temps de calcul de l'optimiseur.

Les durées sont des estimations basées sur le réseau OSM et les vitesses configurées. Elles ne tiennent pas compte du trafic en temps réel.

## Règles d'optimisation

La capacité est une **contrainte stricte** : aucune tournée valide ne dépasse la capacité effective de son livreur.

L'objectif est appliqué selon les priorités suivantes :

1. respecter toutes les contraintes dures, en particulier les capacités ;
2. affecter le plus grand nombre possible de colis ;
3. minimiser le coût routier total et conserver des zones de livraison cohérentes ;
4. éviter la fragmentation inutile entre plusieurs livreurs.

Le MVP ne cherche pas à équilibrer artificiellement les charges ni à minimiser la tournée la plus longue. Si un seul livreur peut assurer efficacement tous les colis sans dépasser sa capacité, cette solution est acceptable. Une faible pénalité configurable d'activation d'un livreur pourra favoriser cette concentration sans empêcher une meilleure répartition géographique.

Les distances du réseau routier constituent le coût géographique. La vitesse effective de chaque livreur transforme ces distances en temps estimés. Les paramètres OR-Tools devront inclure une limite de calcul afin de retourner une bonne solution exploitable même lorsque le problème devient important ; une solution peut être proche de l'optimum sans être mathématiquement prouvée optimale.

Un colis non affecté doit recevoir l'une des raisons suivantes, extensibles par la suite :

- `DESTINATION_UNRESOLVED` : destination non géocodée ;
- `NO_ROAD_ROUTE` : aucun trajet routier trouvé ;
- `OVERWEIGHT_FOR_ALL_COURIERS` : le colis dépasse chaque capacité individuelle ;
- `INSUFFICIENT_TOTAL_CAPACITY` : capacité disponible insuffisante ;
- `SOLVER_TIMEOUT` : aucune affectation acceptable trouvée dans le temps imparti.

Le système ne contourne jamais silencieusement une capacité. Il indique le dépassement, la capacité minimale nécessaire et les livreurs candidats. Un responsable peut alors corriger explicitement la capacité du livreur et relancer le calcul. Un futur mode de dérogation devra conserver un avertissement visible et une trace de la décision.

## Architecture cible

```text
Navigateur
  |
  +-- React + TypeScript
  |     +-- formulaires et imports
  |     +-- Leaflet et affichage cartographique
  |     +-- visualisation et exports
  |
  +-- API REST Spring Boot
        +-- comptes et authentification
        +-- plans et validation
        +-- imports/exports
        +-- géocodage
        +-- calcul des matrices OSRM
        +-- optimisation OR-Tools
        |
        +-- PostgreSQL          comptes, sessions, cache
        +-- Nominatim local     texte -> coordonnées
        +-- OSRM local          distances, durées, tracés
```

### Composants

| Composant | Technologie | Responsabilité |
|---|---|---|
| Frontend | React, TypeScript | Formulaires, carte, imports, résultats et téléchargements |
| Carte | Leaflet | Sélection et affichage des points et tournées |
| API | Java, Spring Boot | Sécurité, validation, orchestration et formats de fichiers |
| Optimisation | OR-Tools Java | Affectation et ordre des livraisons avec contraintes |
| Routage | OSRM local | Matrices routières et géométries des trajets |
| Géocodage | Nominatim local | Résolution des destinations textuelles |
| Base métier | PostgreSQL | Comptes, sessions de renouvellement et cache de géocodage |
| Exécution | Docker Compose | Environnement local reproductible et base du déploiement |

OR-Tools traite nativement les problèmes de tournées avec capacités et visites éventuellement abandonnées. Voir la documentation officielle sur le [Vehicle Routing Problem](https://developers.google.com/optimization/routing) et les [contraintes de capacité](https://developers.google.com/optimization/routing/cvrp).

### Modules backend

Le backend doit séparer les modules suivants, même si le MVP reste un monolithe Spring Boot :

```text
account/        comptes, mots de passe et évolution future vers les rôles
auth/           login, JWT, renouvellement et révocation
planning/       modèle du plan et règles de validation
geocoding/      fournisseurs, normalisation, cache et ambiguïtés
routing/        OSRM, matrices, profils et géométries
optimization/   adaptation OR-Tools et explication des résultats
importexport/   JSON, CSV et PDF
```

Cette séparation permet d'ajouter des rôles au module compte et de remplacer Nominatim ou OSRM sans modifier le cœur métier.

### Profils de véhicule et de route

Dans le MVP, `MOTO` et `BICYCLE` utilisent le même préréglage routier conservateur `MAIN_ROADS`. Ils se différencient par leur capacité et leur vitesse. Le chemin est calculé sur les voies principales connues d'OSM, puis la durée est ajustée avec la vitesse effective du livreur.

Les valeurs initiales proposées sont des paramètres applicatifs, pas des limites de sécurité garanties :

| Véhicule | Capacité par défaut | Vitesse moyenne par défaut |
|---|---:|---:|
| `MOTO` | 30 kg | 25 km/h |
| `BICYCLE` | 10 kg | 12 km/h |

Elles doivent être validées avec les conditions réelles d'AZY Livraison et restent configurables par environnement. À terme, plusieurs préréglages de routes pourront être préparés si la qualité des données OSM à Madagascar permet de distinguer de manière fiable moto, bicyclette et autres véhicules.

## Données OpenStreetMap

### Quand télécharger les données ?

**Il n'est pas nécessaire de les télécharger pendant la phase de rédaction ou de création des écrans.** Le premier téléchargement doit avoir lieu lorsque les services Docker OSRM et Nominatim ainsi que leurs scripts d'initialisation sont présents, juste avant le premier test de bout en bout du géocodage et de la génération de tournées.

Le fichier à utiliser est l'extrait Madagascar `madagascar-latest.osm.pbf`, disponible sur la page officielle [Geofabrik — Madagascar](https://download.geofabrik.de/africa/madagascar.html). Il sera placé localement dans :

```text
data/osm/madagascar-latest.osm.pbf
```

Le répertoire `data/` ne doit pas être versionné dans Git. Le même extrait source sert à :

- préparer le graphe OSRM ;
- importer l'index de recherche Nominatim.

L'initialisation géographique sera une étape explicite, distincte du démarrage quotidien :

```text
1. télécharger ou remplacer le fichier .osm.pbf
2. préparer/mettre à jour les données OSRM
3. importer/mettre à jour l'index Nominatim
4. démarrer l'application
```

Il n'est pas utile de retélécharger l'extrait à chaque lancement. Les mises à jour sont déclenchées manuellement pendant une fenêtre de maintenance, puis les index sont reconstruits. Chaque résultat devrait conserver la date ou l'empreinte de la donnée OSM utilisée pour faciliter le diagnostic.

### Géocodage public et local

Le serveur public `nominatim.openstreetmap.org` peut dépanner pour quelques recherches interactives en développement, mais pas pour les imports réguliers. Sa [politique d'utilisation](https://operations.osmfoundation.org/policies/nominatim/) interdit les usages lourds, fixe au maximum une requête par seconde et décourage le géocodage en lot.

Le flux normal d'AZY Livraison utilise donc Nominatim local, un cache PostgreSQL et une interface de fournisseur configurable. En cas de déploiement en ligne, cette interface permettra d'utiliser une instance Nominatim dédiée ou un prestataire compatible.

### Fond de carte

Leaflet affiche les données, mais ne fournit pas les tuiles. Le développement peut utiliser un fournisseur de tuiles OSM configuré par variables d'environnement et afficher obligatoirement l'attribution OpenStreetMap. Un déploiement public significatif devra utiliser un fournisseur adapté ou un serveur de tuiles dédié. Le mode totalement hors connexion nécessitera l'ajout ultérieur d'un service local de tuiles ; OSRM et Nominatim locaux restent, eux, utilisables sans Internet après leur initialisation.

Les données OpenStreetMap sont soumises à la licence [ODbL](https://www.openstreetmap.org/copyright).

## Persistance : fichiers d'abord

### PostgreSQL

La base conserve uniquement ce qui bénéficie réellement d'une persistance serveur :

- les comptes ;
- les mots de passe hachés ;
- les sessions ou jetons de renouvellement hachés et révocables ;
- le cache de géocodage ;
- éventuellement, plus tard, les métadonnées des fichiers volontairement enregistrés.

Par défaut, les plans et résultats ne sont pas enregistrés en base. Ils sont téléchargés par l'utilisateur. Les fichiers temporaires créés pour un export doivent être supprimés après téléchargement ou expiration.

Nominatim utilise son propre stockage technique PostgreSQL/PostGIS et son propre volume Docker. Ce stockage ne fait pas partie de la base métier d'AZY Livraison.

### Plan JSON partageable

Le JSON est le format canonique. Chaque fichier possède une version de schéma afin de permettre de futures migrations.

```json
{
  "schemaVersion": "1.0",
  "pattern": "SINGLE_PICKUP_MULTI_DELIVERY",
  "planId": "plan-2026-08-03-001",
  "name": "Tournée Antananarivo matin",
  "departureAt": "2026-08-03T08:00:00+03:00",
  "timezone": "Indian/Antananarivo",
  "settings": {
    "returnToDepot": false,
    "routingPreset": "MAIN_ROADS"
  },
  "depot": {
    "label": "Dépôt principal",
    "destinationQuery": "Antananarivo, Madagascar",
    "latitude": -18.8792,
    "longitude": 47.5079
  },
  "couriers": [
    {
      "id": "livreur-001",
      "name": "Exemple Livreur",
      "vehicleType": "MOTO",
      "maxWeightKg": 35.0,
      "averageSpeedKmh": 22.0
    },
    {
      "id": "livreur-002",
      "name": "Exemple Vélo",
      "vehicleType": "BICYCLE"
    }
  ],
  "packages": [
    {
      "id": "colis-001",
      "label": "Client Analakely",
      "weightKg": 4.5,
      "phone": "+261000000000",
      "destination": {
        "destinationQuery": "Analakely, Antananarivo"
      }
    }
  ]
}
```

Les valeurs `maxWeightKg` et `averageSpeedKmh` absentes utilisent le préréglage du véhicule. Une valeur présente doit être positive et remplace le préréglage.

Le contrat formel se trouve dans le [schéma JSON du plan 1.0](schemas/delivery-plan/1.0/delivery-plan.schema.json). Des fichiers [valide](schemas/examples/delivery-plan-1.0.valid.json) et [invalide](schemas/examples/delivery-plan-1.0.invalid.json) servent de références.

La structure du fichier rechargé est contrôlée par l'API selon ce schéma, puis complétée par les règles métier. Une version inconnue n'est jamais interprétée silencieusement. Les coordonnées sont omises lorsqu'elles ne sont pas encore connues : des valeurs `null` ne font pas partie du contrat JSON.

L'endpoint de validation retourne le plan normalisé avec les chaînes nettoyées et les valeurs véhicule par défaut matérialisées :

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8081/api/plans/validate `
  -ContentType application/json `
  -InFile .\schemas\examples\delivery-plan-1.0.valid.json
```

Une requête valide répond en HTTP `200` avec `valid: true`. Une requête incorrecte répond en HTTP `400` avec une liste stable d'erreurs contenant `path`, `code` et `message`. Le géocodage des destinations textuelles n'est pas exécuté à cette étape.

### Résultat JSON

Le résultat JSON partageable contient au minimum :

```json
{
  "schemaVersion": "1.0",
  "sourcePlanId": "plan-2026-08-03-001",
  "generatedAt": "2026-08-02T20:00:00+03:00",
  "status": "SUCCESS_WITH_WARNINGS",
  "dataContext": {
    "routingPreset": "MAIN_ROADS",
    "osmDataset": "madagascar-latest.osm.pbf"
  },
  "summary": {
    "packageCount": 12,
    "assignedPackageCount": 11,
    "unassignedPackageCount": 1,
    "totalDistanceKm": 42.8,
    "estimatedDurationMinutes": 138
  },
  "routes": [
    {
      "courierId": "livreur-001",
      "vehicleType": "MOTO",
      "effectiveMaxWeightKg": 35.0,
      "effectiveAverageSpeedKmh": 22.0,
      "loadedWeightKg": 29.5,
      "distanceKm": 31.2,
      "estimatedDurationMinutes": 96,
      "stops": []
    }
  ],
  "unassignedPackages": [
    {
      "packageId": "colis-012",
      "reason": "OVERWEIGHT_FOR_ALL_COURIERS",
      "details": {
        "weightKg": 40.0,
        "minimumRequiredCapacityKg": 40.0
      }
    }
  ],
  "warnings": []
}
```

Les arrêts contiendront les coordonnées, les colis, les distances et durées cumulées, les heures estimées et, si activé, une géométrie GeoJSON. La géométrie peut être omise pour produire un fichier plus léger puis recalculée au chargement.

## Imports et exports CSV

Les CSV sont encodés en UTF-8, avec ou sans BOM. Le séparateur recommandé est le point-virgule, pratique pour les tableurs configurés en français, mais la virgule est également détectée automatiquement. Les nombres acceptent un point ou une virgule décimale lorsque le séparateur de colonnes le permet. Les en-têtes sont stables et en anglais afin de servir de contrat technique.

L'import est atomique : toutes les lignes sont contrôlées avant utilisation et la réponse ne contient aucun élément si une erreur est trouvée. Chaque erreur indique la ligne logique, le champ, un code stable et un message. Les fichiers sont limités à 10 Mo par défaut, 2 000 colis ou 200 livreurs. Cette limite est configurable avec `MAX_IMPORT_FILE_SIZE_MB`.

### Colis

Modèle prêt à remplir : [`schemas/csv/packages-template.csv`](schemas/csv/packages-template.csv).

```csv
id;label;weight_kg;destination_query;latitude;longitude;phone
colis-001;Client Analakely;4.5;Analakely, Antananarivo;;;+261000000000
colis-002;Client coordonnees;2.0;;-18.9100;47.5250;
```

`destination_query` peut être vide uniquement lorsque les deux coordonnées sont présentes. Lorsqu'elles sont présentes, les coordonnées sont prioritaires.

### Livreurs

Modèle prêt à remplir : [`schemas/csv/couriers-template.csv`](schemas/csv/couriers-template.csv).

```csv
id;name;vehicle_type;max_weight_kg;average_speed_kmh
livreur-001;Exemple Livreur;MOTO;35;22
livreur-002;Exemple Vélo;BICYCLE;;
```

Les cellules vides de capacité et de vitesse demandent l'utilisation du préréglage du véhicule.

Une réponse valide contient les éléments normalisés directement compatibles avec le plan :

```json
{
  "valid": true,
  "importType": "PACKAGES",
  "filename": "packages.csv",
  "delimiter": ";",
  "importedCount": 1,
  "items": [
    {
      "id": "colis-001",
      "label": "Client Analakely",
      "weightKg": 4.5,
      "phone": "+261000000000",
      "destination": {
        "destinationQuery": "Analakely, Antananarivo"
      }
    }
  ],
  "errors": []
}
```

L'interface affiche un aperçu de ces éléments en mémoire. Elle ne les enregistre pas dans PostgreSQL et un rechargement de la page efface cet aperçu.

### Résultats

L'export CSV produit une ligne par arrêt/colis et répète les informations nécessaires sur la tournée : livreur, ordre, poids, adresse, coordonnées, distance cumulée, durée cumulée et heure estimée. Le PDF fournit une fiche imprimable par livreur ainsi qu'un résumé des colis non affectés.

## Authentification et sécurité

Le MVP ne propose ni inscription publique ni rôles.

- Un compte administrateur initial est créé depuis les variables d'environnement au premier démarrage.
- Les mots de passe sont hachés avec un algorithme adapté fourni par Spring Security ; ils ne sont jamais journalisés.
- Le frontend garde l'access token JWT de courte durée en mémoire et l'envoie avec `Authorization: Bearer <token>`.
- Le refresh token est rotatif, révocable, conservé haché en base et transmis dans un cookie `HttpOnly`, `SameSite` et `Secure` en production.
- Le logout révoque la session de renouvellement.
- Le module `account` reste distinct afin de recevoir ultérieurement les rôles et permissions.
- Les limites de taille des fichiers, les types MIME, le contenu CSV/JSON et toutes les valeurs numériques sont validés côté backend.
- Les secrets réels ne sont jamais committés. `.env.example` ne contient que des valeurs factices.

## API REST

Le contrat exact sera publié avec OpenAPI, mais le découpage initial est :

| Méthode | Route | Usage |
|---|---|---|
| `POST` | `/api/auth/login` | Obtenir un access token et ouvrir une session |
| `POST` | `/api/auth/refresh` | Renouveler et faire tourner les jetons |
| `POST` | `/api/auth/logout` | Révoquer la session |
| `GET` | `/api/auth/me` | Lire le compte correspondant à l'access token |
| `GET` | `/api/vehicle-presets` | Lire les valeurs par défaut |
| `POST` | `/api/geocoding/search` | Rechercher et confirmer un lieu |
| `POST` | `/api/plans/validate` | Valider et normaliser un plan sans le géocoder ni l'optimiser |
| `POST` | `/api/plans/optimize` | Générer le résultat d'un plan |
| `POST` | `/api/import/packages/csv` | Convertir un CSV colis vers le modèle du plan |
| `POST` | `/api/import/couriers/csv` | Convertir un CSV livreurs vers le modèle du plan |
| `GET` | `/api/import/packages/template` | Télécharger le modèle CSV colis |
| `GET` | `/api/import/couriers/template` | Télécharger le modèle CSV livreurs |
| `POST` | `/api/exports/result/csv` | Produire l'export tabulaire |
| `POST` | `/api/exports/result/pdf` | Produire les fiches imprimables |

Les opérations métier reçoivent le plan dans la requête et retournent le résultat sans exiger son enregistrement en base.

Les routes d'authentification `login`, `refresh` et `logout`, ainsi que les health checks Docker, sont publiques. Toutes les autres routes exigent `Authorization: Bearer <accessToken>`. Le login et le renouvellement répondent avec :

```json
{
  "tokenType": "Bearer",
  "accessToken": "dummy.jwt.value",
  "expiresIn": 900,
  "account": {
    "id": "00000000-0000-0000-0000-000000000000",
    "username": "admin"
  }
}
```

Le refresh token n'est pas exposé dans ce JSON : il est envoyé dans le cookie `azy_refresh_token`, limité au chemin `/api/auth`. Chaque appel réussi à `/api/auth/refresh` remplace et révoque le précédent token. Sa réutilisation échoue en HTTP `401`.

## Configuration

Copier `.env.example` vers `.env`, puis remplacer toutes les valeurs marquées comme factices :

```powershell
Copy-Item .env.example .env
```

Le fichier `.env` ne doit jamais être versionné. Les valeurs factices permettent un lancement strictement local ; tous les mots de passe et secrets devront être remplacés avant de partager ou déployer l'environnement.

Le compte `INITIAL_ADMIN_USERNAME` est créé uniquement s'il n'existe pas encore. Modifier `INITIAL_ADMIN_PASSWORD` après ce premier démarrage ne réinitialise donc pas silencieusement son mot de passe en base. En local, pour repartir entièrement des valeurs du nouveau fichier `.env`, il faut supprimer volontairement le volume PostgreSQL puis relancer l'application.

Les réglages importants sont :

- accès PostgreSQL ;
- compte initial ;
- secrets et durées des jetons ;
- URLs internes OSRM et Nominatim ;
- chemin et URL de l'extrait OSM ;
- préréglages des véhicules ;
- taille maximale des imports ;
- fournisseur et attribution des tuiles ;
- limite de temps de l'optimiseur.

## Structure du dépôt

```text
azy-livraison/
├── backend/                 API Spring Boot, migrations et tests Java
├── frontend/                application React + TypeScript et tests Vitest
├── infrastructure/          à venir : configuration OSRM/Nominatim
├── schemas/                 schéma JSON 1.0, exemples et modèles CSV
├── data/                    à venir : données locales non versionnées
├── compose.yaml             frontend, backend et PostgreSQL
├── .env.example
└── README.md
```

## Développement local

### Démarrage avec Docker Compose

Prérequis : Docker Desktop avec Docker Compose.

```powershell
Copy-Item .env.example .env
docker compose up --build --detach
docker compose ps
```

Les points d'entrée sont ensuite :

- interface : [http://localhost:3000](http://localhost:3000) ;
- état applicatif : [http://localhost:8081/api/system/status](http://localhost:8081/api/system/status) ;
- santé Spring Boot : [http://localhost:8081/actuator/health](http://localhost:8081/actuator/health).

L'interface interroge réellement le backend, qui exécute lui-même `SELECT 1` sur PostgreSQL. Les trois indicateurs doivent apparaître comme opérationnels.

Au premier démarrage, se connecter avec `INITIAL_ADMIN_USERNAME` et `INITIAL_ADMIN_PASSWORD` définis dans `.env`. Les valeurs proposées dans `.env.example` sont volontairement factices et réservées au développement local.

Arrêter les services sans supprimer les données :

```powershell
docker compose down
```

`docker compose down --volumes` supprime également la base locale persistée ; ne l'utiliser que pour réinitialiser volontairement l'environnement.

Les données OSM ne sont pas encore nécessaires. Leur téléchargement interviendra pendant la phase géographique, lorsque OSRM et Nominatim auront été ajoutés au fichier Compose.

### Exécution native pendant le développement

PostgreSQL peut rester dans Docker :

```powershell
docker compose up --detach postgres
```

Lancer ensuite le backend avec Java 21 et Maven :

```powershell
Set-Location backend
$env:POSTGRES_PORT = "5433"
$env:API_PORT = "8081"
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Dans un autre terminal, lancer le frontend avec Node.js 24 LTS :

```powershell
Set-Location frontend
$env:VITE_DEV_API_TARGET = "http://localhost:8081"
npm ci
npm run dev
```

Le serveur Vite est alors disponible sur [http://localhost:5173](http://localhost:5173) et redirige `/api` vers le backend local.

### Tests et vérifications

Backend :

```powershell
Set-Location backend
mvn test
```

Le test Testcontainers vérifie PostgreSQL et la migration Flyway lorsque Docker est disponible. Il est marqué comme ignoré, sans masquer les autres tests, lorsque Docker n'est pas démarré.

Frontend :

```powershell
Set-Location frontend
npm ci
npm run check
npm test
npm run build
```

Validation de l'orchestration :

```powershell
docker compose config --quiet
```

## Tests d'acceptation prioritaires

Le MVP est considéré conforme lorsque les scénarios suivants réussissent :

1. un plan JSON est exporté, réimporté et conserve toutes ses données ;
2. un CSV mêlant destinations textuelles et coordonnées est importé correctement ;
3. les coordonnées ont priorité sur le texte lorsqu'elles sont toutes deux présentes ;
4. les valeurs personnalisées d'un livreur remplacent son préréglage ;
5. aucune solution ne dépasse une capacité effective ;
6. un colis trop lourd est affiché avec une raison et la capacité minimale requise ;
7. l'optimisation peut affecter tous les colis à un seul livreur lorsque c'est la meilleure solution ;
8. les tournées restent groupées géographiquement sans objectif artificiel d'équilibrage ;
9. le retour au dépôt modifie correctement les distances, durées et tracés ;
10. chaque livreur possède une couleur distincte sur la carte ;
11. les exports JSON, CSV et PDF correspondent au résultat affiché ;
12. l'expiration, le renouvellement et la révocation des jetons fonctionnent.

## Évolution vers plusieurs récupérations et destinations

Le modèle interne ne doit pas supposer qu'une tournée est définitivement composée d'un seul dépôt et de simples destinations. Le pattern du MVP est identifié explicitement par :

```text
SINGLE_PICKUP_MULTI_DELIVERY
```

Le domaine utilisera des concepts génériques tels que `Location`, `Shipment`, `RouteStop`, `Vehicle` et `DeliveryPlan`. Dans le MVP, le dépôt commun est le point de récupération implicite de tous les colis. Une version ultérieure pourra ajouter :

```text
MULTI_PICKUP_MULTI_DELIVERY
```

Chaque course possédera alors son propre point de récupération, son point de destination et une contrainte imposant que la récupération précède la livraison. La version du schéma JSON permettra de migrer les fichiers existants sans casser le pattern principal.

## Feuille de route

### Phase 1 — Socle

- [x] monorepo frontend/backend ;
- [x] Docker Compose, PostgreSQL, Flyway et health checks ;
- [x] modèles métier, schéma JSON 1.0 et validation des plans ;
- [x] compte initial, login, access token et refresh token ;
- [x] imports CSV colis et livreurs.

### Phase 2 — Géographie

- téléchargement guidé de l'extrait Madagascar ;
- Nominatim local, cache et confirmation des recherches ambiguës ;
- OSRM local avec le préréglage `MAIN_ROADS` ;
- carte Leaflet et sélection par clic.

### Phase 3 — Optimisation

- matrice routière ;
- capacités et paramètres effectifs des véhicules ;
- objectif orienté zones et coût total ;
- colis non affectés expliqués ;
- affichage comparatif des tournées.

### Phase 4 — Partage et mise en ligne

- exports JSON, CSV et PDF ;
- durcissement de la sécurité ;
- sauvegarde optionnelle de métadonnées ;
- fournisseur de tuiles adapté au trafic réel ;
- déploiement en ligne et sauvegardes PostgreSQL.

### Plus tard

- plusieurs points de récupération et de destination ;
- profils routiers spécialisés ;
- rôles et permissions ;
- créneaux horaires ;
- historique et comparaison de scénarios ;
- mise à jour automatisée et contrôlée des données OSM.
