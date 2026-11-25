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
 * Conexiones a Wolkvox (servidor + token). En la pantalla cada campaña guardada es una conexión con una sola campaña
 * (ver "Campañas con su propio servidor y token"); los métodos de "campaña principal" quedan para uso interno y los números de campaña
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

    /** Alta de una campaña: su número, el servidor donde vive y el token con el que se consulta. */
    public record NuevaCampana(String campanaId, String servidor, String token) {}

    /** Una campaña guardada, como la ve la pantalla. El id es el de su conexión (servidor + token). */
    public record CampanaGuardada(Long id, String campanaId, String nombre, String tipo, String servidor, String token,
                                  Boolean tokenValido, Boolean activa, Integer registros, LocalDateTime desde,
                                  LocalDateTime ultimaRevision, LocalDateTime ultimaValidacion, String ultimoError) {}

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
        return agregarCampana(operacionId, numero, null);
    }

    /** Agrega la campaña con el nombre que le da el usuario (si no da nombre, se usa el de Wolkvox). */
    public CampanaVista agregarCampana(long operacionId, String numero, String nombreUsuario) {
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
                "Revise el número en Wolkvox Manager o si la campaña está en otro servidor."));
        LocalDateTime ahora = ahora();
        return QuarkusTransaction.requiringNew().call(() -> {
            CampanaEntidad e = ya.isPresent() ? campanas.findById(ya.get().id) : new CampanaEntidad();
            if (e.id == null) {
                e.operacionId = operacionId;
                e.campanaId = c.id();
                e.creado = ahora;
            }
            if (nombreUsuario != null && !nombreUsuario.isBlank()) e.nombre = nombreUsuario.trim();
            else if (e.nombre == null || e.nombre.isBlank()) e.nombre = c.nombre();
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

    // ─── Campañas con su propio servidor y token (sin campaña principal) ───

    public List<CampanaGuardada> campanasGuardadas() {
        return QuarkusTransaction.requiringNew().call(() -> operaciones.vigentes().stream()
                .flatMap(o -> campanas.deOperacion(o.id).stream().map(c -> guardada(o, c))).toList());
    }

    public CampanaGuardada campanaGuardada(long id) {
        return QuarkusTransaction.requiringNew().call(() -> {
            OperacionEntidad o = entidad(id);
            return campanas.deOperacion(id).stream().findFirst().map(c -> guardada(o, c)).orElseThrow(() -> ErrorNegocio.noEncontrado(
                    "Campaña no encontrada", "La campaña " + id + " ya no está guardada.", "Recargue la página."));
        });
    }

    /**
     * Guarda una campaña: comprueba en Wolkvox que el token sirve en ese servidor y que la campaña existe.
     * Si ya estaba guardada (o se había quitado), se actualiza su token.
     */
    public CampanaGuardada guardarCampana(NuevaCampana d) {
        String numero = requerido(d.campanaId(), "número de campaña", "Escriba el número (ID) de la campaña que aparece en Wolkvox Manager.");
        if (!numero.matches("\\d{1,12}")) {
            throw ErrorNegocio.datos("Número de campaña no válido", "\"" + numero + "\" no es un número de campaña.",
                    "Escriba solo el número (ID) de la campaña que aparece en Wolkvox Manager.");
        }
        Conexion con = new Conexion(requerido(d.servidor(), "servidor", "Escriba los 4 dígitos de wvXXXX.wolkvox.com."),
                requerido(d.token(), "token", "Pegue el token creado en Wolkvox Manager → Configuración → Integraciones → Tokens."));
        validarServidor(con.servidor());
        Campana c = wolkvox.campana(con, numero).orElseThrow(() -> ErrorNegocio.noEncontrado(
                "La campaña " + numero + " no existe en wv" + con.servidor(),
                "El token funciona, pero el servidor wv" + con.servidor() + " no tiene ninguna campaña con el número " + numero + ".",
                "Revise el número en Wolkvox Manager o si la campaña está en otro servidor."));
        String clave = "wv" + con.servidor() + " · " + numero;
        LocalDateTime ahora = ahora();
        long id = QuarkusTransaction.requiringNew().call(() -> {
            OperacionEntidad o = operaciones.find("lower(nombre) = ?1", clave.toLowerCase()).firstResultOptional().orElseGet(OperacionEntidad::new);
            if (o.id == null) {
                o.nombre = clave;
                o.creado = ahora;
            }
            o.servidor = con.servidor();
            o.token = con.token();
            o.archivada = false;
            o.tokenValido = true;
            o.ultimaValidacion = ahora;
            o.ultimoError = null;
            if (o.id == null) operaciones.persist(o);
            return o.id;
        });
        agregarCampana(id, c.id());
        return campanaGuardada(id);
    }

    /** Cambia el token (y si se quiere el servidor) de una campaña guardada; se prueba antes de guardar. */
    public CampanaGuardada cambiarToken(long id, String servidor, String token) {
        CampanaGuardada actual = campanaGuardada(id);
        String srv = servidor == null || servidor.isBlank() ? actual.servidor() : servidor;
        String tok = token == null || token.isBlank() ? QuarkusTransaction.requiringNew().call(() -> entidad(id).token) : token;
        if (!srv.equals(actual.servidor())) {
            return guardarCampana(new NuevaCampana(actual.campanaId(), srv, tok));
        }
        editar(id, new Datos(null, srv, tok));
        agregarCampana(id, actual.campanaId());
        return campanaGuardada(id);
    }

    /** Prueba el token y refresca nombre, tipo y estado de la campaña. No lanza error: lo deja en la campaña. */
    public CampanaGuardada probarCampana(long id) {
        Vista v = validar(id);
        if (v.ultimoError() == null) {
            String numero = campanaGuardada(id).campanaId();
            try {
                agregarCampana(id, numero);
            } catch (ErrorNegocio e) {
                QuarkusTransaction.requiringNew().run(() -> entidad(id).ultimoError = recortar(e.titulo() + ": " + e.getMessage()));
            }
        }
        return campanaGuardada(id);
    }

    private CampanaGuardada guardada(OperacionEntidad o, CampanaEntidad c) {
        return new CampanaGuardada(o.id, c.campanaId, c.nombre, c.tipo != null ? c.tipo.name() : null, o.servidor,
                enmascarar(o.token), o.tokenValido, c.activa, c.registros, c.desde, c.ultimaRevision, o.ultimaValidacion,
                o.ultimoError != null ? o.ultimoError : c.ultimoError);
    }

    // ─── Para los otros servicios ───

    public OperacionEntidad entidad(long id) {
        OperacionEntidad o = operaciones.findById(id);
        if (o == null) throw ErrorNegocio.noEncontrado("Campaña no encontrada", "La campaña guardada " + id + " ya no existe.",
                "Recargue la página.");
        return o;
    }

    public CampanaEntidad campanaRegistrada(long operacionId, String campanaId) {
        return campanas.buscar(operacionId, campanaId).orElseThrow(() -> ErrorNegocio.noEncontrado(
                "Campaña no registrada", "La campaña " + campanaId + " no está guardada.",
                "Agréguela en la pestaña Campañas."));
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
