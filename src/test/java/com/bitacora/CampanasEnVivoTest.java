package com.bitacora;

import com.bitacora.aplicacion.CampanaServicio;
import com.bitacora.infraestructura.wolkvox.WolkvoxSimuladoAdaptador;
import com.bitacora.dominio.modelo.Campana;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** Escaneo de campañas activas/inactivas, historial y vista por hora con Wolkvox simulado. */
@QuarkusTest
class CampanasEnVivoTest {

    @Inject CampanaServicio campanas;
    @Inject WolkvoxSimuladoAdaptador simulado;

    @Test
    void listaLasCampanasDelServidorConSuEstado() {
        given().when().get("/api/campanas?refrescar=true").then().statusCode(200)
                .body("id", hasItem("15315")).body("activa", everyItem(notNullValue()));
    }

    @Test
    void elEscaneoDetectaCambiosHechosPorFueraDeLaApp() {
        given().when().post("/api/campanas/escanear").then().statusCode(200).body("revisadas", greaterThan(0));
        // Alguien activa la campaña directamente en Wolkvox Manager
        simulado.iniciarCampana(new Campana("23592", "PREVIEW_INBOUND", com.bitacora.dominio.modelo.TipoMarcacion.PREVIEW));
        given().when().post("/api/campanas/escanear").then().statusCode(200).body("cambios", greaterThanOrEqualTo(1));
        given().when().get("/api/campanas/historial?fecha=" + LocalDate.now()).then().statusCode(200)
                .body("findAll { it.campanaId == '23592' }.origen", hasItem("ESCANEO"))
                .body("findAll { it.campanaId == '23592' }.tipo", hasItem("INICIO"));
        given().when().get("/api/campanas/estado").then().statusCode(200)
                .body("campanas.find { it.campanaId == '23592' }.activa", is(true))
                .body("ultimoEscaneo.revisadas", greaterThan(0));
        simulado.detenerCampana(new Campana("23592", "PREVIEW_INBOUND", com.bitacora.dominio.modelo.TipoMarcacion.PREVIEW));
        campanas.escanear();
    }

    @Test
    void vistaPorHoraDeGestion() {
        given().when().get("/api/bitacora/hora?fecha=" + LocalDate.now() + "&hora=10").then().statusCode(200)
                .body("cargues", notNullValue()).body("cortes", notNullValue()).body("eventos", notNullValue());
        given().when().get("/api/bitacora/hora?hora=30").then().statusCode(400);
    }
}
