package com.uberswe.votifier.testtool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class VoteSender {

    public record SendResult(boolean success, String greeting, String serverResponse, String error) {}

    public static SendResult sendV1Vote(String host, int port, String publicKeyPem,
                                        String serviceName, String username, String address, String timestamp) {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(10000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String greeting = readGreeting(in);

            String voteMessage = "VOTE\n" + serviceName + "\n" + username + "\n" + address + "\n" + timestamp;
            byte[] messageBytes = voteMessage.getBytes(StandardCharsets.UTF_8);

            PublicKey publicKey = parsePublicKey(publicKeyPem);
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] encrypted = cipher.doFinal(messageBytes);

            out.write(encrypted);
            out.flush();

            return new SendResult(true, greeting, "v1 vote sent (no server response for v1)", null);
        } catch (Exception e) {
            return new SendResult(false, null, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public static SendResult sendV2Vote(String host, int port, String token,
                                        String serviceName, String username, String address, String timestamp) {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(10000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String greetingLine = readGreeting(in);
            String challenge = greetingLine.substring("VOTIFIER 2 ".length()).trim();

            JsonObject voteData = new JsonObject();
            voteData.addProperty("serviceName", serviceName);
            voteData.addProperty("username", username);
            voteData.addProperty("address", address);
            voteData.addProperty("timestamp", timestamp);
            voteData.addProperty("challenge", challenge);
            String payloadStr = voteData.toString();

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(payloadStr.getBytes(StandardCharsets.UTF_8));
            String sigB64 = Base64.getEncoder().encodeToString(sig);

            JsonObject message = new JsonObject();
            message.addProperty("payload", payloadStr);
            message.addProperty("signature", sigB64);
            byte[] messageBytes = message.toString().getBytes(StandardCharsets.UTF_8);

            out.write(0x73); // 's'
            out.write(0x3A); // ':'
            out.write((messageBytes.length >> 8) & 0xFF);
            out.write(messageBytes.length & 0xFF);
            out.write(messageBytes);
            out.flush();

            String responseJson = readResponse(in);

            return new SendResult(true, greetingLine, responseJson, null);
        } catch (Exception e) {
            return new SendResult(false, null, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String readGreeting(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            sb.append((char) b);
            if (b == '\n') break;
        }
        return sb.toString().trim();
    }

    private static String readResponse(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            char c = (char) b;
            if (c == '\n') break;
            if (c != '\r') sb.append(c);
        }
        return sb.toString();
    }

    private static PublicKey parsePublicKey(String pem) throws Exception {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
    }
}
