package fr.cartovin.cartobio;

import org.locationtech.jts.geom.Envelope;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.cartovin.geo.GeoJson;

@RestController
@RequestMapping("/api/cartobio")
public class CartobioController {

    private final FichierCartobio fichier;

    public CartobioController(FichierCartobio fichier) {
        this.fichier = fichier;
    }

    /** Progression de la préparation du fichier (téléchargement, indexation, prêt). */
    @GetMapping("/statut")
    public FichierCartobio.Statut statut() {
        return fichier.statut();
    }

    /**
     * @param bbox emprise "ouest,sud,est,nord" en WGS84 (longitudes / latitudes)
     */
    @GetMapping("/parcelles")
    public GeoJson.FeatureCollection parcelles(@RequestParam String bbox) {
        return fichier.dansEmprise(emprise(bbox));
    }

    static Envelope emprise(String bbox) {
        String[] v = bbox.split(",");
        if (v.length != 4) {
            throw new IllegalArgumentException("bbox attendue : ouest,sud,est,nord");
        }
        double ouest = Double.parseDouble(v[0]);
        double sud = Double.parseDouble(v[1]);
        double est = Double.parseDouble(v[2]);
        double nord = Double.parseDouble(v[3]);
        if (ouest >= est || sud >= nord) {
            throw new IllegalArgumentException("bbox invalide : " + bbox);
        }
        return new Envelope(ouest, est, sud, nord);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail requeteInvalide(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(FichierCartobio.PasPretException.class)
    ProblemDetail pasPret(FichierCartobio.PasPretException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
}
