package com.uberswe.votifier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
    private ProtocolConfig votifierV1 = new ProtocolConfig(true);
    private NuVotifierV2Config nuVotifierV2 = new NuVotifierV2Config();
    private int votePointsPerVote = 1;
    private List<String> voteCommands = new ArrayList<>(Collections.singletonList("say {player} voted and earned {points} vote point(s)!"));
    private VoteShopConfig voteShop = new VoteShopConfig();

    private transient Path configDir;
    private transient Map<String, List<Vote>> legacyPendingVotes = new LinkedHashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static VotifierConfig load(Path configDir) throws IOException {
        Path configFile = configDir.resolve("config.json");
        VotifierConfig config;
        if (Files.exists(configFile)) {
            JsonObject raw;
            try (Reader reader = Files.newBufferedReader(configFile)) {
                raw = JsonParser.parseReader(reader).getAsJsonObject();
            }
            config = GSON.fromJson(raw, VotifierConfig.class);
            migrateLegacyFields(config, raw);
        } else {
            config = null;
        }
        if (config == null) {
            config = new VotifierConfig();
        }
        config.normalize();
        config.configDir = configDir;
        config.save();
        return config;
    }

    private static void migrateLegacyFields(VotifierConfig config, JsonObject raw) {
        if (raw.has("token") && !raw.get("token").isJsonNull()) {
            config.nuVotifierV2.token = raw.get("token").getAsString();
        }
        if (raw.has("commands") && !raw.get("commands").isJsonNull()) {
            config.voteCommands = GSON.fromJson(
                    raw.get("commands"),
                    new com.google.gson.reflect.TypeToken<List<String>>() {}.getType()
            );
        }
        if (raw.has("pendingVotes") && !raw.get("pendingVotes").isJsonNull()) {
            config.legacyPendingVotes = GSON.fromJson(
                    raw.get("pendingVotes"),
                    new com.google.gson.reflect.TypeToken<Map<String, List<Vote>>>() {}.getType()
            );
        }
    }

    private void normalize() {
        if (votifierV1 == null) {
            votifierV1 = new ProtocolConfig(true);
        }
        if (nuVotifierV2 == null) {
            nuVotifierV2 = new NuVotifierV2Config();
        }
        if (nuVotifierV2.token == null || nuVotifierV2.token.isBlank()) {
            nuVotifierV2.token = UUID.randomUUID().toString();
        }
        if (votePointsPerVote < 1) {
            votePointsPerVote = 1;
        }
        if (voteCommands == null) {
            voteCommands = new ArrayList<>();
        }
        if (voteShop == null) {
            voteShop = new VoteShopConfig();
        }
        voteShop.normalize();
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
        return nuVotifierV2.getToken();
    }

    public ProtocolConfig getVotifierV1() {
        return votifierV1;
    }

    public NuVotifierV2Config getNuVotifierV2() {
        return nuVotifierV2;
    }

    public int getVotePointsPerVote() {
        return votePointsPerVote;
    }

    public List<String> getVoteCommands() {
        return voteCommands;
    }

    public VoteShopConfig getVoteShop() {
        return voteShop;
    }

    public Path getPlayerVotesFile() {
        return configDir.resolve("player_votes.json");
    }

    public Path getVoteHistoryFile() {
        return configDir.resolve("vote_history.jsonl");
    }

    public Map<String, List<Vote>> getLegacyPendingVotes() {
        return legacyPendingVotes;
    }

    public void clearLegacyPendingVotes() {
        legacyPendingVotes = new LinkedHashMap<>();
    }

    public static class ProtocolConfig {
        private boolean enabled;

        public ProtocolConfig() {
            this(true);
        }

        public ProtocolConfig(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isEnabled() {
            return enabled;
        }
    }

    public static class NuVotifierV2Config extends ProtocolConfig {
        private String token = UUID.randomUUID().toString();

        public NuVotifierV2Config() {
            super(true);
        }

        public String getToken() {
            return token;
        }
    }

    public static class VoteShopConfig {
        private boolean enabled = true;
        private List<VoteShopItem> items = new ArrayList<>();

        private void normalize() {
            if (items == null) {
                items = new ArrayList<>();
            }
        }

        public boolean isEnabled() {
            return enabled;
        }

        public List<VoteShopItem> getItems() {
            return items;
        }

        public VoteShopItem findItem(String id) {
            if (id == null) {
                return null;
            }
            for (VoteShopItem item : items) {
                if (item.getId().equalsIgnoreCase(id)) {
                    return item;
                }
            }
            return null;
        }
    }

    public static class VoteShopItem {
        private String id = "";
        private String name = "";
        private String description = "";
        private int cost = 1;
        private List<String> commands = new ArrayList<>();

        public String getId() {
            return id;
        }

        public String getName() {
            return name == null || name.isBlank() ? id : name;
        }

        public String getDescription() {
            return description == null ? "" : description;
        }

        public int getCost() {
            return Math.max(1, cost);
        }

        public List<String> getCommands() {
            return commands == null ? Collections.emptyList() : commands;
        }
    }
}
