package com.bitacora;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** Campañas con su propio servidor y token, escaneo e historial, y errores explicados. */
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
    void rechazaDatosMalEscritos() {
        given().contentType(ContentType.JSON).body(Map.of("nombre", "X", "servidor", "abc", "token", "T"))
                .when().post("/api/operaciones").then().statusCode(400).body("titulo", is("Servidor no válido"));
        long op = FlujoCargueTest.crearOperacion("Y " + System.nanoTime(), "0003", "TOKEN-0003");
        given().contentType(ContentType.JSON).body(Map.of("campanaId", "12a", "nombre", "X"))
                .when().post("/api/operaciones/" + op + "/campanas").then().statusCode(400).body("titulo", is("Número de campaña no válido"));
    }

    @Test
    void cadaCampanaUsaSuServidorYSuToken() {
        long b = FlujoCargueTest.guardarCampana("777", "0100", "TOKEN-0100");
        given().when().get("/api/operaciones").then().statusCode(200)
                .body("find { it.id == " + b + " }.campanas[0].nombre", is("MI CAMPAÑA 777"))
                .body("find { it.id == " + b + " }.campanas[0].tipo", is("PREDICTIVO"))
                .body("find { it.id == " + b + " }.servidor", is("0100"))
                .body("find { it.id == " + b + " }.token", not(containsString("TOKEN")));
        // 777 no existe en wv0003, aunque el token de ese servidor sea válido
        long a = FlujoCargueTest.crearOperacion("A " + System.nanoTime(), "0003", "TOKEN-0003");
        given().contentType(ContentType.JSON).body(Map.of("campanaId", "777", "nombre", "OTRA"))
                .when().post("/api/operaciones/" + a + "/campanas").then().statusCode(404).body("titulo", containsString("no existe en wv0003"));
    }

    @Test
    void elEscaneoDetectaCambiosHechosEnWolkvoxManager() {
        long id = FlujoCargueTest.guardarCampana("777", "0100", "TOKEN-0100");
        WolkvoxFalso.cambiarDesdeManager("0100", "777", true);
        given().when().post("/api/campanas/escanear").then().statusCode(200).body("cambios", greaterThanOrEqualTo(1));
        given().when().get("/api/campanas/historial?fecha=" + LocalDate.now()).then().statusCode(200)
                .body("findAll { it.operacionId == " + id + " }.origen", hasItem("ESCANEO"))
                .body("findAll { it.operacionId == " + id + " }.tipo", hasItem("INICIO"));
        given().when().get("/api/operaciones").then().statusCode(200)
                .body("find { it.id == " + id + " }.campanas[0].activa", is(true))
                .body("find { it.id == " + id + " }.campanas[0].nombre", is("MI CAMPAÑA 777"));
        WolkvoxFalso.cambiarDesdeManager("0100", "777", false);
        given().when().post("/api/campanas/escanear").then().statusCode(200);
    }

    @Test
    void vistaPorHoraDeGestion() {
        given().when().get("/api/bitacora/hora?fecha=" + LocalDate.now() + "&hora=10").then().statusCode(200)
                .body("cargues", notNullValue()).body("cortes", notNullValue()).body("eventos", notNullValue());
        given().when().get("/api/bitacora/hora?hora=30").then().statusCode(400);
    }
}
