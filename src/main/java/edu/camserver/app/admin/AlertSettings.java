package edu.camserver.app.admin;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Email alert configuration, edited in the admin panel and stored in
 * {@code <app.admin.data-dir>/alerts.json} (readable by root only).
 *
 * @param downAfterMinutes a component must stay in trouble this long before mail goes out, so
 *                         deploy restarts (the seeing stream drops for ~20 s) do not alert
 * @param repeatHours      remind again while a problem lasts; 0 sends one mail per problem
 * @param muted            component keys ({@code camera:<id>}, {@code seeing}, {@code server},
 *                         {@code health-checks}) that never alert
 */
public record AlertSettings(boolean enabled, List<String> recipients, String smtpHost, int smtpPort, String security,
                            String username, String password, String from, int downAfterMinutes, boolean alertOnWarn,
                            int repeatHours, List<String> muted) {

    static final Set<String> SECURITY = Set.of("none", "starttls", "starttls-required", "ssl");

    public static AlertSettings defaults() {
        // smtp.ucsb.edu is the campus relay; port 25 is reachable from armageddon.
        return new AlertSettings(false, List.of(), "smtp.ucsb.edu", 25, "starttls", null, null,
                "All Sky Cam <allsky-alerts@deepspace.ucsb.edu>", 10, false, 24, List.of());
    }

    /** Validated copy; throws {@link IllegalArgumentException} with a message fit for the panel. */
    public AlertSettings validated() {
        List<String> addresses = new ArrayList<>();
        for (String recipient : recipients == null ? List.<String>of() : recipients) {
            if (recipient == null || recipient.isBlank()) continue;
            addresses.add(checkAddress(recipient.trim(), "Recipient"));
        }
        if (addresses.size() > 10) throw new IllegalArgumentException("At most 10 recipients");
        if (enabled && addresses.isEmpty()) throw new IllegalArgumentException("Add a recipient before enabling alerts");
        if (smtpHost == null || !smtpHost.matches("[A-Za-z0-9.-]{1,253}")) throw new IllegalArgumentException("Invalid SMTP host");
        if (smtpPort < 1 || smtpPort > 65535) throw new IllegalArgumentException("Invalid SMTP port");
        if (!SECURITY.contains(security)) throw new IllegalArgumentException("Security must be one of " + SECURITY);
        if (from == null || from.isBlank()) throw new IllegalArgumentException("Set a sender address");
        checkAddress(from.trim(), "Sender");
        if (downAfterMinutes < 1 || downAfterMinutes > 1440) throw new IllegalArgumentException("Delay must be 1–1440 minutes");
        if (repeatHours < 0 || repeatHours > 720) throw new IllegalArgumentException("Reminder interval must be 0–720 hours");
        String user = username == null || username.isBlank() ? null : username.trim();
        return new AlertSettings(enabled, List.copyOf(addresses), smtpHost.trim(), smtpPort, security, user,
                user == null ? null : password, from.trim(), downAfterMinutes, alertOnWarn, repeatHours,
                List.copyOf(new LinkedHashSet<>(muted == null ? List.of() : muted)));
    }

    /** Same settings with the stored SMTP password kept when the panel sends none. */
    public AlertSettings keepingPassword(AlertSettings stored) {
        boolean keep = (password == null || password.isEmpty()) && stored != null;
        return new AlertSettings(enabled, recipients, smtpHost, smtpPort, security, username,
                keep ? stored.password() : password, from, downAfterMinutes, alertOnWarn, repeatHours, muted);
    }

    /** What the panel may see: the password is replaced by whether one is set. */
    public java.util.Map<String, Object> redacted() {
        java.util.Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("enabled", enabled);
        view.put("recipients", recipients);
        view.put("smtpHost", smtpHost);
        view.put("smtpPort", smtpPort);
        view.put("security", security);
        view.put("username", username);
        view.put("passwordSet", password != null && !password.isEmpty());
        view.put("from", from);
        view.put("downAfterMinutes", downAfterMinutes);
        view.put("alertOnWarn", alertOnWarn);
        view.put("repeatHours", repeatHours);
        view.put("muted", muted);
        return view;
    }

    private static String checkAddress(String value, String label) {
        try {
            InternetAddress address = new InternetAddress(value, true);
            address.validate();
            return value;
        } catch (AddressException e) {
            throw new IllegalArgumentException(label + " address is invalid: " + value);
        }
    }
}
