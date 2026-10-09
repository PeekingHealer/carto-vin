import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  GeoJSONSource,
  LngLat,
  Map as MlMap,
  MapGeoJSONFeature,
  NavigationControl,
  Popup,
  ScaleControl,
  StyleSpecification,
} from 'maplibre-gl';
import { Subject, catchError, of, switchMap, tap } from 'rxjs';
import { Bbox, ParcelleBioProps, ParcellesBio } from '../../core/models/cartobio';
import { CartobioSource } from '../../core/sources/cartobio.source';

/** En dessous de ce zoom, on ne charge pas les parcelles (trop de volume). */
export const ZOOM_MIN_PARCELLES = 13;

const SOURCE_ID = 'parcelles-bio';
const VIDE: ParcellesBio = { type: 'FeatureCollection', features: [] };

/** Tuiles raster de la Géoplateforme IGN (WMTS, projection Web Mercator). */
const wmts = (layer: string, format: string) =>
  `https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=${layer}` +
  `&STYLE=normal&TILEMATRIXSET=PM&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&FORMAT=${format}`;

const STYLE: StyleSpecification = {
  version: 8,
  sources: {
    plan: {
      type: 'raster',
      tiles: [wmts('GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2', 'image/png')],
      tileSize: 256,
      maxzoom: 19,
      attribution: '© IGN – Géoplateforme',
    },
    ortho: {
      type: 'raster',
      tiles: [wmts('ORTHOIMAGERY.ORTHOPHOTOS', 'image/jpeg')],
      tileSize: 256,
      maxzoom: 19,
      attribution: '© IGN – Géoplateforme',
    },
  },
  layers: [
    { id: 'plan', type: 'raster', source: 'plan' },
    { id: 'ortho', type: 'raster', source: 'ortho', layout: { visibility: 'none' } },
  ],
};

export type EtatChargement = 'zoom-insuffisant' | 'chargement' | 'ok' | 'erreur';

@Component({
  selector: 'app-map',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div #carte class="carte"></div>`,
  styles: `
    :host { display: block; position: relative; }
    .carte { position: absolute; inset: 0; }
  `,
})
export class MapComponent {
  /** Passe à true quand le back a fini de préparer le fichier CartoBio. */
  readonly actif = input(false);
  /** Fond de carte affiché. */
  readonly fond = input<'plan' | 'ortho'>('plan');

  readonly etat = output<EtatChargement>();
  readonly nbParcelles = output<number>();

  private readonly cartobio = inject(CartobioSource);
  private readonly conteneur = viewChild.required<ElementRef<HTMLDivElement>>('carte');
  private readonly carte = signal<MlMap | null>(null);
  private readonly demandes = new Subject<Bbox | null>();
  private selection: string | number | undefined;

  constructor() {
    afterNextRender(() => this.creerCarte());
    inject(DestroyRef).onDestroy(() => this.carte()?.remove());

    // Une seule requête à la fois : un nouveau déplacement annule la précédente.
    this.demandes
      .pipe(
        switchMap((bbox) => {
          if (!bbox) {
            return of(VIDE);
          }
          this.etat.emit('chargement');
          return this.cartobio.parcelles(bbox).pipe(
            tap(() => this.etat.emit('ok')),
            catchError(() => {
              this.etat.emit('erreur');
              return of(VIDE);
            }),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((fc) => this.afficher(fc));

    effect(() => {
      this.actif();
      if (this.carte()) this.rafraichir();
    });

    effect(() => {
      const carte = this.carte();
      const fond = this.fond();
      if (!carte) return;
      carte.setLayoutProperty('plan', 'visibility', fond === 'plan' ? 'visible' : 'none');
      carte.setLayoutProperty('ortho', 'visibility', fond === 'ortho' ? 'visible' : 'none');
    });
  }

  private creerCarte(): void {
    const carte = new MlMap({
      container: this.conteneur().nativeElement,
      style: STYLE,
      center: [-0.35, 44.85], // Entre-deux-Mers
      zoom: ZOOM_MIN_PARCELLES,
    });
    carte.addControl(new NavigationControl(), 'top-right');
    carte.addControl(new ScaleControl({ unit: 'metric' }), 'bottom-left');

    carte.on('load', () => {
      carte.addSource(SOURCE_ID, { type: 'geojson', data: VIDE, generateId: true });
      carte.addLayer({
        id: 'parcelles-fond',
        type: 'fill',
        source: SOURCE_ID,
        paint: {
          'fill-color': [
            'match',
            ['get', 'convers'],
            'AB', '#2e7d32',
            'C1', '#9ccc65',
            'C2', '#7cb342',
            'C3', '#558b2f',
            '#8d6e63',
          ],
          'fill-opacity': ['case', ['boolean', ['feature-state', 'selection'], false], 0.75, 0.4],
        },
      });
      carte.addLayer({
        id: 'parcelles-contour',
        type: 'line',
        source: SOURCE_ID,
        paint: {
          'line-color': ['case', ['boolean', ['feature-state', 'selection'], false], '#f9a825', '#1b5e20'],
          'line-width': ['case', ['boolean', ['feature-state', 'selection'], false], 3, 1],
        },
      });

      carte.on('moveend', () => this.rafraichir());
      carte.on('click', 'parcelles-fond', (e) => {
        const f = e.features?.[0];
        if (f) this.selectionner(carte, f, e.lngLat);
      });
      carte.on('mouseenter', 'parcelles-fond', () => (carte.getCanvas().style.cursor = 'pointer'));
      carte.on('mouseleave', 'parcelles-fond', () => (carte.getCanvas().style.cursor = ''));

      this.carte.set(carte);
    });
  }

  private rafraichir(): void {
    const carte = this.carte();
    if (!carte) return;
    if (!this.actif()) {
      this.demandes.next(null);
      return;
    }
    if (carte.getZoom() < ZOOM_MIN_PARCELLES) {
      this.etat.emit('zoom-insuffisant');
      this.demandes.next(null);
      return;
    }
    const b = carte.getBounds();
    this.demandes.next([b.getWest(), b.getSouth(), b.getEast(), b.getNorth()]);
  }

  private afficher(fc: ParcellesBio): void {
    const carte = this.carte();
    if (!carte) return;
    this.selection = undefined;
    carte.getSource<GeoJSONSource>(SOURCE_ID)?.setData(fc);
    this.nbParcelles.emit(fc.features.length);
  }

  private selectionner(carte: MlMap, f: MapGeoJSONFeature, position: LngLat): void {
    if (this.selection !== undefined) {
      carte.setFeatureState({ source: SOURCE_ID, id: this.selection }, { selection: false });
    }
    this.selection = f.id;
    carte.setFeatureState({ source: SOURCE_ID, id: f.id }, { selection: true });

    new Popup({ maxWidth: '280px' }).setLngLat(position).setHTML(contenuPopup(f.properties as ParcelleBioProps)).addTo(carte);
  }
}

const LIBELLES_CONVERSION: Record<string, string> = {
  AB: 'Agriculture biologique',
  C1: 'Conversion — 1re année',
  C2: 'Conversion — 2e année',
  C3: 'Conversion — 3e année',
};

function echapper(v: unknown): string {
  return String(v ?? '—').replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}

function contenuPopup(p: ParcelleBioProps): string {
  const surface = typeof p.surface === 'number' ? `${p.surface.toFixed(2)} ha` : '—';
  return `
    <div class="popup-parcelle">
      <strong>${echapper(LIBELLES_CONVERSION[p.convers ?? ''] ?? p.convers)}</strong>
      <dl>
        <dt>Culture (code)</dt><dd>${echapper(p.culture)}</dd>
        <dt>Surface</dt><dd>${surface}</dd>
        <dt>Commune</dt><dd>${echapper(p.nomcomm)} (${echapper(p.commune)})</dd>
        <dt>Engagement bio</dt><dd>${echapper(p.dateng)}</dd>
        <dt>Année PAC</dt><dd>${echapper(p.annee)}</dd>
      </dl>
    </div>`;
}
