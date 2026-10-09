package fr.cartovin.cartobio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import org.locationtech.jts.geom.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import fr.cartovin.geo.GeoJson;
import fr.cartovin.geo.GeoPackage;

/**
 * Prépare l'unique fichier CartoBio utilisé par l'application, puis répond aux requêtes par emprise.
 * <p>
 * Au démarrage (en tâche de fond) : recherche du fichier sur data.gouv, téléchargement s'il n'est
 * pas déjà dans le dossier de cache, ouverture et vérification de l'index spatial. Les requêtes
 * lisent ensuite directement le fichier : rien n'est chargé en mémoire.
 */
@Service
public class FichierCartobio {

    private static final Logger LOG = LoggerFactory.getLogger(FichierCartobio.class);

    public enum Etat { RECHERCHE, TELECHARGEMENT, INDEXATION, PRET, ERREUR }

    /** État exposé au front pour afficher la progression du premier démarrage. */
    public record Statut(Etat etat, String titre, long octetsRecus, Long octetsTotal, String message) {
    }

    private final CartobioProperties props;
    private final HttpClient http = HttpClient.newBuilder()
            .proxy(ProxySelector.getDefault()) // respecte -Dhttps.proxyHost si un proxy est configuré
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private volatile Etat etat = Etat.RECHERCHE;
    private volatile String titre;
    private volatile Long octetsTotal;
    private volatile String erreur;
    private final AtomicLong octetsRecus = new AtomicLong();
    private volatile GeoPackage gpkg;

    public FichierCartobio(CartobioProperties props) {
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void preparerEnTacheDeFond() {
        Thread.ofVirtual().name("preparation-cartobio").start(this::preparer);
    }

    public Statut statut() {
        return new Statut(etat, titre, octetsRecus.get(), octetsTotal, erreur);
    }

    public GeoJson.FeatureCollection dansEmprise(Envelope empriseWgs84) {
        GeoPackage fichier = gpkg;
        if (fichier == null) {
            throw new PasPretException(statut());
        }
        List<GeoJson.Feature> features = new ArrayList<>();
        fichier.rechercher(empriseWgs84, props.maxParcelles(),
                (geometrie, attributs) -> features.add(GeoJson.Feature.of(geometrie, attributs)));
        return GeoJson.FeatureCollection.of(features);
    }

    private void preparer() {
        try {
            Ressource ressource = trouverRessource();
            titre = ressource.title();
            octetsTotal = ressource.filesize();
            Path fichier = Path.of(props.dossierCache()).resolve(ressource.id() + ".gpkg");

            if (Files.exists(fichier) && (octetsTotal == null || Files.size(fichier) == octetsTotal)) {
                LOG.info("Fichier CartoBio déjà en cache : {}", fichier);
                octetsRecus.set(Files.size(fichier));
            } else {
                telecharger(ressource.url(), fichier);
            }

            etat = Etat.INDEXATION;
            gpkg = GeoPackage.ouvrir(fichier);
            etat = Etat.PRET;
            LOG.info("CartoBio prêt : « {} »", titre);
        } catch (Exception e) {
            LOG.error("Préparation du fichier CartoBio impossible", e);
            erreur = e.getMessage();
            etat = Etat.ERREUR;
        }
    }

    private Ressource trouverRessource() {
        RestClient client = RestClient.builder().requestFactory(new JdkClientHttpRequestFactory(http)).build();
        Dataset dataset = client.get().uri(props.datasetUrl()).retrieve().body(Dataset.class);
        List<Ressource> gpkg = dataset == null ? List.of() : dataset.resources().stream()
                .filter(r -> "gpkg".equalsIgnoreCase(r.format()))
                .toList();
        List<String> motsCles = props.motsCles().stream().map(FichierCartobio::normaliser).toList();
        return gpkg.stream()
                .filter(r -> motsCles.stream().allMatch(normaliser(r.title())::contains))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Aucun fichier GeoPackage dont le titre contient "
                        + props.motsCles() + ". Titres disponibles : " + gpkg.stream().map(Ressource::title).toList()));
    }

    private void telecharger(String url, Path cible) throws IOException, InterruptedException {
        etat = Etat.TELECHARGEMENT;
        Files.createDirectories(cible.getParent());
        Path partiel = cible.resolveSibling(cible.getFileName() + ".part");
        LOG.info("Téléchargement de « {} » ({} octets) depuis {}", titre, octetsTotal, url);
        long debut = System.currentTimeMillis();

        HttpResponse<InputStream> reponse = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (reponse.statusCode() != 200) {
            throw new IOException("data.gouv a répondu " + reponse.statusCode() + " pour " + url);
        }
        try (InputStream in = reponse.body(); OutputStream out = Files.newOutputStream(partiel)) {
            byte[] tampon = new byte[1 << 16];
            int lus;
            while ((lus = in.read(tampon)) != -1) {
                out.write(tampon, 0, lus);
                octetsRecus.addAndGet(lus);
            }
        }
        Files.move(partiel, cible, StandardCopyOption.REPLACE_EXISTING);
        LOG.info("Téléchargement terminé en {} s", (System.currentTimeMillis() - debut) / 1000);
    }

    private static String normaliser(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    public static class PasPretException extends RuntimeException {
        private final transient Statut statut;

        PasPretException(Statut statut) {
            super("Le fichier CartoBio n'est pas encore prêt (" + statut.etat() + ")");
            this.statut = statut;
        }

        public Statut statut() {
            return statut;
        }
    }

    /** Sous-ensemble de la réponse de l'API data.gouv qui nous intéresse. */
    record Dataset(List<Ressource> resources) {
    }

    record Ressource(String id, String title, String format, String url, Long filesize) {
    }
}
