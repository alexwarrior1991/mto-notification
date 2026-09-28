package com.alejandro.mtonotification.configuration.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Recomputes the signature the publisher put on the bytes it sent.
 *
 * <p>Es la única comprobación de integridad que un consumidor puede rehacer. El {@code messageHash}
 * que viaja <b>dentro</b> del payload se calcula sobre el objeto antes de serializarlo, así que para
 * comprobarlo habría que deserializar y volver a serializar, y esa ida y vuelta no conserva la
 * identidad: un {@code 1.50} vuelve como {@code 1.5} y da otro resultado. La firma va en cabecera
 * precisamente porque se calcula sobre los bytes, que es lo único que llega igual a los dos
 * lados.</p>
 *
 * <p>Con secreto compartido es HMAC-SHA256 y protege de manipulación: quien altere el mensaje no
 * puede recalcularla. Sin secreto se degrada a un SHA-256 simple, que detecta corrupción pero no
 * manipulación, porque cualquiera que cambie el contenido puede recalcular el hash. Las dos cosas
 * valen para algo; sólo la primera es seguridad.</p>
 *
 * <h2>Cuando los dos lados no están configurados igual</h2>
 *
 * <p>El emisor dice en una cabecera qué algoritmo usó, y ahí está el desastre que hay que evitar: si
 * él firma con HMAC porque tiene secreto y aquí no lo hay, cualquier comparación falla y el servicio
 * mandaría <b>todos</b> los mensajes válidos a la DLQ. Eso no es una firma incorrecta, es una
 * configuración incompleta, y se distingue: en {@link MessageSignatureMode#OPTIONAL} se acepta el
 * mensaje avisando una vez, y en {@link MessageSignatureMode#REQUIRED} se rechaza con un mensaje que
 * dice cuál de los dos lados falta por configurar.</p>
 */
public class MessagePayloadSignatureVerifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(MessagePayloadSignatureVerifier.class);

    static final String HMAC_ALGORITHM = "HmacSHA256";
    static final String DIGEST_ALGORITHM = "SHA-256";

    /** Nombres tal y como los escribe {@code mto-configuration} en la cabecera. */
    static final String HMAC_ALGORITHM_HEADER_VALUE = "HMAC-SHA256";
    static final String DIGEST_ALGORITHM_HEADER_VALUE = "SHA-256";

    private final MessageSignatureProperties properties;

    /**
     * Una configuración desparejada no cambia de un mensaje al siguiente: avisar en cada entrega
     * llenaría el log sin añadir nada. Se avisa en la primera y el resto va a DEBUG.
     */
    private final AtomicBoolean mismatchAlreadyReported = new AtomicBoolean();

    public MessagePayloadSignatureVerifier(MessageSignatureProperties properties) {
        this.properties = properties;

        if (properties.mode() == MessageSignatureMode.DISABLED) {
            LOGGER.warn("Message signature verification is disabled: nothing checks that what arrives is "
                    + "what mto-configuration sent. Set app.messaging.signature.mode to OPTIONAL or REQUIRED.");
        } else if (!properties.hasSecret()) {
            LOGGER.info("Message signatures are verified with plain {}, which detects corruption but not "
                            + "tampering: whoever alters a message can recompute it. Share "
                            + "app.messaging.signature.secret with mto-configuration to use HMAC.",
                    DIGEST_ALGORITHM);
        }
    }

    /**
     * Comprueba la firma de un mensaje y decide si hay que rechazarlo.
     *
     * @param payload            los bytes recibidos, sin reserializar
     * @param signature          valor de la cabecera {@code messageSignature}, o {@code null}
     * @param declaredAlgorithm  valor de la cabecera {@code messageSignatureAlgorithm}, o {@code null}
     * @return el motivo del rechazo, o vacío si el mensaje se acepta
     */
    public Optional<String> rejectionReason(byte[] payload, String signature, String declaredAlgorithm) {
        if (properties.mode() == MessageSignatureMode.DISABLED) {
            return Optional.empty();
        }

        if (signature == null || signature.isBlank()) {
            return whenNotVerifiable("the message carries no signature");
        }

        String algorithm = expectedAlgorithm();

        if (declaredAlgorithm != null && !algorithm.equalsIgnoreCase(declaredAlgorithm.trim())) {
            return whenNotVerifiable(("it is signed with %s and this service can only compute %s, so the two "
                    + "sides do not share the same app.messaging.signature.secret").formatted(
                            declaredAlgorithm, algorithm));
        }

        if (!matches(payload, signature)) {
            // Firma presente, comprobable y distinta: o el mensaje viene alterado o llega corrupto.
            // No depende del modo, porque aceptarlo seria peor que no comprobar nada.
            return Optional.of("its signature does not match the bytes received (algorithm " + algorithm + ")");
        }

        return Optional.empty();
    }

    private Optional<String> whenNotVerifiable(String reason) {
        if (properties.mode() == MessageSignatureMode.REQUIRED) {
            return Optional.of(reason);
        }

        if (mismatchAlreadyReported.compareAndSet(false, true)) {
            LOGGER.warn("Accepting a message that cannot be verified because {}. Messages are going "
                            + "unverified; set app.messaging.signature.mode=REQUIRED once both services share "
                            + "the secret. Further occurrences are logged at DEBUG.", reason);
        } else {
            LOGGER.debug("Accepting an unverifiable message because {}", reason);
        }

        return Optional.empty();
    }

    private String expectedAlgorithm() {
        return properties.hasSecret() ? HMAC_ALGORITHM_HEADER_VALUE : DIGEST_ALGORITHM_HEADER_VALUE;
    }

    /**
     * Comparación en tiempo constante. Comparar firmas con {@code equals} filtra información: el
     * tiempo que tarda en volver dice cuántos caracteres iniciales acertó quien la envió, y con eso
     * se adivina una firma válida byte a byte.
     */
    private boolean matches(byte[] payload, String signature) {
        return MessageDigest.isEqual(
                sign(payload).getBytes(StandardCharsets.UTF_8),
                signature.trim().getBytes(StandardCharsets.UTF_8));
    }

    private String sign(byte[] payload) {
        return properties.hasSecret() ? hmac(payload) : digest(payload);
    }

    private String hmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot compute the message signature", exception);
        }
    }

    private String digest(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(DIGEST_ALGORITHM).digest(payload));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot compute the message digest", exception);
        }
    }
}
