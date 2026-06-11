package com.uberswe.votifier;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ApiVotePoller {
    private final VotifierConfig config;
    private final VoteStorage voteStorage;
    private final HttpClient httpClient;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;
    private static final Gson GSON = new Gson();

    public ApiVotePoller(VotifierConfig config, VoteStorage voteStorage) {
        this.config = config;
        this.voteStorage = voteStorage;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public void start() {
        running = true;
        for (VotifierConfig.ApiVoteSourceConfig source : config.getApiVoteSources()) {
            if (!source.isEnabled()) {
                continue;
            }
            Thread thread = new Thread(() -> pollLoop(source), "Votifier-ApiVote-" + source.getId());
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
            Constants.LOG.info("Started API vote source poller: {}", source.getId());
        }
    }

    public void stop() {
        running = false;
        for (Thread thread : threads) {
            thread.interrupt();
        }
        threads.clear();
    }

    private void pollLoop(VotifierConfig.ApiVoteSourceConfig source) {
        while (running) {
            try {
                pollOnce(source);
            } catch (Exception e) {
                Constants.LOG.error("API vote source {} poll failed", source.getId(), e);
            }
            try {
                Thread.sleep(source.getPollIntervalSeconds() * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void pollOnce(VotifierConfig.ApiVoteSourceConfig source) throws Exception {
        HttpResponse<String> response = sendRequest(
                source,
                source.getUnclaimedVotesMethod(),
                renderTemplate(source.getUnclaimedVotesUrl(), source, List.of()),
                renderTemplate(source.getUnclaimedVotesBody(), source, List.of())
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Constants.LOG.warn("API vote source {} unclaimed request failed: HTTP {}", source.getId(), response.statusCode());
            return;
        }

        List<ApiVote> votes = parseVotes(source, response.body());
        if (votes.isEmpty()) {
            return;
        }

        HttpResponse<String> claimResponse = sendRequest(
                source,
                source.getBatchClaimMethod(),
                renderTemplate(source.getBatchClaimUrl(), source, votes),
                renderTemplate(source.getBatchClaimBody(), source, votes)
        );
        if (claimResponse.statusCode() < 200 || claimResponse.statusCode() >= 300) {
            Constants.LOG.warn("API vote source {} batch claim failed: HTTP {}", source.getId(), claimResponse.statusCode());
            return;
        }

        for (ApiVote apiVote : votes) {
            voteStorage.addVote(new Vote(
                    source.getId(),
                    apiVote.username(),
                    apiVote.address(),
                    apiVote.timestamp()
            ));
        }
        Constants.LOG.info("API vote source {} claimed and stored {} vote(s)", source.getId(), votes.size());
    }

    private HttpResponse<String> sendRequest(VotifierConfig.ApiVoteSourceConfig source, String method, String url, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "Modded-Votifier/1.0");

        for (Map.Entry<String, String> header : source.getHeaders().entrySet()) {
            builder.header(header.getKey(), renderTemplate(header.getValue(), source, List.of()));
        }

        String normalizedMethod = method == null || method.isBlank() ? "GET" : method.toUpperCase();
        if (body == null || body.isBlank()) {
            builder.method(normalizedMethod, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(normalizedMethod, HttpRequest.BodyPublishers.ofString(body));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private List<ApiVote> parseVotes(VotifierConfig.ApiVoteSourceConfig source, String responseBody) {
        JsonElement root = JsonParser.parseString(responseBody);
        JsonElement votesElement = select(root, source.getVotesPath());
        if (votesElement == null || !votesElement.isJsonArray()) {
            Constants.LOG.warn("API vote source {} response did not contain vote array at path {}", source.getId(), source.getVotesPath());
            return List.of();
        }

        List<ApiVote> votes = new ArrayList<>();
        for (JsonElement element : votesElement.getAsJsonArray()) {
            String id = stringAt(element, source.getVoteIdPath());
            String username = stringAt(element, source.getUsernamePath());
            if (id.isBlank() || username.isBlank()) {
                continue;
            }
            String timestamp = stringAt(element, source.getTimestampPath());
            if (timestamp.isBlank()) {
                timestamp = Instant.now().toString();
            }
            votes.add(new ApiVote(
                    id,
                    username,
                    stringAt(element, source.getAddressPath()),
                    timestamp
            ));
        }
        return votes;
    }

    private JsonElement select(JsonElement root, String path) {
        if (path == null || path.isBlank()) {
            return root;
        }
        JsonElement current = root;
        for (String part : path.split("\\.")) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            current = current.getAsJsonObject().get(part);
        }
        return current;
    }

    private String stringAt(JsonElement root, String path) {
        JsonElement element = select(root, path);
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        return element.toString();
    }

    private String renderTemplate(String template, VotifierConfig.ApiVoteSourceConfig source, List<ApiVote> votes) {
        if (template == null || template.isBlank()) {
            return "";
        }
        return template
                .replace("{baseUrl}", trimTrailingSlash(source.getBaseUrl()))
                .replace("{apiKey}", urlEncode(source.getApiKey()))
                .replace("{serverId}", urlEncode(source.getServerId()))
                .replace("{voteIdsJsonArray}", voteIdsJsonArray(votes))
                .replace("{voteIdsCsv}", voteIdsCsv(votes))
                .replace("{voteUpdatesJsonArray}", voteUpdatesJsonArray(votes));
    }

    private String voteIdsJsonArray(List<ApiVote> votes) {
        JsonArray array = new JsonArray();
        for (ApiVote vote : votes) {
            array.add(vote.id());
        }
        return GSON.toJson(array);
    }

    private String voteUpdatesJsonArray(List<ApiVote> votes) {
        JsonArray array = new JsonArray();
        for (ApiVote vote : votes) {
            JsonObject update = new JsonObject();
            update.addProperty("id", vote.id());
            update.addProperty("claimed", true);
            array.add(update);
        }
        return GSON.toJson(array);
    }

    private String voteIdsCsv(List<ApiVote> votes) {
        return String.join(",", votes.stream().map(ApiVote::id).toList());
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private record ApiVote(String id, String username, String address, String timestamp) {
    }
}
