# Carto Vin

Carte des parcelles viticoles et de leurs pratiques, construite uniquement sur des données ouvertes.
Aucune base de données : le back-end relaie et met en forme, le front affiche.

```
carto-vin/
├── backend/    Spring Boot 4.1 · Java 25 — API sans état
└── frontend/   Angular 22 · MapLibre GL 6 — carte interactive
```

## Prérequis

| Outil   | Version                     |
|---------|-----------------------------|
| JDK     | 25 (LTS)                    |
| Maven   | 3.9+                        |
| Node.js | 22.22.3+, 24.15+ ou 26+ (exigé par Angular CLI 22) |

## Lancer en local

```bash
# Terminal 1 — back-end sur http://localhost:8080
cd backend
mvn spring-boot:run

# Terminal 2 — front sur http://localhost:4200
cd frontend
npm install
npm start
```

En développement, `frontend/proxy.conf.json` redirige `/api` vers le port 8080 : pas de CORS à configurer.

## Fonctionnement de l'étape 1 (CartoBio)

1. Au démarrage, le back cherche sur data.gouv le GeoPackage du jeu « Parcelles certifiées en
   Agriculture Biologique sur CartoBio » dont le titre contient les mots de
   `cartovin.cartobio.mots-cles` (par défaut : *hexagonale* et *2025*).
2. Il le télécharge une seule fois dans `~/.cache/carto-vin` (supprimer le fichier force un
   nouveau téléchargement), puis vérifie son index spatial et le crée s'il manque.
3. Le front suit la progression via `GET /api/cartobio/statut`.
4. Une fois prêt, le front demande `GET /api/cartobio/parcelles?bbox=ouest,sud,est,nord` à chaque
   déplacement (zoom 13 minimum). Le back interroge l'index spatial du fichier, convertit les
   géométries Lambert-93 en WGS84 et renvoie du GeoJSON. Rien n'est chargé en mémoire.
5. Les parcelles sont colorées par niveau de conversion (AB, C1, C2, C3) ; un clic ouvre une
   fiche et met la parcelle en surbrillance.

Fonds de carte : Plan IGN et photographies aériennes, servis par la Géoplateforme IGN (WMTS).

## Architecture du back-end

Un seul module, découpé par source de données :

```
fr.cartovin
├── CartoVinApplication
├── cartobio/
│   ├── CartobioController      endpoints /api/cartobio/statut et /parcelles
│   ├── FichierCartobio         recherche, téléchargement et interrogation du fichier
│   └── CartobioProperties      configuration (application.yml)
└── geo/
    ├── GeoPackage              lecture par index spatial des fichiers .gpkg (SQLite + WKB)
    ├── Lambert93               conversion EPSG:2154 → WGS84
    └── GeoJson                 modèle de réponse (records)
```

Chaque nouvelle source (E-Phy, PhytAtmo, Hub'Eau…) viendra dans son propre package, sur le même modèle.

## Limites connues

- Les données CartoBio sont anonymisées : on sait qu'une parcelle est bio, pas à qui elle appartient.
- Les fichiers des DROM utilisent d'autres projections (EPSG:5490, 2972, 2975, 4471) qui ne sont pas encore gérées.
- Le tout premier démarrage dépend du temps de téléchargement du fichier depuis data.gouv.
