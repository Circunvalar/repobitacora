package com.bitacora;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** Campañas principales con su servidor y token, escaneo e historial, y errores explicados. */
@QuarkusTest
@QuarkusTestResource(value = WolkvoxFalso.class, restrictToAnnotatedClass = false)
class CampanasEnVivoTest {

    @Test
    void rechazaTokenInvalidoConMensajeClaro() {
        given().contentType(ContentType.JSON).body(Map.of("nombre", "MALA", "servidor", "0003", "token", "OTRO"))
                .when().post("/api/operaciones").then().statusCode(401)
                .body("titulo", is("Token inválido o vencido")).body("sugerencia", containsString("Tokens"));
    }

    @Test
    void rechazaServidorQueNoEsNumero() {
        given().contentType(ContentType.JSON).body(Map.of("nombre", "X", "servidor", "abc", "token", "T"))
                .when().post("/api/operaciones").then().statusCode(400).body("titulo", is("Servidor no válido"));
    }

    @Test
    void cadaCampanaPrincipalUsaSuServidorYSuToken() {
        long a = FlujoCargueTest.crearOperacion("A " + System.nanoTime(), "0003", "TOKEN-0003");
        long b = FlujoCargueTest.crearOperacion("B " + System.nanoTime(), "0100", "TOKEN-0100");
        given().contentType(ContentType.JSON).body(Map.of("campanaId", "777")).when().post("/api/operaciones/" + b + "/campanas")
                .then().statusCode(200).body("nombre", is("COBRANZA_TEMPRANA")).body("tipo", is("PREDICTIVO"));
        // 777 no existe en el servidor de A
        given().contentType(ContentType.JSON).body(Map.of("campanaId", "777")).when().post("/api/operaciones/" + a + "/campanas")
                .then().statusCode(404).body("titulo", containsString("no existe en wv0003"));
        given().contentType(ContentType.JSON).body(Map.of("campanaId", "12a")).when().post("/api/operaciones/" + a + "/campanas")
                .then().statusCode(400).body("titulo", is("Número de campaña no válido"));
    }

    @Test
    void elEscaneoDetectaCambiosHechosEnWolkvoxManager() {
        long op = FlujoCargueTest.crearOperacion("ESCANEO " + System.nanoTime(), "0100", "TOKEN-0100");
        given().contentType(ContentType.JSON).body(Map.of("campanaId", "777")).when().post("/api/operaciones/" + op + "/campanas")
                .then().statusCode(200).body("activa", is(false));
        WolkvoxFalso.cambiarDesdeManager("0100", "777", true);
        given().when().post("/api/campanas/escanear").then().statusCode(200).body("cambios", greaterThanOrEqualTo(1));
        given().when().get("/api/campanas/historial?fecha=" + LocalDate.now()).then().statusCode(200)
                .body("findAll { it.operacionId == " + op + " }.origen", hasItem("ESCANEO"))
                .body("findAll { it.operacionId == " + op + " }.tipo", hasItem("INICIO"));
        given().when().get("/api/operaciones/").then().statusCode(200)
                .body("find { it.id == " + op + " }.campanas[0].activa", is(true));
        WolkvoxFalso.cambiarDesdeManager("0100", "777", false);
    }

    @Test
    void vistaPorHoraDeGestion() {
        given().when().get("/api/bitacora/hora?fecha=" + LocalDate.now() + "&hora=10").then().statusCode(200)
                .body("cargues", notNullValue()).body("cortes", notNullValue()).body("eventos", notNullValue());
        given().when().get("/api/bitacora/hora?hora=30").then().statusCode(400);
    }
}
