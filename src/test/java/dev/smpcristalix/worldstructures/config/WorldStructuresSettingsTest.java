package dev.smpcristalix.worldstructures.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldStructuresSettingsTest {

    @Test
    void defaultConfigLoadsWithoutChangingApprovedBalance() throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream("config.yml");
        assertTrue(stream != null);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));

        WorldStructuresSettings settings = WorldStructuresSettings.from(yaml);
        assertEquals(8, settings.structures().size());
        assertEquals(5.0, settings.boss().healthMultiplier());
        assertEquals(3.0, settings.boss().damageMultiplier());
        assertEquals(0.05, settings.boss().shardDropChance());
    }

    @Test
    void rejectsNonFiniteCombatAndGenerationValues() {
        YamlConfiguration combat = new YamlConfiguration();
        combat.set("general.enabled", false);
        combat.set("structure-mobs.elite-chance", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> WorldStructuresSettings.from(combat));

        YamlConfiguration generation = new YamlConfiguration();
        generation.set("general.enabled", false);
        generation.set("generation.spawn-chance-per-region", Double.POSITIVE_INFINITY);
        assertThrows(IllegalArgumentException.class, () -> WorldStructuresSettings.from(generation));

        YamlConfiguration runtime = new YamlConfiguration();
        runtime.set("general.enabled", false);
        runtime.set("runtime.boss-leash-radius", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> WorldStructuresSettings.from(runtime));
    }
}
