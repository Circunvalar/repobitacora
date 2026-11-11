package com.bitacora;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Flujo completo con Wolkvox simulado: escanear → cargar → bitácora → activar → métricas → Excel. */
@QuarkusTest
class FlujoCargueTest {

    static final Path ENTRADA = Path.of("target/prueba/entrada");

    @BeforeEach
    void preparar() throws Exception {
        Files.createDirectories(ENTRADA);
        StringBuilder csv = new StringBuilder("Nombre,Apellido,ID,Tel1,Tel2,Opt1\n");
        for (int i = 1; i <= 250; i++) csv.append("Cliente").append(i).append(",X,").append(1000 + i).append(",30012345").append(String.format("%02d", i % 100)).append(",,a\n");
        csv.append("Malo,X,,abc,,a\n");
        Files.writeString(ENTRADA.resolve("MASIVO_PRUEBA_WOLKVOX_20261110.csv"), csv);
    }

    @Test
    void cargaUnaListaYLaDejaEnBitacora() {
        given().when().get("/api/archivos").then().statusCode(200)
                .body("nombre", hasItem("MASIVO_PRUEBA_WOLKVOX_20261110.csv"));

        given().when().get("/api/archivos/MASIVO_PRUEBA_WOLKVOX_20261110.csv/revision").then().statusCode(200)
                .body("validos", is(250)).body("invalidos", is(1));

        String hoy = LocalDate.now().toString();
        given().contentType(ContentType.JSON).body("""
                {"cargues":[{"archivo":"MASIVO_PRUEBA_WOLKVOX_20261110.csv","campanaId":"30849","estrategia":"NUEVA",
                 "jefe":"CORDINADORES REINTEGRA","nombreEstrategia":"Incumplidos hasta 2 días",
                 "horaInicioPlan":"10:00","horaFinPlan":"11:00","fecha":"%s"}]}""".formatted(hoy))
                .when().post("/api/cargues").then().statusCode(200)
                .body("[0].estado", is("CARGADO_CON_RECHAZOS"))
                .body("[0].registrosNoValidos", is(1))
                .body("[0].registrosCargados", is(248)) // el simulado rechaza 1 %
                .body("[0].tipoMarcacion", is("PREVIEW"))
                .body("[0].descargaInforme", is("PDT"));

        // El archivo pasa a procesados y no se puede volver a cargar a la misma campaña
        assertTrue(Files.exists(Path.of("target/prueba/procesados/MASIVO_PRUEBA_WOLKVOX_20261110.csv")));

        given().when().post("/api/campanas/30849/iniciar").then().statusCode(200).body("inicio", notNullValue());
        given().when().get("/api/cargues?fecha=" + hoy).then().statusCode(200)
                .body("[0].inicioCampana", notNullValue()).body("[0].campanaNombre", is("REINTEGRA_INCUMPLIDOS"));

        given().when().post("/api/metricas/recolectar?fecha=" + hoy).then().statusCode(200);

        given().when().get("/api/bitacora/excel").then().statusCode(200)
                .header("Content-Disposition", containsString("Bitacora.xlsx"));
        assertTrue(Files.exists(Path.of("target/prueba/Bitacora.xlsx")));
    }

    @Test
    void rechazaCampanaInexistente() {
        given().contentType(ContentType.JSON).body("""
                {"cargues":[{"archivo":"MASIVO_PRUEBA_WOLKVOX_20261110.csv","campanaId":"99999"}]}""")
                .when().post("/api/cargues").then().statusCode(404).body("error", containsString("99999"));
    }
}
