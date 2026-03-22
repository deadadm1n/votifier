package com.uberswe.votifier;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VotifierServerTest {

    @TempDir
    Path tempDir;

    private VotifierConfig config;
    private RSAKeyManager keyManager;
    private VoteStorage voteStorage;
    private VotifierServer server;

    @BeforeEach
    void setUp() throws Exception {
        config = VotifierConfig.load(tempDir);
        keyManager = new RSAKeyManager();
        keyManager.load(tempDir);

        // Use a custom config with port 0 for ephemeral port
        Path configDir = tempDir.resolve("server-config");
        java.nio.file.Files.createDirectories(configDir);
        // Write config with port 0
        String json = "{\"host\":\"127.0.0.1\",\"port\":0,\"token\":\"" + config.getToken() + "\",\"commands\":[]}";
        java.nio.file.Files.writeString(configDir.resolve("config.json"), json);
        VotifierConfig serverConfig = VotifierConfig.load(configDir);
        voteStorage = new VoteStorage(serverConfig);

        server = new VotifierServer(serverConfig, keyManager, voteStorage);
        server.start();
        // Give the server a moment to bind
        Thread.sleep(100);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void serverStartsAndReportsPort() {
        int port = server.getPort();
        assertTrue(port > 0, "Server should bind to a real port, got: " + port);
    }

    @Test
    void greetingFormat() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(5000);
            InputStream in = socket.getInputStream();

            StringBuilder greeting = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                greeting.append((char) b);
                if (b == '\n') break;
            }

            String line = greeting.toString();
            assertTrue(line.startsWith("VOTIFIER 2 "), "Greeting should start with 'VOTIFIER 2 ', got: " + line);
            assertTrue(line.endsWith("\n"), "Greeting should end with newline");

            // Extract challenge and verify it looks like a UUID
            String challenge = line.substring("VOTIFIER 2 ".length()).trim();
            assertFalse(challenge.isEmpty(), "Challenge should not be empty");
        }
    }

    @Test
    void v1VoteReceived() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(5000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // Read greeting
            readGreeting(in);

            // Build v1 vote message (plaintext must be under 245 bytes for PKCS1)
            // RSA/ECB/PKCS1Padding with 2048-bit key produces exactly 256 bytes of ciphertext
            String voteMessage = "VOTE\nTestService\nV1Player\n127.0.0.1\n1700000000";
            byte[] messageBytes = voteMessage.getBytes(StandardCharsets.UTF_8);

            // Encrypt with RSA — ciphertext will be 256 bytes
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, keyManager.getKeyPair().getPublic());
            byte[] encrypted = cipher.doFinal(messageBytes);

            out.write(encrypted);
            out.flush();
        }

        // Give server time to process
        Thread.sleep(500);

        List<Vote> votes = voteStorage.getAndRemoveVotes("V1Player");
        assertEquals(1, votes.size());
        assertEquals("TestService", votes.get(0).getServiceName());
        assertEquals("V1Player", votes.get(0).getUsername());
        assertEquals("127.0.0.1", votes.get(0).getAddress());
        assertEquals("1700000000", votes.get(0).getTimestamp());
    }

    @Test
    void v2VoteReceived() throws Exception {
        String responseJson;
        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(5000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // Read greeting and extract challenge
            String challenge = readGreeting(in);

            // Build v2 payload
            JsonObject voteData = new JsonObject();
            voteData.addProperty("serviceName", "TestServiceV2");
            voteData.addProperty("username", "V2Player");
            voteData.addProperty("address", "10.0.0.1");
            voteData.addProperty("timestamp", "1700000001");
            voteData.addProperty("challenge", challenge);
            String payloadStr = voteData.toString();

            // Sign with HMAC-SHA256
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.getToken().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(payloadStr.getBytes(StandardCharsets.UTF_8));
            String sigB64 = Base64.getEncoder().encodeToString(sig);

            // Build message
            JsonObject message = new JsonObject();
            message.addProperty("payload", payloadStr);
            message.addProperty("signature", sigB64);
            byte[] messageBytes = message.toString().getBytes(StandardCharsets.UTF_8);

            // Write v2 header: "s:" + 2-byte length + payload
            out.write(0x73); // 's'
            out.write(0x3A); // ':'
            out.write((messageBytes.length >> 8) & 0xFF);
            out.write(messageBytes.length & 0xFF);
            out.write(messageBytes);
            out.flush();

            // Read JSON response
            responseJson = readResponse(in);
        }

        // Verify response
        JsonObject response = JsonParser.parseString(responseJson).getAsJsonObject();
        assertEquals("ok", response.get("status").getAsString());

        List<Vote> votes = voteStorage.getAndRemoveVotes("V2Player");
        assertEquals(1, votes.size());
        assertEquals("TestServiceV2", votes.get(0).getServiceName());
        assertEquals("V2Player", votes.get(0).getUsername());
        assertEquals("10.0.0.1", votes.get(0).getAddress());
        assertEquals("1700000001", votes.get(0).getTimestamp());
    }

    @Test
    void v2BadSignatureRejected() throws Exception {
        String responseJson;
        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(5000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String challenge = readGreeting(in);

            JsonObject voteData = new JsonObject();
            voteData.addProperty("serviceName", "BadSigService");
            voteData.addProperty("username", "BadSigPlayer");
            voteData.addProperty("address", "10.0.0.1");
            voteData.addProperty("timestamp", "now");
            voteData.addProperty("challenge", challenge);
            String payloadStr = voteData.toString();

            // Sign with wrong key
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("wrong-token".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(payloadStr.getBytes(StandardCharsets.UTF_8));
            String sigB64 = Base64.getEncoder().encodeToString(sig);

            JsonObject message = new JsonObject();
            message.addProperty("payload", payloadStr);
            message.addProperty("signature", sigB64);
            byte[] messageBytes = message.toString().getBytes(StandardCharsets.UTF_8);

            out.write(0x73);
            out.write(0x3A);
            out.write((messageBytes.length >> 8) & 0xFF);
            out.write(messageBytes.length & 0xFF);
            out.write(messageBytes);
            out.flush();

            // Read error response
            responseJson = readResponse(in);
        }

        JsonObject response = JsonParser.parseString(responseJson).getAsJsonObject();
        assertEquals("error", response.get("status").getAsString());

        List<Vote> votes = voteStorage.getAndRemoveVotes("BadSigPlayer");
        assertTrue(votes.isEmpty(), "Vote with bad signature should be rejected");
    }

    @Test
    void v2WrongChallengeRejected() throws Exception {
        String responseJson;
        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(5000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // Read greeting but use a wrong challenge
            readGreeting(in);

            JsonObject voteData = new JsonObject();
            voteData.addProperty("serviceName", "BadChallengeService");
            voteData.addProperty("username", "BadChallengePlayer");
            voteData.addProperty("address", "10.0.0.1");
            voteData.addProperty("timestamp", "now");
            voteData.addProperty("challenge", "wrong-challenge-value");
            String payloadStr = voteData.toString();

            // Sign correctly but with wrong challenge in payload
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.getToken().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(payloadStr.getBytes(StandardCharsets.UTF_8));
            String sigB64 = Base64.getEncoder().encodeToString(sig);

            JsonObject message = new JsonObject();
            message.addProperty("payload", payloadStr);
            message.addProperty("signature", sigB64);
            byte[] messageBytes = message.toString().getBytes(StandardCharsets.UTF_8);

            out.write(0x73);
            out.write(0x3A);
            out.write((messageBytes.length >> 8) & 0xFF);
            out.write(messageBytes.length & 0xFF);
            out.write(messageBytes);
            out.flush();

            // Read error response
            responseJson = readResponse(in);
        }

        JsonObject response = JsonParser.parseString(responseJson).getAsJsonObject();
        assertEquals("error", response.get("status").getAsString());

        List<Vote> votes = voteStorage.getAndRemoveVotes("BadChallengePlayer");
        assertTrue(votes.isEmpty(), "Vote with wrong challenge should be rejected");
    }

    private String readGreeting(InputStream in) throws Exception {
        StringBuilder greeting = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            greeting.append((char) b);
            if (b == '\n') break;
        }
        String line = greeting.toString();
        return line.substring("VOTIFIER 2 ".length()).trim();
    }

    private String readResponse(InputStream in) throws Exception {
        // NuVotifier v2 response is raw JSON terminated by \r\n (no magic prefix, no length prefix)
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            char c = (char) b;
            if (c == '\n') break;
            if (c != '\r') sb.append(c);
        }
        String response = sb.toString();
        assertFalse(response.isEmpty(), "Expected a JSON response");
        return response;
    }
}
