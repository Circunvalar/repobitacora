package com.bitacora;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
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

/** Flujo completo contra un servidor que responde como Wolkvox: campaña principal → cargar → bitácora → activar → métricas → Excel. */
@QuarkusTest
@QuarkusTestResource(value = WolkvoxFalso.class, restrictToAnnotatedClass = false)
class FlujoCargueTest {

    static final Path ENTRADA = Path.of("target/prueba/entrada");

    @BeforeEach
    void preparar() throws Exception {
        Files.createDirectories(ENTRADA);
        StringBuilder csv = new StringBuilder("Nombre;Apellido;Tipo ID;ID;Teléfono 1;Tel2;Opt1;Email\n");
        for (int i = 1; i <= 250; i++) csv.append("Cliente").append(i).append(";X;CC;").append(1000 + i).append(";30012345").append(String.format("%02d", i % 100)).append(";;a;c").append(i).append("@x.co\n");
        csv.append("Malo;X;CC;;abc;;a;\n");
        Files.writeString(ENTRADA.resolve("MASIVO_PRUEBA_WOLKVOX_20261110.csv"), csv);
    }

    static long crearOperacion(String nombre, String servidor, String token) {
        return given().contentType(ContentType.JSON).body(Map.of("nombre", nombre, "servidor", servidor, "token", token))
                .when().post("/api/operaciones").then().statusCode(200)
                .body("tokenValido", is(true)).body("token", not(containsString(token)))
                .extract().jsonPath().getLong("id");
    }

    @Test
    void cargaUnaListaYLaDejaEnBitacora() {
        long op = crearOperacion("REINTEGRA " + System.nanoTime(), "wv0003", "TOKEN-0003");

        given().when().get("/api/archivos/MASIVO_PRUEBA_WOLKVOX_20261110.csv/revision").then().statusCode(200)
                .body("validos", is(250)).body("invalidos", is(1));

        String hoy = LocalDate.now().toString();
        // El número 30849 no estaba registrado: el cargue lo registra para futuros cargues
        given().contentType(ContentType.JSON).body("""
                {"cargues":[{"operacionId":%d,"archivo":"MASIVO_PRUEBA_WOLKVOX_20261110.csv","campanaId":"30849","estrategia":"NUEVA",
                 "jefe":"CORDINADORES REINTEGRA","nombreEstrategia":"Incumplidos hasta 2 días",
                 "horaInicioPlan":"10:00","horaFinPlan":"11:00","fecha":"%s"}]}""".formatted(op, hoy))
                .when().post("/api/cargues").then().statusCode(200)
                .body("[0].estado", is("CARGADO_CON_RECHAZOS"))
                .body("[0].registrosNoValidos", is(1))
                .body("[0].registrosCargados", is(250))
                .body("[0].tipoMarcacion", is("PREVIEW"))
                .body("[0].operacionId", is((int) op));

        given().when().get("/api/operaciones").then().statusCode(200)
                .body("find { it.id == " + op + " }.campanas.campanaId", hasItem("30849"));

        // Lo que llegó a Wolkvox: campos de add_record; email no se manda a una campaña preview
        Map<String, Object> carga = WolkvoxFalso.CARGAS.get(WolkvoxFalso.CARGAS.size() - 1);
        assertEquals("preview", carga.get("type_campaign"));
        assertEquals("30849", carga.get("campaign_id"));
        @SuppressWarnings("unchecked") Map<String, String> primero = ((List<Map<String, String>>) carga.get("registros")).get(0);
        assertEquals("Cliente1", primero.get("customer_name"));
        assertEquals("CC", primero.get("id_type"));
        assertEquals("1001", primero.get("customer_id"));
        assertNotNull(primero.get("tel1"));
        assertFalse(primero.containsKey("email"));

        assertTrue(Files.exists(Path.of("target/prueba/procesados/MASIVO_PRUEBA_WOLKVOX_20261110.csv")));

        given().when().post("/api/campanas/" + op + "/30849/iniciar").then().statusCode(200).body("inicio", notNullValue());
        given().when().get("/api/cargues?fecha=" + hoy).then().statusCode(200)
                .body("find { it.operacionId == " + op + " }.inicioCampana", notNullValue())
                .body("find { it.operacionId == " + op + " }.campanaNombre", is("REINTEGRA_INCUMPLIDOS"));
        // Volver a iniciar una campaña ya encendida no es error
        given().when().post("/api/campanas/" + op + "/30849/iniciar").then().statusCode(200);

        given().when().post("/api/metricas/recolectar?fecha=" + hoy).then().statusCode(200);
        given().when().post("/api/campanas/" + op + "/30849/detener").then().statusCode(200);

        given().when().get("/api/bitacora/excel").then().statusCode(200)
                .header("Content-Disposition", containsString("Bitacora.xlsx"));
    }

    @Test
    void cargueSinCampanaPrincipalExplicaQueFalta() {
        given().contentType(ContentType.JSON).body("""
                {"cargues":[{"archivo":"MASIVO_PRUEBA_WOLKVOX_20261110.csv","campanaId":"30849"}]}""")
                .when().post("/api/cargues").then().statusCode(400).body("titulo", notNullValue());
    }
}
