package fr.cartovin.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class Lambert93Test {

    @Test
    void origineDeLaProjection() {
        double[] ll = Lambert93.versLonLat(700_000, 6_600_000);
        assertThat(ll[0]).isCloseTo(3.0, within(1e-9));
        assertThat(ll[1]).isCloseTo(46.5, within(1e-9));
    }

    @Test
    void allerRetour() {
        double[] xy = Lambert93.depuisLonLat(-0.5812134, 44.8213921);
        double[] ll = Lambert93.versLonLat(xy[0], xy[1]);
        assertThat(ll[0]).isCloseTo(-0.5812134, within(1e-8));
        assertThat(ll[1]).isCloseTo(44.8213921, within(1e-8));
    }

    @Test
    void pointABordeaux() {
        double[] ll = Lambert93.versLonLat(417_000, 6_420_000);
        assertThat(ll[0]).isCloseTo(-0.5812, within(1e-4));
        assertThat(ll[1]).isCloseTo(44.8214, within(1e-4));
    }
}
