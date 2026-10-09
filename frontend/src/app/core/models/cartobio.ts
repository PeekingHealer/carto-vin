import type { FeatureCollection, Polygon, MultiPolygon } from 'geojson';

/** Avancement de la préparation du fichier CartoBio côté serveur. */
export interface StatutCartobio {
  etat: 'RECHERCHE' | 'TELECHARGEMENT' | 'INDEXATION' | 'PRET' | 'ERREUR';
  titre: string | null;
  octetsRecus: number;
  octetsTotal: number | null;
  message: string | null;
}

/** Attributs d'une parcelle bio tels que publiés par l'Agence Bio (noms en minuscules). */
export interface ParcelleBioProps {
  annee?: number;
  culture?: string;
  convers?: 'AB' | 'C1' | 'C2' | 'C3' | string;
  dateng?: string | null;
  surface?: number;
  commune?: string;
  nomcomm?: string;
  dept?: string;
}

export type ParcellesBio = FeatureCollection<Polygon | MultiPolygon, ParcelleBioProps>;

/** Emprise de la carte : [ouest, sud, est, nord] en WGS84. */
export type Bbox = [number, number, number, number];
