# Importer les colis d’une demande

Dans une nouvelle demande normale ou express, l’action **Importer un fichier CSV** de la section Colis permet de télécharger le modèle complet puis d’ajouter les lignes à la demande. Cette action est disponible pour le client et pour l’administrateur qui crée une demande en son nom.

Modèle : [client-packages-template.csv](client-packages-template.csv). Ce modèle correspond aux demandes actuelles ; `packages-template.csv` reste réservé aux anciens outils de planification.

Le retrait et la date sont communs à la demande et se renseignent dans le formulaire. Le fichier contient une ligne par colis, avec tous les champs du formulaire :

| Colonne | Contenu |
| --- | --- |
| `recipient_name` | Nom du destinataire, obligatoire, 200 caractères maximum. |
| `destination_label` | Repère court affiché dans les listes et rapports. Si vide, le début du lieu recherché est utilisé. |
| `destination_query` | Lieu textuel à rechercher, par exemple `Ankorondrano, Antananarivo`. Obligatoire sans coordonnées. |
| `latitude`, `longitude` | Coordonnées facultatives, à renseigner ensemble. Prioritaires sur la recherche textuelle. |
| `contact` | Contact du destinataire, obligatoire, conservé comme texte pour garder les zéros initiaux et le `+261`. |
| `amount_to_collect_ariary` | Montant entier positif ou nul à récupérer, frais de livraison inclus. Obligatoire, même s’il vaut 0. |
| `faritra` | `oui`/`non`, `true`/`false` ou `1`/`0`. Vide = non. |
| `mobile_money_paid` | Même format de booléen. Information uniquement. Vide = non. |
| `weight_kg` | Poids facultatif, point ou virgule décimale. Vide = poids par défaut du compte/réglage. |
| `desired_delivery_time` | Début du créneau facultatif au format `HH:mm`. La fin est une heure après. Pour un express, cette heure doit être postérieure à maintenant à Antananarivo. |

Les colonnes facultatives peuvent être omises. Les en-têtes restent ceux du modèle ; leur ordre est libre. Les fichiers sont limités à 10 Mo et la demande complète à 500 colis. Utiliser CSV UTF-8, avec ou sans BOM, et le séparateur point-virgule (recommandé) ou virgule. Les cellules contenant le séparateur, des guillemets ou plusieurs lignes doivent être entourées de guillemets ; un guillemet dans une cellule s’écrit `""`. Dans un tableur, conserver la colonne contact au format texte avant l’export CSV.

Toutes les lignes sont vérifiées avant les recherches. Une erreur de structure ou de valeur empêche l’import complet et indique la ligne à corriger. Pour un lieu textuel, le premier résultat dans la zone desservie est retenu, en tenant compte de la grille normale/express et, côté administrateur, du client choisi. Le tarif utilise également le poids et le créneau.

Après import, vérifier les destinataires, les repères, les positions et les montants dans la liste, puis modifier chaque colis si nécessaire. Aucune demande n’est enregistrée pendant l’import. Un lieu introuvable ou un tarif indisponible ne supprime pas la ligne : le colis reste dans le formulaire avec un avertissement. Il faut corriger son lieu/tarif, ou retirer le colis, avant de pouvoir envoyer la demande.
