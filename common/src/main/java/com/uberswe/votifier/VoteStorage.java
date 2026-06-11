package com.uberswe.votifier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class VoteStorage {
    private final VotifierConfig config;
    private final Map<String, PlayerVoteData> playerVotes;
    private volatile Consumer<Vote> voteCallback;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public VoteStorage(VotifierConfig config) {
        this.config = config;
        this.playerVotes = loadPlayerVotes();
        migrateLegacyPendingVotes();
    }

    public void setVoteCallback(Consumer<Vote> callback) {
        this.voteCallback = callback;
    }

    public synchronized void addVote(Vote vote) {
        PlayerVoteData data = dataFor(vote.getUsername());
        data.addVote(vote, config.getVotePointsPerVote());
        save();
        appendHistory(vote);
        Consumer<Vote> cb = voteCallback;
        if (cb != null) {
            cb.accept(vote);
        }
    }

    public synchronized List<Vote> getAndRemoveVotes(String playerName) {
        PlayerVoteData data = playerVotes.get(key(playerName));
        if (data == null || data.getPendingRewards().isEmpty()) {
            return Collections.emptyList();
        }
        List<Vote> votes = new ArrayList<>(data.getPendingRewards());
        data.getPendingRewards().clear();
        save();
        return votes;
    }

    public synchronized int getBalance(String playerName) {
        PlayerVoteData data = playerVotes.get(key(playerName));
        return data == null ? 0 : data.getBalance();
    }

    public synchronized int getLifetimeVotes(String playerName) {
        PlayerVoteData data = playerVotes.get(key(playerName));
        return data == null ? 0 : data.getLifetimeVotes();
    }

    public synchronized boolean spend(String playerName, int cost) {
        PlayerVoteData data = playerVotes.get(key(playerName));
        if (data == null || !data.spend(cost)) {
            return false;
        }
        save();
        return true;
    }

    public synchronized void addBalance(String playerName, int amount) {
        dataFor(playerName).addBalance(amount);
        save();
    }

    public synchronized void setBalance(String playerName, int amount) {
        dataFor(playerName).setBalance(amount);
        save();
    }

    public synchronized List<Map.Entry<String, PlayerVoteData>> topBalances(int limit) {
        return playerVotes.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, PlayerVoteData> e) -> e.getValue().getBalance()).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    private PlayerVoteData dataFor(String playerName) {
        return playerVotes.computeIfAbsent(key(playerName), k -> new PlayerVoteData());
    }

    private Map<String, PlayerVoteData> loadPlayerVotes() {
        if (!Files.exists(config.getPlayerVotesFile())) {
            return new LinkedHashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(config.getPlayerVotesFile())) {
            Map<String, PlayerVoteData> loaded = GSON.fromJson(
                    reader,
                    new TypeToken<Map<String, PlayerVoteData>>() {}.getType()
            );
            return loaded == null ? new LinkedHashMap<>() : loaded;
        } catch (IOException e) {
            Constants.LOG.error("Failed to load player vote data", e);
            return new LinkedHashMap<>();
        }
    }

    private void migrateLegacyPendingVotes() {
        Map<String, List<Vote>> legacy = config.getLegacyPendingVotes();
        if (legacy == null || legacy.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<Vote>> entry : legacy.entrySet()) {
            for (Vote vote : entry.getValue()) {
                dataFor(entry.getKey()).addVote(vote, config.getVotePointsPerVote());
            }
        }
        config.clearLegacyPendingVotes();
        save();
        try {
            config.save();
        } catch (IOException e) {
            Constants.LOG.error("Failed to clear legacy vote data from config", e);
        }
    }

    private String key(String playerName) {
        return playerName == null ? "" : playerName.toLowerCase();
    }

    private void appendHistory(Vote vote) {
        try {
            Files.createDirectories(config.getVoteHistoryFile().getParent());
            try (Writer writer = Files.newBufferedWriter(
                    config.getVoteHistoryFile(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            )) {
                GSON.toJson(vote, writer);
                writer.write(System.lineSeparator());
            }
        } catch (IOException e) {
            Constants.LOG.error("Failed to append vote history", e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(config.getPlayerVotesFile().getParent());
            try (Writer writer = Files.newBufferedWriter(config.getPlayerVotesFile())) {
                GSON.toJson(playerVotes, writer);
            }
        } catch (IOException e) {
            Constants.LOG.error("Failed to save player vote data", e);
        }
    }
}
