package fr.cartovin.geo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTReader;

class GeoPackageTest {

    private final GeometryFactory gf = new GeometryFactory();

    @Test
    void decodeUnBlobAvecEnveloppe() throws Exception {
        Geometry carre = new WKTReader(gf).read("POLYGON ((0 0, 1 0, 1 1, 0 1, 0 0))");
        byte[] wkb = new WKBWriter().write(carre);

        // En-tête GeoPackage : "GP", version 0, drapeaux (little endian + enveloppe xy = 32 octets), srs 2154
        ByteBuffer blob = ByteBuffer.allocate(8 + 32 + wkb.length).order(ByteOrder.LITTLE_ENDIAN);
        blob.put((byte) 'G').put((byte) 'P').put((byte) 0).put((byte) 0b0000_0011).putInt(2154);
        blob.putDouble(0).putDouble(1).putDouble(0).putDouble(1);
        blob.put(wkb);

        Geometry lue = GeoPackage.geometrie(blob.array(), new WKBReader(gf));

        assertThat(lue.equalsExact(carre)).isTrue();
        assertThat(GeoPackage.enveloppe(blob.array(), new WKBReader(gf)))
                .isEqualTo(new org.locationtech.jts.geom.Envelope(0, 1, 0, 1));
    }

    @Test
    void geometrieVideRenvoieNull() {
        byte[] blob = {'G', 'P', 0, 0b0001_0001, 0, 0, 0, 0};
        assertThat(GeoPackage.geometrie(blob, new WKBReader(gf))).isNull();
    }
}
