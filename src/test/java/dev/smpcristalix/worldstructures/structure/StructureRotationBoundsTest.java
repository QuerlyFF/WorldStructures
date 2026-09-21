package dev.smpcristalix.worldstructures.structure;

import org.bukkit.block.structure.StructureRotation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class StructureRotationBoundsTest {

    @Test
    void transformsEveryRotationAroundTheStructureOrigin() {
        assertArrayEquals(new int[]{4, 7},
                StructurePlacementService.transformOffset(4, 7, StructureRotation.NONE));
        assertArrayEquals(new int[]{-7, 4},
                StructurePlacementService.transformOffset(4, 7, StructureRotation.CLOCKWISE_90));
        assertArrayEquals(new int[]{-4, -7},
                StructurePlacementService.transformOffset(4, 7, StructureRotation.CLOCKWISE_180));
        assertArrayEquals(new int[]{7, -4},
                StructurePlacementService.transformOffset(4, 7, StructureRotation.COUNTERCLOCKWISE_90));
    }
}
