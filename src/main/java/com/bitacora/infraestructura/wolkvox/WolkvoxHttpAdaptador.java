package com.bitacora.infraestructura.wolkvox;

import com.bitacora.aplicacion.BitacoraConfig;
import com.bitacora.aplicacion.ValidadorCsv;
import com.bitacora.dominio.modelo.Campana;
import com.bitacora.dominio.modelo.MetricaHora;
import com.bitacora.dominio.modelo.ResultadoLote;
import com.bitacora.dominio.modelo.TipoMarcacion;
import com.bitacora.dominio.puerto.WolkvoxPuerto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * Cliente real de la API v2 de Wolkvox Manager: https://wv{servidor}.wolkvox.com/api/v2/ con el encabezado wolkvox-token.
 * Rutas y campos tomados de la colección oficial de Postman de Wolkvox (wolkvox APIs ES):
 * <ul>
 *   <li>Campañas: GET information.php?api=campaigns → campaign_id, campaign_name, type_campaign, status (Started/Stopped), records</li>
 *   <li>Cargue: POST campaign.php?api=add_record&amp;type_campaign=…&amp;campaign_id=…[&amp;campaign_status=start|stop], cuerpo = arreglo JSON</li>
 *   <li>Iniciar / detener: PUT campaign.php?api=start|stop&amp;campaign_id=…</li>
 *   <li>Agentes por campaña: GET reports_manager.php?api=agent_4 (agent_id, outbound, login_time, campaign_id)</li>
 *   <li>Intentos de la campaña: GET reports_manager.php?api=campaign_4 (result, records)</li>
 * </ul>
 * Respeta los límites publicados: 2 solicitudes simultáneas por token, 60 s por solicitud, 10.000 registros por cargue.
 */
@ApplicationScoped
public class WolkvoxHttpAdaptador implements WolkvoxPuerto {

    private static final Logger LOG = Logger.getLogger(WolkvoxHttpAdaptador.class);
    private static final DateTimeFormatter FECHA_API = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern COL_TEL = Pattern.compile("^(?:tel|telefono)(\\d{1,2})$");
    private static final Pattern COL_OPT = Pattern.compile("^(?:opt|opcional)(\\d{1,2})$");

    private final BitacoraConfig config;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final Semaphore concurrencia;
    private final Map<String, String> columnas = new HashMap<>();
    /** agent_4 trae todas las campañas: se reutiliza la respuesta de una misma ventana por unos minutos. */
    private final Map<String, CacheReporte> cacheAgentes = new ConcurrentHashMap<>();

    private record CacheReporte(long momento, JsonNode datos) {}

    public WolkvoxHttpAdaptador(BitacoraConfig config, ObjectMapper json) {
        this.config = config;
        this.json = json;
        this.concurrencia = new Semaphore(config.wolkvox().maxConcurrencia(), true);
        for (String par : config.wolkvox().columnas()) {
            String[] p = par.split("=");
            if (p.length == 2) columnas.put(ValidadorCsv.normalizar(p[0]), p[1].trim());
        }
    }

    private BitacoraConfig.Wolkvox.Endpoints ep() { return config.wolkvox().endpoints(); }

    @Override
    @Retry(maxRetries = 3, delay = 2, delayUnit = ChronoUnit.SECONDS, jitter = 500)
    @Timeout(value = 65, unit = ChronoUnit.SECONDS)
    public List<Campana> campanas() {
        JsonNode data = llamar("GET", ep().campanas(), Map.of(), null).path("data");
        List<Campana> r = new ArrayList<>();
        for (JsonNode n : data) {
            String id = texto(n, "campaign_id", "id");
            if (id.isBlank()) continue;
            String tipo = texto(n, "type_campaign", "campaign_type", "type").toLowerCase(Locale.ROOT);
            String estado = texto(n, "status").toLowerCase(Locale.ROOT);
            Boolean activa = estado.isBlank() ? null : estado.startsWith("start");
            r.add(new Campana(id, texto(n, "campaign_name", "name"),
                    tipo.contains("preview") ? TipoMarcacion.PREVIEW : TipoMarcacion.PREDICTIVO,
                    activa, entero(texto(n, "records"))));
        }
        return r;
    }

    /** Sin reintento automático: si Wolkvox alcanzó a recibir el lote, reintentar duplicaría los registros. */
    @Override
    @Timeout(value = 65, unit = ChronoUnit.SECONDS)
    public ResultadoLote cargarRegistros(Campana campana, List<Map<String, String>> lote) {
        List<Map<String, String>> cuerpo = lote.stream().map(this::aRegistroApi).toList();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("type_campaign", campana.tipo().valorApi());
        params.put("campaign_id", campana.id());
        if (campana.tipo() == TipoMarcacion.PREDICTIVO) params.put("campaign_status", campana.estaActiva() ? "start" : "stop");
        JsonNode r = llamar("POST", ep().cargarRegistros(), params, cuerpo);
        JsonNode d = r.path("data");
        if (d.isArray() && !d.isEmpty()) d = d.get(0);
        Integer total = entero(texto(d, "total_registers", "loaded"));
        int aceptados = total != null ? Math.min(total, lote.size()) : lote.size();
        return new ResultadoLote(aceptados, lote.size() - aceptados, r.path("msg").asText(""));
    }

    /** Pasa una fila del CSV (encabezados de la plantilla) a los campos que espera add_record. */
    Map<String, String> aRegistroApi(Map<String, String> fila) {
        Map<String, String> r = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : fila.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (v.isEmpty()) continue;
            String campo = campoApi(ValidadorCsv.normalizar(e.getKey()));
            if (campo != null) r.putIfAbsent(campo, v);
        }
        return r;
    }

    String campoApi(String columna) {
        String c = columnas.get(columna);
        if (c != null) return c;
        Matcher m = COL_TEL.matcher(columna);
        if (m.matches()) return "tel" + Integer.parseInt(m.group(1));
        m = COL_OPT.matcher(columna);
        if (m.matches()) return "opt" + Integer.parseInt(m.group(1));
        return null;
    }

    @Override
    @Retry(maxRetries = 2, delay = 2, delayUnit = ChronoUnit.SECONDS)
    public void iniciarCampana(Campana campana) {
        llamar("PUT", ep().iniciar(), Map.of("campaign_id", campana.id()), null);
    }

    @Override
    @Retry(maxRetries = 2, delay = 2, delayUnit = ChronoUnit.SECONDS)
    public void detenerCampana(Campana campana) {
        llamar("PUT", ep().detener(), Map.of("campaign_id", campana.id()), null);
    }

    @Override
    @Retry(maxRetries = 3, delay = 2, delayUnit = ChronoUnit.SECONDS, jitter = 500)
    public List<MetricaHora> metricasPorHora(Campana campana, LocalDateTime desde, LocalDateTime hasta) {
        List<MetricaHora> r = new ArrayList<>();
        for (LocalDateTime h = desde.truncatedTo(ChronoUnit.HOURS); h.isBefore(hasta); h = h.plusHours(1)) {
            LocalDateTime fin = h.plusHours(1).minusSeconds(1);
            Set<String> agentes = new HashSet<>();
            int gestionados = 0;
            for (JsonNode n : agentesPorCampana(h, fin)) {
                if (!campana.id().equals(texto(n, "campaign_id"))) continue;
                int salientes = Optional.ofNullable(entero(texto(n, "outbound"))).orElse(0);
                String login = texto(n, "login_time");
                if (salientes > 0 || (!login.isBlank() && !login.matches("[0:]+"))) agentes.add(texto(n, "agent_id"));
                gestionados += salientes;
            }
            int barridos = 0;
            for (JsonNode n : llamar("GET", ep().intentosCampana(), rango(campana, h, fin), null).path("data")) {
                barridos += Optional.ofNullable(entero(texto(n, "records"))).orElse(0);
            }
            r.add(new MetricaHora(campana.id(), h, agentes.size(), barridos, gestionados));
        }
        return r;
    }

    @Override
    @Retry(maxRetries = 3, delay = 2, delayUnit = ChronoUnit.SECONDS, jitter = 500)
    public int gestionadosDeLista(Campana campana, String nombreLista, LocalDateTime desde, LocalDateTime hasta) {
        // Wolkvox no reporta por lista: se cuentan las llamadas salientes gestionadas por agentes en la campaña
        // mientras la lista estuvo activa.
        int total = 0;
        for (JsonNode n : agentesPorCampana(desde, hasta)) {
            if (campana.id().equals(texto(n, "campaign_id"))) total += Optional.ofNullable(entero(texto(n, "outbound"))).orElse(0);
        }
        return total;
    }

    private JsonNode agentesPorCampana(LocalDateTime desde, LocalDateTime hasta) {
        String clave = desde.format(FECHA_API) + "-" + hasta.format(FECHA_API);
        long ahora = System.currentTimeMillis();
        cacheAgentes.values().removeIf(c -> ahora - c.momento() > 180_000);
        CacheReporte c = cacheAgentes.get(clave);
        if (c != null) return c.datos();
        JsonNode d = llamar("GET", ep().agentesPorCampana(),
                Map.of("date_ini", desde.format(FECHA_API), "date_end", hasta.format(FECHA_API)), null).path("data");
        cacheAgentes.put(clave, new CacheReporte(ahora, d));
        return d;
    }

    private static Map<String, String> rango(Campana c, LocalDateTime desde, LocalDateTime hasta) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("campaign_id", c.id());
        m.put("date_ini", desde.format(FECHA_API));
        m.put("date_end", hasta.format(FECHA_API));
        return m;
    }

    private static Integer entero(String s) {
        if (s == null) return null;
        String t = s.trim().replace(",", "");
        if (t.isEmpty()) return null;
        try { return (int) Double.parseDouble(t); } catch (NumberFormatException e) { return null; }
    }

    private JsonNode llamar(String metodo, String ruta, Map<String, String> params, Object cuerpo) {
        String token = config.wolkvox().token().filter(x -> !x.isBlank()).orElseThrow(() -> new IllegalStateException("Falta bitacora.wolkvox.token"));
        String base = config.wolkvox().urlBase().orElseGet(() -> "https://wv" + config.wolkvox().servidor()
                .map(x -> x.trim().replaceFirst("(?i)^wv", "")).filter(x -> !x.isBlank())
                .orElseThrow(() -> new IllegalStateException("Falta bitacora.wolkvox.servidor")) + ".wolkvox.com/api/v2/");
        StringBuilder url = new StringBuilder(base.endsWith("/") ? base : base + "/").append(ruta);
        for (Map.Entry<String, String> p : params.entrySet()) {
            url.append(url.indexOf("?") < 0 ? '?' : '&').append(p.getKey()).append('=')
               .append(URLEncoder.encode(p.getValue(), StandardCharsets.UTF_8));
        }
        try {
            HttpRequest.BodyPublisher body = cuerpo == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(cuerpo));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(60))
                    .header("wolkvox-token", token)
                    .header("Content-Type", "application/json")
                    .method(metodo, body).build();
            concurrencia.acquire();
            try {
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                String cuerpoResp = resp.body() == null ? "" : resp.body().trim();
                JsonNode n = cuerpoResp.isEmpty() ? json.createObjectNode() : json.readTree(cuerpoResp);
                String code = n.path("code").asText("");
                if (resp.statusCode() >= 300 || (!code.isEmpty() && !code.startsWith("2"))) {
                    String detalle = n.path("msg").asText(n.path("error").asText(cuerpoResp));
                    if (resp.statusCode() == 401 || resp.statusCode() == 403) detalle = "token inválido o sin permisos (" + detalle + ")";
                    throw new WolkvoxException(metodo + " " + ruta + " respondió " + resp.statusCode() + ": " + detalle);
                }
                return n;
            } finally {
                concurrencia.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WolkvoxException("Llamada interrumpida", e);
        } catch (WolkvoxException e) {
            throw e;
        } catch (Exception e) {
            LOG.warnf("Error llamando %s: %s", ruta, e.getMessage());
            throw new WolkvoxException("Error llamando " + ruta + ": " + e.getMessage(), e);
        }
    }

    private static String texto(JsonNode n, String... campos) {
        for (String c : campos) if (n.hasNonNull(c)) return n.get(c).asText();
        return "";
    }
}
