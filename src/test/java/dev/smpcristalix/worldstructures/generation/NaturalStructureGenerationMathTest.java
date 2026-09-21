package dev.smpcristalix.worldstructures.generation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NaturalStructureGenerationMathTest {

    @Test
    void negativeChunksUseFloorDivRegions() {
        assertEquals(0, NaturalStructureGenerationListener.regionCoordinate(0, 24));
        assertEquals(-1, NaturalStructureGenerationListener.regionCoordinate(-1, 24));
        assertEquals(-1, NaturalStructureGenerationListener.regionCoordinate(-24, 24));
        assertEquals(-2, NaturalStructureGenerationListener.regionCoordinate(-25, 24));
    }

    @Test
    void marginsKeepAdjacentRegionCandidatesSeparated() {
        int leftMaximum = NaturalStructureGenerationListener.candidateChunk(-1, 24, 10, 3);
        int rightMinimum = NaturalStructureGenerationListener.candidateChunk(0, 24, 10, 0);
        assertEquals(-11, leftMaximum);
        assertEquals(10, rightMinimum);
        assertEquals(21, rightMinimum - leftMaximum);
    }
}
