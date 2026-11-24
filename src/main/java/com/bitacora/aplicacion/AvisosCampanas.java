package com.bitacora.aplicacion;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Canal en tiempo real hacia las pantallas abiertas (Server-Sent Events): cada activación/detención
 * y cada escaneo se publica aquí, y el navegador lo muestra sin recargar.
 */
@ApplicationScoped
public class AvisosCampanas {

    /**
     * tipo: CAMBIO (una campaña se activó o se detuvo), ESCANEO (terminó una revisión), PING (mantener la conexión).
     * evento: INICIO | DETENCION cuando tipo = CAMBIO.
     */
    public record Aviso(String tipo, Long operacionId, String operacionNombre, String campanaId, String campanaNombre,
                        String evento, LocalDateTime momento, String origen, Integer activas, Integer revisadas,
                        String error, String sugerencia) {

        static Aviso ping() {
            return new Aviso("PING", null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    private final BroadcastProcessor<Aviso> canal = BroadcastProcessor.create();

    public void publicar(Aviso a) {
        canal.onNext(a);
    }

    /** Flujo para un navegador: avisos reales más un PING cada 25 s para que la conexión no se corte. */
    public Multi<Aviso> suscribir() {
        Multi<Aviso> ping = Multi.createFrom().ticks().every(Duration.ofSeconds(25)).map(t -> Aviso.ping());
        return Multi.createBy().merging().streams(canal, ping);
    }
}
