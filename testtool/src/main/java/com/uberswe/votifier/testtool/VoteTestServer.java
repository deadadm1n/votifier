package com.uberswe.votifier.testtool;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class VoteTestServer {

    private static final Gson GSON = new Gson();
    private static final String[] CONFIG_SEARCH_PATHS = {
            "neoforge/run/config/votifier",
            "run/config/votifier",
            "config/votifier"
    };

    public static void main(String[] args) throws IOException {
        int port = 8080;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
            }
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", VoteTestServer::handleStaticFile);
        server.createContext("/api/send-vote", VoteTestServer::handleSendVote);
        server.createContext("/api/read-config", VoteTestServer::handleReadConfig);
        server.start();

        System.out.println("Vote Test Tool running at http://localhost:" + port);
    }

    private static void handleStaticFile(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        try (InputStream is = VoteTestServer.class.getResourceAsStream("/static/index.html")) {
            if (is == null) {
                byte[] msg = "index.html not found on classpath".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, msg.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(msg);
                }
                return;
            }
            byte[] html = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html);
            }
        }
    }

    private static void handleSendVote(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String body;
        try (InputStream is = exchange.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        JsonObject req = JsonParser.parseString(body).getAsJsonObject();
        String host = req.get("host").getAsString();
        int port = req.get("port").getAsInt();
        String protocol = req.get("protocol").getAsString();
        String serviceName = req.get("serviceName").getAsString();
        String username = req.get("username").getAsString();
        String address = req.get("address").getAsString();
        String timestamp = req.get("timestamp").getAsString();

        VoteSender.SendResult result;
        if ("v1".equals(protocol)) {
            String publicKey = req.get("publicKey").getAsString();
            result = VoteSender.sendV1Vote(host, port, publicKey, serviceName, username, address, timestamp);
        } else {
            String token = req.get("token").getAsString();
            result = VoteSender.sendV2Vote(host, port, token, serviceName, username, address, timestamp);
        }

        JsonObject resp = new JsonObject();
        resp.addProperty("success", result.success());
        if (result.greeting() != null) resp.addProperty("greeting", result.greeting());
        if (result.serverResponse() != null) resp.addProperty("serverResponse", result.serverResponse());
        if (result.error() != null) resp.addProperty("error", result.error());

        sendJson(exchange, 200, resp);
    }

    private static void handleReadConfig(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        Path projectRoot = Path.of("").toAbsolutePath();
        for (String searchPath : CONFIG_SEARCH_PATHS) {
            Path configDir = projectRoot.resolve(searchPath);
            Path configFile = configDir.resolve("config.json");
            Path publicKeyFile = configDir.resolve("public.pem");

            if (Files.exists(configFile)) {
                JsonObject resp = new JsonObject();
                resp.addProperty("found", true);
                resp.addProperty("path", configDir.toString());

                String configJson = Files.readString(configFile);
                JsonObject config = JsonParser.parseString(configJson).getAsJsonObject();
                if (config.has("token")) resp.addProperty("token", config.get("token").getAsString());
                if (config.has("port")) resp.addProperty("port", config.get("port").getAsInt());
                if (config.has("host")) resp.addProperty("host", config.get("host").getAsString());

                if (Files.exists(publicKeyFile)) {
                    resp.addProperty("publicKey", Files.readString(publicKeyFile));
                }

                sendJson(exchange, 200, resp);
                return;
            }
        }

        JsonObject resp = new JsonObject();
        resp.addProperty("found", false);
        resp.addProperty("error", "No votifier config found. Searched: " + String.join(", ", CONFIG_SEARCH_PATHS));
        sendJson(exchange, 200, resp);
    }

    private static void sendJson(HttpExchange exchange, int status, JsonObject json) throws IOException {
        byte[] bytes = GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
