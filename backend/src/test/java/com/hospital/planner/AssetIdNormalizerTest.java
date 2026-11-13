package com.hospital.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hospital.planner.service.AssetIdNormalizer;
import org.junit.jupiter.api.Test;

class AssetIdNormalizerTest {
    @Test
    void inconsistentIdentifiersCollapseToOneCanonicalTag() {
        for (String raw : new String[]{"VENT-0001", "vent 1", "Vent_001", " vent-1 ", "VENT0001", "vent-00001"}) {
            assertEquals("VENT-0001", AssetIdNormalizer.normalize(raw), raw);
        }
    }

    @Test
    void nonStandardFallsBackToFlatUpperCase() {
        assertEquals("OLDVENTA", AssetIdNormalizer.normalize("old-vent-a"));
    }

    @Test
    void blankIsNull() {
        assertNull(AssetIdNormalizer.normalize("  --  "));
        assertNull(AssetIdNormalizer.normalize(null));
    }
}
