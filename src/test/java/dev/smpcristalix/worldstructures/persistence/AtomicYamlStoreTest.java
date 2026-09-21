package dev.smpcristalix.worldstructures.persistence;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicYamlStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicallyReplacesValidYaml() throws Exception {
        Path file = temporaryDirectory.resolve("state.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("value", 42);

        assertTrue(AtomicYamlStore.save(file.toFile(), yaml, Logger.getAnonymousLogger()));
        YamlConfiguration loaded = AtomicYamlStore.load(file.toFile(), Logger.getAnonymousLogger());
        assertEquals(42, loaded.getInt("value"));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(1L, files.count());
        }
    }

    @Test
    void reportsCorruptYamlInsteadOfReturningEmptyState() throws Exception {
        Path file = temporaryDirectory.resolve("state.yml");
        String corrupt = "state: [unterminated\n";
        Files.writeString(file, corrupt, StandardCharsets.UTF_8);

        assertNull(AtomicYamlStore.load(file.toFile(), Logger.getAnonymousLogger()));
        assertEquals(corrupt, Files.readString(file, StandardCharsets.UTF_8));
    }
}
