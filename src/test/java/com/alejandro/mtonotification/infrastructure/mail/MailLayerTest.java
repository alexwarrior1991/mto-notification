package com.alejandro.mtonotification.infrastructure.mail;

import com.alejandro.mtonotification.application.dto.delivery.Recipient;
import com.alejandro.mtonotification.configuration.notification.NotificationProperties;
import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Notification;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** El canal de correo contra un SMTP de verdad: lo que sale por el cable, no un doble del sender. */
class MailLayerTest {

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.SMTP);

    private final NotificationProperties.Email email = new NotificationProperties.Email(true, "notificaciones@mto.local", "[MTO]", "http://backoffice:8085/", 20);

    private EmailChannel channel() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("localhost");
        sender.setPort(GREEN_MAIL.getSmtp().getPort());
        return new EmailChannel(sender, email);
    }

    @Test
    void oneEmailPerRecipientWithThePrefixedSubjectAndTheAbsoluteLink() throws Exception {
        Notification notification = Notification.builder().ruleKey("access-login-streak").category(ActivityCategory.ACCESS)
                .severity(ActivitySeverity.CRITICAL).title("Racha de accesos fallidos: alice")
                .body("3 intentos <fallidos> & mas").link("/actividad/accesos?username=alice").build();

        channel().send(notification, new Recipient("ops", "ops@mto.local"));

        assertTrue(GREEN_MAIL.waitForIncomingEmail(5000, 1));
        MimeMessage received = GREEN_MAIL.getReceivedMessages()[0];
        assertEquals("[MTO][CRITICAL] Racha de accesos fallidos: alice", received.getSubject());
        assertEquals("ops@mto.local", received.getAllRecipients()[0].toString());
        assertEquals("notificaciones@mto.local", received.getFrom()[0].toString());
        String body = GreenMailUtil.getBody(received);
        assertTrue(body.contains("http://backoffice:8085/actividad/accesos?username=alice"), body);
        assertTrue(body.contains("&lt;fallidos&gt; &amp; mas"), "el HTML va escapado");
        assertTrue(body.contains("3 intentos <fallidos> & mas"), "y el texto plano, tal cual");
    }

    @Test
    void anAbsoluteLinkIsKeptAndAMissingBodyOrLinkIsFine() {
        EmailChannel channel = channel();
        Notification notification = Notification.builder().ruleKey("r").category(ActivityCategory.SYSTEM)
                .severity(ActivitySeverity.INFO).title("t").link("https://otro/sitio").build();
        assertEquals("https://otro/sitio", channel.absoluteLink(notification.getLink()));
        assertEquals("http://backoffice:8085/x", channel.absoluteLink("x"));
        assertTrue(channel.html(notification).contains("<h2>t</h2>"));
        assertFalse(channel.text(notification).contains("null"));
        assertEquals("[MTO][INFO] t", channel.subject(notification));
    }
}
