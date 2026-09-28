package com.alejandro.mtonotification.infrastructure.mail;

import com.alejandro.mtonotification.application.dto.delivery.Recipient;
import com.alejandro.mtonotification.application.service.DeliveryChannel;
import com.alejandro.mtonotification.configuration.notification.NotificationProperties;
import com.alejandro.mtonotification.domain.model.DeliveryChannels;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Notification;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;

/**
 * El canal de correo: un mensaje por persona, con texto y HTML (todo escapado: el titulo y el
 * cuerpo salen de plantillas sobre datos de las fuentes), asunto {@code [MTO][GRAVEDAD] titulo} y
 * el enlace hecho absoluto contra el backoffice. Se registra solo con el correo encendido.
 */
public class EmailChannel implements DeliveryChannel {

    private final JavaMailSender mailSender;
    private final NotificationProperties.Email email;

    public EmailChannel(JavaMailSender mailSender, NotificationProperties.Email email) {
        this.mailSender = mailSender;
        this.email = email;
    }

    @Override
    public String channel() {
        return DeliveryChannels.EMAIL;
    }

    @Override
    public void send(Notification notification, Recipient recipient) throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(email.from());
        helper.setTo(recipient.address());
        helper.setSubject(subject(notification));
        helper.setText(text(notification), html(notification));
        mailSender.send(message);
    }

    String subject(Notification notification) {
        return email.subjectPrefix() + "[" + notification.getSeverity() + "] " + notification.getTitle();
    }

    String absoluteLink(String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        if (link.startsWith("http://") || link.startsWith("https://")) {
            return link;
        }
        return email.linkBaseUrl() + (link.startsWith("/") ? link : "/" + link);
    }

    String text(Notification notification) {
        StringBuilder text = new StringBuilder(notification.getTitle()).append("\n\n");
        if (notification.getBody() != null) {
            text.append(notification.getBody()).append("\n\n");
        }
        String link = absoluteLink(notification.getLink());
        if (link != null) {
            text.append(link).append("\n");
        }
        return text.toString();
    }

    String html(Notification notification) {
        StringBuilder html = new StringBuilder("<html><body style=\"font-family:sans-serif\">");
        html.append("<h2>").append(HtmlUtils.htmlEscape(notification.getTitle())).append("</h2>");
        if (notification.getBody() != null) {
            html.append("<p>").append(HtmlUtils.htmlEscape(notification.getBody()).replace("\n", "<br>")).append("</p>");
        }
        String link = absoluteLink(notification.getLink());
        if (link != null) {
            String escaped = HtmlUtils.htmlEscape(link);
            html.append("<p><a href=\"").append(escaped).append("\">").append(escaped).append("</a></p>");
        }
        html.append("<p style=\"color:#666;font-size:small\">").append(HtmlUtils.htmlEscape(notification.getSeverity().name()))
                .append(" &middot; ").append(HtmlUtils.htmlEscape(notification.getCategory().name())).append("</p>");
        html.append("</body></html>");
        return html.toString();
    }
}
