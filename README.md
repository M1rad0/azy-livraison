# AZY Livraison

Application web locale-first de préparation, répartition et visualisation de tournées de livraison à Antananarivo, Madagascar.

> **État du projet : workflow journalier multi-utilisateur persistant.** Les demandes, colis, livreurs, réglages, dispatchs, comptes rendus et clôtures sont historisés dans PostgreSQL. Le dispatch opérationnel est manuel : affectation des récupérations, répartition des colis, ordre des passages et échanges à prévoir. Le ralliement reste un repère, sans passage imposé. Les anciens plans automatiques `VIA_HUB` restent consultables.

## Objectif

AZY Livraison permet à plusieurs clients de déposer leurs demandes, puis à l'équipe d'exploitation de constituer une file journalière et de répartir les colis entre les livreurs disponibles. L'optimisation tient compte des retraits, des départs réels des livreurs, des capacités, du point de ralliement, des routes et des horaires.

L'application reste locale-first et déployable en ligne. PostgreSQL est maintenant la source de vérité des opérations courantes et de leur historique ; les fichiers JSON, CSV et PDF restent utiles pour l'échange, l'export et les outils historiques.

## Version multi-utilisateur

### Rôles et accès

Trois rôles sont prévus et portés par le compte et le JWT :

- `CLIENT` : inscription, gestion du profil et du retrait par défaut, création et consultation de ses demandes ;
- `ADMIN` : gestion de la file d'attente, des livreurs, des réglages et des dispatchs ;
- `COURIER` : rôle réservé au futur espace livreur. Les profils opérationnels des livreurs peuvent déjà exister sans compte connecté.

L'inscription publique crée uniquement un compte `CLIENT`. Les comptes existant avant la migration multi-utilisateur sont repris comme `ADMIN`. Les routes `/api/client/**` et `/api/admin/**` sont protégées par rôle côté backend ; masquer une fonction dans l'interface ne remplace jamais ce contrôle.

### Parcours client

Un client peut :

1. créer son compte puis ouvrir une session ;
2. compléter son nom, son contact et, s'il le souhaite, un point de retrait par défaut ;
3. créer une demande pour une date, avec un point de retrait confirmé et un ou plusieurs colis ;
4. enregistrer le retrait saisi comme nouveau retrait par défaut ;
5. consulter l'historique et l'état de ses demandes.

Chaque colis contient :

- le nom du destinataire ;
- l'indicateur `faritra` ;
- une destination confirmée avec libellé et coordonnées ;
- un contact ;
- le total à récupérer en ariary, livraison incluse ;
- l'indicateur de paiement préalable par mobile money ;
- un poids facultatif.

Lorsque le poids n'est pas fourni, sa valeur effective est copiée depuis `defaultPackageWeightKg` au moment de l'enregistrement. La demande conserve donc le poids réellement utilisé et sa provenance (`PROVIDED` ou `DEFAULT_SETTING`), même si le réglage change ensuite.

### Tarification géographique

La destination confirmée de chaque colis est comparée à des paliers concentriques configurables autour d'un point central commun. Le premier rayon qui couvre le point fournit le nom de la zone et les frais de livraison. Au-delà du dernier rayon, la destination est explicitement considérée comme non couverte et la demande n'est pas enregistrée partiellement.

La configuration initiale utilise le centre Antsahavola–Antaninarenina et les paliers `2,5 / 4,5 / 8 / 10,5 / 16,5 km` aux prix respectifs de `3 000 / 4 000 / 5 000 / 6 000 / 8 000 Ar`. Elle a été calibrée avec OpenStreetMap à partir des quartiers du document métier ; le [rapport de géocodage et de calibration](schemas/examples/pricing-zones-osm-analysis.json) conserve les correspondances, ambiguïtés et limites de cette approximation radiale.

Le tarif est calculé côté backend au moment de l'enregistrement et copié dans un snapshot du colis. Un changement ultérieur des zones ne réécrit donc pas l'historique. Les frais sont affichés comme la part livraison du total déclaré, sans modifier automatiquement `amountToCollectAriary`. L'indicateur `faritra` et le type de véhicule restent indépendants de la tarification dans cette première version.

Chaque grille normale/express, générale ou personnelle, peut définir des **mots-clés tarifaires**. La recherche porte sur le libellé et l'adresse du lieu, sans distinction de casse ni d'accents. La clé la plus longue correspondante gagne ; à égalité, la première règle gagne. Son montant **remplace la base de zone** ; seuls les suppléments de poids et de créneau s'ajoutent ensuite. La destination doit toujours appartenir à la couverture géographique. L'import CSV `cle;tarif` ajoute des règles à vérifier avant l'enregistrement de la grille ; les doublons normalisés n'écrasent pas les règles existantes.

Les colis acceptent un créneau libre début/fin, sans durée imposée d'une heure. Une fin avant le début indique le lendemain ; les anciennes données avec seulement un début conservent leur fin à +1 h. Le choix exclusif **Matin** (avant midi) ou **Soir** (après midi) désactive les heures précises et utilise le supplément de période de la grille, **500 Ar par défaut**, sans cumul avec les suppléments horaires. Pour un créneau libre, la règle actuelle reste fondée uniquement sur son heure de début. Pour les expresses, un début précis renseigné doit rester futur ; la période Matin n'est plus disponible une fois midi passé.

Le client propriétaire et l'administrateur peuvent modifier les champs d'un colis ou le **reprogrammer avant validation du dispatch** (avant confirmation pour un express). L'API applique ce verrou, et pas seulement l'affichage des boutons. Un changement de destination, poids ou créneau recalcule le tarif automatique mais conserve un éventuel écrasement administratif ; une correction de contact seule ne réévalue pas le tarif historique. La reprogrammation crée une demande normale et un colis liés, conserve le retrait, le tarif et la priorité, libère la place d'origine et contrôle le quota de la date cible. Le brouillon de dispatch éventuel est resynchronisé, ses affectations encore valides conservées et sa version avancée ; un éditeur périmé doit le recharger. Après dispatch, le report continue à passer par le compte rendu habituel.

### Capacité journalière stricte

La limite journalière correspond actuellement à un **nombre de colis**, pas à un nombre de demandes ni à un poids total. Lors de la création, le backend compare les colis déjà en attente pour la date, la limite configurée et les nouveaux colis.

Si la projection dépasse la limite, la demande complète est refusée en HTTP `409` avec le code `DAILY_CAPACITY_EXCEEDED`. La réponse contient `limit`, `current`, `requested`, `projected`, `remaining` et surtout `excess`, afin d'indiquer exactement combien de colis retirer. Il n'existe aucun contournement utilisateur de cette limite. Un colis annulé libère sa place ; un colis reporté consomme une place dans le quota de sa nouvelle date uniquement lors de sa reprogrammation.

### Parcours administrateur

L'administrateur peut :

- consulter les demandes et la file d'attente d'une date ;
- créer, modifier, désactiver et réactiver les profils de livreurs ;
- choisir le véhicule, le départ habituel, l'heure de départ et les éventuelles valeurs personnalisées de capacité ou de vitesse ;
- modifier les préréglages `MOTO` et `BICYCLE` ;
- modifier le point central, les rayons et les prix des zones de livraison ;
- régler la limite journalière, le poids par défaut, le siège par défaut, les durées de service, les tolérances horaires, le délai OR-Tools et le profil routier ;
- sélectionner les livreurs actifs d'une journée, éventuellement remplacer le ralliement de référence, puis préparer un dispatch manuel avec tous les colis normaux en attente de cette date.

Le dispatch se prépare en trois étapes : **Récupérations**, **Livraisons**, puis **Vérification**. Les retraits situés aux mêmes coordonnées sont regroupés, tout en conservant les clients, demandes et colis d'origine. Une vue liste ou carte permet de consulter les points et les départs enregistrés des livreurs. Les fiches se déplacent vers les livreurs par glisser-déposer ; la recherche de livreur et les flèches permettent aussi de travailler au clavier ou sur mobile. Les regroupements géographiques sont uniquement une aide visuelle, sans affectation automatique.

Un bouton secondaire **Répartition automatique** peut proposer les affectations et l'ordre de **l'étape ouverte uniquement**. Il utilise les distances routières directionnelles OSRM pour rapprocher les passages, sans tenir compte des poids, capacités ni horaires. Les récupérations partent des départs enregistrés ; les livraisons partent de la dernière récupération de chaque livreur, ou de son départ s'il n'en fait aucune. La proposition favorise la conservation des colis chez leur collecteur, avec une pénalité indicative par colis échangé (au moins 1,5 km, augmentée si les fins de collecte sont éloignées). C'est une heuristique, pas une garantie d'optimum global ni un calcul du trajet réel des passations. Aucun passage au ralliement, horaire ou kilométrage financier n'est ajouté. Une confirmation précède le remplacement d'affectations existantes ; la proposition est annulable jusqu'à la prochaine retouche ou navigation, puis reste entièrement modifiable avec la sauvegarde habituelle. Une panne du routage laisse les affectations intactes et le mode manuel disponible.

Les colis sont initialement proposés à la livraison chez leur collecteur ; l'administrateur peut ensuite les déplacer individuellement et ordonner chaque tournée. Les différences collecteur/livreur produisent une liste d'échanges, sans inventer leur lieu, leur heure ou un trajet par le siège. Les poids et créneaux restent informatifs et ne bloquent pas les affectations. Aucune heure d'arrivée ou distance non calculée n'est présentée comme une estimation réelle.

Un seul brouillon ou plan actif est autorisé par date. Le brouillon est enregistré dans PostgreSQL, avec sauvegarde automatique, version et snapshots des entrées. Sa création ferme les admissions normales et les annulations de colis de cette date ; les expresses restent indépendantes. L'abandon du brouillon rouvre les admissions. La validation exige un collecteur pour chaque retrait et un livreur pour chaque colis, sans doublon. Elle est atomique et idempotente et alimente les tables existantes pour les comptes rendus et rapports. Un plan manuel validé peut être rouvert dans l'éditeur tant qu'aucun résultat de livraison n'est renseigné ; l'ancienne version reste historisée.

L'onglet **Briefing**, situé après Dispatch, reprend la répartition validée de la date choisie, dans l'ordre **Récupérations → Échanges → Colis à livrer**. La vue d'ensemble regroupe les missions par livreur ; la vue individuelle utilise une recherche de livreur et conserve les ordres de passage enregistrés. L'aperçu de vérification avant validation réutilise la même présentation, sans génération de message.

Pour un plan manuel validé, **Briefing texte** ouvre un message modifiable et copiable par livreur : retraits, vendeurs et leurs contacts actuels, personnes à rencontrer, colis à donner ou prendre, puis liste finale. Dans toutes les listes de colis, les repères, lieux abrégés et contacts destinataires remplacent les noms des vendeurs et destinataires ; les montants à encaisser et créneaux renseignés restent présents dans la liste finale. Ces ajustements concernent uniquement le texte, pas la vue graphique. Aucun lieu de rencontre, trajet ou horaire d'échange n'est inventé et aucun SMS n'est envoyé par l'application. Les retouches sont temporaires, propres au livreur et au plan affichés ; elles ne modifient pas le dispatch et ne sont pas enregistrées en base. Les plans annulés ou remplacés ne sont pas utilisés et une répartition incomplète est signalée plutôt que transformée en instructions trompeuses. Les anciens plans automatiques restent consultables dans Dispatch.

### Compte rendu, clôture et rapports

Après le dispatch, l'administrateur attribue à chaque colis une issue finale :

- `DELIVERED` : livré ; le montant réellement récupéré vaut par défaut le montant prévu mais reste modifiable ;
- `CANCELLED` : annulé avant dispatch, sans frais de livraison ;
- `POSTPONED` : reporté, sans frais sur la journée d'origine ; après clôture du compte client ou de la journée, il reste dans l'onglet **Reportés** jusqu'à sa reprogrammation manuelle à une date choisie, avec un nouveau retrait ;
- `RETURN_TO_SENDER` : livraison non aboutie à retourner à l'expéditeur, avec frais de livraison et suivi séparé du retour physique.

Le livreur prévu par l'optimiseur et le livreur réellement intervenu sont deux données distinctes. Lors du compte rendu, l'affectation prévue est proposée par défaut mais l'administrateur peut choisir n'importe quel profil livreur, même inactif ou absent du dispatch. Ce choix est obligatoire pour `DELIVERED` et `RETURN_TO_SENDER`, les deux issues facturées, puis son identité est conservée sous forme de snapshot pour les futurs calculs de rémunération. Les anciens comptes rendus ne sont pas attribués automatiquement : sans preuve d'exécution, ils restent explicitement non renseignés.

L'indicateur Mobile Money est purement informatif : il ne met jamais un montant à zéro et n'intervient dans aucun total financier. Le montant à reverser est toujours `montant réellement récupéré - frais de livraison` et peut donc être négatif pour un retour.

La clôture manuelle n'est possible que lorsque tous les colis ont une issue finale et que chaque issue facturée possède son livreur réel. Les colis reportés ne bloquent pas les clôtures et peuvent rester sans nouvelle date aussi longtemps que nécessaire. Leur reprogrammation crée un colis lié dans une demande normale à la date choisie, y compris pour un express d'origine, conserve le tarif et la priorité initiaux, et respecte le quota et les fermetures de cette date. Un seul successeur peut être créé par colis.

Une journée clôturée peut être rouverte avec une raison auditée. Une fois clôturée, elle fournit un rapport général et un rapport par compte client, chacun en PDF et en véritable fichier Excel `.xlsx`. Les exports distinguent le livreur prévu du livreur réel et regroupent les synthèses par livreur réel. Le retour physique d'un colis peut encore être confirmé après la clôture : cette action est auditée, ne change aucun montant financier et apparaît dans les exports régénérés ensuite.

### Import de colis dans une demande

Le formulaire partagé entre le client et l'administration propose un import CSV et un modèle contenant tous les champs du colis. L'import remplit la liste modifiable de la demande ; l'enregistrement reste une action explicite après vérification. Pour un lieu textuel sans coordonnées, le premier résultat de la recherche géographique dans la zone desservie est retenu. Un lieu introuvable ou un tarif indisponible reste signalé et doit être corrigé avant l'envoi. Le retrait et la date sont choisis dans le formulaire pour l'ensemble des lignes. Le [format CSV complet](schemas/csv/README.md) décrit les colonnes et les exemples.

### Statuts persistés

| Objet | Statuts |
|---|---|
| Demande | `PENDING`, `PARTIALLY_DISPATCHED`, `DISPATCHED`, `CANCELLED` |
| Colis | `PENDING`, `DISPATCHED`, `UNASSIGNED` |
| Dispatch | `GENERATING`, `READY`, `READY_WITH_WARNINGS`, `FAILED` |
| Issue métier du colis | `DELIVERED`, `CANCELLED`, `POSTPONED`, `RETURN_TO_SENDER` |
| Journée | `OPEN`, `DISPATCHING`, `DISPATCHED`, `CLOSED` |

Les statuts de colis et de demande sont des statuts techniques historiques ; l'issue métier finale fait foi dans l'interface et les rapports. `CANCELLED` est exposé comme statut dérivé lorsqu'une demande ne contient plus que des colis annulés. Un colis non affecté par l'optimiseur reste inclus dans le plan du jour avec sa raison et doit recevoir une issue finale, généralement un report explicite. Le statut de la demande est recalculé à partir de l'ensemble de ses colis.

### Limite de routage actuelle

La préparation et la validation manuelles n'appellent ni la matrice OSRM ni OR-Tools : les limites de routage ne les bloquent pas. Seule l'aide facultative **Répartition automatique** appelle la matrice OSRM, sans OR-Tools et après déduplication des coordonnées. Elle reste soumise à la limite configurée (par défaut `250` points, départs compris) ; si elle est dépassée, le brouillon reste modifiable manuellement. Les outils automatiques historiques contrôlent séparément :

- collecte : départs des livreurs sélectionnés + retraits distincts + siège ;
- distribution : siège + destinations des colis.

Ce seuil couvre la limite métier actuelle de `100` colis, y compris un grand nombre de retraits et de livreurs. Si la configuration est réduite, le backend refuse clairement le calcul et demande d'augmenter la capacité du service de routage au lieu de tronquer silencieusement le plan.

## Outils historiques de préparation et de comparaison

> Cette section décrit le workspace par fichiers et les endpoints techniques conservés pour l'administration, le diagnostic et la comparaison. Elle ne décrit plus le parcours principal d'un client.

### Parcours de l'utilisateur

Après la connexion, l'utilisateur choisit l'un des deux patterns de planification :

- **un retrait, plusieurs livraisons** pour traiter une demande avec une origine commune ;
- **plusieurs clients** pour regrouper plusieurs clients, chacun possédant un ou plusieurs retraits et plusieurs colis, avec une équipe de livreurs partagée.

Chaque choix ouvre un espace de travail dans lequel :

1. le formulaire constitue la source visible du plan en cours ;
2. les imports CSV de colis et de livreurs alimentent ce même formulaire, sans créer un parcours séparé ;
3. le plan peut être exporté en JSON à tout moment comme brouillon partageable ;
4. un fichier JSON précédemment exporté peut être rouvert pour reprendre et modifier le travail ;
5. le résultat n'est généré qu'après validation du formulaire complet.

Dans ce workspace historique, le fichier de plan reste la sauvegarde portable de référence. Cette règle ne s'applique pas aux demandes et dispatchs du nouveau parcours, qui sont persistés dans PostgreSQL.

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
3. respecter les créneaux et réduire les retards ;
4. depuis le siège, utiliser le plus grand nombre possible de livreurs capables de prendre au moins un colis ;
5. équilibrer le nombre de colis entre les livreurs mobilisés ;
6. minimiser ensuite le coût routier total et conserver des zones de livraison cohérentes.

La collecte conserve sa logique géographique : un retrait reste confié à un seul collecteur lorsqu'il tient dans sa capacité. Après la redistribution au siège, les colis sont au contraire répartis au maximum entre les livreurs capables, sans dépasser les capacités ni rendre un créneau impossible. À nombre de livreurs et ponctualité équivalents, l'équilibrage de la charge précède la distance totale.

Les distances du réseau routier constituent le coût géographique. La vitesse effective de chaque livreur transforme ces distances en temps estimés. Les paramètres OR-Tools devront inclure une limite de calcul afin de retourner une bonne solution exploitable même lorsque le problème devient important ; une solution peut être proche de l'optimum sans être mathématiquement prouvée optimale.

Un colis non affecté doit recevoir l'une des raisons suivantes, extensibles par la suite :

- `DESTINATION_UNRESOLVED` : destination non géocodée ;
- `NO_ROAD_ROUTE` : aucun trajet routier trouvé ;
- `OVERWEIGHT_FOR_ALL_COURIERS` : le colis dépasse chaque capacité individuelle ;
- `INSUFFICIENT_TOTAL_CAPACITY` : capacité disponible insuffisante ;
- `SOLVER_TIMEOUT` : aucune affectation acceptable trouvée dans le temps imparti.

Le système ne contourne jamais silencieusement une capacité. Il indique le dépassement, la capacité minimale nécessaire et les livreurs candidats. Un responsable peut alors corriger explicitement la capacité du livreur et relancer le calcul. Un futur mode de dérogation devra conserver un avertissement visible et une trace de la décision.

### Comparaison avec redistribution par un siège

Pour un plan multi-clients `2.1`, l'application calcule et conserve deux scénarios : les tournées directes et une organisation en quatre phases **collecte → rendez-vous au siège → redistribution → livraison**. Chaque livreur utilisé part de son lieu à son heure de départ, les colis collectés passent obligatoirement par `redistributionHub`, puis la livraison repart du siège après l'arrivée du dernier participant et la redistribution des charges.

Le choix d'un distributeur inclut son trajet réel origine → siège lorsqu'il n'a effectué aucune collecte. Si une collecte trop tardive empêche ensuite plusieurs livraisons, une boucle bornée recalcule la collecte avec une heure maximale d'arrivée au siège et conserve la meilleure solution complète trouvée ; les colis écartés restent explicitement non affectés.

Les durées opérationnelles par défaut sont de 10 minutes par retrait visité, 15 minutes pour la redistribution commune et 5 minutes par colis livré. Les créneaux acceptent par défaut 0 minute d'avance et jusqu'à 30 minutes de retard ; ces valeurs sont configurables.

La recommandation compare les scénarios dans cet ordre : nombre de colis livrés, nombre de livraisons à l'heure, retard cumulé, nombre de livreurs mobilisés, kilomètres réellement parcourus, puis heure de fin. Une égalité complète conserve le scénario direct. Pour le scénario par siège, le résultat fournit également l'heure du rendez-vous, un manifeste par livreur et le transfert de chaque colis entre collecteur et distributeur.

## Architecture

```text
Navigateur
  |
  +-- React + TypeScript
  |     +-- espaces CLIENT et ADMIN
  |     +-- demandes, file, réglages et dispatchs
  |     +-- Leaflet et affichage cartographique
  |     +-- outils historiques et exports
  |
  +-- API REST Spring Boot
        +-- comptes et authentification
        +-- profils clients et livreurs
        +-- demandes et file journalière
        +-- réglages et dispatchs persistants
        +-- géocodage
        +-- calcul des matrices OSRM
        +-- optimisation OR-Tools
        |
        +-- PostgreSQL          données métier et historique
        +-- Nominatim local     texte -> coordonnées
        +-- OSRM local          distances, durées, tracés
```

### Composants

| Composant | Technologie | Responsabilité |
|---|---|---|
| Frontend | React, TypeScript | Parcours client/admin, formulaires, cartes et résultats |
| Carte | Leaflet | Sélection et affichage des points et tournées |
| API | Java, Spring Boot | Sécurité, validation, orchestration et formats de fichiers |
| Optimisation | OR-Tools Java | Affectation et ordre des livraisons avec contraintes |
| Routage | OSRM local | Matrices routières et géométries des trajets |
| Géocodage | Nominatim local | Résolution des destinations textuelles |
| Base métier | PostgreSQL | Comptes, profils, demandes, colis, réglages, dispatchs, sessions et cache |
| Exécution | Docker Compose | Environnement local reproductible et base du déploiement |

OR-Tools traite nativement les problèmes de tournées avec capacités et visites éventuellement abandonnées. Voir la documentation officielle sur le [Vehicle Routing Problem](https://developers.google.com/optimization/routing) et les [contraintes de capacité](https://developers.google.com/optimization/routing/cvrp).

### Modules backend

Le backend reste un monolithe modulaire Spring Boot :

```text
account/        comptes, rôles, profils clients, login et jetons
administration/ réglages opérationnels, préréglages et profils livreurs
delivery/       demandes persistées, colis, file et règles de capacité
dispatch/       brouillons manuels, affectations et anciens résultats VIA_HUB persistés
pricing/        paliers géographiques, devis et snapshots tarifaires
planning/       modèle du plan et règles de validation
geography/      fournisseurs de géocodage, cache et ambiguïtés
routing/        OSRM, matrices, profils et géométries
optimization/   adaptation OR-Tools et explication des résultats
importexport/   JSON, CSV et PDF
```

Cette séparation permet notamment de conserver l'optimiseur indépendant de la persistance opérationnelle et de remplacer Nominatim ou OSRM sans modifier le cœur métier.

### Profils de véhicule et de route

Dans le MVP, `MOTO` et `BICYCLE` utilisent le même préréglage routier conservateur `MAIN_ROADS`. Ils se différencient par leur capacité et leur vitesse. Le chemin est calculé sur les voies principales connues d'OSM, puis la durée est ajustée avec la vitesse effective du livreur.

Les valeurs initiales proposées sont des paramètres applicatifs, pas des limites de sécurité garanties :

| Véhicule | Capacité par défaut | Vitesse moyenne par défaut |
|---|---:|---:|
| `MOTO` | 30 kg | 25 km/h |
| `BICYCLE` | 10 kg | 12 km/h |

Elles doivent être validées avec les conditions réelles d'AZY Livraison. Elles sont désormais configurables en base par l'administrateur, avec une surcharge possible sur chaque profil livreur. À terme, plusieurs préréglages de routes pourront être préparés si la qualité des données OSM à Madagascar permet de distinguer de manière fiable moto, bicyclette et autres véhicules.

## Données OpenStreetMap

### Quand télécharger les données ?

**Le téléchargement devient nécessaire à partir de cette phase géographique.** Les services Nominatim et OSRM ainsi que leurs scripts d'initialisation sont maintenant présents. Le téléchargement reste une action explicite afin de ne jamais consommer plusieurs centaines de mégaoctets ni lancer un import long pendant un démarrage quotidien.

Le fichier à utiliser est l'extrait Madagascar `madagascar-latest.osm.pbf`, disponible sur la page officielle [Geofabrik — Madagascar](https://download.geofabrik.de/africa/madagascar.html). Il sera placé localement dans :

```text
data/osm/madagascar-latest.osm.pbf
```

Le répertoire `data/` ne doit pas être versionné dans Git. Le même extrait source sert à :

- préparer le graphe OSRM ;
- importer l'index de recherche Nominatim.

Télécharger l'extrait et vérifier automatiquement son empreinte MD5 :

```powershell
.\infrastructure\osm\download-madagascar.ps1
```

Le fichier PBF courant fait environ 370 Mo. Les index Nominatim et OSRM demandent ensuite plusieurs gigaoctets supplémentaires et leur première préparation peut durer longtemps selon le disque, la mémoire et le nombre de cœurs disponibles.

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

## Persistance métier et fichiers historiques

### PostgreSQL

PostgreSQL est la source de vérité du parcours multi-utilisateur. La base conserve notamment :

- les comptes, rôles, profils clients et retraits par défaut ;
- les mots de passe hachés et les sessions de renouvellement révocables ;
- les réglages opérationnels, préréglages de véhicules et profils livreurs ;
- les demandes, colis, snapshots de capacité, statuts et événements ;
- les dispatchs, réservations anti-concurrence, snapshots, arrêts, affectations et métriques ;
- le cache de géocodage.

Les snapshots rendent un ancien résultat relisible même après la modification d'un livreur ou d'un réglage. Les fichiers temporaires créés pour un export doivent toujours être supprimés après téléchargement ou expiration.

Nominatim utilise son propre stockage technique PostgreSQL/PostGIS et son propre volume Docker. Ce stockage ne fait pas partie de la base métier d'AZY Livraison.

### Plan JSON partageable

Le JSON reste le format canonique des anciens brouillons partageables et des outils de comparaison. Il ne remplace pas l'enregistrement en base des demandes et dispatchs du front-office. Chaque fichier possède une version de schéma afin de permettre de futures migrations.

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
      "averageSpeedKmh": 22.0,
      "departureAt": "2026-08-03T07:30:00+03:00",
      "departureLocation": {
        "destinationQuery": "Isoraka, Antananarivo",
        "latitude": -18.915,
        "longitude": 47.518
      }
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
      },
      "deliveryWindow": {
        "startAt": "2026-08-03T09:00:00+03:00",
        "endAt": "2026-08-03T11:00:00+03:00"
      }
    }
  ]
}
```

Les valeurs `maxWeightKg` et `averageSpeedKmh` absentes utilisent le préréglage du véhicule. Une valeur présente doit être positive et remplace le préréglage. `deliveryWindow`, `departureAt` et `departureLocation` sont facultatifs. Sans départ personnalisé, un livreur part du dépôt à l'heure globale du plan. Un créneau présent doit posséder un début et une fin cohérents.

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
id;label;weight_kg;destination_query;latitude;longitude;phone;window_start_at;window_end_at
colis-001;Client Analakely;4.5;Analakely, Antananarivo;;;+261000000000;2026-08-05T09:00:00+03:00;2026-08-05T11:00:00+03:00
colis-002;Client coordonnees;2.0;;-18.9100;47.5250;;;
```

`destination_query` peut être vide uniquement lorsque les deux coordonnées sont présentes. Lorsqu'elles sont présentes, les coordonnées sont prioritaires. `window_start_at` et `window_end_at` sont facultatifs, mais doivent être fournis ensemble au format ISO avec le décalage `+03:00`.

### Livreurs

Modèle prêt à remplir : [`schemas/csv/couriers-template.csv`](schemas/csv/couriers-template.csv).

```csv
id;name;vehicle_type;max_weight_kg;average_speed_kmh;departure_at;departure_query;departure_latitude;departure_longitude
livreur-001;Exemple Livreur;MOTO;35;22;2026-08-05T08:00:00+03:00;Analakely, Antananarivo;;
livreur-002;Exemple Vélo;BICYCLE;;;;;;
```

Les cellules vides de capacité et de vitesse demandent l'utilisation du préréglage du véhicule. Les quatre colonnes de départ sont facultatives ; sans valeur, le dépôt et l'heure globale du plan sont utilisés.

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

L'outil historique affiche un aperçu de ces éléments en mémoire. Cet aperçu d'import n'est pas une demande client persistée et un rechargement de la page l'efface.

### Résultats

L'export CSV produit une ligne par arrêt/colis et répète les informations nécessaires sur la tournée : livreur, ordre, poids, adresse, coordonnées, distance cumulée, durée cumulée et heure estimée. Le PDF fournit une fiche imprimable par livreur ainsi qu'un résumé des colis non affectés.

## Authentification et sécurité

L'application propose une inscription publique réservée aux clients et une autorisation serveur par rôles.

- `POST /api/auth/register` crée toujours un compte `CLIENT` et ouvre sa première session.
- Un compte `ADMIN` initial est créé depuis les variables d'environnement au premier démarrage.
- Le rôle `COURIER` existe dans le modèle de sécurité, mais aucun parcours de connexion livreur n'est encore exposé.
- Les mots de passe sont hachés avec un algorithme adapté fourni par Spring Security ; ils ne sont jamais journalisés.
- Le frontend garde l'access token JWT de courte durée en mémoire et l'envoie avec `Authorization: Bearer <token>`.
- Le JWT contient le rôle utilisé par Spring Security pour construire l'autorité `ROLE_CLIENT`, `ROLE_ADMIN` ou `ROLE_COURIER`.
- Le refresh token est rotatif, révocable, conservé haché en base et transmis dans un cookie `HttpOnly`, `SameSite` et `Secure` en production.
- Le logout révoque la session de renouvellement.
- Les clients ne lisent que leurs propres demandes ; les opérations de file, réglages, livreurs et dispatch sont réservées aux administrateurs.
- Les limites de taille des fichiers, les types MIME, le contenu CSV/JSON et toutes les valeurs numériques sont validés côté backend.
- Les secrets réels ne sont jamais committés. `.env.example` ne contient que des valeurs factices.

## API REST

### Authentification et profil client

| Méthode | Route | Usage |
|---|---|---|
| `POST` | `/api/auth/register` | Créer un compte `CLIENT` et ouvrir une session |
| `POST` | `/api/auth/login` | Obtenir un access token et ouvrir une session |
| `POST` | `/api/auth/refresh` | Renouveler et faire tourner les jetons |
| `POST` | `/api/auth/logout` | Révoquer la session |
| `GET` | `/api/auth/me` | Lire le compte correspondant à l'access token |
| `GET` | `/api/client/profile` | Lire le profil et le retrait par défaut du client connecté |
| `PUT` | `/api/client/profile` | Modifier le profil et le retrait par défaut |

`register`, `login`, `refresh` et `logout`, ainsi que les health checks Docker, sont publics. `/api/auth/me` exige une session authentifiée. Les routes `/api/client/**` exigent `CLIENT` et les routes `/api/admin/**` exigent `ADMIN`.

### Demandes client et file administrateur

| Méthode | Route | Usage |
|---|---|---|
| `GET` | `/api/client/delivery-requests` | Lister les demandes du client connecté |
| `GET` | `/api/client/delivery-requests/{id}` | Lire une de ses demandes |
| `POST` | `/api/client/delivery-requests` | Créer une demande persistante ; refus atomique si le quota est dépassé |
| `POST` | `/api/client/delivery-packages/{id}/cancel` | Annuler l'un de ses colis encore en attente |
| `GET` | `/api/admin/delivery-requests?date=&status=` | Filtrer toutes les demandes |
| `GET` | `/api/admin/queue?date=YYYY-MM-DD` | Lire la file encore en attente de cette date, avec les compteurs opérationnels |
| `POST` | `/api/admin/delivery-packages/{id}/cancel` | Annuler un colis encore en attente |

La file opérationnelle est strictement limitée à la date choisie. Les demandes d'autres dates restent visibles dans la liste globale et ne sont jamais aspirées silencieusement : la reprogrammation d'un colis reporté se fait uniquement par une action explicite avec une nouvelle date.

### Administration et dispatch

Le parcours courant utilise les endpoints suivants, réservés aux administrateurs :

| Méthode | Route | Usage |
|---|---|---|
| `POST` | `/api/admin/manual-dispatch-drafts` | Préparer la file normale complète de la date ; exige `Idempotency-Key` |
| `GET` | `/api/admin/manual-dispatch-drafts/active?date=` | Reprendre le brouillon actif, ou `204` |
| `GET` | `/api/admin/manual-dispatch-drafts/{id}` | Relire les entrées et affectations enregistrées |
| `PUT` | `/api/admin/manual-dispatch-drafts/{id}` | Enregistrer départs, étape et affectations avec `expectedVersion` |
| `POST` | `/api/admin/manual-dispatch-drafts/{id}/finalize` | Valider toutes les affectations et publier le plan journalier |
| `POST` | `/api/admin/manual-dispatch-drafts/{id}/cancel` | Abandonner le brouillon et rouvrir les admissions normales |
| `GET` | `/api/admin/manual-dispatch-drafts/for-plan/{planId}` | Consulter le snapshot manuel d'un plan final |
| `POST` | `/api/admin/manual-dispatch-drafts/from-plan/{planId}` | Rouvrir un plan manuel sans résultat renseigné |

Les ordres du brouillon commencent à `0` et sont continus, séparément pour les récupérations et les livraisons de chaque livreur. Les entrées commerciales et les identités ne sont jamais modifiables via le layout. Une version périmée donne `409`. Les champs du résultat d'optimisation, métriques et rendez-vous sont absents pour un nouveau plan manuel ; le `strategy` exposé vaut `MANUAL`. Les routes historiques ci-dessous sont conservées pour compatibilité :

| Méthode | Route | Usage |
|---|---|---|
| `GET`, `PUT` | `/api/admin/settings` | Lire ou modifier les réglages opérationnels |
| `GET`, `PUT` | `/api/admin/vehicle-presets` | Lire ou modifier les préréglages de véhicules |
| `GET`, `POST` | `/api/admin/couriers` | Lister ou créer les profils livreurs |
| `GET`, `PUT` | `/api/admin/couriers/{id}` | Lire ou modifier un profil livreur |
| `POST`, `PATCH` | `/api/admin/couriers/{id}/disable` | Désactiver un profil sans supprimer son historique |
| `POST` | `/api/admin/dispatch-plans` | Créer un dispatch `VIA_HUB` persistant ; exige `Idempotency-Key` |
| `GET` | `/api/admin/dispatch-plans?date=` | Lister les dispatchs, éventuellement pour une date |
| `GET` | `/api/admin/dispatch-plans/{id}` | Relire le résultat détaillé d'un dispatch |

La création d'un dispatch ne reçoit aucun `packageIds` : elle sélectionne obligatoirement tous les colis encore en attente pour la date. Un `hubOverride` peut remplacer le siège par défaut pour cette exécution. Les appels OSRM et OR-Tools sont effectués hors de la transaction longue ; des réservations empêchent qu'un autre dispatch utilise simultanément les mêmes colis ou la même journée.

### Compte rendu journalier et rapports

| Méthode | Route | Usage |
|---|---|---|
| `GET` | `/api/admin/delivery-days/{date}` | Lire l'état opérationnel, les colis et les totaux de la journée |
| `PUT` | `/api/admin/delivery-days/{date}/packages/{id}/outcome` | Enregistrer ou corriger l'issue finale, le montant et le `actualCourierId` d'un colis |
| `GET` | `/api/admin/delivery-packages/postponed?query=` | Rechercher les colis reportés après clôture, encore à reprogrammer |
| `POST` | `/api/admin/delivery-days/{date}/packages/{id}/carry-forward` | Créer le colis lié à la date `targetDate` choisie, avec son nouveau retrait |
| `POST` | `/api/admin/delivery-days/{date}/packages/{id}/return-completed` | Confirmer le retour physique à l'expéditeur |
| `POST` | `/api/admin/delivery-days/{date}/close` | Clôturer après validation des états finaux et des comptes clients |
| `POST` | `/api/admin/delivery-days/{date}/reopen` | Rouvrir avec une raison auditée |
| `GET` | `/api/admin/delivery-days/{date}/reports/general.{pdf,xlsx}` | Télécharger le rapport général clôturé |
| `GET` | `/api/admin/delivery-days/{date}/reports/clients/{accountId}.{pdf,xlsx}` | Télécharger le rapport clôturé d'un compte client |

### Géographie et outils historiques/admin

| Méthode | Route | Usage |
|---|---|---|
| `GET` | `/api/geocoding/search?q=...` | Rechercher des candidats dans Madagascar (`CLIENT` ou `ADMIN`) |
| `GET` | `/api/vehicle-presets` | Lire les anciennes valeurs applicatives (`ADMIN`) |
| `POST` | `/api/plans/validate` | Valider et normaliser un ancien plan par fichier (`ADMIN`) |
| `POST` | `/api/plans/optimize` | Optimiser directement un ancien plan (`ADMIN`) |
| `POST` | `/api/plans/compare` | Comparer direct et siège sur un plan multi-clients `2.1` (`ADMIN`) |
| `POST` | `/api/import/packages/csv` | Convertir un CSV colis vers l'ancien modèle de plan (`ADMIN`) |
| `POST` | `/api/import/couriers/csv` | Convertir un CSV livreurs vers l'ancien modèle de plan (`ADMIN`) |
| `GET` | `/api/import/packages/template` | Télécharger le modèle CSV colis (`ADMIN`) |
| `GET` | `/api/import/couriers/template` | Télécharger le modèle CSV livreurs (`ADMIN`) |

Le front-office ne doit pas appeler `/api/plans/compare` ni choisir entre les scénarios. Ces endpoints restent disponibles pour l'analyse administrative, la reproductibilité des anciens fichiers et la mesure de l'algorithme. Les exports CSV et PDF actuels sont produits côté frontend à partir des résultats affichés.

Le login, l'inscription et le renouvellement répondent avec :

```json
{
  "tokenType": "Bearer",
  "accessToken": "dummy.jwt.value",
  "expiresIn": 900,
  "account": {
    "id": "00000000-0000-0000-0000-000000000000",
    "username": "client-exemple",
    "role": "CLIENT",
    "displayName": "Client Exemple",
    "phone": "+261340000000"
  }
}
```

Le refresh token n'est pas exposé dans ce JSON : il est envoyé dans le cookie `azy_refresh_token`, limité au chemin `/api/auth`. Chaque appel réussi à `/api/auth/refresh` remplace et révoque le précédent token. Sa réutilisation échoue en HTTP `401`.

## Migrations de la version multi-utilisateur

Flyway applique automatiquement les migrations au démarrage, dans l'ordre :

| Migration | Contenu principal |
|---|---|
| `V4__add_account_roles_and_client_profiles.sql` | rôles `CLIENT`/`COURIER`/`ADMIN`, identité affichée, téléphone et retrait client par défaut |
| `V5__create_operational_settings_and_couriers.sql` | réglages globaux, préréglages de véhicules et profils livreurs avec départ habituel |
| `V6__create_delivery_requests.sql` | demandes, colis, snapshots de dépassement, statuts et journal d'événements |
| `V7__create_persisted_dispatch_plans.sql` | dispatchs `VIA_HUB`, réservations, snapshots, livreurs retenus, résultats colis et arrêts |
| `V8__create_delivery_pricing_zones.sql` | point central, zones tarifaires et snapshots de prix des colis |
| `V9__create_daily_delivery_workflow.sql` | issues finales, reports liés, journées, clôtures, audit et snapshots clients |
| `V10__record_actual_delivery_courier.sql` | livreur réellement intervenu et snapshot d'identité distinct de l'affectation planifiée |
| `V20__create_manual_dispatch_drafts.sql` | brouillons manuels versionnés, reprise, identifiants d'idempotence et lien au plan final ; aucune donnée existante supprimée |
| `V21__add_pricing_periods_and_keyword_rules.sql` | suppléments Matin/Soir et règles de remplacement tarifaire par mot-clé |
| `V22__persist_delivery_time_windows.sql` | fin réelle et préférence horaire des colis et des snapshots de dispatch ; reprise des anciens créneaux à +1 h |

Ne renommez et ne modifiez pas une migration déjà appliquée sur une base partagée : tout changement ultérieur doit utiliser une nouvelle version Flyway. Le passage de `V3` à `V4` attribue le rôle `ADMIN` aux comptes déjà présents afin de préserver l'accès au back-office.

## Configuration

Copier `.env.example` vers `.env`, puis remplacer toutes les valeurs marquées comme factices :

```powershell
Copy-Item .env.example .env
```

Le fichier `.env` ne doit jamais être versionné. Les valeurs factices permettent un lancement strictement local ; tous les mots de passe et secrets devront être remplacés avant de partager ou déployer l'environnement.

Le compte `INITIAL_ADMIN_USERNAME` est créé uniquement s'il n'existe pas encore. Modifier `INITIAL_ADMIN_PASSWORD` après ce premier démarrage ne réinitialise donc pas silencieusement son mot de passe en base. En local, pour repartir entièrement des valeurs du nouveau fichier `.env`, il faut supprimer volontairement le volume PostgreSQL puis relancer l'application.

Les variables d'environnement couvrent surtout l'infrastructure :

- accès PostgreSQL ;
- compte initial ;
- secrets et durées des jetons ;
- URLs internes OSRM et Nominatim ;
- chemin et URL de l'extrait OSM ;
- taille maximale des imports ;
- fournisseur et attribution des tuiles ;
- limites de points OSRM.

Les valeurs susceptibles d'être ajustées au quotidien — capacité journalière, siège, poids par défaut, durées de service, tolérances, délai de l'optimiseur et préréglages des véhicules — sont persistées dans PostgreSQL et modifiées via les endpoints d'administration.

## Structure du dépôt

```text
azy-livraison/
├── backend/                 API Spring Boot, migrations et tests Java
├── frontend/                application React + TypeScript et tests Vitest
├── infrastructure/          téléchargement OSM et profil/préparation OSRM
├── schemas/                 schéma JSON 1.0, exemples et modèles CSV
├── data/                    données OSM locales non versionnées
├── compose.yaml             frontend, backend et PostgreSQL
├── compose.geography.yaml   profil Nominatim et OSRM locaux
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

### Géocodage local avec Nominatim

Le profil géographique est volontairement séparé du démarrage quotidien. Après avoir téléchargé le PBF :

```powershell
docker compose -f compose.yaml -f compose.geography.yaml --profile geography up --detach nominatim
docker compose -f compose.yaml -f compose.geography.yaml --profile geography logs --follow nominatim
```

Le premier lancement importe Madagascar et peut prendre du temps. Le volume `nominatim-data` conserve ensuite l'index. Quand `/status` répond, reconstruire ou redémarrer le backend pour utiliser `http://nominatim:8080` dans le réseau Compose :

```powershell
docker compose -f compose.yaml -f compose.geography.yaml --profile geography up --build --detach
```

`NOMINATIM_WARMUP_ON_STARTUP=true` préchauffe l'index avant que le service soit déclaré prêt. Cela allonge le démarrage de Nominatim, mais évite que les premières recherches interactives expirent pendant le chargement des index. Le backend attend jusqu'à `GEOCODING_READ_TIMEOUT=30s` pour absorber les recherches locales plus lentes qui ne sont pas encore dans son cache.

L'index conserve par défaut `NOMINATIM_IMPORT_STYLE=full`. Ce choix préserve les
commerces et points de repère nommés, importants pour retrouver un lieu à
Madagascar même lorsqu'il n'a pas d'adresse formelle. Le style `address` reste
configurable pour une installation qui accepte explicitement de perdre ces POI.

`NOMINATIM_FREEZE=true` supprime en fin d'import les tables réservées aux mises à
jour dynamiques : le stockage est plus léger et davantage de l'index utile peut
rester en cache. La contrepartie est qu'une nouvelle donnée OSM doit être appliquée
par un nouvel import complet, ce qui correspond au mode de maintenance manuel
actuel.

Ces options ne transforment pas un volume déjà importé. Pour reconstruire
l'index sans effacer l'ancien, arrêter Nominatim, choisir dans `.env` un nouveau nom
de volume, puis recréer uniquement ce service :

```powershell
docker compose -f compose.yaml -f compose.geography.yaml --profile geography stop nominatim
# Dans .env : NOMINATIM_DATA_VOLUME=azy-livraison_nominatim-frozen-v1
docker compose -f compose.yaml -f compose.geography.yaml --profile geography up --detach --force-recreate nominatim
docker compose -f compose.yaml -f compose.geography.yaml --profile geography logs --follow nominatim
```

L'ancien volume `azy-livraison_nominatim-data` reste intact. Si le nouvel import
échoue, remettre son nom dans `NOMINATIM_DATA_VOLUME` et recréer le service permet
de revenir à l'index précédent. Ne supprimer un ancien volume qu'après validation
des recherches principales (`Analakely`, `Ankorondrano`, `Alasora`, etc.).

La recherche applicative est disponible dans l'interface et via `GET /api/geocoding/search?q=Analakely`. Elle est limitée à `mg`, renvoie plusieurs candidats à confirmer et met les réponses en cache dans PostgreSQL pendant 30 jours par défaut.

### Routage local avec OSRM

Préparer le graphe `MAIN_ROADS` à partir du même PBF Madagascar :

```powershell
docker compose -f compose.yaml -f compose.geography.yaml --profile geography run --rm osrm-prepare
```

Le script recalcule automatiquement le graphe lorsque le PBF, le profil Lua ou sa version change. Sinon, il détecte l'empreinte existante et termine immédiatement. Les fichiers `madagascar-latest.osrm.*` sont produits dans `data/osm/` et ne sont pas versionnés.

Démarrer ensuite OSRM et l'application :

```powershell
docker compose -f compose.yaml -f compose.geography.yaml --profile geography up --detach osrm
docker compose -f compose.yaml -f compose.geography.yaml --profile geography up --build --detach
```

Le profil conserve les rues locales pour permettre le dernier kilomètre, mais pénalise les voies `unclassified`, `residential`, `living_street` et `service` afin de privilégier les axes principaux. Il active la conduite à gauche. Toute modification du profil nécessite une nouvelle préparation, car les profils OSRM sont appliqués à la construction du graphe et non au moment de la requête. Voir la [documentation officielle des profils OSRM](https://github.com/Project-OSRM/osrm-backend/blob/master/docs/profiles.md).

Deux endpoints authentifiés sont disponibles :

- `POST /api/routing/matrix` calcule les distances et durées entre toutes les paires, et liste explicitement les paires inaccessibles ;
- `POST /api/routing/route` calcule un trajet ordonné, ses tronçons et sa géométrie GeoJSON `LineString`.

Le corps commun contient au moins deux points identifiés :

```json
{
  "points": [
    {"id": "pickup", "latitude": -18.9089, "longitude": 47.5212},
    {"id": "package-1", "latitude": -18.8859714, "longitude": 47.5229240}
  ]
}
```

Les coordonnées d'entrée et celles recalées sur la route sont toutes deux conservées dans la réponse. Les limites de taille, délais réseau et ports sont configurables dans `.env.example`.

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

La version multi-utilisateur est considérée conforme lorsque les scénarios suivants réussissent :

1. une inscription crée uniquement un compte `CLIENT`, dont le JWT ne donne accès à aucune route administrateur ;
2. deux clients ne peuvent jamais consulter leurs demandes respectives ;
3. le retrait par défaut est persisté, modifiable et réutilisable dans une nouvelle demande ;
4. une demande conserve tous les champs obligatoires du colis et applique le poids administrateur lorsque le poids est omis ;
5. un dépassement journalier refuse toute la demande avec `DAILY_CAPACITY_EXCEEDED` et le nombre exact de colis en excès ;
6. les modifications de réglages et de profils livreurs utilisent leur version afin d'éviter les écrasements concurrents ;
7. un dispatch concurrent ne peut pas réserver deux fois la même journée ou le même colis ;
8. le dispatch manuel respecte les affectations et ordres explicites, conserve les départs journaliers et ne force aucun passage au siège ;
9. tous les colis admis de la date entrent dans l'unique plan, tandis que les non-affectés restent traçables et finalisables ;
10. un dispatch terminé peut être relu avec ses snapshots, ses arrêts, ses affectations et ses métriques ;
11. l'expiration, le renouvellement et la révocation des jetons fonctionnent ;
12. une date dispatchée refuse de nouvelles demandes et toute seconde génération de plan ;
13. les reports ne bloquent aucune clôture ; leur reprogrammation différée crée un unique colis lié à la date choisie et consomme le quota de cette date ;
14. les frais et montants à reverser ignorent l'indicateur Mobile Money et autorisent un solde négatif ;
15. les rapports PDF/XLSX ne sont disponibles qu'après clôture et isolent correctement chaque compte client ;
16. les anciens calculs automatiques refusent proprement une matrice trop grande, sans imposer cette limite à l'éditeur manuel.

Les anciens scénarios JSON/CSV, l'optimisation directe et la comparaison direct/siège restent couverts par leurs tests de non-régression.

## Traitement multi-clients historique

Le pattern mono-client reste identifié explicitement par :

```text
SINGLE_PICKUP_MULTI_DELIVERY
```

Le second pattern disponible est :

```text
MULTI_CLIENT_MULTI_PICKUP_DELIVERY
```

Ce second pattern ne représente pas une simple liste de courses point à point. Un plan contient plusieurs clients ; chaque client peut lui-même posséder plusieurs groupes de livraison. Chaque groupe est une occurrence complète du pattern principal : un point de récupération suivi de plusieurs points de livraison.

La liste des livreurs est commune à l'ensemble du plan. L'optimiseur répartit les colis de tous les clients entre le même vivier de livreurs, tout en respectant les capacités, les créneaux horaires et l'obligation d'effectuer chaque récupération avant la livraison correspondante. Dans le schéma courant, chaque livreur possède un lieu et une heure de départ confirmés : ce trajet initial est compté jusqu'au premier retrait attribué ou jusqu'au siège lorsqu'il participe uniquement à la distribution.

Les fichiers mono-client conservent le schéma JSON `1.0`. Les fichiers multi-clients utilisent le schéma `2.1`, une collection `clients` dont chaque élément contient ses `deliveryGroups`, et un point confirmé `redistributionHub`. Les sauvegardes multi-clients `2.0` restent lisibles et sont migrées vers `2.1` à l'ouverture ; l'administrateur doit ensuite renseigner le siège ainsi que les départs manquants avant de lancer la comparaison historique.

## Feuille de route

### Phase 1 — Socle

- [x] monorepo frontend/backend ;
- [x] Docker Compose, PostgreSQL, Flyway et health checks ;
- [x] modèles métier, schéma JSON 1.0 et validation des plans ;
- [x] compte initial, login, access token et refresh token ;
- [x] imports CSV colis et livreurs ;
- [x] choix du pattern et workspace de formulaire unifié ;
- [x] import/export JSON des brouillons de plan.

### Phase 2 — Géographie

- [x] téléchargement guidé de l'extrait Madagascar ;
- [x] Nominatim local, cache et confirmation des recherches ambiguës ;
- [x] OSRM local avec le préréglage `MAIN_ROADS` ;
- [x] carte Leaflet et sélection par clic.

### Phase 3 — Optimisation

- [x] matrice routière ;
- [x] capacités et paramètres effectifs des véhicules ;
- [x] objectif orienté zones et coût total ;
- [x] colis non affectés expliqués ;
- [x] affichage comparatif et carte colorée des tournées ;
- [x] heures de départ et créneaux horaires facultatifs.
- [x] optimisation multi-clients avec retraits multiples, précédences et flotte partagée.
- [x] comparaison des tournées directes et de la collecte/redistribution par un siège commun.

### Phase 4 — Version multi-utilisateur persistante

- [x] rôles `CLIENT`, `COURIER` et `ADMIN` dans les comptes et JWT ;
- [x] inscription client, profil et retrait par défaut ;
- [x] demandes, colis, quota journalier strict, statuts et événements persistants ;
- [x] réglages opérationnels, préréglages et profils livreurs administrables ;
- [x] dispatch `VIA_HUB` persistant avec snapshots et protections contre les doubles traitements ;
- [x] espace client et espace administrateur dédiés dans le frontend ;
- [x] compte rendu, reports différés à une date choisie, retours expéditeur et clôture auditée ;
- [x] rapports financiers généraux et par client en PDF/XLSX ;
- validation de bout en bout sur données de production représentatives.

### Phase 5 — Résultats, partage et mise en ligne

- [x] export JSON du résultat ;
- [x] exports CSV et PDF du résultat ;
- durcissement de la sécurité ;
- [x] exports opérationnels depuis les journées clôturées ;
- fournisseur de tuiles adapté au trafic réel ;
- déploiement en ligne et sauvegardes PostgreSQL.

### Plus tard

- profils routiers spécialisés ;
- espace connecté du livreur et suivi de l'exécution réelle ;
- permissions administratives plus fines ;
- mise à jour automatisée et contrôlée des données OSM.
