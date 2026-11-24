package com.bitacora.aplicacion;

import com.bitacora.dominio.modelo.Campana;
import com.bitacora.dominio.modelo.MetricaHora;
import com.bitacora.dominio.puerto.WolkvoxPuerto;
import com.bitacora.infraestructura.persistencia.CargueEntidad;
import com.bitacora.infraestructura.persistencia.CargueRepositorio;
import com.bitacora.infraestructura.persistencia.CorteHoraEntidad;
import com.bitacora.infraestructura.persistencia.CorteHoraRepositorio;
import com.bitacora.infraestructura.persistencia.EventoCampanaEntidad;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Al cierre de cada hora guarda el corte por campaña (agentes conectados, registros barridos, gestionados)
 * y actualiza TOTAL GESTIONADO de las listas activas. Solo consulta campañas que tienen cargues en el día.
 */
@ApplicationScoped
public class MetricasServicio {

    private static final Logger LOG = Logger.getLogger(MetricasServicio.class);

    private final WolkvoxPuerto wolkvox;
    private final CampanaServicio campanas;
    private final CargueRepositorio cargues;
    private final CorteHoraRepositorio cortes;
    private final BitacoraServicio bitacora;
    private final Clock reloj;

    public MetricasServicio(@Named("wolkvoxActivo") WolkvoxPuerto wolkvox, CampanaServicio campanas,
                            CargueRepositorio cargues, CorteHoraRepositorio cortes, BitacoraServicio bitacora, Clock reloj) {
        this.wolkvox = wolkvox;
        this.campanas = campanas;
        this.cargues = cargues;
        this.cortes = cortes;
        this.bitacora = bitacora;
        this.reloj = reloj;
    }

    @Scheduled(cron = "{bitacora.metricas.cron}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void alCierreDeHora() {
        // Solo la hora que acaba de cerrar y la que está en curso, para no gastar consumos de la API.
        LocalDateTime ahora = LocalDateTime.now(reloj);
        recolectar(ahora.toLocalDate(), ahora.truncatedTo(ChronoUnit.HOURS).minusHours(1));
        if (ahora.getHour() == 0) recolectar(ahora.toLocalDate().minusDays(1), ahora.truncatedTo(ChronoUnit.HOURS).minusHours(1));
    }

    /** Recolección completa del día (botón "Recolectar ahora"). */
    public int recolectar(LocalDate fecha) {
        return recolectar(fecha, null);
    }

    /**
     * Recolecta desde el inicio del día hasta la hora en curso (el corte de la hora en curso es parcial
     * y se completa en la siguiente ejecución). Es idempotente: vuelve a escribir los cortes existentes.
     */
    public int recolectar(LocalDate fecha, LocalDateTime desdeMinimo) {
        LocalDateTime hasta = LocalDateTime.now(reloj).truncatedTo(ChronoUnit.HOURS).plusHours(1);
        if (fecha.isBefore(hasta.toLocalDate())) hasta = fecha.plusDays(1).atStartOfDay();
        LocalDateTime desde = fecha.atStartOfDay();
        if (desdeMinimo != null && desdeMinimo.isAfter(desde)) desde = desdeMinimo;
        final LocalDateTime desdeF = desde;

        List<CargueEntidad> delDia = QuarkusTransaction.requiringNew().call(() -> cargues.delDia(fecha));
        Set<String> ids = delDia.stream().filter(c -> c.registrosCargados > 0).map(c -> c.campanaId).collect(Collectors.toSet());
        int guardados = 0;
        for (String id : ids) {
            try {
                Campana c = campanas.buscar(id);
                LocalDateTime inicio = delDia.stream().filter(x -> x.campanaId.equals(id) && x.inicioCampana != null)
                        .map(x -> x.inicioCampana.truncatedTo(ChronoUnit.HOURS)).min(LocalDateTime::compareTo).orElse(null);
                if (inicio == null || !inicio.isBefore(hasta)) continue; // la campaña no se ha activado
                List<MetricaHora> m = wolkvox.metricasPorHora(c, inicio.isAfter(desdeF) ? inicio : desdeF, hasta);
                guardados += guardar(m);
                actualizarGestionados(c, delDia, hasta);
            } catch (RuntimeException e) {
                LOG.errorf("Métricas de la campaña %s no disponibles: %s", id, e.getMessage());
            }
        }
        bitacora.sincronizarExcelSinFallar();
        return guardados;
    }

    private int guardar(List<MetricaHora> metricas) {
        return QuarkusTransaction.requiringNew().call(() -> {
            for (MetricaHora m : metricas) {
                CorteHoraEntidad e = cortes.buscar(m.campanaId(), m.hora()).orElseGet(CorteHoraEntidad::new);
                e.campanaId = m.campanaId();
                e.hora = m.hora();
                e.agentesConectados = m.agentesConectados();
                e.registrosBarridos = m.registrosBarridos();
                e.gestionados = m.gestionados();
                if (e.id == null) cortes.persist(e);
            }
            return metricas.size();
        });
    }

    private void actualizarGestionados(Campana c, List<CargueEntidad> delDia, LocalDateTime hasta) {
        for (CargueEntidad cg : delDia) {
            if (!cg.campanaId.equals(c.id()) || cg.inicioCampana == null) continue;
            LocalDateTime fin = cg.finCampana != null && cg.finCampana.isBefore(hasta) ? cg.finCampana : hasta;
            int g = Math.min(cg.registrosCargados, wolkvox.gestionadosDeLista(c, cg.nombreLista, cg.inicioCampana, fin));
            QuarkusTransaction.requiringNew().run(() -> cargues.findById(cg.id).totalGestionado = g);
        }
    }

    /** Lo que pasó en una hora de gestión: listas activas, cortes por campaña y activaciones/detenciones. */
    public record VistaHora(LocalDateTime hora, List<CargueEntidad> cargues, List<CorteHoraEntidad> cortes,
                            List<EventoCampanaEntidad> eventos) {}

    public VistaHora hora(LocalDate fecha, int hora) {
        LocalDateTime ini = fecha.atTime(hora, 0);
        LocalDateTime fin = ini.plusHours(1);
        return QuarkusTransaction.requiringNew().call(() -> {
            List<CargueEntidad> activos = cargues.delDia(fecha).stream().filter(c -> estuvoEnLaHora(c, ini, fin)).toList();
            List<CorteHoraEntidad> cs = cortes.delDia(fecha).stream().filter(x -> x.hora.equals(ini)).toList();
            return new VistaHora(ini, activos, cs, campanas.historial(ini, fin));
        });
    }

    /** Activa (real) en algún momento de la hora; si nunca se activó, se usa el horario planeado. */
    static boolean estuvoEnLaHora(CargueEntidad c, LocalDateTime ini, LocalDateTime fin) {
        if (c.inicioCampana != null) {
            LocalDateTime f = c.finCampana != null ? c.finCampana : LocalDateTime.MAX;
            return c.inicioCampana.isBefore(fin) && !f.isBefore(ini);
        }
        if (c.horaInicioPlan == null) return false;
        LocalDateTime pi = c.fecha.atTime(c.horaInicioPlan);
        LocalDateTime pf = c.horaFinPlan != null ? c.fecha.atTime(c.horaFinPlan) : pi.plusHours(1);
        return pi.isBefore(fin) && pf.isAfter(ini);
    }

    public List<CorteHoraEntidad> delDia(LocalDate fecha) {
        return QuarkusTransaction.requiringNew().call(() -> cortes.delDia(fecha));
    }
}
