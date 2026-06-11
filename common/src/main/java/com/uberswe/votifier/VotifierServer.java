package com.uberswe.votifier;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

public class VotifierServer {
    private final VotifierConfig config;
    private final RSAKeyManager keyManager;
    private final VoteStorage voteStorage;
    private ServerSocket serverSocket;
    private Thread listenerThread;
    private volatile boolean running;

    public VotifierServer(VotifierConfig config, RSAKeyManager keyManager, VoteStorage voteStorage) {
        this.config = config;
        this.keyManager = keyManager;
        this.voteStorage = voteStorage;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(config.getHost(), config.getPort()));
        running = true;

        listenerThread = new Thread(this::listen, "Votifier-Listener");
        listenerThread.setDaemon(true);
        listenerThread.start();

        Constants.LOG.info("Votifier server started on {}:{}", config.getHost(), config.getPort());
    }

    private void listen() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(10000);
                new Thread(() -> handleConnection(socket), "Votifier-Handler").start();
            } catch (IOException e) {
                if (running) {
                    Constants.LOG.error("Error accepting connection", e);
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try {
            String challenge = UUID.randomUUID().toString();
            OutputStream out = socket.getOutputStream();
            out.write(("VOTIFIER 2 " + challenge + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = socket.getInputStream();
            int firstByte = in.read();
            int secondByte = in.read();

            if (firstByte == -1 || secondByte == -1) {
                return;
            }

            if (firstByte == 0x73 && secondByte == 0x3A) {
                if (!config.getNuVotifierV2().isEnabled()) {
                    sendV2Response(out, "error", "NuVotifier v2 is disabled");
                    return;
                }
                handleV2(in, out, challenge);
            } else {
                if (!config.getVotifierV1().isEnabled()) {
                    Constants.LOG.warn("Rejected v1 vote because Votifier v1 is disabled");
                    return;
                }
                handleV1(in, firstByte, secondByte);
            }
        } catch (Exception e) {
            Constants.LOG.error("Error handling vote connection", e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handleV1(InputStream in, int firstByte, int secondByte) throws Exception {
        byte[] block = new byte[256];
        block[0] = (byte) firstByte;
        block[1] = (byte) secondByte;
        int offset = 2;
        while (offset < 256) {
            int read = in.read(block, offset, 256 - offset);
            if (read == -1) {
                throw new IOException("Unexpected end of stream");
            }
            offset += read;
        }

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, keyManager.getKeyPair().getPrivate());
        byte[] decrypted = cipher.doFinal(block);
        String message = new String(decrypted, StandardCharsets.UTF_8).trim();

        String[] lines = message.split("\n");
        if (lines.length >= 5 && "VOTE".equals(lines[0])) {
            Vote vote = new Vote(lines[1], lines[2], lines[3], lines[4]);
            voteStorage.addVote(vote);
            Constants.LOG.info("Received v1 vote from {} for player {}", vote.getServiceName(), vote.getUsername());
        } else {
            Constants.LOG.warn("Invalid v1 vote message: {}", message);
        }
    }

    private void handleV2(InputStream in, OutputStream out, String challenge) throws Exception {
        int lengthHigh = in.read();
        int lengthLow = in.read();
        if (lengthHigh == -1 || lengthLow == -1) {
            throw new IOException("Unexpected end of stream reading v2 length");
        }
        int length = (lengthHigh << 8) | lengthLow;

        byte[] payload = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(payload, offset, length - offset);
            if (read == -1) {
                throw new IOException("Unexpected end of stream reading v2 payload");
            }
            offset += read;
        }

        String json = new String(payload, StandardCharsets.UTF_8);
        JsonObject message = JsonParser.parseString(json).getAsJsonObject();

        String payloadStr = message.get("payload").getAsString();
        String signature = message.get("signature").getAsString();

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(config.getToken().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expectedSig = mac.doFinal(payloadStr.getBytes(StandardCharsets.UTF_8));
        String expectedSigB64 = Base64.getEncoder().encodeToString(expectedSig);

        if (!expectedSigB64.equals(signature)) {
            Constants.LOG.warn("Invalid v2 vote signature");
            sendV2Response(out, "error", "signature verification failed");
            return;
        }

        JsonObject voteData = JsonParser.parseString(payloadStr).getAsJsonObject();

        String voteChallenge = voteData.has("challenge") ? voteData.get("challenge").getAsString() : "";
        if (!challenge.equals(voteChallenge)) {
            Constants.LOG.warn("Invalid v2 vote challenge");
            sendV2Response(out, "error", "challenge verification failed");
            return;
        }

        Vote vote = new Vote(
                voteData.get("serviceName").getAsString(),
                voteData.get("username").getAsString(),
                voteData.has("address") ? voteData.get("address").getAsString() : "",
                voteData.has("timestamp") ? voteData.get("timestamp").getAsString() : ""
        );
        voteStorage.addVote(vote);
        Constants.LOG.info("Received v2 vote from {} for player {}", vote.getServiceName(), vote.getUsername());
        sendV2Response(out, "ok", null);
    }

    private void sendV2Response(OutputStream out, String status, String error) throws IOException {
        JsonObject response = new JsonObject();
        response.addProperty("status", status);
        if (error != null) {
            response.addProperty("error", error);
        }
        out.write((response + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Constants.LOG.error("Error closing server socket", e);
        }
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
        Constants.LOG.info("Votifier server stopped");
    }

    public VoteStorage getVoteStorage() {
        return voteStorage;
    }

    public VotifierConfig getConfig() {
        return config;
    }

    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : -1;
    }
}
