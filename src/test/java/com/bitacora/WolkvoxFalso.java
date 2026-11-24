package com.bitacora;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servidor HTTP de prueba que responde como la API v2 de Wolkvox Manager (rutas, campos y mensajes de error
 * de la documentación oficial). Atiende varios servidores: /{servidor}/api/v2/... cada uno con su token.
 * La app real (WolkvoxHttpAdaptador) habla con él durante las pruebas.
 */
public class WolkvoxFalso implements QuarkusTestResourceLifecycleManager {

    public record Camp(String id, String nombre, String tipo, String estado) {}

    static final Map<String, String> TOKENS = Map.of("0003", "TOKEN-0003", "0100", "TOKEN-0100");
    static final Map<String, Map<String, Camp>> CAMPANAS = new ConcurrentHashMap<>();
    static final List<Map<String, Object>> CARGAS = Collections.synchronizedList(new ArrayList<>());
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;

    static void reiniciar() {
        CAMPANAS.clear();
        CARGAS.clear();
        Map<String, Camp> s3 = new ConcurrentHashMap<>();
        s3.put("15315", new Camp("15315", "JCAP_PDS_2", "predictive", "Started"));
        s3.put("30849", new Camp("30849", "REINTEGRA_INCUMPLIDOS", "preview", "Stopped"));
        CAMPANAS.put("0003", s3);
        Map<String, Camp> s100 = new ConcurrentHashMap<>();
        s100.put("777", new Camp("777", "COBRANZA_TEMPRANA", "predictive", "Stopped"));
        CAMPANAS.put("0100", s100);
    }

    /** Simula que alguien enciende o apaga la campaña desde Wolkvox Manager. */
    static void cambiarDesdeManager(String servidor, String id, boolean activa) {
        Camp c = CAMPANAS.get(servidor).get(id);
        CAMPANAS.get(servidor).put(id, new Camp(c.id(), c.nombre(), c.tipo(), activa ? "Started" : "Stopped"));
    }

    @Override
    public Map<String, String> start() {
        reiniciar();
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        server.createContext("/", this::atender);
        server.start();
        return Map.of("bitacora.wolkvox.url-base", "http://127.0.0.1:" + server.getAddress().getPort() + "/{servidor}/api/v2/");
    }

    @Override
    public void stop() {
        if (server != null) server.stop(0);
    }

    private void atender(HttpExchange ex) throws IOException {
        try {
            String[] partes = ex.getRequestURI().getPath().split("/");
            String servidor = partes[1];
            String recurso = partes[partes.length - 1];
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            String api = q.getOrDefault("api", "");
            if (!TOKENS.containsKey(servidor)) { responder(ex, 404, "<html>not found</html>"); return; }
            String token = ex.getRequestHeaders().getFirst("wolkvox-token");
            if (token == null) { json(ex, 401, Map.of("code", 401, "error", "UNAUTHORIZED", "msg", "PLEASE ENTER THE WOLKVOX-TOKEN")); return; }
            if (!TOKENS.get(servidor).equals(token)) { json(ex, 401, Map.of("code", 401, "error", "FORBIDDEN", "msg", "INVALID TOKEN")); return; }
            Map<String, Camp> camps = CAMPANAS.get(servidor);
            switch (recurso + ":" + api) {
                case "information.php:campaigns" -> {
                    String filtro = q.get("campaign");
                    List<Map<String, String>> data = new ArrayList<>();
                    for (Camp c : camps.values()) {
                        if (filtro != null && !filtro.equals(c.id())) continue;
                        data.add(Map.of("campaign_id", c.id(), "campaign_name", c.nombre(), "type_campaign", c.tipo(),
                                "status", c.estado(), "records", "1500"));
                    }
                    if (filtro != null && data.isEmpty()) {
                        json(ex, 404, Map.of("code", 404, "error", "NOT FOUND", "msg", "CAMPAING_ID " + filtro + " DOES NOT EXIST IN YOUR OPERATION."));
                        return;
                    }
                    json(ex, 200, Map.of("code", "", "msg", data.size() + " records were found", "data", data));
                }
                case "campaign.php:add_record" -> {
                    List<?> cuerpo = JSON.readValue(ex.getRequestBody(), List.class);
                    Map<String, Object> carga = new HashMap<>(q);
                    carga.put("servidor", servidor);
                    carga.put("registros", cuerpo);
                    CARGAS.add(carga);
                    json(ex, 200, Map.of("code", "201", "msg", "All records has been added successfully",
                            "data", List.of(Map.of("total_registers", String.valueOf(cuerpo.size())))));
                }
                case "campaign.php:start", "campaign.php:stop" -> {
                    String id = q.get("campaign_id");
                    Camp c = camps.get(id);
                    if (c == null) { json(ex, 404, Map.of("code", 404, "msg", "CAMPAING_ID " + id + " DOES NOT EXIST IN YOUR OPERATION.")); return; }
                    boolean iniciar = api.equals("start");
                    if (iniciar == c.estado().equals("Started")) {
                        json(ex, 404, Map.of("code", 404, "msg", "THE CAMPAIGN HAS NOT BEEN " + (iniciar ? "STARTED" : "STOPPED") + ", PLEASE VALIDATE THE DATA"));
                        return;
                    }
                    cambiarDesdeManager(servidor, id, iniciar);
                    json(ex, 200, Map.of("code", 202, "msg", "The " + id + " campaign_id has been " + (iniciar ? "started" : "stopped") + " successfully"));
                }
                case "reports_manager.php:agent_4" -> json(ex, 200, Map.of("code", 200, "data", List.of(
                        Map.of("agent_id", "1001", "outbound", "12", "login_time", "00:45:00", "campaign_id", "15315"),
                        Map.of("agent_id", "1002", "outbound", "8", "login_time", "00:30:00", "campaign_id", "15315"))));
                case "reports_manager.php:campaign_4" -> {
                    if ("15315".equals(q.get("campaign_id"))) {
                        json(ex, 200, Map.of("code", 200, "data", List.of(Map.of("result", "ANSWER", "records", "20"), Map.of("result", "NO ANSWER", "records", "30"))));
                    } else {
                        json(ex, 404, Map.of("code", 404, "error", "NOT FOUND", "msg", "NO DATA FOUND"));
                    }
                }
                default -> json(ex, 400, Map.of("code", 400, "msg", "THE API YOU WANT TO CONSULT DOES NOT EXIST"));
            }
        } catch (Exception e) {
            responder(ex, 500, "{\"code\":500,\"msg\":\"AN UNEXPECTED ERROR OCCURRED\"}");
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String p : raw.split("&")) {
            String[] kv = p.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return m;
    }

    private static void json(HttpExchange ex, int estado, Object cuerpo) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/json");
        responder(ex, estado, JSON.writeValueAsString(cuerpo));
    }

    private static void responder(HttpExchange ex, int estado, String cuerpo) throws IOException {
        byte[] b = cuerpo.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(estado, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
