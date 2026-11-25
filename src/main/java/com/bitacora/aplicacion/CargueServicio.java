package com.bitacora.aplicacion;

import com.bitacora.dominio.modelo.*;
import com.bitacora.dominio.puerto.ArchivosPuerto;
import com.bitacora.dominio.puerto.WolkvoxPuerto;
import com.bitacora.infraestructura.persistencia.CargueEntidad;
import com.bitacora.infraestructura.persistencia.CargueRepositorio;
import com.bitacora.infraestructura.persistencia.OperacionEntidad;
import com.bitacora.infraestructura.wolkvox.WolkvoxException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Caso de uso principal: escanear → validar → asignar a campaña → cargar por lotes → dejar en bitácora.
 * Nada aquí depende de una campaña concreta: la campaña es un dato de entrada.
 */
@ApplicationScoped
public class CargueServicio {

    private static final Logger LOG = Logger.getLogger(CargueServicio.class);

    public record Solicitud(Long operacionId, String archivo, String campanaId, TipoEstrategia estrategia, String jefe,
                            String nombreEstrategia, LocalTime horaInicioPlan, LocalTime horaFinPlan,
                            String observacion, LocalDate fecha) {}

    public record Vista(String archivo, int filas, int validos, int invalidos, List<String> erroresEstructura,
                        List<RegistroInvalido> ejemplosInvalidos, List<String> encabezado, int columnas, String tipo) {}

    private final ArchivosPuerto archivos;
    private final WolkvoxPuerto wolkvox;
    private final OperacionServicio operaciones;
    private final CargueRepositorio repo;
    private final BitacoraServicio bitacora;
    private final BitacoraConfig config;
    private final ValidadorCsv validador;
    private final ProgramacionServicio programacion;
    private final Clock reloj;

    public CargueServicio(ArchivosPuerto archivos, WolkvoxPuerto wolkvox,
                          OperacionServicio operaciones, CargueRepositorio repo, BitacoraServicio bitacora,
                          BitacoraConfig config, ProgramacionServicio programacion, Clock reloj) {
        this.programacion = programacion;
        this.archivos = archivos;
        this.wolkvox = wolkvox;
        this.operaciones = operaciones;
        this.repo = repo;
        this.bitacora = bitacora;
        this.config = config;
        this.reloj = reloj;
        this.validador = new ValidadorCsv(ValidadorCsv.Reglas.desde(config.validacion()));
    }

    /** Lista los CSV de la carpeta con filas, huella y si ya se cargaron antes. */
    public List<ArchivoCsv> escanear() {
        List<ArchivoCsv> r = new ArrayList<>();
        for (ArchivosPuerto.InfoArchivo a : archivos.listarCsv()) {
            String huella = archivos.huella(a.nombre());
            boolean ya = QuarkusTransaction.joiningExisting().call(() -> repo.huellaYaCargada(huella));
            r.add(new ArchivoCsv(a.nombre(), a.tamanoBytes(), archivos.contarFilas(a.nombre()), huella, a.modificado(), ya));
        }
        return r;
    }

    /** Valida sin cargar, para mostrar cuántos registros no son válidos antes de decidir. */
    public Vista revisar(String archivo) {
        CsvLeido csv = archivos.leer(archivo);
        ResultadoValidacion v = validador.validar(csv);
        return new Vista(archivo, csv.filas().size(), v.validos().size(), v.invalidos().size(), v.erroresEstructura(),
                v.invalidos().stream().limit(20).toList(), csv.encabezado(), PlantillaWolkvox.columnas(csv.encabezado()),
                PlantillaWolkvox.detectar(csv.encabezado()).map(Enum::name).orElse(null));
    }

    /** Registra el cargue en la bitácora y lo ejecuta. Devuelve la fila resultante. */
    public CargueEntidad cargar(Solicitud s) {
        if (s.operacionId() == null || s.campanaId() == null || s.campanaId().isBlank()) {
            throw ErrorNegocio.datos("Falta la campaña", "No se indicó a qué campaña va el cargue de " + s.archivo() + ".",
                    "Elija la campaña destino.");
        }
        if (s.horaInicioPlan() == null) {
            throw ErrorNegocio.datos("Falta la hora de encendido", "Indique a qué hora se debe prender la campaña para gestionar "
                    + s.archivo() + ".", "Escriba la hora de encendido en la fila del archivo.");
        }
        OperacionEntidad op = QuarkusTransaction.requiringNew().call(() -> operaciones.entidad(s.operacionId()));
        // Se registra (o refresca) el número de campaña: así queda guardado para futuros cargues.
        operaciones.agregarCampana(op.id, s.campanaId());
        Campana campana = QuarkusTransaction.requiringNew().call(() -> operaciones.campanaRegistrada(op.id, s.campanaId().trim()).aCampana());
        // La plantilla dice si la lista es predictiva (36 columnas) o preview (35); debe coincidir con la campaña.
        CsvLeido leido = archivos.leer(s.archivo());
        TipoMarcacion tipoArchivo = PlantillaWolkvox.detectar(leido.encabezado()).orElseThrow(() -> ErrorNegocio.datos(
                "El archivo no tiene la plantilla de Wolkvox",
                s.archivo() + " tiene " + PlantillaWolkvox.columnas(leido.encabezado()) + " columnas: la predictiva tiene "
                        + PlantillaWolkvox.COLUMNAS_PREDICTIVA + " y la preview " + PlantillaWolkvox.COLUMNAS_PREVIEW + ".",
                "Descargue la plantilla de la campaña en Wolkvox Manager y copie los datos en ella."));
        if (campana.tipo() != null && campana.tipo() != tipoArchivo) {
            throw ErrorNegocio.datos("La lista no corresponde al tipo de campaña",
                    s.archivo() + " es una lista " + PlantillaWolkvox.nombre(tipoArchivo) + " y la campaña " + campana.id()
                            + " (" + campana.nombre() + ") es " + PlantillaWolkvox.nombre(campana.tipo()) + ".",
                    "Elija una campaña " + PlantillaWolkvox.nombre(tipoArchivo) + " o use la plantilla correcta.");
        }
        String huella = archivos.huella(s.archivo());
        String nombreLista = NombreLista.desdeArchivo(s.archivo());
        String base = NombreLista.base(nombreLista);

        Long id = QuarkusTransaction.requiringNew().call(() -> {
            if (!config.cargue().permitirDuplicados() && repo.huellaYaCargada(huella, op.id, campana.id())) {
                throw ErrorNegocio.conflicto("Archivo ya cargado", "El archivo " + s.archivo() + " ya se cargó a la campaña "
                        + campana.id() + " (wv" + op.servidor + ").", "Si de verdad quiere repetirlo, cambie el archivo o use REMARCACIÓN con un archivo nuevo.");
            }
            CargueEntidad c = new CargueEntidad();
            c.fecha = s.fecha() != null ? s.fecha() : LocalDate.now(reloj);
            c.horaInicioPlan = s.horaInicioPlan();
            c.horaFinPlan = s.horaFinPlan();
            c.operacionId = op.id;
            c.operacionNombre = op.nombre;
            c.campanaId = campana.id();
            c.campanaNombre = campana.nombre();
            c.tipoMarcacion = tipoArchivo;
            c.archivo = s.archivo();
            c.nombreLista = nombreLista;
            c.listaBase = base;
            c.huella = huella;
            c.estrategia = s.estrategia() != null ? s.estrategia() : TipoEstrategia.NUEVA;
            c.remarcacion = c.estrategia == TipoEstrategia.REMARCACION ? (int) repo.previosDeLista(op.id, campana.id(), base) : 0;
            if (c.estrategia == TipoEstrategia.REMARCACION && c.remarcacion == 0) c.remarcacion = 1;
            c.jefe = s.jefe();
            c.nombreEstrategia = s.nombreEstrategia();
            c.observacion = s.observacion();
            c.estado = EstadoCargue.CARGANDO;
            c.inicioCargue = ahora();
            c.creado = ahora();
            repo.persist(c);
            return c.id;
        });

        int[] enviados = {0, 0}; // aceptados, rechazados por Wolkvox (para no perder la cuenta si un lote falla)
        try {
            CsvLeido csv = leido;
            ResultadoValidacion v = validador.validar(csv);
            if (!v.estructuraValida()) throw ErrorNegocio.datos("El archivo no tiene la plantilla de Wolkvox",
                    String.join("; ", v.erroresEstructura()), "Revise los encabezados del CSV.");

            String rutaRechazados = v.invalidos().isEmpty() ? null
                    : archivos.escribirRechazados(s.archivo(), csv.encabezado(), v.invalidos(), csv.separador());

            int tam = Math.min(config.cargue().tamanoLote(), 10_000); // límite de Wolkvox por consumo
            List<Map<String, String>> validos = v.validos();
            Campana destino = campana; // recién leída de Wolkvox: su estado (start/stop) va en add_record
            for (int i = 0; i < validos.size(); i += tam) {
                ResultadoLote r = wolkvox.cargarRegistros(op.conexion(), destino, validos.subList(i, Math.min(i + tam, validos.size())));
                enviados[0] += r.aceptados();
                enviados[1] += r.rechazados();
            }
            final int ac = enviados[0], rw = enviados[1];
            QuarkusTransaction.requiringNew().run(() -> {
                CargueEntidad c = repo.findById(id);
                c.registrosArchivo = csv.filas().size();
                c.registrosNoValidos = v.invalidos().size();
                c.registrosCargados = ac;
                c.rechazadosWolkvox = rw;
                c.archivoRechazados = rutaRechazados;
                c.estado = (v.invalidos().isEmpty() && rw == 0) ? EstadoCargue.CARGADO : EstadoCargue.CARGADO_CON_RECHAZOS;
                c.finCargue = ahora();
                // Si la campaña ya estaba encendida, la lista empieza a marcarse desde este momento.
                if (destino.estaActiva() && ac > 0 && c.inicioCampana == null) c.inicioCampana = c.finCargue;
                if (!v.invalidos().isEmpty()) {
                    String aviso = v.invalidos().size() + " no válidos";
                    c.observacion = c.observacion == null || c.observacion.isBlank() ? aviso : c.observacion + " · " + aviso;
                }
            });
            archivos.moverAProcesados(s.archivo());
            // Encendido de hoy a la hora pedida (solo si la campaña no está ya encendida).
            LocalDate fechaCargue = s.fecha() != null ? s.fecha() : LocalDate.now(reloj);
            if (ac > 0 && !destino.estaActiva() && fechaCargue.equals(LocalDate.now(reloj))) {
                try {
                    programacion.programar(op.id, campana.id(), s.horaInicioPlan(), id);
                } catch (RuntimeException e) {
                    LOG.errorf("La lista %s se cargó, pero no se pudo programar el encendido: %s", s.archivo(), e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            LOG.errorf(e, "Falló el cargue de %s", s.archivo());
            QuarkusTransaction.requiringNew().run(() -> {
                CargueEntidad c = repo.findById(id);
                c.estado = EstadoCargue.FALLIDO;
                c.finCargue = ahora();
                c.registrosCargados = enviados[0];
                c.rechazadosWolkvox = enviados[1];
                String motivo = e instanceof WolkvoxException w ? w.resumen() + " · " + w.sugerencia()
                        : e instanceof ErrorNegocio n ? n.titulo() + ": " + n.getMessage() : e.getMessage();
                c.error = recortar((enviados[0] > 0 ? "Se alcanzaron a cargar " + enviados[0] + " registros (no vuelva a cargar el mismo archivo completo). " : "") + motivo);
            });
        }
        bitacora.sincronizarExcelSinFallar();
        return QuarkusTransaction.requiringNew().call(() -> repo.findById(id));
    }

    private LocalDateTime ahora() {
        return LocalDateTime.now(reloj).truncatedTo(ChronoUnit.SECONDS);
    }

    private static String recortar(String m) {
        if (m == null) return "Error desconocido";
        return m.length() > 1000 ? m.substring(0, 1000) : m;
    }
}
