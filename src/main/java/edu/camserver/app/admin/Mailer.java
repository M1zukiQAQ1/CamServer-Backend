package edu.camserver.app.admin;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/** Plain-text mail over SMTP with the settings from the admin panel. */
@Component
public class Mailer {

    public void send(AlertSettings settings, List<String> recipients, String subject, String body) throws MessagingException {
        if (recipients.isEmpty()) throw new MessagingException("No recipient address is configured");
        Properties props = new Properties();
        props.put("mail.smtp.host", settings.smtpHost());
        props.put("mail.smtp.port", Integer.toString(settings.smtpPort()));
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "20000");
        props.put("mail.smtp.writetimeout", "20000");
        props.put("mail.smtp.localhost", "armageddon.deepspace.ucsb.edu");
        switch (settings.security()) {
            case "ssl" -> {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            case "starttls-required" -> {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
                props.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            case "starttls" -> props.put("mail.smtp.starttls.enable", "true");
            default -> { }
        }
        boolean authenticate = settings.username() != null && !settings.username().isBlank();
        props.put("mail.smtp.auth", Boolean.toString(authenticate));
        Session session = authenticate
                ? Session.getInstance(props, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(settings.username(), settings.password() == null ? "" : settings.password());
                    }
                })
                : Session.getInstance(props);
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(settings.from(), true));
        for (String recipient : recipients) {
            message.addRecipient(Message.RecipientType.TO, new InternetAddress(recipient, true));
        }
        message.setSubject(subject, StandardCharsets.UTF_8.name());
        message.setText(body, StandardCharsets.UTF_8.name());
        message.setSentDate(new Date());
        Transport.send(message);
    }
}
