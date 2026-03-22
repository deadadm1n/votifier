package com.uberswe.votifier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class VotifierConfig {
    private String host = "0.0.0.0";
    private int port = 8192;
    private String token = "";
    private List<String> commands = new ArrayList<>(Collections.singletonList("say {player} voted on createmodservers.com!"));
    private Map<String, List<Vote>> pendingVotes = new LinkedHashMap<>();

    private transient Path configDir;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static VotifierConfig load(Path configDir) throws IOException {
        Path configFile = configDir.resolve("config.json");
        VotifierConfig config;
        if (Files.exists(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile)) {
                config = GSON.fromJson(reader, VotifierConfig.class);
            }
        } else {
            config = null;
        }
        if (config == null) {
            config = new VotifierConfig();
            config.token = UUID.randomUUID().toString();
        }
        if (config.pendingVotes == null) {
            config.pendingVotes = new LinkedHashMap<>();
        }
        config.configDir = configDir;
        config.save();

        // Migrate legacy pending_votes.json into config.json
        Path legacyFile = configDir.resolve("pending_votes.json");
        if (Files.exists(legacyFile)) {
            try {
                Files.delete(legacyFile);
            } catch (IOException ignored) {
            }
        }

        return config;
    }

    public void save() throws IOException {
        Files.createDirectories(configDir);
        Path configFile = configDir.resolve("config.json");
        try (Writer writer = Files.newBufferedWriter(configFile)) {
            GSON.toJson(this, writer);
        }
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getToken() {
        return token;
    }

    public List<String> getCommands() {
        return commands;
    }

    public Map<String, List<Vote>> getPendingVotes() {
        return pendingVotes;
    }
}
