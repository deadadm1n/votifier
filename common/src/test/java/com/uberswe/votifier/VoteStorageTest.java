package com.uberswe.votifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class VoteStorageTest {

    @Test
    void addAndRetrieve(@TempDir Path tempDir) throws Exception {
        VoteStorage storage = new VoteStorage(VotifierConfig.load(tempDir));
        Vote vote = new Vote("Service", "PlayerOne", "127.0.0.1", "now");
        storage.addVote(vote);

        List<Vote> votes = storage.getAndRemoveVotes("PlayerOne");
        assertEquals(1, votes.size());
        assertEquals("Service", votes.get(0).getServiceName());
        assertEquals("PlayerOne", votes.get(0).getUsername());
    }

    @Test
    void getAndRemoveIsDestructive(@TempDir Path tempDir) throws Exception {
        VoteStorage storage = new VoteStorage(VotifierConfig.load(tempDir));
        storage.addVote(new Vote("Service", "PlayerOne", "127.0.0.1", "now"));

        List<Vote> first = storage.getAndRemoveVotes("PlayerOne");
        assertEquals(1, first.size());

        List<Vote> second = storage.getAndRemoveVotes("PlayerOne");
        assertTrue(second.isEmpty());
    }

    @Test
    void caseInsensitiveLookup(@TempDir Path tempDir) throws Exception {
        VoteStorage storage = new VoteStorage(VotifierConfig.load(tempDir));
        storage.addVote(new Vote("Service", "PlayerOne", "127.0.0.1", "now"));

        // Retrieve with different case
        List<Vote> votes = storage.getAndRemoveVotes("playerone");
        assertEquals(1, votes.size());
    }

    @Test
    void caseInsensitiveAddAndRetrieve(@TempDir Path tempDir) throws Exception {
        VoteStorage storage = new VoteStorage(VotifierConfig.load(tempDir));
        storage.addVote(new Vote("Service1", "PlayerOne", "127.0.0.1", "1"));
        storage.addVote(new Vote("Service2", "PLAYERONE", "127.0.0.1", "2"));
        storage.addVote(new Vote("Service3", "playerone", "127.0.0.1", "3"));

        List<Vote> votes = storage.getAndRemoveVotes("PlayerOne");
        assertEquals(3, votes.size());
    }

    @Test
    void multipleVotesPerPlayer(@TempDir Path tempDir) throws Exception {
        VoteStorage storage = new VoteStorage(VotifierConfig.load(tempDir));
        storage.addVote(new Vote("Service1", "PlayerOne", "127.0.0.1", "1"));
        storage.addVote(new Vote("Service2", "PlayerOne", "127.0.0.1", "2"));
        storage.addVote(new Vote("Service3", "PlayerOne", "127.0.0.1", "3"));

        List<Vote> votes = storage.getAndRemoveVotes("PlayerOne");
        assertEquals(3, votes.size());
    }

    @Test
    void persistenceAcrossInstances(@TempDir Path tempDir) throws Exception {
        VoteStorage first = new VoteStorage(VotifierConfig.load(tempDir));
        first.addVote(new Vote("Service", "PlayerOne", "127.0.0.1", "now"));

        // Create a new instance by reloading config from the same directory
        VoteStorage second = new VoteStorage(VotifierConfig.load(tempDir));
        List<Vote> votes = second.getAndRemoveVotes("PlayerOne");
        assertEquals(1, votes.size());
        assertEquals("Service", votes.get(0).getServiceName());
    }

    @Test
    void emptyReturnsEmptyList(@TempDir Path tempDir) throws Exception {
        VoteStorage storage = new VoteStorage(VotifierConfig.load(tempDir));
        List<Vote> votes = storage.getAndRemoveVotes("NonExistent");
        assertNotNull(votes);
        assertTrue(votes.isEmpty());
    }

    @Test
    void concurrentWrites(@TempDir Path tempDir) throws Exception {
        VoteStorage storage = new VoteStorage(VotifierConfig.load(tempDir));
        int threadCount = 10;
        int votesPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threadCount; t++) {
            final int threadNum = t;
            executor.submit(() -> {
                try {
                    for (int v = 0; v < votesPerThread; v++) {
                        storage.addVote(new Vote(
                                "Service-" + threadNum,
                                "Player",
                                "127.0.0.1",
                                String.valueOf(threadNum * votesPerThread + v)
                        ));
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Threads did not complete in time");
        executor.shutdown();
        assertTrue(errors.isEmpty(), "Errors during concurrent writes: " + errors);

        List<Vote> allVotes = storage.getAndRemoveVotes("Player");
        assertEquals(threadCount * votesPerThread, allVotes.size());
    }

    @Test
    void multiplePlayers(@TempDir Path tempDir) throws Exception {
        VoteStorage storage = new VoteStorage(VotifierConfig.load(tempDir));
        storage.addVote(new Vote("Service", "Alice", "127.0.0.1", "1"));
        storage.addVote(new Vote("Service", "Bob", "127.0.0.1", "2"));
        storage.addVote(new Vote("Service", "Alice", "127.0.0.1", "3"));

        List<Vote> aliceVotes = storage.getAndRemoveVotes("Alice");
        assertEquals(2, aliceVotes.size());

        List<Vote> bobVotes = storage.getAndRemoveVotes("Bob");
        assertEquals(1, bobVotes.size());
    }
}
