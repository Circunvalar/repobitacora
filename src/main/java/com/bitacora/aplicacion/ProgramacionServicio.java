package com.bitacora.aplicacion;

import com.bitacora.infraestructura.persistencia.*;
import com.bitacora.infraestructura.persistencia.ProgramacionEntidad.Estado;
import com.bitacora.infraestructura.wolkvox.WolkvoxException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Encendido programado de campañas. Solo enciende (nunca apaga) y solo el día en que se programó:
 * cada día se sube una base distinta en la misma campaña, así que lo que no se ejecutó queda VENCIDA.
 */
@ApplicationScoped
public class ProgramacionServicio {

    private static final Logger LOG = Logger.getLogger(ProgramacionServicio.class);

    public record Vista(Long id, Long campanaGuardadaId, String campanaId, String campanaNombre, String servidor,
                        LocalDate fecha, LocalTime hora, Long cargueId, String nombreLista, String estado,
                        LocalDateTime ejecutada, String error) {}

    private final ProgramacionRepositorio repo;
    private final OperacionServicio operaciones;
    private final CampanaRepositorio campanas;
    private final CargueRepositorio cargues;
    private final CampanaServicio control;
    private final AvisosCampanas avisos;
    private final Clock reloj;

    public ProgramacionServicio(ProgramacionRepositorio repo, OperacionServicio operaciones, CampanaRepositorio campanas,
                                CargueRepositorio cargues, CampanaServicio control, AvisosCampanas avisos, Clock reloj) {
        this.repo = repo;
        this.operaciones = operaciones;
        this.campanas = campanas;
        this.cargues = cargues;
        this.control = control;
        this.avisos = avisos;
        this.reloj = reloj;
    }

    /** Programa el encendido de hoy. Si la hora ya pasó, se enciende en la siguiente revisión (máx. 30 s). */
    public Vista programar(long campanaGuardadaId, String campanaId, LocalTime hora, Long cargueId) {
        if (hora == null) {
            throw ErrorNegocio.datos("Falta la hora de encendido", "Indique a qué hora se debe prender la campaña.",
                    "Escriba la hora en formato 24 h, por ejemplo 08:00.");
        }
        String numero = campanaId == null || campanaId.isBlank() ? operaciones.campanaGuardada(campanaGuardadaId).campanaId() : campanaId;
        LocalDateTime ahora = ahora();
        Vista v = QuarkusTransaction.requiringNew().call(() -> {
            operaciones.campanaRegistrada(campanaGuardadaId, numero); // falla con un mensaje claro si no existe
            ProgramacionEntidad p = new ProgramacionEntidad();
            p.operacionId = campanaGuardadaId;
            p.campanaId = numero;
            p.fecha = ahora.toLocalDate();
            p.hora = hora.truncatedTo(ChronoUnit.MINUTES);
            p.cargueId = cargueId;
            p.estado = Estado.PENDIENTE;
            p.creado = ahora;
            repo.persist(p);
            return vista(p);
        });
        LOG.infof("Encendido programado: campaña %s a las %s", numero, v.hora());
        return v;
    }

    public List<Vista> delDia(LocalDate fecha) {
        return QuarkusTransaction.requiringNew().call(() -> repo.delDia(fecha).stream().map(this::vista).toList());
    }

    public Vista cambiarHora(long id, LocalTime hora) {
        if (hora == null) throw ErrorNegocio.datos("Falta la hora", "Indique la nueva hora de encendido.", "Ejemplo: 08:00.");
        return QuarkusTransaction.requiringNew().call(() -> {
            ProgramacionEntidad p = pendiente(id);
            p.hora = hora.truncatedTo(ChronoUnit.MINUTES);
            CargueEntidad c = p.cargueId != null ? cargues.findById(p.cargueId) : null;
            if (c != null) c.horaInicioPlan = p.hora;
            return vista(p);
        });
    }

    public Vista cancelar(long id) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ProgramacionEntidad p = pendiente(id);
            p.estado = Estado.CANCELADA;
            return vista(p);
        });
    }

    /** Cada 30 s: enciende las campañas cuya hora ya llegó. */
    @Scheduled(every = "30s", delayed = "20s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void revisar() {
        ejecutarPendientes();
    }

    public int ejecutarPendientes() {
        LocalDateTime ahora = ahora();
        List<ProgramacionEntidad> listas = QuarkusTransaction.requiringNew().call(() -> {
            repo.vencer(ahora.toLocalDate());
            return repo.porEjecutar(ahora.toLocalDate(), ahora.toLocalTime());
        });
        int hechas = 0;
        for (ProgramacionEntidad p : listas) {
            String error = null;
            try {
                control.iniciar(p.operacionId, p.campanaId, "PROGRAMADO");
                hechas++;
            } catch (WolkvoxException e) {
                error = e.resumen() + " · " + e.sugerencia();
            } catch (ErrorNegocio e) {
                error = e.titulo() + ": " + e.getMessage();
            } catch (RuntimeException e) {
                error = e.getMessage();
            }
            final String err = error;
            QuarkusTransaction.requiringNew().run(() -> {
                ProgramacionEntidad x = repo.findById(p.id);
                x.estado = err == null ? Estado.EJECUTADA : Estado.FALLIDA;
                x.ejecutada = ahora();
                x.error = err == null ? null : err.length() > 1000 ? err.substring(0, 1000) : err;
            });
            if (err != null) {
                LOG.errorf("No se pudo encender la campaña %s programada a las %s: %s", p.campanaId, p.hora, err);
                avisos.publicar(new AvisosCampanas.Aviso("ERROR", p.operacionId, null, p.campanaId, null, null, ahora,
                        "PROGRAMADO", null, null, "No se pudo encender la campaña " + p.campanaId + " programada a las " + p.hora + ": " + err,
                        "Revise el token de la campaña o enciéndala a mano desde \"Campañas en vivo\"."));
            }
        }
        return hechas;
    }

    private ProgramacionEntidad pendiente(long id) {
        ProgramacionEntidad p = repo.findById(id);
        if (p == null) throw ErrorNegocio.noEncontrado("Programación no encontrada", "No existe la programación " + id + ".", "Recargue la página.");
        if (p.estado != Estado.PENDIENTE) {
            throw ErrorNegocio.conflicto("Ya no se puede cambiar", "Esta programación está " + p.estado.name().toLowerCase() + ".",
                    "Solo se pueden cambiar las que siguen pendientes.");
        }
        return p;
    }

    private Vista vista(ProgramacionEntidad p) {
        CampanaEntidad c = campanas.buscar(p.operacionId, p.campanaId).orElse(null);
        OperacionEntidad o = operaciones.entidad(p.operacionId);
        CargueEntidad cg = p.cargueId != null ? cargues.findById(p.cargueId) : null;
        return new Vista(p.id, p.operacionId, p.campanaId, c != null ? c.nombre : null, o.servidor, p.fecha, p.hora,
                p.cargueId, cg != null ? cg.nombreLista : null, p.estado.name(), p.ejecutada, p.error);
    }

    private LocalDateTime ahora() {
        return LocalDateTime.now(reloj).truncatedTo(ChronoUnit.SECONDS);
    }
}
