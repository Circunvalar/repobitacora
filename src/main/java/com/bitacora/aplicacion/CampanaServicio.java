package com.bitacora.aplicacion;

import com.bitacora.dominio.modelo.Campana;
import com.bitacora.dominio.puerto.WolkvoxPuerto;
import com.bitacora.infraestructura.persistencia.CampanaEstadoEntidad;
import com.bitacora.infraestructura.persistencia.CampanaEstadoRepositorio;
import com.bitacora.infraestructura.persistencia.CargueEntidad;
import com.bitacora.infraestructura.persistencia.CargueRepositorio;
import com.bitacora.infraestructura.persistencia.EventoCampanaEntidad;
import com.bitacora.infraestructura.persistencia.EventoCampanaRepositorio;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
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
 * Catálogo de campañas y control de activación.
 * <ul>
 *   <li>Cuando la app inicia o detiene una campaña, guarda la hora exacta (HORA INICIO / HORA FINAL de la bitácora).</li>
 *   <li>Cada {@code bitacora.campanas.escaneo} (5 min por defecto) consulta en Wolkvox qué campañas están activas;
 *       si alguna cambió de estado por fuera de la app, deja el evento en el historial y actualiza la bitácora.</li>
 *   <li>Cada cambio se publica en tiempo real a las pantallas abiertas.</li>
 * </ul>
 */
@ApplicationScoped
public class CampanaServicio {

    private static final Logger LOG = Logger.getLogger(CampanaServicio.class);

    public record ResultadoEscaneo(LocalDateTime momento, int revisadas, int activas, int cambios, String error) {}

    private final WolkvoxPuerto wolkvox;
    private final CargueRepositorio cargues;
    private final EventoCampanaRepositorio eventos;
    private final CampanaEstadoRepositorio estados;
    private final AvisosCampanas avisos;
    private final BitacoraConfig config;
    private final Clock reloj;
    private final Map<String, Campana> cache = new ConcurrentHashMap<>();
    private volatile ResultadoEscaneo ultimoEscaneo;

    public CampanaServicio(@Named("wolkvoxActivo") WolkvoxPuerto wolkvox, CargueRepositorio cargues,
                           EventoCampanaRepositorio eventos, CampanaEstadoRepositorio estados, AvisosCampanas avisos,
                           BitacoraConfig config, Clock reloj) {
        this.wolkvox = wolkvox;
        this.cargues = cargues;
        this.eventos = eventos;
        this.estados = estados;
        this.avisos = avisos;
        this.config = config;
        this.reloj = reloj;
    }

    /** Campañas existentes en el servidor de Wolkvox (con refrescar=true se vuelven a pedir). */
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

    public LocalDateTime iniciar(String campanaId, String origen) {
        Campana c = buscar(campanaId);
        wolkvox.iniciarCampana(c);
        LocalDateTime ahora = ahora();
        cache.put(c.id(), new Campana(c.id(), c.nombre(), c.tipo(), true, c.registros()));
        QuarkusTransaction.requiringNew().run(() -> aplicarCambio(c, true, ahora, origen, true));
        publicarCambio(c, true, ahora, origen);
        return ahora;
    }

    public LocalDateTime detener(String campanaId, String origen) {
        Campana c = buscar(campanaId);
        wolkvox.detenerCampana(c);
        LocalDateTime ahora = ahora();
        cache.put(c.id(), new Campana(c.id(), c.nombre(), c.tipo(), false, c.registros()));
        QuarkusTransaction.requiringNew().run(() -> aplicarCambio(c, false, ahora, origen, true));
        publicarCambio(c, false, ahora, origen);
        return ahora;
    }

    /** Escaneo periódico de campañas activas/inactivas. */
    @Scheduled(every = "{bitacora.campanas.escaneo}", delayed = "15s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void escaneoProgramado() {
        escanear();
    }

    /** Pide a Wolkvox el estado de todas las campañas y registra los cambios. */
    public synchronized ResultadoEscaneo escanear() {
        LocalDateTime ahora = ahora();
        List<Campana> todas;
        try {
            todas = listar(true);
        } catch (RuntimeException e) {
            LOG.errorf("Escaneo de campañas falló: %s", e.getMessage());
            ultimoEscaneo = new ResultadoEscaneo(ahora, 0, 0, 0, e.getMessage());
            avisos.publicar(new AvisosCampanas.Aviso("ESCANEO", null, null, null, ahora, "ESCANEO", null, null, e.getMessage()));
            return ultimoEscaneo;
        }
        List<Campana> cambiadas = QuarkusTransaction.requiringNew().call(() -> {
            List<Campana> cambios = new java.util.ArrayList<>();
            for (Campana c : todas) {
                CampanaEstadoEntidad e = estados.findById(c.id());
                if (e == null) {
                    e = new CampanaEstadoEntidad();
                    e.campanaId = c.id();
                    e.activa = c.activa();
                    e.nombre = c.nombre();
                    e.tipo = c.tipo();
                    e.registros = c.registros();
                    e.ultimaRevision = ahora;
                    estados.persist(e);
                } else if (c.activa() != null && !c.activa().equals(e.activa)) {
                    aplicarCambio(c, c.activa(), ahora, "ESCANEO", false);
                    e.activa = c.activa();
                    e.desde = ahora;
                    cambios.add(c);
                }
                e.nombre = c.nombre();
                e.tipo = c.tipo();
                e.registros = c.registros();
                e.ultimaRevision = ahora;
            }
            return cambios;
        });
        cambiadas.forEach(c -> publicarCambio(c, c.estaActiva(), ahora, "ESCANEO"));
        int activas = (int) todas.stream().filter(Campana::estaActiva).count();
        ultimoEscaneo = new ResultadoEscaneo(ahora, todas.size(), activas, cambiadas.size(), null);
        avisos.publicar(new AvisosCampanas.Aviso("ESCANEO", null, null, null, ahora, "ESCANEO", activas, todas.size(), null));
        if (!cambiadas.isEmpty()) LOG.infof("Escaneo: %d cambio(s) de estado en %d campañas", cambiadas.size(), todas.size());
        return ultimoEscaneo;
    }

    public ResultadoEscaneo ultimoEscaneo() {
        return ultimoEscaneo;
    }

    public List<CampanaEstadoEntidad> estados() {
        return QuarkusTransaction.joiningExisting().call(estados::todas);
    }

    public List<EventoCampanaEntidad> historial(LocalDateTime desde, LocalDateTime hasta) {
        return QuarkusTransaction.joiningExisting().call(() -> eventos.entre(desde, hasta));
    }

    /**
     * Deja el evento en el historial y pasa la hora a la bitácora: al activarse, HORA INICIO de los cargues
     * del día que aún no la tienen; al detenerse, HORA FINAL de los que estaban activos.
     */
    private void aplicarCambio(Campana c, boolean activa, LocalDateTime momento, String origen, boolean actualizarEstado) {
        EventoCampanaEntidad ev = new EventoCampanaEntidad();
        ev.campanaId = c.id();
        ev.campanaNombre = c.nombre();
        ev.tipo = activa ? EventoCampanaEntidad.Tipo.INICIO : EventoCampanaEntidad.Tipo.DETENCION;
        ev.momento = momento;
        ev.origen = origen;
        eventos.persist(ev);
        for (CargueEntidad cg : cargues.activosDeCampanaEnFecha(c.id(), momento.toLocalDate())) {
            if (activa) {
                if (cg.inicioCampana == null) cg.inicioCampana = momento;
                cg.finCampana = null;
            } else if (cg.inicioCampana != null && cg.finCampana == null) {
                cg.finCampana = momento;
            }
        }
        if (actualizarEstado) {
            CampanaEstadoEntidad e = estados.findById(c.id());
            if (e == null) {
                e = new CampanaEstadoEntidad();
                e.campanaId = c.id();
                e.nombre = c.nombre();
                e.tipo = c.tipo();
                e.registros = c.registros();
                e.ultimaRevision = momento;
                estados.persist(e);
            }
            e.activa = activa;
            e.desde = momento;
            e.ultimaRevision = momento;
        }
    }

    private void publicarCambio(Campana c, boolean activa, LocalDateTime momento, String origen) {
        avisos.publicar(new AvisosCampanas.Aviso("CAMBIO", c.id(), c.nombre(), activa ? "INICIO" : "DETENCION",
                momento, origen, null, null, null));
    }

    private LocalDateTime ahora() {
        return LocalDateTime.now(reloj).truncatedTo(ChronoUnit.SECONDS);
    }

    /** Si está activado, cada minuto inicia o detiene las campañas según el horario planeado de sus cargues. */
    @Scheduled(every = "60s", delayed = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void activarSegunHorario() {
        if (!config.campanas().autoActivar()) return;
        LocalDate hoy = LocalDate.now(reloj);
        LocalTime ahora = LocalTime.now(reloj);
        for (CargueEntidad cg : QuarkusTransaction.requiringNew().call(() -> cargues.delDia(hoy))) {
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
