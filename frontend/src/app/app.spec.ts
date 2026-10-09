import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('interroge le statut CartoBio au démarrage', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    await new Promise((r) => setTimeout(r, 10)); // premier tick du timer de suivi
    http.expectOne('/api/cartobio/statut').flush({ etat: 'PRET', titre: 'France hexagonale 2025', octetsRecus: 0, octetsTotal: null, message: null });
    expect(fixture.componentInstance).toBeTruthy();
  });
});
