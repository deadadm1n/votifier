package com.uberswe.votifier;

import java.util.ArrayList;
import java.util.List;

public class PlayerVoteData {
    private int balance;
    private int lifetimeVotes;
    private List<Vote> pendingRewards = new ArrayList<>();

    public int getBalance() {
        return balance;
    }

    public int getLifetimeVotes() {
        return lifetimeVotes;
    }

    public List<Vote> getPendingRewards() {
        if (pendingRewards == null) {
            pendingRewards = new ArrayList<>();
        }
        return pendingRewards;
    }

    public void addVote(Vote vote, int points) {
        balance += Math.max(1, points);
        lifetimeVotes++;
        getPendingRewards().add(vote);
    }

    public boolean spend(int cost) {
        if (cost < 1 || balance < cost) {
            return false;
        }
        balance -= cost;
        return true;
    }

    public void addBalance(int amount) {
        balance = Math.max(0, balance + amount);
    }

    public void setBalance(int amount) {
        balance = Math.max(0, amount);
    }
}
