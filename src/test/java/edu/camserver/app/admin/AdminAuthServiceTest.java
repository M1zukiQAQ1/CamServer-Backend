package edu.camserver.app.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdminAuthServiceTest {
    @TempDir Path dir;

    /** A clock the test can move forward. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-09-28T22:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private AdminProperties properties() throws Exception {
        AdminProperties properties = new AdminProperties();
        properties.setAllowedNetworks(List.of("128.111.180.0/22", "128.111.237.32/27"));
        Path file = dir.resolve("password");
        Files.writeString(file, PasswordHash.encode("correct horse".toCharArray(), new byte[16], 100_000) + "\n");
        properties.setPasswordFile(file.toString());
        return properties;
    }

    @Test void clientAddressComesFromNginxOnlyWhenTheRequestArrivedOverLoopback() throws Exception {
        AdminAuthService auth = new AdminAuthService(properties(), new TestClock());
        assertTrue(auth.client("128.111.180.9", "127.0.0.1").allowed());
        assertEquals("128.111.180.0/22", auth.client("128.111.180.9", "127.0.0.1").network());
        assertFalse(auth.client("8.8.8.8", "127.0.0.1").allowed());
        assertFalse(auth.client("128.111.23.98", "127.0.0.1").allowed(), "campus wired is not the VPN");
        assertFalse(auth.client("2607:f8b0::1", "127.0.0.1").allowed(), "IPv6 is refused");
        assertFalse(auth.client("garbage", "127.0.0.1").allowed());
        // A peer that is not loopback cannot choose its address with the header.
        assertFalse(auth.client("128.111.180.9", "8.8.8.8").allowed());
        // Direct loopback use (SSH tunnel) is allowed by default ...
        assertTrue(auth.client(null, "127.0.0.1").allowed());
        AdminProperties strict = properties();
        strict.setAllowLoopback(false);
        // ... and can be switched off.
        assertFalse(new AdminAuthService(strict, new TestClock()).client(null, "127.0.0.1").allowed());
    }

    @Test void loginIssuesAnExpiringSessionAndLogoutEndsIt() throws Exception {
        TestClock clock = new TestClock();
        AdminAuthService auth = new AdminAuthService(properties(), clock);
        AdminAuthService.Client client = auth.client("128.111.180.9", "127.0.0.1");
        assertEquals(AdminAuthService.LoginResult.WRONG_PASSWORD, auth.login(client, "wrong").result());
        AdminAuthService.Login login = auth.login(client, "correct horse");
        assertEquals(AdminAuthService.LoginResult.OK, login.result());
        assertTrue(auth.session(login.token()).isPresent());
        assertTrue(auth.session("forged").isEmpty());
        clock.now = clock.now.plus(Duration.ofMinutes(121));
        assertTrue(auth.session(login.token()).isEmpty(), "idle timeout");

        AdminAuthService.Login second = auth.login(client, "correct horse");
        for (int i = 0; i < 12; i++) {
            clock.now = clock.now.plus(Duration.ofMinutes(60));
            if (i < 11) assertTrue(auth.session(second.token()).isPresent(), "active use keeps it for 12 h");
        }
        assertTrue(auth.session(second.token()).isEmpty(), "absolute lifetime");

        AdminAuthService.Login third = auth.login(client, "correct horse");
        auth.logout(third.token());
        assertTrue(auth.session(third.token()).isEmpty());
    }

    @Test void repeatedFailuresLockTheAddressOut() throws Exception {
        TestClock clock = new TestClock();
        AdminAuthService auth = new AdminAuthService(properties(), clock);
        AdminAuthService.Client client = auth.client("128.111.180.9", "127.0.0.1");
        for (int i = 0; i < 5; i++) assertEquals(AdminAuthService.LoginResult.WRONG_PASSWORD, auth.login(client, "guess" + i).result());
        assertEquals(AdminAuthService.LoginResult.LOCKED, auth.login(client, "correct horse").result());
        AdminAuthService.Client other = auth.client("128.111.180.10", "127.0.0.1");
        assertEquals(AdminAuthService.LoginResult.OK, auth.login(other, "correct horse").result());
        clock.now = clock.now.plus(Duration.ofMinutes(16));
        assertEquals(AdminAuthService.LoginResult.OK, auth.login(client, "correct horse").result());
    }

    @Test void missingOrWeakPasswordFileRefusesEveryLogin() throws Exception {
        AdminProperties properties = properties();
        properties.setPasswordFile(dir.resolve("absent").toString());
        AdminAuthService auth = new AdminAuthService(properties, new TestClock());
        assertFalse(auth.passwordConfigured());
        assertEquals(AdminAuthService.LoginResult.NOT_CONFIGURED, auth.login(auth.client(null, "127.0.0.1"), "x").result());
        Files.writeString(dir.resolve("absent"), "pbkdf2-sha256$1000$AAAA$AAAA");
        assertFalse(auth.passwordConfigured(), "too few iterations");
    }

    @Test void hashWrittenByThePythonScriptVerifies() {
        // hashlib.pbkdf2_hmac('sha256', b'Tr0ub4dor&3', bytes(range(16)), 200000), as scripts/admin_password.py writes it
        String python = "pbkdf2-sha256$200000$AAECAwQFBgcICQoLDA0ODw==$3MY6uCg7jim0rSS7pq+nZtEl94Eq0lsjtkozU0XP9Lk=";
        PasswordHash hash = PasswordHash.parse(python);
        assertTrue(hash.matches("Tr0ub4dor&3".toCharArray()));
        assertFalse(hash.matches("Tr0ub4dor&4".toCharArray()));
        byte[] salt = new byte[16];
        for (int i = 0; i < 16; i++) salt[i] = (byte) i;
        assertEquals(python, PasswordHash.encode("Tr0ub4dor&3".toCharArray(), salt, 200_000));
    }

    @Test void allowListRejectsMalformedNetworks() {
        assertThrows(IllegalArgumentException.class, () -> new NetworkAllowList(List.of("128.111.180.1/22")));
        assertThrows(IllegalArgumentException.class, () -> new NetworkAllowList(List.of("vpn.ucsb.edu")));
        NetworkAllowList list = new NetworkAllowList(List.of("128.111.237.64/31"));
        assertTrue(list.match("128.111.237.65").isPresent());
        assertFalse(list.match("128.111.237.66").isPresent());
        assertFalse(list.match("128.111.237.065x").isPresent());
    }
}
