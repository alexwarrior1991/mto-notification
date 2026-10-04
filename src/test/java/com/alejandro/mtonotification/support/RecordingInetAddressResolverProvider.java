package com.alejandro.mtonotification.support;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

/**
 * El resolvedor de nombres de la JVM de los tests: apunta cada nombre que se resuelve y delega en el
 * de la JDK, asi que nada cambia para el resto de tests (Testcontainers, el contexto completo). Sirve
 * para probar que algo no consulta el DNS: el filtro de IP de los accesos. Lo registra
 * {@code META-INF/services/java.net.spi.InetAddressResolverProvider}, y la JDK lo carga en la primera
 * resolucion de la JVM.
 */
public final class RecordingInetAddressResolverProvider extends InetAddressResolverProvider {

    private static final Queue<String> LOOKUPS = new ConcurrentLinkedQueue<>();

    /** Si en esta JVM se ha resuelto ese nombre. */
    public static boolean lookedUp(String host) {
        return LOOKUPS.contains(host);
    }

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy) throws UnknownHostException {
                LOOKUPS.add(host);
                return builtin.lookupByName(host, lookupPolicy);
            }

            @Override
            public String lookupByAddress(byte[] address) throws UnknownHostException {
                return builtin.lookupByAddress(address);
            }
        };
    }

    @Override
    public String name() {
        return "recording";
    }
}
