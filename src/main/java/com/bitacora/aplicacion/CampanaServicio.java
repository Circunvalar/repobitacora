package com.bitacora.aplicacion;

import com.bitacora.dominio.modelo.Campana;
import com.bitacora.dominio.puerto.WolkvoxPuerto;
import com.bitacora.infraestructura.persistencia.CargueEntidad;
import com.bitacora.infraestructura.persistencia.CargueRepositorio;
import com.bitacora.infraestructura.persistencia.EventoCampanaEntidad;
import com.bitacora.infraestructura.persistencia.EventoCampanaRepositorio;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catálogo de campañas y control de activación. Cuando la app inicia o detiene una campaña,
 * guarda la hora exacta: esa es la HORA INICIO / HORA FINAL de la bitácora.
 */
@ApplicationScoped
public class CampanaServicio {

    private static final Logger LOG = Logger.getLogger(CampanaServicio.class);

    private final WolkvoxPuerto wolkvox;
    private final CargueRepositorio cargues;
    private final EventoCampanaRepositorio eventos;
    private final BitacoraConfig config;
    private final Clock reloj;
    private final Map<String, Campana> cache = new ConcurrentHashMap<>();

    public CampanaServicio(@Named("wolkvoxActivo") WolkvoxPuerto wolkvox, CargueRepositorio cargues,
                           EventoCampanaRepositorio eventos, BitacoraConfig config, Clock reloj) {
        this.wolkvox = wolkvox;
        this.cargues = cargues;
        this.eventos = eventos;
        this.config = config;
        this.reloj = reloj;
    }

    public List<Campana> listar(boolean refrescar) {
        if (refrescar || cache.isEmpty()) {
            List<Campana> todas = wolkvox.campanas();
            cache.clear();
            todas.forEach(c -> cache.put(c.id(), c));
        }
        return cache.values().stream().sorted((a, b) -> a.nombre().compareToIgnoreCase(b.nombre())).toList();
    }

    public Campana buscar(String id) {
        if (!cache.containsKey(id)) listar(true);
        Campana c = cache.get(id);
        if (c == null) throw new NoSuchElementException("La campaña " + id + " no existe en Wolkvox");
        return c;
    }

    @Transactional
    public LocalDateTime iniciar(String campanaId, String origen) {
        Campana c = buscar(campanaId);
        wolkvox.iniciarCampana(c);
        LocalDateTime ahora = LocalDateTime.now(reloj).truncatedTo(ChronoUnit.SECONDS);
        registrar(campanaId, EventoCampanaEntidad.Tipo.INICIO, ahora, origen);
        for (CargueEntidad cg : cargues.activosDeCampanaEnFecha(campanaId, ahora.toLocalDate())) {
            if (cg.inicioCampana == null) cg.inicioCampana = ahora;
            cg.finCampana = null;
        }
        return ahora;
    }

    @Transactional
    public LocalDateTime detener(String campanaId, String origen) {
        Campana c = buscar(campanaId);
        wolkvox.detenerCampana(c);
        LocalDateTime ahora = LocalDateTime.now(reloj).truncatedTo(ChronoUnit.SECONDS);
        registrar(campanaId, EventoCampanaEntidad.Tipo.DETENCION, ahora, origen);
        for (CargueEntidad cg : cargues.activosDeCampanaEnFecha(campanaId, ahora.toLocalDate())) {
            if (cg.inicioCampana != null && cg.finCampana == null) cg.finCampana = ahora;
        }
        return ahora;
    }

    private void registrar(String campanaId, EventoCampanaEntidad.Tipo tipo, LocalDateTime momento, String origen) {
        EventoCampanaEntidad e = new EventoCampanaEntidad();
        e.campanaId = campanaId;
        e.tipo = tipo;
        e.momento = momento;
        e.origen = origen;
        eventos.persist(e);
    }

    /** Si está activado, cada minuto inicia o detiene las campañas según el horario planeado de sus cargues. */
    @Scheduled(every = "60s", delayed = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void activarSegunHorario() {
        if (!config.campanas().autoActivar()) return;
        LocalDate hoy = LocalDate.now(reloj);
        LocalTime ahora = LocalTime.now(reloj);
        for (CargueEntidad cg : cargues.delDia(hoy)) {
            try {
                if (cg.registrosCargados <= 0) continue;
                if (cg.inicioCampana == null && cg.horaInicioPlan != null && !ahora.isBefore(cg.horaInicioPlan)
                        && (cg.horaFinPlan == null || ahora.isBefore(cg.horaFinPlan))) {
                    iniciar(cg.campanaId, "HORARIO");
                } else if (cg.inicioCampana != null && cg.finCampana == null && cg.horaFinPlan != null
                        && !ahora.isBefore(cg.horaFinPlan)) {
                    detener(cg.campanaId, "HORARIO");
                }
            } catch (RuntimeException e) {
                LOG.errorf("No se pudo aplicar el horario de la campaña %s: %s", cg.campanaId, e.getMessage());
            }
        }
    }
}
