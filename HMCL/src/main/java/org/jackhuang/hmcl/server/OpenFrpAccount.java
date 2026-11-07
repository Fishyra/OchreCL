package org.jackhuang.hmcl.server;

import com.google.gson.*;
import org.bouncycastle.crypto.engines.*;
import org.bouncycastle.crypto.macs.Poly1305;
import org.bouncycastle.crypto.params.*;
import org.bouncycastle.math.ec.rfc7748.X25519;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/// OpenFrp's official remote authorization protocol; credentials never leave process memory.
@NotNullByDefault
public final class OpenFrpAccount {
    /// Official account portal, including account registration.
    public static final String PORTAL = "https://console.openfrp.net/";
    /// Ephemeral Curve25519 private key, never persisted.
    private final byte[] secret = new byte[32];
    /// Authorization request identity; cleared when the dialog closes.
    private String request = "";
    /// Current API authorization, refreshed from response headers.
    private String authorization = "";
    /// Starts a new five-minute remote authorization and returns the official browser URL.
    public String begin() throws Exception {
        X25519.generatePrivateKey(new SecureRandom(), secret);
        byte[] publicKey = new byte[32]; X25519.generatePublicKey(secret, 0, publicKey, 0);
        JsonObject body = new JsonObject(); body.addProperty("public_key", Base64.getUrlEncoder().encodeToString(publicKey));
        var response = request("https://access.openfrp.net/argoAccess/requestLogin", "POST", body.toString(), false);
        var data = response.getAsJsonObject("data"); request = data.get("request_uuid").getAsString();
        String url = data.get("authorization_url").getAsString();
        if (!url.startsWith("https://console.openfrp.net/")) throw new IOException("OpenFrp 返回了无法识别的授权页面");
        return url;
    }
    /// Polls no more than once per five seconds; returns false while browser approval is pending.
    public boolean poll() throws Exception {
        var connection = NetworkUtils.createHttpConnection("https://access.openfrp.net/argoAccess/pollLogin?request_uuid=" + java.net.URLEncoder.encode(request, StandardCharsets.UTF_8));
        configure(connection); connection.setRequestMethod("GET");
        try {
            int code = connection.getResponseCode();
            if (code == 204) return false;
            if (code != 200) throw new IOException("OpenFrp 授权请求失败（" + code + "），请重新登录");
            JsonObject data;
            try (var stream = connection.getInputStream()) { data = JsonParser.parseString(new String(stream.readNBytes(65536), StandardCharsets.UTF_8)).getAsJsonObject(); }
            if (!data.has("data") || data.get("data").isJsonNull()) return false;
            var payload = data.getAsJsonObject("data");
            if (!payload.has("authorization_data")) return false;
            @Nullable String sender = connection.getHeaderField("x-request-public-key");
            if (sender == null) throw new IOException("OpenFrp 授权缺少验证密钥");
            authorization = decrypt(secret, Base64.getUrlDecoder().decode(sender), Base64.getDecoder().decode(payload.get("authorization_data").getAsString()));
        } finally { connection.disconnect(); }
        return true;
    }
    /// Loads only TCP tunnels targeting this server's loopback port; never silently edits account tunnels.
    public @Unmodifiable List<Tunnel> tunnels(int port) throws Exception {
        var user = request("https://api.openfrp.net/frp/api/getUserInfo", "POST", "{}", true).getAsJsonObject("data");
        String token = user.get("token").getAsString();
        var data = request("https://api.openfrp.net/frp/api/getUserProxies", "POST", "{}", true).getAsJsonObject("data");
        List<Tunnel> tunnels = new ArrayList<>();
        for (var entry : data.getAsJsonArray("list")) {
            var item = entry.getAsJsonObject(); String ip = item.get("localIp").getAsString();
            if (item.get("proxyType").getAsString().equals("tcp") && item.get("localPort").getAsInt() == port
                && Set.of("127.0.0.1", "localhost", "::1").contains(ip))
                tunnels.add(new Tunnel(item.get("id").getAsInt(), item.get("proxyName").getAsString(), item.get("connectAddress").getAsString(), token, port));
        }
        return List.copyOf(tunnels);
    }
    /// Selected tunnel; toString deliberately excludes its secret access token.
    @NotNullByDefault
    public record Tunnel(int id, String name, String address, String token, int localPort) {
        /// Displays only public tunnel metadata.
        @Override public String toString() { return name + " · " + address; }
    }
    /// Clears ephemeral session material on cancellation or successful selection.
    public void clear() { Arrays.fill(secret, (byte) 0); request = ""; authorization = ""; }
    /// Sends bounded official API requests without including credentials in error messages.
    private JsonObject request(String url, String method, String body, boolean authenticated) throws Exception {
        var connection = NetworkUtils.createHttpConnection(url); configure(connection); connection.setRequestMethod(method);
        if (authenticated) connection.setRequestProperty("Authorization", authorization);
        connection.setDoOutput(true);
        try {
            try (var stream = connection.getOutputStream()) { stream.write(body.getBytes(StandardCharsets.UTF_8)); }
            if (connection.getResponseCode() != 200) throw new IOException("OpenFrp 请求失败（" + connection.getResponseCode() + "）");
            @Nullable String updated = connection.getHeaderField("Authorization"); if (authenticated && updated != null) authorization = updated;
            String text; try (var stream = connection.getInputStream()) { text = new String(stream.readNBytes(2 * 1024 * 1024), StandardCharsets.UTF_8); }
            var result = JsonParser.parseString(text).getAsJsonObject();
            if ((result.has("flag") && !result.get("flag").getAsBoolean()) || (result.has("code") && result.get("code").getAsInt() != 200)) throw new IOException("OpenFrp 未接受请求，请检查账号授权后重试");
            return result;
        } finally { connection.disconnect(); }
    }
    /// Sets timeouts and an application user agent required by the official service.
    private static void configure(java.net.HttpURLConnection connection) {
        connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "OchreCL/1.0"); connection.setRequestProperty("Content-Type", "application/json");
    }
    /// Opens the official NaCl box: X25519, HSalsa20, XSalsa20 and authenticated Poly1305.
    static String decrypt(byte[] privateKey, byte[] publicKey, byte[] payload) throws IOException {
        if (publicKey.length != 32 || payload.length < 40) throw new IOException("OpenFrp 授权数据无效");
        byte[] shared = new byte[32];
        if (!X25519.calculateAgreement(privateKey, 0, publicKey, 0, shared, 0)) throw new IOException("OpenFrp 授权密钥无效");
        int[] input = new int[16], output = new int[16];
        byte[] constants = "expand 32-byte k".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < 4; i++) input[i * 5] = org.bouncycastle.util.Pack.littleEndianToInt(constants, i * 4);
        for (int i = 0; i < 4; i++) { input[1 + i] = org.bouncycastle.util.Pack.littleEndianToInt(shared, i * 4); input[11 + i] = org.bouncycastle.util.Pack.littleEndianToInt(shared, 16 + i * 4); }
        Salsa20Engine.salsaCore(20, input, output);
        int[] indexes = {0, 5, 10, 15, 6, 7, 8, 9}; byte[] key = new byte[32];
        for (int i = 0; i < 8; i++) org.bouncycastle.util.Pack.intToLittleEndian(output[indexes[i]] - input[indexes[i]], key, i * 4);
        var cipher = new XSalsa20Engine(); cipher.init(false, new ParametersWithIV(new KeyParameter(key), Arrays.copyOf(payload, 24)));
        byte[] polyKey = new byte[32]; cipher.processBytes(polyKey, 0, 32, polyKey, 0);
        var mac = new Poly1305(); mac.init(new KeyParameter(polyKey)); mac.update(payload, 40, payload.length - 40);
        byte[] tag = new byte[16]; mac.doFinal(tag, 0);
        if (!java.security.MessageDigest.isEqual(tag, Arrays.copyOfRange(payload, 24, 40))) throw new IOException("OpenFrp 授权数据验证失败");
        byte[] plain = new byte[payload.length - 40]; cipher.processBytes(payload, 40, plain.length, plain, 0);
        Arrays.fill(shared, (byte) 0); Arrays.fill(key, (byte) 0); Arrays.fill(polyKey, (byte) 0);
        return new String(plain, StandardCharsets.UTF_8);
    }
}
