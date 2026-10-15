package dev.omamobile.host;

import org.junit.Test;
import static org.junit.Assert.*;

public class GraphicalConfigTest {
    @Test public void exactPreviousSeedsGainStockDisplayShortcutOnce() {
        for (boolean phone : new boolean[]{true, false}) {
            String old = GraphicalSession.previousSeedConfig(phone);
            String migrated = GraphicalSession.migrateDisplayShortcut(old, phone);
            assertTrue(migrated.startsWith(old));
            assertTrue(migrated.contains("hl.bind(\"SUPER + CTRL + D\", hl.dsp.exec_cmd(\"omarchy-shell shell toggle omarchy.monitor\"))"));
            assertEquals(migrated, GraphicalSession.migrateDisplayShortcut(migrated, phone));
        }
    }

    @Test public void previousDesktopCursorMigrationAlsoMatchesExactly() {
        String cursor = "hl.config({ cursor = { no_hardware_cursors = 1 } })\n";
        String old = GraphicalSession.previousSeedConfig(false).replace(cursor, "") + "\n" + cursor;
        assertTrue(GraphicalSession.migrateDisplayShortcut(old, false).contains("SUPER + CTRL + D"));
    }

    @Test public void userChangesAndExistingBindingsArePreserved() {
        for (boolean phone : new boolean[]{true, false}) {
            String old = GraphicalSession.previousSeedConfig(phone);
            for (String custom : new String[]{old + "-- My settings\n", old.replace("gaps_in = 5", "gaps_in = 9"),
                    old + "hl.bind(\"SUPER + CTRL + D\", hl.dsp.exec_cmd(\"my-display-tool\"))\n"}) {
                assertEquals(custom, GraphicalSession.migrateDisplayShortcut(custom, phone));
            }
        }
    }
}
