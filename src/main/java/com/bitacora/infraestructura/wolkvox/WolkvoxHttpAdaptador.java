package com.bitacora.infraestructura.wolkvox;

import com.bitacora.aplicacion.BitacoraConfig;
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
import java.util.concurrent.Semaphore;

/**
 * Cliente real de la API v2 de Wolkvox: https://wv{servidor}.wolkvox.com/api/v2/ con el encabezado wolkvox-token.
 *
 * Las rutas están en application.properties porque aún falta confirmarlas contra la colección de Postman.
 * Respeta los límites publicados: 2 solicitudes simultáneas por token, 60 s por solicitud.
 * Los nombres de campos de las respuestas están marcados con PENDIENTE donde son supuestos.
 */
@ApplicationScoped
public class WolkvoxHttpAdaptador implements WolkvoxPuerto {

    private static final Logger LOG = Logger.getLogger(WolkvoxHttpAdaptador.class);
    private static final DateTimeFormatter FECHA_API = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final BitacoraConfig config;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final Semaphore concurrencia;

    public WolkvoxHttpAdaptador(BitacoraConfig config, ObjectMapper json) {
        this.config = config;
        this.json = json;
        this.concurrencia = new Semaphore(config.wolkvox().maxConcurrencia(), true);
    }

    private BitacoraConfig.Wolkvox.Endpoints ep() { return config.wolkvox().endpoints(); }

    @Override
    @Retry(maxRetries = 3, delay = 2, delayUnit = ChronoUnit.SECONDS, jitter = 500)
    @Timeout(value = 65, unit = ChronoUnit.SECONDS)
    public List<Campana> campanas() {
        JsonNode data = llamar("GET", ep().campanas(), Map.of(), null).path("data");
        List<Campana> r = new ArrayList<>();
        for (JsonNode n : data) {
            // PENDIENTE: confirmar nombres de campos en la respuesta.
            String tipo = texto(n, "type_campaign", "campaign_type", "type").toLowerCase(Locale.ROOT);
            r.add(new Campana(texto(n, "campaign_id", "id"), texto(n, "campaign_name", "name"),
                    tipo.contains("preview") ? TipoMarcacion.PREVIEW : TipoMarcacion.PREDICTIVO));
        }
        return r;
    }

    @Override
    @Retry(maxRetries = 3, delay = 3, delayUnit = ChronoUnit.SECONDS, jitter = 1000)
    @Timeout(value = 65, unit = ChronoUnit.SECONDS)
    public ResultadoLote cargarRegistros(Campana campana, List<Map<String, String>> lote) {
        // PENDIENTE: cuerpo según la colección de Postman. Se envían las columnas tal cual vienen en la plantilla Wolkvox.
        Map<String, Object> cuerpo = Map.of("campaign_id", campana.id(),
                "type_campaign", campana.tipo().valorApi(), "records", lote);
        JsonNode r = llamar("POST", ep().cargarRegistros(), params(campana), cuerpo);
        int rechazados = r.path("data").path("rejected").asInt(0);
        int aceptados = r.path("data").path("loaded").asInt(lote.size() - rechazados);
        return new ResultadoLote(aceptados, rechazados, r.path("msg").asText(""));
    }

    @Override
    @Retry(maxRetries = 2, delay = 2, delayUnit = ChronoUnit.SECONDS)
    public void iniciarCampana(Campana campana) {
        llamar("PUT", ep().iniciar(), params(campana), null);
    }

    @Override
    @Retry(maxRetries = 2, delay = 2, delayUnit = ChronoUnit.SECONDS)
    public void detenerCampana(Campana campana) {
        llamar("PUT", ep().detener(), params(campana), null);
    }

    @Override
    @Retry(maxRetries = 3, delay = 2, delayUnit = ChronoUnit.SECONDS, jitter = 500)
    @Timeout(value = 65, unit = ChronoUnit.SECONDS)
    public List<MetricaHora> metricasPorHora(Campana campana, LocalDateTime desde, LocalDateTime hasta) {
        Map<String, String> rango = Map.of("date_ini", desde.format(FECHA_API), "date_end", hasta.format(FECHA_API));

        // Agentes conectados por hora: reporte de estados de agente hora a hora (LOGIN_TIME > 0 en esa hora).
        Map<LocalDateTime, Set<String>> agentes = new TreeMap<>();
        for (JsonNode n : llamar("GET", ep().agentesPorHora(), rango, null).path("data")) {
            LocalDateTime h = horaDe(texto(n, "DATE", "date"), texto(n, "HOUR", "hour"));
            if (h != null && !"00:00:00".equals(texto(n, "LOGIN_TIME", "login_time"))) {
                agentes.computeIfAbsent(h, k -> new HashSet<>()).add(texto(n, "AGENT_ID", "agent_id"));
            }
        }

        // Barrido por hora: detalle de interacciones salientes de la campaña. PENDIENTE: campos exactos.
        Map<LocalDateTime, Set<String>> barridos = new TreeMap<>();
        Map<LocalDateTime, Integer> gestionados = new TreeMap<>();
        for (JsonNode n : llamar("GET", ep().interacciones(), rango, null).path("data")) {
            if (!campana.id().equals(texto(n, "campaign_id", "CAMPAIGN_ID"))) continue;
            LocalDateTime h = horaDe(texto(n, "date", "DATE"), null);
            if (h == null) continue;
            barridos.computeIfAbsent(h, k -> new HashSet<>()).add(texto(n, "customer_id", "id_customer", "telephone"));
            gestionados.merge(h, 1, Integer::sum);
        }

        List<MetricaHora> r = new ArrayList<>();
        for (LocalDateTime h = desde; h.isBefore(hasta); h = h.plusHours(1)) {
            r.add(new MetricaHora(campana.id(), h, agentes.getOrDefault(h, Set.of()).size(),
                    barridos.getOrDefault(h, Set.of()).size(), gestionados.getOrDefault(h, 0)));
        }
        return r;
    }

    @Override
    @Retry(maxRetries = 3, delay = 2, delayUnit = ChronoUnit.SECONDS, jitter = 500)
    @Timeout(value = 65, unit = ChronoUnit.SECONDS)
    public int gestionadosDeLista(Campana campana, String nombreLista, LocalDateTime desde, LocalDateTime hasta) {
        Map<String, String> rango = Map.of("date_ini", desde.format(FECHA_API), "date_end", hasta.format(FECHA_API));
        Set<String> registros = new HashSet<>();
        for (JsonNode n : llamar("GET", ep().interacciones(), rango, null).path("data")) {
            if (campana.id().equals(texto(n, "campaign_id", "CAMPAIGN_ID"))) {
                registros.add(texto(n, "customer_id", "id_customer", "telephone"));
            }
        }
        return registros.size();
    }

    private Map<String, String> params(Campana c) {
        return Map.of("campaign_id", c.id(), "type_campaign", c.tipo().valorApi());
    }

    private JsonNode llamar(String metodo, String ruta, Map<String, String> params, Object cuerpo) {
        String servidor = config.wolkvox().servidor().orElseThrow(() -> new IllegalStateException("Falta bitacora.wolkvox.servidor"));
        String token = config.wolkvox().token().orElseThrow(() -> new IllegalStateException("Falta bitacora.wolkvox.token"));
        StringBuilder url = new StringBuilder("https://wv").append(servidor).append(".wolkvox.com/api/v2/").append(ruta);
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
                JsonNode n = json.readTree(resp.body());
                String code = n.path("code").asText("");
                if (resp.statusCode() >= 300 || (!code.isEmpty() && !code.startsWith("2"))) {
                    throw new WolkvoxException(metodo + " " + ruta + " respondió " + resp.statusCode() + ": " + n.path("msg").asText(resp.body()));
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

    /** Acepta "2026-11-10 10:23:45", "2026-11-10" + "10", etc. y devuelve la hora truncada. */
    private static LocalDateTime horaDe(String fecha, String hora) {
        try {
            String f = fecha.trim().replace('T', ' ');
            if (hora != null && !hora.isBlank()) {
                String h = hora.trim();
                f = f.substring(0, 10) + " " + (h.length() <= 2 ? h + ":00:00" : h);
            }
            if (f.length() == 10) f += " 00:00:00";
            return LocalDateTime.parse(f.substring(0, 19).replace(' ', 'T')).truncatedTo(ChronoUnit.HOURS);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
