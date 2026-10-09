package fr.cartovin.geo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

/**
 * Modèle GeoJSON (RFC 7946) en records : sérialisé tel quel par Spring, sans dépendance
 * à une version particulière de Jackson.
 */
public final class GeoJson {

    private GeoJson() {
    }

    public record FeatureCollection(String type, List<Feature> features) {
        public static FeatureCollection of(List<Feature> features) {
            return new FeatureCollection("FeatureCollection", features);
        }
    }

    public record Feature(String type, Map<String, Object> properties, Geometrie geometry) {
        public static Feature of(Geometry geometrie, Map<String, Object> proprietes) {
            return new Feature("Feature", proprietes, Geometrie.of(geometrie));
        }
    }

    public record Geometrie(String type, Object coordinates) {

        static Geometrie of(Geometry g) {
            return switch (g) {
                case Polygon p -> new Geometrie("Polygon", anneaux(p));
                case MultiPolygon mp -> {
                    List<List<List<double[]>>> polygones = new ArrayList<>();
                    for (int i = 0; i < mp.getNumGeometries(); i++) {
                        polygones.add(anneaux((Polygon) mp.getGeometryN(i)));
                    }
                    yield new Geometrie("MultiPolygon", polygones);
                }
                default -> throw new IllegalArgumentException("Géométrie non gérée : " + g.getGeometryType());
            };
        }

        private static List<List<double[]>> anneaux(Polygon p) {
            List<List<double[]>> anneaux = new ArrayList<>();
            anneaux.add(positions(p.getExteriorRing()));
            for (int i = 0; i < p.getNumInteriorRing(); i++) {
                anneaux.add(positions(p.getInteriorRingN(i)));
            }
            return anneaux;
        }

        /** Arrondi à 6 décimales (~10 cm) pour alléger les réponses. */
        private static List<double[]> positions(LineString ligne) {
            List<double[]> positions = new ArrayList<>(ligne.getNumPoints());
            for (Coordinate c : ligne.getCoordinates()) {
                positions.add(new double[] {arrondi(c.x), arrondi(c.y)});
            }
            return positions;
        }

        private static double arrondi(double v) {
            return Math.round(v * 1e6) / 1e6;
        }
    }
}
