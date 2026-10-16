package dev.omamobile.host;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Surface lifecycle geometry must exclude Android's transient zero-size callbacks. */
public class CompositorControllerGeometryTest {
    @Test public void acceptsOnlyPositiveSurfaceGeometry() {
        assertFalse(CompositorController.validSurfaceSize(0, 720));
        assertFalse(CompositorController.validSurfaceSize(720, 0));
        assertFalse(CompositorController.validSurfaceSize(-1, 720));
        assertTrue(CompositorController.validSurfaceSize(720, 1280));
    }
}
