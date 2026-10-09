import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Bbox, ParcellesBio, StatutCartobio } from '../models/cartobio';

/** Accès aux données CartoBio, via le back-end (qui lit le fichier publié sur data.gouv). */
@Injectable({ providedIn: 'root' })
export class CartobioSource {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/cartobio';

  statut(): Observable<StatutCartobio> {
    return this.http.get<StatutCartobio>(`${this.baseUrl}/statut`);
  }

  parcelles(bbox: Bbox): Observable<ParcellesBio> {
    const params = new HttpParams().set('bbox', bbox.map((v) => v.toFixed(6)).join(','));
    return this.http.get<ParcellesBio>(`${this.baseUrl}/parcelles`, { params });
  }
}
