package fr.cartovin.geo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accès en lecture à un fichier GeoPackage (format OGC, base SQLite) via son index spatial R-tree.
 * <p>
 * Le fichier n'est jamais chargé en mémoire : chaque recherche lit uniquement les entités dont
 * l'emprise croise celle demandée. Si le fichier ne contient pas d'index spatial, il est créé une
 * fois à l'ouverture.
 */
public final class GeoPackage {

    private static final Logger LOG = LoggerFactory.getLogger(GeoPackage.class);

    /** Taille de l'enveloppe optionnelle selon l'indicateur codé sur les bits 1 à 3 du drapeau. */
    private static final int[] TAILLES_ENVELOPPE = {0, 32, 48, 48, 64};

    private final String url;
    private final String table;
    private final String colonneGeom;
    private final String clePrimaire;
    private final String tableIndex;
    private final Projection projection;

    private GeoPackage(Path fichier, String table, String colonneGeom, String clePrimaire, Projection projection) {
        this.url = "jdbc:sqlite:" + fichier.toAbsolutePath();
        this.table = table;
        this.colonneGeom = colonneGeom;
        this.clePrimaire = clePrimaire;
        this.tableIndex = "rtree_" + table + "_" + colonneGeom;
        this.projection = projection;
    }

    /** Ouvre le fichier, lit ses métadonnées et crée l'index spatial s'il manque. */
    public static GeoPackage ouvrir(Path fichier) {
        String url = "jdbc:sqlite:" + fichier.toAbsolutePath();
        try (Connection cnx = DriverManager.getConnection(url); Statement st = cnx.createStatement()) {
            String table;
            String colonneGeom;
            int srs;
            try (ResultSet rs = st.executeQuery(
                    "SELECT table_name, column_name, srs_id FROM gpkg_geometry_columns LIMIT 1")) {
                if (!rs.next()) {
                    throw new IllegalStateException("Aucune table de géométries dans " + fichier);
                }
                table = rs.getString(1);
                colonneGeom = rs.getString(2);
                srs = rs.getInt(3);
            }
            String clePrimaire = "fid";
            try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + id(table) + ")")) {
                while (rs.next()) {
                    if (rs.getInt("pk") == 1) {
                        clePrimaire = rs.getString("name");
                    }
                }
            }
            GeoPackage gpkg = new GeoPackage(fichier, table, colonneGeom, clePrimaire, Projection.depuisSrs(srs));
            LOG.info("GeoPackage {} : table {}, géométrie {}, EPSG:{}", fichier.getFileName(), table, colonneGeom, srs);
            gpkg.creerIndexSiAbsent(cnx);
            return gpkg;
        } catch (SQLException e) {
            throw new IllegalStateException("Ouverture GeoPackage impossible : " + fichier, e);
        }
    }

    /**
     * Parcourt les entités dont l'emprise croise {@code empriseWgs84}, géométries converties en WGS84.
     * Les noms d'attributs sont normalisés en minuscules.
     *
     * @return le nombre d'entités lues
     */
    public int rechercher(Envelope empriseWgs84, int limite, BiConsumer<Geometry, Map<String, Object>> consommateur) {
        Envelope e = projection.depuisWgs84(empriseWgs84);
        String sql = "SELECT t.* FROM " + id(table) + " t JOIN " + id(tableIndex) + " r ON t." + id(clePrimaire)
                + " = r.id WHERE r.maxx >= ? AND r.minx <= ? AND r.maxy >= ? AND r.miny <= ? LIMIT ?";
        int n = 0;
        try (Connection cnx = DriverManager.getConnection(url); PreparedStatement ps = cnx.prepareStatement(sql)) {
            ps.setDouble(1, e.getMinX());
            ps.setDouble(2, e.getMaxX());
            ps.setDouble(3, e.getMinY());
            ps.setDouble(4, e.getMaxY());
            ps.setInt(5, limite);
            WKBReader wkb = new WKBReader();
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    byte[] blob = rs.getBytes(colonneGeom);
                    Geometry geometrie = blob == null ? null : geometrie(blob, wkb);
                    if (geometrie == null || geometrie.isEmpty()) {
                        continue;
                    }
                    projection.versWgs84(geometrie);

                    Map<String, Object> attributs = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        String nom = meta.getColumnName(i);
                        if (!nom.equalsIgnoreCase(colonneGeom) && !nom.equalsIgnoreCase(clePrimaire)) {
                            attributs.put(nom.toLowerCase(Locale.ROOT), rs.getObject(i));
                        }
                    }
                    consommateur.accept(geometrie, attributs);
                    n++;
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Recherche GeoPackage impossible", ex);
        }
        return n;
    }

    private void creerIndexSiAbsent(Connection cnx) throws SQLException {
        try (PreparedStatement ps = cnx.prepareStatement("SELECT 1 FROM sqlite_master WHERE name = ?")) {
            ps.setString(1, tableIndex);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    LOG.info("Index spatial {} présent", tableIndex);
                    return;
                }
            }
        }
        LOG.info("Index spatial absent : création de {} (une seule fois)", tableIndex);
        long debut = System.currentTimeMillis();
        cnx.setAutoCommit(false);
        try (Statement st = cnx.createStatement()) {
            st.execute("CREATE VIRTUAL TABLE " + id(tableIndex) + " USING rtree(id, minx, maxx, miny, maxy)");
            WKBReader wkb = new WKBReader();
            try (ResultSet rs = st.executeQuery(
                    "SELECT " + id(clePrimaire) + ", " + id(colonneGeom) + " FROM " + id(table));
                 PreparedStatement insert = cnx.prepareStatement(
                         "INSERT INTO " + id(tableIndex) + " VALUES (?, ?, ?, ?, ?)")) {
                int n = 0;
                while (rs.next()) {
                    byte[] blob = rs.getBytes(2);
                    Envelope env = blob == null ? null : enveloppe(blob, wkb);
                    if (env == null) {
                        continue;
                    }
                    insert.setLong(1, rs.getLong(1));
                    insert.setDouble(2, env.getMinX());
                    insert.setDouble(3, env.getMaxX());
                    insert.setDouble(4, env.getMinY());
                    insert.setDouble(5, env.getMaxY());
                    insert.addBatch();
                    if (++n % 10_000 == 0) {
                        insert.executeBatch();
                    }
                }
                insert.executeBatch();
            }
            cnx.commit();
        } catch (SQLException e) {
            cnx.rollback();
            throw e;
        } finally {
            cnx.setAutoCommit(true);
        }
        LOG.info("Index spatial créé en {} s", (System.currentTimeMillis() - debut) / 1000);
    }

    private static String id(String identifiant) {
        return "\"" + identifiant.replace("\"", "\"\"") + "\"";
    }

    /** Décode un blob géométrie GeoPackage : en-tête "GP" + enveloppe optionnelle + WKB standard. */
    static Geometry geometrie(byte[] blob, WKBReader wkb) {
        verifierEnTete(blob);
        if (estVide(blob)) {
            return null;
        }
        try {
            return wkb.read(Arrays.copyOfRange(blob, debutWkb(blob), blob.length));
        } catch (ParseException e) {
            throw new IllegalArgumentException("WKB invalide", e);
        }
    }

    /** Emprise d'un blob : lue dans l'en-tête si elle y figure, sinon calculée depuis la géométrie. */
    static Envelope enveloppe(byte[] blob, WKBReader wkb) {
        verifierEnTete(blob);
        if (estVide(blob)) {
            return null;
        }
        if (indicateurEnveloppe(blob) > 0) {
            ByteOrder ordre = (blob[3] & 1) == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
            ByteBuffer b = ByteBuffer.wrap(blob, 8, 32).order(ordre);
            double minX = b.getDouble();
            double maxX = b.getDouble();
            double minY = b.getDouble();
            double maxY = b.getDouble();
            return new Envelope(minX, maxX, minY, maxY);
        }
        Geometry g = geometrie(blob, wkb);
        return g == null ? null : g.getEnvelopeInternal();
    }

    private static void verifierEnTete(byte[] blob) {
        if (blob.length < 8 || blob[0] != 'G' || blob[1] != 'P') {
            throw new IllegalArgumentException("Blob GeoPackage invalide");
        }
        if (indicateurEnveloppe(blob) >= TAILLES_ENVELOPPE.length) {
            throw new IllegalArgumentException("Indicateur d'enveloppe inconnu : " + indicateurEnveloppe(blob));
        }
    }

    private static boolean estVide(byte[] blob) {
        return (blob[3] & 0b1_0000) != 0;
    }

    private static int indicateurEnveloppe(byte[] blob) {
        return (blob[3] >> 1) & 0b111;
    }

    private static int debutWkb(byte[] blob) {
        return 8 + TAILLES_ENVELOPPE[indicateurEnveloppe(blob)];
    }

    /** Projections présentes dans les fichiers CartoBio de métropole. */
    private enum Projection {
        WGS84, LAMBERT93;

        static Projection depuisSrs(int srs) {
            return switch (srs) {
                case 4326 -> WGS84;
                case 2154 -> LAMBERT93;
                default -> throw new IllegalStateException(
                        "Projection EPSG:" + srs + " non gérée (seules 4326 et 2154 le sont pour l'instant)");
            };
        }

        void versWgs84(Geometry g) {
            if (this == LAMBERT93) {
                g.apply(Lambert93.VERS_WGS84);
                g.geometryChanged();
            }
        }

        Envelope depuisWgs84(Envelope e) {
            return this == LAMBERT93 ? Lambert93.depuisWgs84(e) : e;
        }
    }
}
