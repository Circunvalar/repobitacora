package com.bitacora.aplicacion;

import com.bitacora.dominio.modelo.Campana;
import com.bitacora.dominio.puerto.WolkvoxPuerto;
import com.bitacora.infraestructura.persistencia.*;
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
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Control de activación de las campañas registradas.
 * <ul>
 *   <li>Cuando la app inicia o detiene una campaña, guarda la hora exacta (HORA INICIO / HORA FINAL de la bitácora).</li>
 *   <li>Cada {@code bitacora.campanas.escaneo} (5 min por defecto) consulta cada servidor de Wolkvox una vez y revisa
 *       qué campañas registradas están activas; si alguna cambió por fuera de la app, deja el evento en el historial y
 *       actualiza la bitácora.</li>
 *   <li>Cada cambio y cada error se publica en tiempo real a las pantallas abiertas.</li>
 * </ul>
 */
@ApplicationScoped
public class CampanaServicio {

    private static final Logger LOG = Logger.getLogger(CampanaServicio.class);

    public record ErrorEscaneo(Long operacionId, String operacion, String titulo, String detalle, String sugerencia) {}

    public record ResultadoEscaneo(LocalDateTime momento, int revisadas, int activas, int cambios, List<ErrorEscaneo> errores) {}

    private final WolkvoxPuerto wolkvox;
    private final OperacionServicio operaciones;
    private final OperacionRepositorio operacionRepo;
    private final CampanaRepositorio campanaRepo;
    private final CargueRepositorio cargues;
    private final EventoCampanaRepositorio eventos;
    private final AvisosCampanas avisos;
    private final BitacoraConfig config;
    private final Clock reloj;
    private volatile ResultadoEscaneo ultimoEscaneo;

    public CampanaServicio(WolkvoxPuerto wolkvox, OperacionServicio operaciones, OperacionRepositorio operacionRepo,
                           CampanaRepositorio campanaRepo, CargueRepositorio cargues, EventoCampanaRepositorio eventos,
                           AvisosCampanas avisos, BitacoraConfig config, Clock reloj) {
        this.wolkvox = wolkvox;
        this.operaciones = operaciones;
        this.operacionRepo = operacionRepo;
        this.campanaRepo = campanaRepo;
        this.cargues = cargues;
        this.eventos = eventos;
        this.avisos = avisos;
        this.config = config;
        this.reloj = reloj;
    }

    public LocalDateTime iniciar(long operacionId, String campanaId, String origen) {
        return cambiar(operacionId, campanaId, true, origen);
    }

    public LocalDateTime detener(long operacionId, String campanaId, String origen) {
        return cambiar(operacionId, campanaId, false, origen);
    }

    private LocalDateTime cambiar(long operacionId, String campanaId, boolean activar, String origen) {
        record Ctx(OperacionEntidad op, CampanaEntidad camp) {}
        Ctx ctx = QuarkusTransaction.requiringNew().call(() ->
                new Ctx(operaciones.entidad(operacionId), operaciones.campanaRegistrada(operacionId, campanaId)));
        Campana c = ctx.camp().aCampana();
        if (activar) wolkvox.iniciarCampana(ctx.op().conexion(), c);
        else wolkvox.detenerCampana(ctx.op().conexion(), c);
        LocalDateTime ahora = ahora();
        QuarkusTransaction.requiringNew().run(() -> {
            CampanaEntidad e = campanaRepo.findById(ctx.camp().id);
            aplicarCambio(ctx.op(), e, activar, ahora, origen);
        });
        publicarCambio(ctx.op(), ctx.camp(), activar, ahora, origen);
        return ahora;
    }

    /** Escaneo periódico de campañas activas/inactivas. */
    @Scheduled(every = "{bitacora.campanas.escaneo}", delayed = "15s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void escaneoProgramado() {
        escanear();
    }

    /** Una consulta por servidor (information.php?api=campaigns) y se comparan las campañas registradas. */
    public synchronized ResultadoEscaneo escanear() {
        LocalDateTime ahora = ahora();
        List<OperacionEntidad> ops = QuarkusTransaction.requiringNew().call(operacionRepo::vigentes);
        int revisadas = 0, activas = 0, cambios = 0;
        List<ErrorEscaneo> errores = new ArrayList<>();
        for (OperacionEntidad op : ops) {
            List<CampanaEntidad> registradas = QuarkusTransaction.requiringNew().call(() -> campanaRepo.deOperacion(op.id));
            if (registradas.isEmpty()) continue;
            Map<String, Campana> servidor;
            try {
                servidor = wolkvox.campanas(op.conexion()).stream().collect(Collectors.toMap(Campana::id, Function.identity(), (a, b) -> a));
            } catch (WolkvoxException e) {
                LOG.errorf("Escaneo de %s (%s) falló: %s", op.nombre, op.conexion(), e.resumen());
                errores.add(new ErrorEscaneo(op.id, op.nombre, e.titulo(), e.getMessage(), e.sugerencia()));
                QuarkusTransaction.requiringNew().run(() -> {
                    OperacionEntidad x = operacionRepo.findById(op.id);
                    x.ultimoError = recortar(e.resumen() + " · " + e.sugerencia());
                    x.ultimoEscaneo = ahora;
                    if (e.tipo() == WolkvoxException.Tipo.TOKEN) x.tokenValido = false;
                });
                avisos.publicar(new AvisosCampanas.Aviso("ERROR", op.id, op.nombre, null, null, null, ahora, "ESCANEO",
                        null, null, e.titulo() + ": " + e.getMessage(), e.sugerencia()));
                continue;
            }
            List<CampanaEntidad> cambiadas = QuarkusTransaction.requiringNew().call(() -> {
                List<CampanaEntidad> lista = new ArrayList<>();
                OperacionEntidad x = operacionRepo.findById(op.id);
                x.ultimoEscaneo = ahora;
                x.ultimoError = null;
                x.tokenValido = true;
                for (CampanaEntidad r : campanaRepo.deOperacion(op.id)) {
                    Campana c = servidor.get(r.campanaId);
                    r.ultimaRevision = ahora;
                    if (c == null) {
                        r.ultimoError = "La campaña ya no aparece en el servidor wv" + op.servidor;
                        continue;
                    }
                    r.ultimoError = null;
                    if (r.nombre == null || r.nombre.isBlank()) r.nombre = c.nombre();
                    r.tipo = c.tipo();
                    r.registros = c.registros();
                    if (c.activa() != null && r.activa != null && !c.activa().equals(r.activa)) {
                        aplicarCambio(x, r, c.activa(), ahora, "ESCANEO");
                        lista.add(r);
                    } else if (r.activa == null) {
                        r.activa = c.activa();
                    }
                }
                return lista;
            });
            for (CampanaEntidad r : cambiadas) publicarCambio(op, r, Boolean.TRUE.equals(r.activa), ahora, "ESCANEO");
            for (CampanaEntidad r : registradas) {
                Campana c = servidor.get(r.campanaId);
                if (c == null) continue;
                revisadas++;
                if (c.estaActiva()) activas++;
            }
            cambios += cambiadas.size();
        }
        ultimoEscaneo = new ResultadoEscaneo(ahora, revisadas, activas, cambios, errores);
        avisos.publicar(new AvisosCampanas.Aviso("ESCANEO", null, null, null, null, null, ahora, "ESCANEO", activas, revisadas,
                errores.isEmpty() ? null : errores.size() + " campaña(s) principal(es) con error", null));
        if (cambios > 0) LOG.infof("Escaneo: %d cambio(s) de estado en %d campañas", cambios, revisadas);
        return ultimoEscaneo;
    }

    public ResultadoEscaneo ultimoEscaneo() {
        return ultimoEscaneo;
    }

    public List<EventoCampanaEntidad> historial(LocalDateTime desde, LocalDateTime hasta) {
        return QuarkusTransaction.joiningExisting().call(() -> eventos.entre(desde, hasta));
    }

    /**
     * Deja el evento en el historial y pasa la hora a la bitácora: al activarse, HORA INICIO de los cargues
     * del día que aún no la tienen; al detenerse, HORA FINAL de los que estaban activos.
     */
    private void aplicarCambio(OperacionEntidad op, CampanaEntidad c, boolean activa, LocalDateTime momento, String origen) {
        EventoCampanaEntidad ev = new EventoCampanaEntidad();
        ev.operacionId = op.id;
        ev.operacionNombre = op.nombre;
        ev.campanaId = c.campanaId;
        ev.campanaNombre = c.nombre;
        ev.tipo = activa ? EventoCampanaEntidad.Tipo.INICIO : EventoCampanaEntidad.Tipo.DETENCION;
        ev.momento = momento;
        ev.origen = origen;
        eventos.persist(ev);
        for (CargueEntidad cg : cargues.activosDeCampanaEnFecha(op.id, c.campanaId, momento.toLocalDate())) {
            if (activa) {
                if (cg.inicioCampana == null) cg.inicioCampana = momento;
                cg.finCampana = null;
            } else if (cg.inicioCampana != null && cg.finCampana == null) {
                cg.finCampana = momento;
            }
        }
        c.activa = activa;
        c.desde = momento;
        c.ultimaRevision = momento;
    }

    private void publicarCambio(OperacionEntidad op, CampanaEntidad c, boolean activa, LocalDateTime momento, String origen) {
        avisos.publicar(new AvisosCampanas.Aviso("CAMBIO", op.id, op.nombre, c.campanaId, c.nombre, activa ? "INICIO" : "DETENCION",
                momento, origen, null, null, null, null));
    }

    private static String recortar(String s) {
        return s == null ? null : s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    private LocalDateTime ahora() {
        return LocalDateTime.now(reloj).truncatedTo(ChronoUnit.SECONDS);
    }
}
