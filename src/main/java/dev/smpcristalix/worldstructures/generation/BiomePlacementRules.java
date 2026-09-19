package dev.smpcristalix.worldstructures.generation;

import org.bukkit.World;

import java.util.Locale;
import java.util.Set;

/**
 * Общие проверки биома и высоты для естественной генерации структур.
 * Биомы храним строками, чтобы конфиг было проще расширять без жёсткой привязки к enum-константам API.
 */
final class BiomePlacementRules {

    private BiomePlacementRules() {
    }

    static boolean matchesBiome(World world, int x, int y, int z, Set<String> allowedBiomes) {
        if (allowedBiomes.isEmpty()) return true;
        String biome = world.getBiome(x, y, z).getKey().getKey().toLowerCase(Locale.ROOT);
        return allowedBiomes.contains(biome);
    }

    static boolean matchesHeight(int y, int minY, int maxY) {
        return y >= minY && y <= maxY;
    }
}
