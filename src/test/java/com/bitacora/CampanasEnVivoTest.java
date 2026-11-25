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
        given().contentType(ContentType.JSON).body(Map.of("campanaId", "15315", "servidor", "0003", "token", "OTRO"))
                .when().post("/api/mis-campanas").then().statusCode(401)
                .body("titulo", is("Token inválido o vencido")).body("sugerencia", containsString("Tokens"));
    }

    @Test
    void rechazaDatosMalEscritos() {
        given().contentType(ContentType.JSON).body(Map.of("campanaId", "15315", "servidor", "abc", "token", "T"))
                .when().post("/api/mis-campanas").then().statusCode(400).body("titulo", is("Servidor no válido"));
        given().contentType(ContentType.JSON).body(Map.of("campanaId", "12a", "servidor", "0003", "token", "TOKEN-0003"))
                .when().post("/api/mis-campanas").then().statusCode(400).body("titulo", is("Número de campaña no válido"));
    }

    @Test
    void cadaCampanaUsaSuServidorYSuToken() {
        FlujoCargueTest.guardarCampana("777", "0100", "TOKEN-0100");
        given().when().get("/api/mis-campanas").then().statusCode(200)
                .body("find { it.campanaId == '777' }.nombre", is("COBRANZA_TEMPRANA"))
                .body("find { it.campanaId == '777' }.tipo", is("PREDICTIVO"))
                .body("find { it.campanaId == '777' }.servidor", is("0100"))
                .body("find { it.campanaId == '777' }.token", not(containsString("TOKEN")));
        // 777 no existe en wv0003, aunque el token de ese servidor sea válido
        given().contentType(ContentType.JSON).body(Map.of("campanaId", "777", "servidor", "0003", "token", "TOKEN-0003"))
                .when().post("/api/mis-campanas").then().statusCode(404).body("titulo", containsString("no existe en wv0003"));
    }

    @Test
    void elEscaneoDetectaCambiosHechosEnWolkvoxManager() {
        long id = FlujoCargueTest.guardarCampana("777", "0100", "TOKEN-0100");
        WolkvoxFalso.cambiarDesdeManager("0100", "777", true);
        given().when().post("/api/campanas/escanear").then().statusCode(200).body("cambios", greaterThanOrEqualTo(1));
        given().when().get("/api/campanas/historial?fecha=" + LocalDate.now()).then().statusCode(200)
                .body("findAll { it.operacionId == " + id + " }.origen", hasItem("ESCANEO"))
                .body("findAll { it.operacionId == " + id + " }.tipo", hasItem("INICIO"));
        given().when().get("/api/mis-campanas").then().statusCode(200)
                .body("find { it.id == " + id + " }.activa", is(true));
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
