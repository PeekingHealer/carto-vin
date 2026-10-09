import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, of, switchMap, takeWhile, timer } from 'rxjs';
import { StatutCartobio } from './core/models/cartobio';
import { CartobioSource } from './core/sources/cartobio.source';
import { EtatChargement, MapComponent, ZOOM_MIN_PARCELLES } from './features/map/map.component';

const INJOIGNABLE: StatutCartobio = {
  etat: 'ERREUR',
  titre: null,
  octetsRecus: 0,
  octetsTotal: null,
  message: 'Back-end injoignable : lancez le serveur Java sur le port 8080.',
};

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MapComponent],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly cartobio = inject(CartobioSource);

  /** Interroge le back toutes les 2 s jusqu'à ce que le fichier soit prêt (ou en erreur). */
  protected readonly statut = toSignal(
    timer(0, 2000).pipe(
      switchMap(() => this.cartobio.statut().pipe(catchError(() => of(INJOIGNABLE)))),
      takeWhile((s) => s.etat !== 'PRET' && s.etat !== 'ERREUR', true),
    ),
    { initialValue: null },
  );
  protected readonly pret = computed(() => this.statut()?.etat === 'PRET');

  protected readonly fond = signal<'plan' | 'ortho'>('plan');
  protected readonly etat = signal<EtatChargement>('ok');
  protected readonly nb = signal(0);

  protected readonly enErreur = computed(() => this.statut()?.etat === 'ERREUR' || this.etat() === 'erreur');

  protected readonly message = computed(() => {
    const s = this.statut();
    if (!s) return 'Connexion au serveur…';
    switch (s.etat) {
      case 'RECHERCHE':
        return 'Recherche du fichier CartoBio sur data.gouv…';
      case 'TELECHARGEMENT': {
        const pct = s.octetsTotal ? ` ${Math.floor((100 * s.octetsRecus) / s.octetsTotal)} %` : '';
        return `Téléchargement initial du fichier CartoBio${pct} (${(s.octetsRecus / 1_048_576).toFixed(0)} Mo)…`;
      }
      case 'INDEXATION':
        return 'Préparation de l’index spatial (une seule fois)…';
      case 'ERREUR':
        return s.message ?? 'Erreur côté serveur, voir les logs du back-end.';
    }
    switch (this.etat()) {
      case 'zoom-insuffisant':
        return `Zoomez davantage (niveau ${ZOOM_MIN_PARCELLES} minimum) pour voir les parcelles.`;
      case 'chargement':
        return 'Chargement des parcelles…';
      case 'erreur':
        return 'Erreur lors du chargement des parcelles.';
      default:
        return `${this.nb()} parcelle(s) bio dans la vue.`;
    }
  });
}
