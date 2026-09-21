package dev.smpcristalix.worldstructures.persistence;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Crash-safe YAML loading and replacement shared by every runtime state file. */
public final class AtomicYamlStore {

    private AtomicYamlStore() {}

    /**
     * Returns {@code null} for an unreadable/corrupt file. Callers must then keep
     * the in-memory state and refuse to overwrite the only recoverable copy.
     */
    public static YamlConfiguration load(File file, Logger logger) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (!file.isFile()) return yaml;
        try {
            yaml.load(file);
            return yaml;
        } catch (IOException | InvalidConfigurationException exception) {
            logger.log(Level.SEVERE,
                    "Не удалось безопасно прочитать " + file.getName()
                            + "; файл не будет перезаписан до исправления/перезапуска.",
                    exception);
            return null;
        }
    }

    public static boolean save(File file, YamlConfiguration yaml, Logger logger) {
        Path target = file.toPath().toAbsolutePath();
        Path parent = target.getParent();
        Path temporary = null;
        try {
            if (parent != null) Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, file.getName() + ".", ".tmp");
            Files.writeString(
                    temporary,
                    yaml.saveToString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Не удалось атомарно сохранить " + file.getName(), exception);
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best effort cleanup; the valid target is never deleted here.
                }
            }
        }
    }
}
