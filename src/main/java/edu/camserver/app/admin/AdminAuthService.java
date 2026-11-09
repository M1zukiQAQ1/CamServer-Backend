package edu.camserver.app.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who may use the admin panel: requests must come from an allowed network (the UCSB VPN) and
 * carry a session cookie obtained with the admin password.
 *
 * <p>The client address is nginx's {@code X-Real-IP}, which nginx always overwrites; the backend
 * listens on loopback only, so nothing else can reach it with a forged header except processes
 * on this machine, which still need the password. {@code X-Forwarded-For} is never used because
 * clients can prepend to it. Sessions live in memory: a backend restart logs everyone out.
 */
@Service
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);
    public static final String COOKIE = "allsky_admin";

    public record Client(String address, boolean allowed, String network) {
    }

    public record Session(String address, Instant createdAt, Instant expiresAt, Instant lastSeen) {
    }

    public enum LoginResult { OK, WRONG_PASSWORD, LOCKED, NOT_CONFIGURED }

    public record Login(LoginResult result, String token, Session session, Duration retryAfter) {
    }

    private final AdminProperties properties;
    private final NetworkAllowList allowList;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> failuresByAddress = new ConcurrentHashMap<>();
    private final Deque<Instant> failures = new ArrayDeque<>();
    private PasswordHash cachedHash;
    private FileTime cachedHashTime;

    @Autowired
    public AdminAuthService(AdminProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AdminAuthService(AdminProperties properties, Clock clock) {
        this.properties = properties;
        this.allowList = new NetworkAllowList(properties.getAllowedNetworks());
        this.clock = clock;
        log.info("Admin panel accepts {}{}", allowList.cidrs(), properties.isAllowLoopback() ? " and loopback" : "");
    }

    public Client client(HttpServletRequest request) {
        return client(request.getHeader("X-Real-IP"), request.getRemoteAddr());
    }

    Client client(String realIp, String remoteAddr) {
        boolean viaLoopback = isLoopback(remoteAddr);
        String address = viaLoopback && realIp != null && !realIp.isBlank() ? realIp.trim() : remoteAddr;
        if (address == null) return new Client("unknown", false, null);
        if (properties.isAllowLoopback() && isLoopback(address)) return new Client(address, true, "loopback");
        Optional<String> network = allowList.match(address);
        return new Client(address, network.isPresent(), network.orElse(null));
    }

    public boolean passwordConfigured() {
        return loadHash().isPresent();
    }

    public Login login(Client client, String password) {
        Instant now = clock.instant();
        Duration locked = lockedFor(client.address(), now);
        if (!locked.isZero()) {
            log.warn("Admin login from {} refused: locked out for {} s", client.address(), locked.toSeconds());
            return new Login(LoginResult.LOCKED, null, null, locked);
        }
        Optional<PasswordHash> hash = loadHash();
        if (hash.isEmpty()) return new Login(LoginResult.NOT_CONFIGURED, null, null, null);
        if (password == null || password.isEmpty() || password.length() > 256 || !hash.get().matches(password.toCharArray())) {
            recordFailure(client.address(), now);
            log.warn("Admin login from {} failed", client.address());
            return new Login(LoginResult.WRONG_PASSWORD, null, null, null);
        }
        failuresByAddress.remove(client.address());
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Session session = new Session(client.address(), now, now.plus(Duration.ofHours(properties.getSessionHours())), now);
        sessions.put(digest(token), session);
        sessions.entrySet().removeIf(e -> !valid(e.getValue(), now));
        log.info("Admin login from {}", client.address());
        return new Login(LoginResult.OK, token, session, null);
    }

    /** The live session for this cookie value, refreshing its idle timer. */
    public Optional<Session> session(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Instant now = clock.instant();
        String key = digest(token);
        Session session = sessions.get(key);
        if (session == null) return Optional.empty();
        if (!valid(session, now)) {
            sessions.remove(key);
            return Optional.empty();
        }
        Session touched = new Session(session.address(), session.createdAt(), session.expiresAt(), now);
        sessions.put(key, touched);
        return Optional.of(touched);
    }

    public void logout(String token) {
        if (token != null) sessions.remove(digest(token));
    }

    public Duration sessionLifetime() {
        return Duration.ofHours(properties.getSessionHours());
    }

    public java.util.List<String> allowedNetworks() {
        return allowList.cidrs();
    }

    private boolean valid(Session session, Instant now) {
        return now.isBefore(session.expiresAt())
                && now.isBefore(session.lastSeen().plus(Duration.ofMinutes(properties.getIdleMinutes())));
    }

    private synchronized Duration lockedFor(String address, Instant now) {
        Duration window = Duration.ofMinutes(properties.getLockoutMinutes());
        prune(failures, now.minus(window));
        Deque<Instant> mine = failuresByAddress.get(address);
        if (mine != null) prune(mine, now.minus(window));
        Instant until = null;
        if (mine != null && mine.size() >= properties.getMaxFailuresPerAddress()) until = mine.peekLast().plus(window);
        if (failures.size() >= properties.getMaxFailuresTotal()) {
            Instant all = failures.peekLast().plus(window);
            if (until == null || all.isAfter(until)) until = all;
        }
        return until == null || !until.isAfter(now) ? Duration.ZERO : Duration.between(now, until);
    }

    private synchronized void recordFailure(String address, Instant now) {
        failures.addLast(now);
        failuresByAddress.computeIfAbsent(address, a -> new ArrayDeque<>()).addLast(now);
    }

    private static void prune(Deque<Instant> events, Instant before) {
        while (!events.isEmpty() && events.peekFirst().isBefore(before)) events.pollFirst();
    }

    private synchronized Optional<PasswordHash> loadHash() {
        Path file = Path.of(properties.getPasswordFile());
        try {
            FileTime modified = Files.getLastModifiedTime(file);
            if (cachedHash == null || !modified.equals(cachedHashTime)) {
                cachedHash = PasswordHash.parse(Files.readString(file, StandardCharsets.UTF_8));
                cachedHashTime = modified;
            }
            return Optional.of(cachedHash);
        } catch (IOException | RuntimeException e) {
            if (cachedHash != null || cachedHashTime == null) {
                log.warn("Admin password file {} unusable: {}", file.toAbsolutePath(), e.toString());
            }
            cachedHash = null;
            cachedHashTime = FileTime.fromMillis(0);
            return Optional.empty();
        }
    }

    private static boolean isLoopback(String address) {
        return address != null && (address.startsWith("127.") || address.equals("::1") || address.equals("0:0:0:0:0:0:0:1"));
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
