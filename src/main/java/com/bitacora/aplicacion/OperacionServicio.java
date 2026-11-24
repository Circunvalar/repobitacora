package com.bitacora.aplicacion;

import com.bitacora.dominio.modelo.Campana;
import com.bitacora.dominio.modelo.Conexion;
import com.bitacora.dominio.puerto.WolkvoxPuerto;
import com.bitacora.infraestructura.persistencia.*;
import com.bitacora.infraestructura.wolkvox.WolkvoxException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Campañas principales (cada una con su servidor de Wolkvox y su token) y los números de campaña
 * que se registran a mano dentro de cada una para futuros cargues.
 */
@ApplicationScoped
public class OperacionServicio {

    /** Lo que ve la pantalla: nunca incluye el token completo. */
    public record Vista(Long id, String nombre, String servidor, String token, boolean archivada, Boolean tokenValido,
                        Integer campanasServidor, LocalDateTime ultimaValidacion, String ultimoError,
                        LocalDateTime ultimoEscaneo, List<CampanaVista> campanas) {}

    public record CampanaVista(String campanaId, String nombre, String tipo, Boolean activa, Integer registros,
                               LocalDateTime desde, LocalDateTime ultimaRevision, String ultimoError) {

        static CampanaVista de(CampanaEntidad c) {
            return new CampanaVista(c.campanaId, c.nombre, c.tipo != null ? c.tipo.name() : null, c.activa, c.registros,
                    c.desde, c.ultimaRevision, c.ultimoError);
        }
    }

    public record Datos(String nombre, String servidor, String token) {}

    private final OperacionRepositorio operaciones;
    private final CampanaRepositorio campanas;
    private final CargueRepositorio cargues;
    private final WolkvoxPuerto wolkvox;
    private final Clock reloj;

    public OperacionServicio(OperacionRepositorio operaciones, CampanaRepositorio campanas, CargueRepositorio cargues,
                             WolkvoxPuerto wolkvox, Clock reloj) {
        this.operaciones = operaciones;
        this.campanas = campanas;
        this.cargues = cargues;
        this.wolkvox = wolkvox;
        this.reloj = reloj;
    }

    public List<Vista> listar() {
        return QuarkusTransaction.requiringNew().call(() -> operaciones.vigentes().stream().map(this::vista).toList());
    }

    public Vista ver(long id) {
        return QuarkusTransaction.requiringNew().call(() -> vista(entidad(id)));
    }

    /** Crea la campaña principal solo si el token funciona en ese servidor. */
    public Vista crear(Datos d) {
        String nombre = requerido(d.nombre(), "nombre", "Escriba un nombre que identifique la campaña principal, p. ej. REINTEGRA.");
        Conexion con = new Conexion(requerido(d.servidor(), "servidor", "Escriba los 4 dígitos de wvXXXX.wolkvox.com."),
                requerido(d.token(), "token", "Pegue el token creado en Wolkvox Manager → Configuración → Integraciones → Tokens."));
        validarServidor(con.servidor());
        if (QuarkusTransaction.requiringNew().call(() -> operaciones.existeNombre(nombre, null))) {
            throw ErrorNegocio.conflicto("Nombre repetido", "Ya existe una campaña principal llamada " + nombre + ".", "Use otro nombre.");
        }
        List<Campana> visibles = wolkvox.campanas(con); // lanza un error claro si el token o el servidor no sirven
        LocalDateTime ahora = ahora();
        return QuarkusTransaction.requiringNew().call(() -> {
            OperacionEntidad o = new OperacionEntidad();
            o.nombre = nombre;
            o.servidor = con.servidor();
            o.token = con.token();
            o.tokenValido = true;
            o.campanasServidor = visibles.size();
            o.ultimaValidacion = ahora;
            o.creado = ahora;
            operaciones.persist(o);
            return vista(o);
        });
    }

    /** Cambia nombre, servidor o token (token vacío = conservar el actual). Si cambia la conexión, se valida antes de guardar. */
    public Vista editar(long id, Datos d) {
        OperacionEntidad actual = QuarkusTransaction.requiringNew().call(() -> entidad(id));
        String nombre = d.nombre() == null || d.nombre().isBlank() ? actual.nombre : d.nombre().trim();
        String servidor = d.servidor() == null || d.servidor().isBlank() ? actual.servidor : new Conexion(d.servidor(), "").servidor();
        String token = d.token() == null || d.token().isBlank() ? actual.token : d.token().trim();
        validarServidor(servidor);
        if (QuarkusTransaction.requiringNew().call(() -> operaciones.existeNombre(nombre, id))) {
            throw ErrorNegocio.conflicto("Nombre repetido", "Ya existe una campaña principal llamada " + nombre + ".", "Use otro nombre.");
        }
        boolean cambiaConexion = !servidor.equals(actual.servidor) || !token.equals(actual.token);
        Integer visibles = cambiaConexion ? wolkvox.campanas(new Conexion(servidor, token)).size() : null;
        LocalDateTime ahora = ahora();
        return QuarkusTransaction.requiringNew().call(() -> {
            OperacionEntidad o = entidad(id);
            o.nombre = nombre;
            o.servidor = servidor;
            o.token = token;
            if (cambiaConexion) {
                o.tokenValido = true;
                o.campanasServidor = visibles;
                o.ultimaValidacion = ahora;
                o.ultimoError = null;
            }
            return vista(o);
        });
    }

    /** Prueba el token ahora mismo. No lanza error: deja el resultado en la campaña principal para mostrarlo. */
    public Vista validar(long id) {
        OperacionEntidad o = QuarkusTransaction.requiringNew().call(() -> entidad(id));
        Integer visibles = null;
        String error = null;
        boolean tokenMalo = false;
        try {
            visibles = wolkvox.campanas(o.conexion()).size();
        } catch (WolkvoxException e) {
            error = e.resumen() + " · " + e.sugerencia();
            tokenMalo = e.tipo() == WolkvoxException.Tipo.TOKEN;
        }
        final Integer v = visibles;
        final String err = error;
        final boolean malo = tokenMalo;
        LocalDateTime ahora = ahora();
        return QuarkusTransaction.requiringNew().call(() -> {
            OperacionEntidad x = entidad(id);
            x.ultimaValidacion = ahora;
            x.ultimoError = recortar(err);
            if (err == null) {
                x.tokenValido = true;
                x.campanasServidor = v;
            } else if (malo) {
                x.tokenValido = false;
            }
            return vista(x);
        });
    }

    /** Si ya tiene cargues se archiva (la bitácora no pierde nada); si no, se borra. */
    public String eliminar(long id) {
        return QuarkusTransaction.requiringNew().call(() -> {
            OperacionEntidad o = entidad(id);
            if (cargues.deOperacion(id) > 0) {
                o.archivada = true;
                return "archivada";
            }
            campanas.delete("operacionId", id);
            operaciones.delete(o);
            return "eliminada";
        });
    }

    /** Registra un número de campaña después de comprobar en Wolkvox que existe en ese servidor. */
    public CampanaVista agregarCampana(long operacionId, String numero) {
        String id = numero == null ? "" : numero.trim();
        if (!id.matches("\\d{1,12}")) {
            throw ErrorNegocio.datos("Número de campaña no válido", "\"" + id + "\" no es un número de campaña.",
                    "Escriba solo el número (ID) de la campaña que aparece en Wolkvox Manager.");
        }
        OperacionEntidad o = QuarkusTransaction.requiringNew().call(() -> entidad(operacionId));
        Optional<CampanaEntidad> ya = QuarkusTransaction.requiringNew().call(() -> campanas.buscar(operacionId, id));
        Campana c = wolkvox.campana(o.conexion(), id).orElseThrow(() -> ErrorNegocio.noEncontrado(
                "La campaña " + id + " no existe en wv" + o.servidor,
                "El servidor wv" + o.servidor + " (" + o.nombre + ") no tiene ninguna campaña con el número " + id + ".",
                "Revise el número en Wolkvox Manager o si pertenece a otra campaña principal."));
        LocalDateTime ahora = ahora();
        return QuarkusTransaction.requiringNew().call(() -> {
            CampanaEntidad e = ya.isPresent() ? campanas.findById(ya.get().id) : new CampanaEntidad();
            if (e.id == null) {
                e.operacionId = operacionId;
                e.campanaId = c.id();
                e.creado = ahora;
            }
            e.nombre = c.nombre();
            e.tipo = c.tipo();
            e.activa = c.activa();
            e.registros = c.registros();
            e.ultimaRevision = ahora;
            e.ultimoError = null;
            if (e.id == null) campanas.persist(e);
            return CampanaVista.de(e);
        });
    }

    public void quitarCampana(long operacionId, String campanaId) {
        QuarkusTransaction.requiringNew().run(() -> campanas.delete("operacionId = ?1 and campanaId = ?2", operacionId, campanaId));
    }

    // ─── Para los otros servicios ───

    public OperacionEntidad entidad(long id) {
        OperacionEntidad o = operaciones.findById(id);
        if (o == null) throw ErrorNegocio.noEncontrado("Campaña principal no encontrada", "No existe la campaña principal " + id + ".",
                "Recargue la página.");
        return o;
    }

    public CampanaEntidad campanaRegistrada(long operacionId, String campanaId) {
        return campanas.buscar(operacionId, campanaId).orElseThrow(() -> ErrorNegocio.noEncontrado(
                "Campaña no registrada", "La campaña " + campanaId + " no está registrada en esta campaña principal.",
                "Agréguela primero escribiendo su número."));
    }

    private Vista vista(OperacionEntidad o) {
        return new Vista(o.id, o.nombre, o.servidor, enmascarar(o.token), o.archivada, o.tokenValido, o.campanasServidor,
                o.ultimaValidacion, o.ultimoError, o.ultimoEscaneo,
                o.id == null ? List.of() : campanas.deOperacion(o.id).stream().map(CampanaVista::de).toList());
    }

    static String enmascarar(String token) {
        if (token == null || token.isBlank()) return "";
        return "••••" + (token.length() > 4 ? token.substring(token.length() - 4) : "");
    }

    private static void validarServidor(String servidor) {
        if (!servidor.matches("\\d{1,6}")) {
            throw ErrorNegocio.datos("Servidor no válido", "\"" + servidor + "\" no es un número de servidor.",
                    "Escriba solo los dígitos que van después de \"wv\": si entra por wv0003.wolkvox.com, escriba 0003.");
        }
    }

    private static String requerido(String v, String campo, String sugerencia) {
        if (v == null || v.isBlank()) throw ErrorNegocio.datos("Falta el " + campo, "El campo " + campo + " es obligatorio.", sugerencia);
        return v.trim();
    }

    private static String recortar(String s) {
        return s == null ? null : s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    private LocalDateTime ahora() {
        return LocalDateTime.now(reloj).truncatedTo(ChronoUnit.SECONDS);
    }
}
