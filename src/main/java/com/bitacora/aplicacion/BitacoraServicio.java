package com.bitacora.aplicacion;

import com.bitacora.dominio.modelo.EstadoDescarga;
import com.bitacora.dominio.modelo.TipoEstrategia;
import com.bitacora.dominio.puerto.BitacoraExcelPuerto;
import com.bitacora.dominio.puerto.BitacoraExcelPuerto.FilaBitacora;
import com.bitacora.infraestructura.persistencia.CargueEntidad;
import com.bitacora.infraestructura.persistencia.CargueRepositorio;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/** Lectura y edición de la bitácora, y su sincronización con el Excel. */
@ApplicationScoped
public class BitacoraServicio {

    private static final Logger LOG = Logger.getLogger(BitacoraServicio.class);
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("h:mma", Locale.US);

    /** Campos que el usuario puede corregir a mano desde la pantalla. */
    public record Edicion(String jefe, TipoEstrategia estrategia, String nombreEstrategia, String observacion,
                          LocalTime horaInicioPlan, LocalTime horaFinPlan, Integer totalGestionado,
                          EstadoDescarga descargaInforme) {}

    private final CargueRepositorio repo;
    private final BitacoraExcelPuerto excel;

    public BitacoraServicio(CargueRepositorio repo, BitacoraExcelPuerto excel) {
        this.repo = repo;
        this.excel = excel;
    }

    @Transactional
    public List<CargueEntidad> delDia(LocalDate fecha) {
        return repo.delDia(fecha);
    }

    @Transactional
    public CargueEntidad editar(long id, Edicion e) {
        CargueEntidad c = repo.findByIdOptional(id).orElseThrow(() -> new NoSuchElementException("No existe el cargue " + id));
        if (e.jefe() != null) c.jefe = e.jefe();
        if (e.estrategia() != null) c.estrategia = e.estrategia();
        if (e.nombreEstrategia() != null) c.nombreEstrategia = e.nombreEstrategia();
        if (e.observacion() != null) c.observacion = e.observacion();
        if (e.horaInicioPlan() != null) c.horaInicioPlan = e.horaInicioPlan();
        if (e.horaFinPlan() != null) c.horaFinPlan = e.horaFinPlan();
        if (e.totalGestionado() != null) c.totalGestionado = e.totalGestionado();
        if (e.descargaInforme() != null) c.descargaInforme = e.descargaInforme();
        return c;
    }

    public List<FilaBitacora> filas() {
        return QuarkusTransaction.joiningExisting().call(() -> repo.todos().stream().map(BitacoraServicio::aFila).toList());
    }

    public Path sincronizarExcel() {
        return excel.escribir(filas());
    }

    public byte[] excelEnMemoria() {
        return excel.generar(filas());
    }

    /** Se llama después de cada cambio; si el Excel está abierto no se pierde nada, queda en la base. */
    public void sincronizarExcelSinFallar() {
        try {
            sincronizarExcel();
        } catch (RuntimeException e) {
            LOG.warn("Excel no sincronizado: " + e.getMessage());
        }
    }

    static FilaBitacora aFila(CargueEntidad c) {
        String inicio = c.inicioCampana != null ? c.inicioCampana.toLocalTime().format(HORA)
                : c.horaInicioPlan != null ? c.horaInicioPlan.format(HORA) : null;
        // Si la campaña sigue activa, HORA FINAL queda vacía hasta que se detenga.
        String fin = c.finCampana != null ? c.finCampana.toLocalTime().format(HORA)
                : c.inicioCampana == null && c.horaFinPlan != null ? c.horaFinPlan.format(HORA) : null;
        String plan = (c.horaInicioPlan != null ? c.horaInicioPlan.format(HORA) : "?") + " - "
                + (c.horaFinPlan != null ? c.horaFinPlan.format(HORA) : "?");
        Integer pct = c.porcentajeGestion();
        return new FilaBitacora(c.fecha.format(FECHA), inicio, fin, c.campanaId, c.nombreLista, c.campanaNombre,
                c.estrategia.name(), c.remarcacion, c.jefe, c.registrosCargados, c.totalGestionado,
                c.tipoMarcacion != null ? c.tipoMarcacion.name() : null, pct != null ? pct + "%" : "0%",
                c.descargaInforme.name(), c.nombreEstrategia, c.observacion, c.registrosNoValidos, c.huella, plan);
    }
}
