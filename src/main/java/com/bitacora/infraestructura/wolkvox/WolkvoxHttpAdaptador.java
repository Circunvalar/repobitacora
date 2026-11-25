package com.bitacora.infraestructura.wolkvox;

import com.bitacora.aplicacion.BitacoraConfig;
import com.bitacora.aplicacion.ValidadorCsv;
import com.bitacora.dominio.modelo.*;
import com.bitacora.dominio.puerto.WolkvoxPuerto;
import com.bitacora.infraestructura.wolkvox.WolkvoxException.Tipo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente de la API v2 de Wolkvox Manager: https://wv{servidor}.wolkvox.com/api/v2/ con el encabezado wolkvox-token.
 * Rutas, campos y códigos de error según la colección oficial de Postman ("wolkvox APIs (ES)") y
 * https://www.wolkvox.com/developers/codigos-de-error :
 * <ul>
 *   <li>Campañas: GET information.php?api=campaigns[&amp;campaign=ID] → campaign_id, campaign_name, type_campaign, status (Started/Stopped), records</li>
 *   <li>Cargue: POST campaign.php?api=add_record&amp;type_campaign=…&amp;campaign_id=…[&amp;campaign_status=start|stop], cuerpo = arreglo JSON</li>
 *   <li>Iniciar / detener: PUT campaign.php?api=start|stop&amp;campaign_id=…</li>
 *   <li>Agentes por campaña: GET reports_manager.php?api=agent_4 (agent_id, outbound, login_time, campaign_id)</li>
 *   <li>Intentos de la campaña: GET reports_manager.php?api=campaign_4 (result, records)</li>
 * </ul>
 * Límites: 2 solicitudes simultáneas por token, 60 s por solicitud, 10.000 registros por cargue,
 * 60.000 registros por consulta, 31 días por consulta, licencias × 1000 consumos diarios por token.
 */
@ApplicationScoped
public class WolkvoxHttpAdaptador implements WolkvoxPuerto {

    private static final Logger LOG = Logger.getLogger(WolkvoxHttpAdaptador.class);
    private static final DateTimeFormatter FECHA_API = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern COL_TEL = Pattern.compile("^(?:tel|telefono)(\\d{1,2})$");
    private static final Pattern COL_OPT = Pattern.compile("^(?:opt|opcional)(\\d{1,2})$");
    private static final Pattern NUMERO = Pattern.compile("(\\d+)");
    /** Campos que add_record acepta solo en campañas predictivas (la de preview no los documenta). */
    private static final Set<String> SOLO_PREDICTIVA = Set.of("tel_extra", "email", "recall_date", "recall_telephone");
    /** agent_id solo existe en preview. */
    private static final Set<String> SOLO_PREVIEW = Set.of("agent_id");

    private final BitacoraConfig config;
    private final ObjectMapper json;
    static {
        // Usa el proxy configurado en Windows (redes corporativas). Debe ir antes de crear el cliente.
        if (System.getProperty("java.net.useSystemProxies") == null) System.setProperty("java.net.useSystemProxies", "true");
    }

    private final HttpClient http = HttpClient.newBuilder()
            .proxy(ProxySelector.getDefault())
            .connectTimeout(Duration.ofSeconds(15)).build();
    /** Wolkvox permite 2 solicitudes simultáneas por token. */
    private final Map<String, Semaphore> cupos = new ConcurrentHashMap<>();
    private final Map<String, String> columnas = new HashMap<>();
    /** agent_4 trae todas las campañas del servidor: se reutiliza la respuesta de una misma ventana por unos minutos. */
    private final Map<String, CacheReporte> cacheAgentes = new ConcurrentHashMap<>();

    private record CacheReporte(long momento, JsonNode datos) {}

    public WolkvoxHttpAdaptador(BitacoraConfig config, ObjectMapper json) {
        this.config = config;
        this.json = json;
        for (String par : config.wolkvox().columnas()) {
            String[] p = par.split("=");
            if (p.length == 2) columnas.put(ValidadorCsv.normalizar(p[0]), p[1].trim());
        }
    }

    private BitacoraConfig.Wolkvox.Endpoints ep() { return config.wolkvox().endpoints(); }

    // ─── Campañas ───────────────────────────────────────────────────────

    @Override
    public List<Campana> campanas(Conexion c) {
        List<Campana> r = new ArrayList<>();
        for (JsonNode n : llamar(c, "GET", ep().campanas(), Map.of(), null, false).path("data")) {
            Campana cp = aCampana(n);
            if (cp != null) r.add(cp);
        }
        return r;
    }

    @Override
    public Optional<Campana> campana(Conexion c, String campanaId) {
        String id = campanaId.trim();
        JsonNode data;
        try {
            data = llamar(c, "GET", ep().campanas(), Map.of("campaign", id), null, true).path("data");
        } catch (WolkvoxException e) {
            if (e.tipo() == Tipo.NO_EXISTE) return Optional.empty();
            throw e;
        }
        for (JsonNode n : data) {
            Campana cp = aCampana(n);
            if (cp != null && cp.id().equals(id)) return Optional.of(cp);
        }
        return Optional.empty();
    }

    private static Campana aCampana(JsonNode n) {
        String id = texto(n, "campaign_id", "id");
        if (id.isBlank()) return null;
        String tipo = texto(n, "type_campaign", "campaign_type", "type").toLowerCase(Locale.ROOT);
        String estado = texto(n, "status").toLowerCase(Locale.ROOT);
        Boolean activa = estado.isBlank() ? null : estado.startsWith("start");
        return new Campana(id.trim(), texto(n, "campaign_name", "name").trim(),
                tipo.contains("preview") ? TipoMarcacion.PREVIEW : TipoMarcacion.PREDICTIVO, activa, entero(texto(n, "records")));
    }

    // ─── Cargue ─────────────────────────────────────────────────────────

    /** Sin reintento automático: si Wolkvox alcanzó a recibir el lote, reintentar duplicaría los registros. */
    @Override
    public ResultadoLote cargarRegistros(Conexion c, Campana campana, List<Map<String, String>> lote) {
        List<Map<String, String>> cuerpo = lote.stream().map(f -> aRegistroApi(f, campana.tipo())).toList();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("type_campaign", campana.tipo().valorApi());
        params.put("campaign_id", campana.id());
        if (campana.tipo() == TipoMarcacion.PREDICTIVO) params.put("campaign_status", campana.estaActiva() ? "start" : "stop");
        JsonNode r;
        try {
            r = llamar(c, "POST", ep().cargarRegistros(), params, cuerpo, false);
        } catch (WolkvoxException e) {
            // "X RECORDS HAS BEEN ADDED, PLEASE VALIDATE THE DATA": entraron solo X
            String m = e.getMessage() == null ? "" : e.getMessage().toUpperCase(Locale.ROOT);
            if (m.contains("HAS BEEN ADDED")) {
                Matcher x = NUMERO.matcher(m);
                int aceptados = x.find() ? Math.min(Integer.parseInt(x.group(1)), lote.size()) : 0;
                return new ResultadoLote(aceptados, lote.size() - aceptados, e.getMessage());
            }
            throw e;
        }
        JsonNode d = r.path("data");
        if (d.isArray() && !d.isEmpty()) d = d.get(0);
        Integer total = entero(texto(d, "total_registers", "loaded"));
        int aceptados = total != null ? Math.min(total, lote.size()) : lote.size();
        return new ResultadoLote(aceptados, lote.size() - aceptados, r.path("msg").asText(""));
    }

    /** Pasa una fila del CSV (encabezados de la plantilla) a los campos que espera add_record. */
    public Map<String, String> aRegistroApi(Map<String, String> fila, TipoMarcacion tipo) {
        Map<String, String> r = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : fila.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (v.isEmpty()) continue;
            String campo = campoApi(ValidadorCsv.normalizar(e.getKey()));
            if (campo == null) continue;
            if (tipo == TipoMarcacion.PREVIEW && SOLO_PREDICTIVA.contains(campo)) continue;
            if (tipo == TipoMarcacion.PREDICTIVO && SOLO_PREVIEW.contains(campo)) continue;
            r.putIfAbsent(campo, v);
        }
        return r;
    }

    String campoApi(String columna) {
        String c = columnas.get(columna);
        if (c != null) return c;
        Matcher m = COL_TEL.matcher(columna);
        if (m.matches() && Integer.parseInt(m.group(1)) <= 10) return "tel" + Integer.parseInt(m.group(1));
        m = COL_OPT.matcher(columna);
        if (m.matches() && Integer.parseInt(m.group(1)) <= 12) return "opt" + Integer.parseInt(m.group(1));
        return null;
    }

    // ─── Iniciar / detener ──────────────────────────────────────────────

    @Override
    public void iniciarCampana(Conexion c, Campana campana) {
        cambiarEstado(c, campana, ep().iniciar(), "STARTED");
    }

    @Override
    public void detenerCampana(Conexion c, Campana campana) {
        cambiarEstado(c, campana, ep().detener(), "STOPPED");
    }

    private void cambiarEstado(Conexion c, Campana campana, String ruta, String yaEstaba) {
        try {
            llamar(c, "PUT", ruta, Map.of("campaign_id", campana.id()), null, false);
        } catch (WolkvoxException e) {
            // "THE CAMPAIGN HAS NOT BEEN STARTED/STOPPED": ya estaba en ese estado, no es un error para la operación.
            String m = e.getMessage() == null ? "" : e.getMessage().toUpperCase(Locale.ROOT);
            if (m.contains("HAS NOT BEEN " + yaEstaba)) {
                LOG.infof("La campaña %s de %s ya estaba %s", campana.id(), c, yaEstaba.toLowerCase(Locale.ROOT));
                return;
            }
            throw e;
        }
    }

    // ─── Métricas ───────────────────────────────────────────────────────

    @Override
    public List<MetricaHora> metricasPorHora(Conexion c, Campana campana, LocalDateTime desde, LocalDateTime hasta) {
        List<MetricaHora> r = new ArrayList<>();
        for (LocalDateTime h = desde.truncatedTo(ChronoUnit.HOURS); h.isBefore(hasta); h = h.plusHours(1)) {
            LocalDateTime fin = h.plusHours(1).minusSeconds(1);
            Set<String> agentes = new HashSet<>();
            int gestionados = 0;
            for (JsonNode n : agentesPorCampana(c, h, fin)) {
                if (!campana.id().equals(texto(n, "campaign_id").trim())) continue;
                int salientes = Optional.ofNullable(entero(texto(n, "outbound"))).orElse(0);
                String login = texto(n, "login_time");
                if (salientes > 0 || (!login.isBlank() && !login.matches("[0:.]+"))) agentes.add(texto(n, "agent_id"));
                gestionados += salientes;
            }
            int barridos = 0;
            Map<String, String> p = new LinkedHashMap<>();
            p.put("campaign_id", campana.id());
            p.put("date_ini", h.format(FECHA_API));
            p.put("date_end", fin.format(FECHA_API));
            for (JsonNode n : llamar(c, "GET", ep().intentosCampana(), p, null, true).path("data")) {
                barridos += Optional.ofNullable(entero(texto(n, "records"))).orElse(0);
            }
            r.add(new MetricaHora(campana.id(), h, agentes.size(), barridos, gestionados));
        }
        return r;
    }

    @Override
    public int gestionados(Conexion c, Campana campana, LocalDateTime desde, LocalDateTime hasta) {
        int total = 0;
        for (JsonNode n : agentesPorCampana(c, desde, hasta)) {
            if (campana.id().equals(texto(n, "campaign_id").trim())) total += Optional.ofNullable(entero(texto(n, "outbound"))).orElse(0);
        }
        return total;
    }

    private JsonNode agentesPorCampana(Conexion c, LocalDateTime desde, LocalDateTime hasta) {
        String clave = c.servidor() + ":" + desde.format(FECHA_API) + "-" + hasta.format(FECHA_API);
        long ahora = System.currentTimeMillis();
        cacheAgentes.values().removeIf(x -> ahora - x.momento() > 180_000);
        CacheReporte cache = cacheAgentes.get(clave);
        if (cache != null) return cache.datos();
        Map<String, String> p = new LinkedHashMap<>();
        p.put("date_ini", desde.format(FECHA_API));
        p.put("date_end", hasta.format(FECHA_API));
        JsonNode d = llamar(c, "GET", ep().agentesPorCampana(), p, null, true).path("data");
        cacheAgentes.put(clave, new CacheReporte(ahora, d));
        return d;
    }

    // ─── HTTP ───────────────────────────────────────────────────────────

    /**
     * Hace la llamada respetando 2 simultáneas por token y reintenta solo lo que tiene sentido reintentar
     * (servidor ocupado, límite de simultáneas, caídas de red), nunca un POST de cargue.
     *
     * @param vacioSiNoHayDatos para reportes: "NO DATA FOUND" devuelve data vacía en lugar de error.
     */
    private JsonNode llamar(Conexion c, String metodo, String ruta, Map<String, String> params, Object cuerpo, boolean vacioSiNoHayDatos) {
        if (c.servidor().isBlank()) {
            throw new WolkvoxException(Tipo.DATOS, "Falta el servidor", "La campaña no tiene número de servidor.",
                    "Escriba los 4 dígitos de wvXXXX.wolkvox.com en la configuración de la campaña.");
        }
        if (c.token().isBlank()) {
            throw new WolkvoxException(Tipo.TOKEN, "Falta el token", "La campaña no tiene token.",
                    "Cree un token en Wolkvox Manager → Configuración → Integraciones → Tokens y péguelo en la campaña.");
        }
        int intentos = "POST".equals(metodo) ? 1 : 3;
        for (int i = 1; ; i++) {
            try {
                return llamarUnaVez(c, metodo, ruta, params, cuerpo, vacioSiNoHayDatos);
            } catch (WolkvoxException e) {
                if (!e.reintentable() || i >= intentos) throw e;
                LOG.warnf("Wolkvox %s %s: %s. Reintento %d en %d s", c, ruta, e.getMessage(), i, 2 * i);
                try {
                    Thread.sleep(2000L * i);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private JsonNode llamarUnaVez(Conexion c, String metodo, String ruta, Map<String, String> params, Object cuerpo, boolean vacioSiNoHayDatos) {
        String base = config.wolkvox().urlBase().replace("{servidor}", c.servidor());
        StringBuilder url = new StringBuilder(base.endsWith("/") ? base : base + "/").append(ruta);
        for (Map.Entry<String, String> p : params.entrySet()) {
            url.append(url.indexOf("?") < 0 ? '?' : '&').append(p.getKey()).append('=')
               .append(URLEncoder.encode(p.getValue(), StandardCharsets.UTF_8));
        }
        Semaphore cupo = cupos.computeIfAbsent(c.token(), k -> new Semaphore(config.wolkvox().maxConcurrencia(), true));
        try {
            HttpRequest.BodyPublisher body = cuerpo == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(cuerpo));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(60))
                    .header("wolkvox-token", c.token())
                    .header("Content-Type", "application/json")
                    .method(metodo, body).build();
            cupo.acquire();
            HttpResponse<String> resp;
            try {
                resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            } finally {
                cupo.release();
            }
            String texto = resp.body() == null ? "" : resp.body().trim();
            JsonNode n;
            try {
                n = texto.isEmpty() ? json.createObjectNode() : json.readTree(texto);
            } catch (IOException e) {
                if (resp.statusCode() >= 300) throw traducir(c, resp.statusCode(), texto, ruta);
                throw new WolkvoxException(Tipo.SERVIDOR, "Respuesta inesperada de Wolkvox",
                        "El servidor " + c + " no respondió en el formato esperado: " + recortar(texto),
                        "Revise que el número de servidor sea el correcto.", resp.statusCode(), false, e);
            }
            int codigo = codigo(n, resp.statusCode());
            if (codigo >= 300) {
                String msg = n.path("msg").asText(n.path("error").asText(""));
                if (msg.isBlank()) msg = n.path("error").asText(recortar(texto));
                String m = msg.toUpperCase(Locale.ROOT);
                if (vacioSiNoHayDatos && codigo == 404 && (m.contains("NO DATA") || m.contains("VALIDATE DATES") || m.contains("NO RECORDS"))) {
                    return json.createObjectNode().set("data", json.createArrayNode());
                }
                throw traducir(c, codigo, msg, ruta);
            }
            return n;
        } catch (WolkvoxException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WolkvoxException(Tipo.RED, "Llamada interrumpida", "Se interrumpió la llamada a " + c, "Intente de nuevo.", 0, false, e);
        } catch (UnknownHostException e) {
            throw new WolkvoxException(Tipo.RED, "Servidor no encontrado",
                    "No existe la dirección wv" + c.servidor() + ".wolkvox.com o el equipo no tiene internet.",
                    "Revise el número de servidor (los 4 dígitos después de \"wv\") y la conexión a internet.", 0, false, e);
        } catch (HttpTimeoutException e) {
            throw new WolkvoxException(Tipo.RED, "Wolkvox no respondió a tiempo",
                    "El servidor " + c + " tardó más de 60 segundos.", "Intente de nuevo en unos minutos.", 0, true, e);
        } catch (ConnectException e) {
            throw new WolkvoxException(Tipo.RED, "Sin conexión con Wolkvox",
                    "No se pudo conectar con " + c + " (" + causa(e) + ").",
                    "Revise la conexión a internet, el número de servidor y si la red usa proxy o firewall que bloquee *.wolkvox.com.", 0, true, e);
        } catch (IOException e) {
            throw new WolkvoxException(Tipo.RED, "Error de comunicación con Wolkvox",
                    causa(e), "Intente de nuevo.", 0, true, e);
        }
    }

    /** Mensaje técnico de la excepción más profunda, para diagnosticar fallos de red. */
    static String causa(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m != null && !m.isBlank() ? ": " + m : "");
    }

    /** El código puede venir en el estado HTTP o en el campo "code" del cuerpo (a veces como texto). */
    private static int codigo(JsonNode n, int http) {
        if (http >= 300) return http;
        Integer c = entero(n.path("code").asText(""));
        return c != null && c >= 100 ? c : http;
    }

    /** Traduce los códigos documentados en https://www.wolkvox.com/developers/codigos-de-error. */
    static WolkvoxException traducir(Conexion c, int codigo, String msg, String ruta) {
        String m = msg == null ? "" : msg.toUpperCase(Locale.ROOT);
        String srv = c.toString();
        if (codigo == 401 || codigo == 403) {
            if (m.contains("LIMIT EXCEEDED") && !m.contains("SIMULTANEOUS"))
                return new WolkvoxException(Tipo.LIMITE, "Se acabaron los consumos del día", msg + " (" + srv + ")",
                        "Wolkvox permite licencias × 1000 consumos diarios por token. Espere al día siguiente o cree otro token.", codigo, false, null);
            if (m.contains("SIMULTANEOUS"))
                return new WolkvoxException(Tipo.LIMITE, "Demasiadas consultas al mismo tiempo", msg + " (" + srv + ")",
                        "Wolkvox acepta 2 consultas simultáneas por token. La app reintenta sola.", codigo, true, null);
            if (m.contains("TRY AGAIN LATER") || m.contains("CANNOT BE CONSUMED"))
                return new WolkvoxException(Tipo.SERVIDOR, "Wolkvox está ocupado", msg + " (" + srv + ")",
                        "El servidor está sacando copias de seguridad. Intente de nuevo en unos minutos.", codigo, true, null);
            return new WolkvoxException(Tipo.TOKEN, "Token inválido o vencido", (msg.isBlank() ? "Wolkvox rechazó el token" : msg) + " (" + srv + ")",
                    "Cree un token nuevo en Wolkvox Manager → Configuración → Integraciones → Tokens y actualícelo en la campaña.", codigo, false, null);
        }
        if (codigo == 404) {
            if (m.contains("DOES NOT EXIST") || m.contains("NOT EXIST"))
                return new WolkvoxException(Tipo.NO_EXISTE, "No existe en el servidor", msg + " (" + srv + ")",
                        "Revise el número de campaña y que pertenezca al servidor " + srv + ".", codigo, false, null);
            if (m.contains("LICENSES") || m.contains("YOU CAN UPLOAD"))
                return new WolkvoxException(Tipo.LIMITE, "Supera los registros permitidos por licencias", msg,
                        "Cargue menos registros o pida más licencias activas a Wolkvox.", codigo, false, null);
            return new WolkvoxException(Tipo.DATOS, "Wolkvox rechazó la solicitud", msg + " (" + srv + ")",
                    "Revise los datos enviados.", codigo, false, null);
        }
        if (codigo == 412)
            return new WolkvoxException(Tipo.LIMITE, "Supera un límite de Wolkvox", msg,
                    "Máximo 10.000 registros por envío, 60.000 por consulta y 60 segundos por llamada.", codigo, false, null);
        if (codigo == 416)
            return new WolkvoxException(Tipo.DATOS, "Fechas no válidas para Wolkvox", msg, "Máximo 31 días por consulta.", codigo, false, null);
        if (codigo == 400 || codigo == 405)
            return new WolkvoxException(Tipo.DATOS, "Solicitud no válida", msg + " (" + ruta + ")",
                    "Puede que la ruta de la API haya cambiado: revise bitacora.wolkvox.endpoints en la configuración.", codigo, false, null);
        if (codigo >= 500)
            return new WolkvoxException(Tipo.SERVIDOR, "Wolkvox no está disponible", msg + " (" + srv + ")",
                    "Es un problema temporal del servidor de Wolkvox. Intente de nuevo en unos minutos.", codigo, true, null);
        return new WolkvoxException(Tipo.SERVIDOR, "Error de Wolkvox " + codigo, msg + " (" + srv + ")", "Intente de nuevo.", codigo, false, null);
    }

    private static String texto(JsonNode n, String... campos) {
        for (String c : campos) if (n.hasNonNull(c)) return n.get(c).asText();
        return "";
    }

    private static Integer entero(String s) {
        if (s == null) return null;
        String t = s.trim().replace(",", "");
        if (t.isEmpty()) return null;
        try { return (int) Double.parseDouble(t); } catch (NumberFormatException e) { return null; }
    }

    private static String recortar(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
