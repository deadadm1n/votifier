package com.uberswe.votifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class VoteStorage {
    private final VotifierConfig config;
    private final Map<String, List<Vote>> pendingVotes;
    private volatile Consumer<Vote> voteCallback;

    public VoteStorage(VotifierConfig config) {
        this.config = config;
        this.pendingVotes = config.getPendingVotes();
    }

    public void setVoteCallback(Consumer<Vote> callback) {
        this.voteCallback = callback;
    }

    public synchronized void addVote(Vote vote) {
        pendingVotes.computeIfAbsent(vote.getUsername().toLowerCase(), k -> new ArrayList<>()).add(vote);
        save();
        Consumer<Vote> cb = voteCallback;
        if (cb != null) {
            cb.accept(vote);
        }
    }

    public synchronized List<Vote> getAndRemoveVotes(String playerName) {
        List<Vote> votes = pendingVotes.remove(playerName.toLowerCase());
        if (votes != null) {
            save();
        }
        return votes != null ? votes : Collections.emptyList();
    }

    private void save() {
        try {
            config.save();
        } catch (IOException e) {
            Constants.LOG.error("Failed to save config", e);
        }
    }
}
