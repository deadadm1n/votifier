package com.uberswe.votifier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VotifierConfigTest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Test
    void loadCreatesDefaults(@TempDir Path tempDir) throws Exception {
        VotifierConfig config = VotifierConfig.load(tempDir);

        assertEquals("0.0.0.0", config.getHost());
        assertEquals(8192, config.getPort());
        assertNotNull(config.getToken());
        assertFalse(config.getToken().isEmpty());
        assertTrue(config.getVotifierV1().isEnabled());
        assertTrue(config.getNuVotifierV2().isEnabled());
        assertEquals(1, config.getVotePointsPerVote());
        assertNotNull(config.getVoteCommands());
        assertFalse(config.getVoteCommands().isEmpty());
        assertNotNull(config.getApiVoteSources());
    }

    @Test
    void loadCreatesConfigFile(@TempDir Path tempDir) throws Exception {
        VotifierConfig.load(tempDir);
        assertTrue(Files.exists(tempDir.resolve("config.json")));
    }

    @Test
    void tokenIsValidUuid(@TempDir Path tempDir) throws Exception {
        VotifierConfig config = VotifierConfig.load(tempDir);
        assertDoesNotThrow(() -> UUID.fromString(config.getToken()));
    }

    @Test
    void defaultCommandContainsPlayerPlaceholder(@TempDir Path tempDir) throws Exception {
        VotifierConfig config = VotifierConfig.load(tempDir);
        assertTrue(config.getVoteCommands().stream().anyMatch(c -> c.contains("{player}")));
    }

    @Test
    void loadExistingConfig(@TempDir Path tempDir) throws Exception {
        // Write a custom config
        Path configFile = tempDir.resolve("config.json");
        String json = GSON.toJson(new ConfigData("192.168.1.1", 9999, "my-token", new String[]{"op {player}"}));
        Files.writeString(configFile, json);

        VotifierConfig config = VotifierConfig.load(tempDir);
        assertEquals("192.168.1.1", config.getHost());
        assertEquals(9999, config.getPort());
        assertEquals("my-token", config.getToken());
        assertEquals(1, config.getVoteCommands().size());
        assertEquals("op {player}", config.getVoteCommands().get(0));
    }

    @Test
    void roundtrip(@TempDir Path tempDir) throws Exception {
        VotifierConfig first = VotifierConfig.load(tempDir);
        String token = first.getToken();

        VotifierConfig second = VotifierConfig.load(tempDir);
        assertEquals(token, second.getToken());
        assertEquals(first.getHost(), second.getHost());
        assertEquals(first.getPort(), second.getPort());
    }

    @Test
    void saveOverwritesExisting(@TempDir Path tempDir) throws Exception {
        VotifierConfig config = VotifierConfig.load(tempDir);
        String originalContent = Files.readString(tempDir.resolve("config.json"));

        // Save again (content should remain valid JSON)
        config.save();
        String newContent = Files.readString(tempDir.resolve("config.json"));
        assertFalse(newContent.isEmpty());
        assertEquals(originalContent, newContent);
    }

    // Helper class for creating test config JSON
    private record ConfigData(String host, int port, String token, String[] commands) {}
}
