package com.bitacora;

import com.bitacora.aplicacion.ProgramacionServicio;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** Flujo completo contra un servidor que responde como Wolkvox: campaña → cargar con hora → encendido programado → bitácora → Excel. */
@QuarkusTest
@QuarkusTestResource(value = WolkvoxFalso.class, restrictToAnnotatedClass = false)
class FlujoCargueTest {

    static final Path ENTRADA = Path.of("target/prueba/entrada");
    static final String BASE = "customer_name\tcustomer_last_name\tid_type\tcustomer_id\tage\tgender\tcountry\tstate\tcity\tzone\taddress"
            + "\topt1\topt2\topt3\topt4\topt5\topt6\topt7\topt8\topt9\topt10\topt11\topt12"
            + "\ttel1\ttel2\ttel3\ttel4\ttel5\ttel6\ttel7\ttel8\ttel9\ttel10";
    /** Plantilla predictiva de Wolkvox: 36 columnas. */
    static final String PREDICTIVA = BASE + "\ttel_extra\temail\trecall";
    /** Plantilla preview de prueba: 35 columnas. */
    static final String PREVIEW = BASE + "\ttel_extra\tagent_id";

    @Inject
    ProgramacionServicio programacion;

    @BeforeEach
    void preparar() throws Exception {
        Files.createDirectories(ENTRADA);
        Files.writeString(ENTRADA.resolve("MASIVO_PRUEBA_WOLKVOX_20261110.csv"), csv(PREVIEW, 250));
        Files.writeString(ENTRADA.resolve("PREDICTIVA_PRUEBA_20261110.csv"), csv(PREDICTIVA, 5));
    }

    static String csv(String encabezado, int filas) {
        int cols = encabezado.split("\t").length;
        StringBuilder s = new StringBuilder(encabezado).append('\n');
        for (int i = 1; i <= filas; i++) {
            String[] f = new String[cols];
            java.util.Arrays.fill(f, "");
            f[0] = "Cliente" + i; f[1] = "X"; f[2] = "CC"; f[3] = String.valueOf(1000 + i); f[11] = "a";
            f[23] = "30012345" + String.format("%02d", i % 100);
            if (cols == 36) f[34] = "c" + i + "@x.co";
            s.append(String.join("\t", f)).append('\n');
        }
        String[] malo = new String[cols];
        java.util.Arrays.fill(malo, "");
        malo[0] = "Malo"; malo[23] = "abc";
        return s.append(String.join("\t", malo)).append('\n').toString();
    }

    static long guardarCampana(String campanaId, String servidor, String token) {
        return given().contentType(ContentType.JSON).body(Map.of("campanaId", campanaId, "servidor", servidor, "token", token))
                .when().post("/api/mis-campanas").then().statusCode(200)
                .body("tokenValido", is(true)).body("token", not(containsString(token))).body("campanaId", is(campanaId))
                .extract().jsonPath().getLong("id");
    }

    @Test
    void cargaUnaListaProgramaElEncendidoYLaDejaEnBitacora() {
        long id = guardarCampana("30849", "wv0003", "TOKEN-0003");
        WolkvoxFalso.cambiarDesdeManager("0003", "30849", false);

        given().when().get("/api/archivos/MASIVO_PRUEBA_WOLKVOX_20261110.csv/revision").then().statusCode(200)
                .body("validos", is(250)).body("invalidos", is(1)).body("columnas", is(35)).body("tipo", is("PREVIEW"));
        given().when().get("/api/archivos/PREDICTIVA_PRUEBA_20261110.csv/revision").then().statusCode(200)
                .body("columnas", is(36)).body("tipo", is("PREDICTIVO"));

        String hoy = LocalDate.now().toString();
        // Sin hora de encendido no se carga
        given().contentType(ContentType.JSON).body("""
                {"cargues":[{"operacionId":%d,"archivo":"MASIVO_PRUEBA_WOLKVOX_20261110.csv","campanaId":"30849","fecha":"%s"}]}"""
                .formatted(id, hoy)).when().post("/api/cargues").then().statusCode(400).body("titulo", is("Falta la hora de encendido"));
        // Una lista predictiva no entra a una campaña preview
        given().contentType(ContentType.JSON).body("""
                {"cargues":[{"operacionId":%d,"archivo":"PREDICTIVA_PRUEBA_20261110.csv","campanaId":"30849","horaInicioPlan":"00:00","fecha":"%s"}]}"""
                .formatted(id, hoy)).when().post("/api/cargues").then().statusCode(400)
                .body("titulo", is("La lista no corresponde al tipo de campaña"));

        given().contentType(ContentType.JSON).body("""
                {"cargues":[{"operacionId":%d,"archivo":"MASIVO_PRUEBA_WOLKVOX_20261110.csv","campanaId":"30849","estrategia":"NUEVA",
                 "jefe":"CORDINADORES REINTEGRA","nombreEstrategia":"Incumplidos hasta 2 días",
                 "horaInicioPlan":"00:00","fecha":"%s"}]}""".formatted(id, hoy))
                .when().post("/api/cargues").then().statusCode(200)
                .body("[0].estado", is("CARGADO_CON_RECHAZOS"))
                .body("[0].registrosNoValidos", is(1))
                .body("[0].registrosCargados", is(250))
                .body("[0].tipoMarcacion", is("PREVIEW"));

        // Lo que llegó a Wolkvox: campos de add_record; tel_extra no se manda a una campaña preview
        Map<String, Object> carga = WolkvoxFalso.CARGAS.get(WolkvoxFalso.CARGAS.size() - 1);
        assertEquals("preview", carga.get("type_campaign"));
        assertEquals("30849", carga.get("campaign_id"));
        @SuppressWarnings("unchecked") Map<String, String> primero = ((List<Map<String, String>>) carga.get("registros")).get(0);
        assertEquals("Cliente1", primero.get("customer_name"));
        assertEquals("CC", primero.get("id_type"));
        assertEquals("1001", primero.get("customer_id"));
        assertNotNull(primero.get("tel1"));
        assertFalse(primero.containsKey("tel_extra"));
        assertTrue(Files.exists(Path.of("target/prueba/procesados/MASIVO_PRUEBA_WOLKVOX_20261110.csv")));

        // El cargue dejó programado el encendido de hoy; al llegar la hora se prende sola
        given().when().get("/api/programaciones").then().statusCode(200)
                .body("findAll { it.campanaGuardadaId == " + id + " }.estado", hasItem("PENDIENTE"));
        assertTrue(programacion.ejecutarPendientes() >= 1);
        given().when().get("/api/programaciones").then().statusCode(200)
                .body("findAll { it.campanaGuardadaId == " + id + " }.estado", hasItem("EJECUTADA"));
        given().when().get("/api/cargues?fecha=" + hoy).then().statusCode(200)
                .body("find { it.operacionId == " + id + " }.inicioCampana", notNullValue());
        given().when().get("/api/campanas/historial?fecha=" + hoy).then().statusCode(200)
                .body("findAll { it.operacionId == " + id + " }.origen", hasItem("PROGRAMADO"));

        // Observación por lista: queda en la bitácora
        long cargue = given().when().get("/api/cargues?fecha=" + hoy).then().extract().jsonPath()
                .getLong("find { it.operacionId == " + id + " }.id");
        given().contentType(ContentType.JSON).body(Map.of("observacion", "Lista con baja contactabilidad"))
                .when().patch("/api/cargues/" + cargue).then().statusCode(200).body("observacion", is("Lista con baja contactabilidad"));

        given().when().post("/api/metricas/recolectar?fecha=" + hoy).then().statusCode(200);
        given().when().post("/api/campanas/" + id + "/30849/detener").then().statusCode(200);
        given().when().get("/api/bitacora/excel").then().statusCode(200)
                .header("Content-Disposition", containsString("Bitacora.xlsx"));
    }

    @Test
    void programarCambiarYCancelarUnEncendido() {
        long id = guardarCampana("777", "0100", "TOKEN-0100");
        long p = given().contentType(ContentType.JSON).body(Map.of("campanaGuardadaId", id, "hora", "23:59"))
                .when().post("/api/programaciones").then().statusCode(200).body("estado", is("PENDIENTE"))
                .body("campanaId", is("777")).extract().jsonPath().getLong("id");
        given().contentType(ContentType.JSON).body(Map.of("hora", "23:58")).when().put("/api/programaciones/" + p)
                .then().statusCode(200).body("hora", startsWith("23:58"));
        given().when().delete("/api/programaciones/" + p).then().statusCode(200).body("estado", is("CANCELADA"));
        given().when().delete("/api/programaciones/" + p).then().statusCode(409);
        given().contentType(ContentType.JSON).body(Map.of("campanaGuardadaId", id))
                .when().post("/api/programaciones").then().statusCode(400).body("titulo", is("Falta la hora de encendido"));
    }

    @Test
    void cargueSinCampanaExplicaQueFalta() {
        given().contentType(ContentType.JSON).body("""
                {"cargues":[{"archivo":"MASIVO_PRUEBA_WOLKVOX_20261110.csv","campanaId":"30849"}]}""")
                .when().post("/api/cargues").then().statusCode(400).body("titulo", notNullValue());
    }
}
