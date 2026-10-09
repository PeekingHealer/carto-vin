package fr.cartovin.cartobio;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param datasetUrl    API data.gouv du jeu « Parcelles certifiées en Agriculture Biologique sur CartoBio »
 * @param motsCles      mots devant tous figurer dans le titre du fichier GeoPackage à utiliser
 * @param dossierCache  dossier où le fichier téléchargé est conservé entre deux démarrages
 * @param maxParcelles  nombre maximum de parcelles renvoyées par requête
 */
@ConfigurationProperties("cartovin.cartobio")
public record CartobioProperties(String datasetUrl, List<String> motsCles, String dossierCache, int maxParcelles) {
}
