package fr.cartovin.geo;

import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.Envelope;

/**
 * Conversion Lambert-93 (EPSG:2154) vers longitude / latitude (RGF93, assimilable à WGS84 à
 * l'échelle d'une parcelle). Formules IGN de la projection conique conforme, sans dépendance.
 */
public final class Lambert93 {

    private static final double E = 0.08181919104281579;   // excentricité de l'ellipsoïde GRS80
    private static final double N = 0.7256077650532670;    // exposant de la projection
    private static final double C = 11754255.426096;       // constante de la projection (m)
    private static final double XS = 700000.0;              // coordonnées du pôle
    private static final double YS = 12655612.049876;
    private static final double LON0 = Math.toRadians(3.0);  // méridien central
    private static final double TOLERANCE = 1e-11;

    /** Filtre JTS qui convertit les coordonnées d'une géométrie en place. */
    public static final CoordinateFilter VERS_WGS84 = c -> {
        double[] ll = versLonLat(c.x, c.y);
        c.x = ll[0];
        c.y = ll[1];
    };

    private Lambert93() {
    }

    /** @return {longitude, latitude} en degrés décimaux */
    public static double[] versLonLat(double x, double y) {
        double dx = x - XS;
        double dy = y - YS;
        double r = Math.hypot(dx, dy);
        double gamma = Math.atan2(dx, -dy);
        double lon = LON0 + gamma / N;
        double latIso = -Math.log(Math.abs(r / C)) / N;

        double lat = 2 * Math.atan(Math.exp(latIso)) - Math.PI / 2;
        double precedente;
        do {
            precedente = lat;
            double esin = E * Math.sin(lat);
            lat = 2 * Math.atan(Math.pow((1 + esin) / (1 - esin), E / 2) * Math.exp(latIso)) - Math.PI / 2;
        } while (Math.abs(lat - precedente) > TOLERANCE);

        return new double[] {Math.toDegrees(lon), Math.toDegrees(lat)};
    }

    /** @return {x, y} en mètres Lambert-93 */
    public static double[] depuisLonLat(double lonDeg, double latDeg) {
        double lat = Math.toRadians(latDeg);
        double esin = E * Math.sin(lat);
        double latIso = Math.log(Math.tan(Math.PI / 4 + lat / 2) * Math.pow((1 - esin) / (1 + esin), E / 2));
        double r = C * Math.exp(-N * latIso);
        double gamma = N * (Math.toRadians(lonDeg) - LON0);
        return new double[] {XS + r * Math.sin(gamma), YS - r * Math.cos(gamma)};
    }

    /**
     * Emprise Lambert-93 englobant une emprise WGS84. On projette les coins et les milieux des
     * côtés, car les bords d'un rectangle lon/lat deviennent courbes en Lambert.
     */
    public static Envelope depuisWgs84(Envelope e) {
        Envelope resultat = new Envelope();
        double midX = (e.getMinX() + e.getMaxX()) / 2;
        double midY = (e.getMinY() + e.getMaxY()) / 2;
        double[][] points = {
            {e.getMinX(), e.getMinY()}, {e.getMaxX(), e.getMinY()}, {e.getMinX(), e.getMaxY()},
            {e.getMaxX(), e.getMaxY()}, {midX, e.getMinY()}, {midX, e.getMaxY()},
            {e.getMinX(), midY}, {e.getMaxX(), midY},
        };
        for (double[] p : points) {
            double[] xy = depuisLonLat(p[0], p[1]);
            resultat.expandToInclude(xy[0], xy[1]);
        }
        return resultat;
    }
}
