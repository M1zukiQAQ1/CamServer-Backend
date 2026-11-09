package edu.camserver.app.admin;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Admin panel ({@code /admin} on the site, {@code /api/admin/**} here), bound from {@code app.admin}.
 */
@Component
@ConfigurationProperties(prefix = "app.admin")
@Getter
@Setter
public class AdminProperties {

    /**
     * IPv4 networks (CIDR) the panel answers. The client address is nginx's {@code X-Real-IP};
     * anything else, including IPv6 and a missing header from a non-loopback peer, is refused.
     */
    private List<String> allowedNetworks = new ArrayList<>();

    /** Also accept requests that reach the loopback port directly (an SSH tunnel, local tools). */
    private boolean allowLoopback = true;

    /**
     * One line {@code pbkdf2-sha256$<iterations>$<salt base64>$<hash base64>}; written by
     * {@code scripts/admin_password.py}. Without it every login is refused.
     */
    private String passwordFile = "./data/admin/password";

    /** Settings and state of the panel (alert configuration, alert history). */
    private String dataDir = "./data/admin";

    /** A login lasts at most this long ... */
    private int sessionHours = 12;

    /** ... and ends earlier after this much inactivity. */
    private int idleMinutes = 120;

    /** Failed logins from one address before it is locked out for {@link #lockoutMinutes}. */
    private int maxFailuresPerAddress = 5;

    /** Failed logins from all addresses together before every login is locked out. */
    private int maxFailuresTotal = 30;

    private int lockoutMinutes = 15;
}
