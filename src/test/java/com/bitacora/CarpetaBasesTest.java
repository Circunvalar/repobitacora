package com.bitacora;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** La carpeta de bases se elige desde la app y se leen bases .txt y .xlsx además de .csv. */
@QuarkusTest
@QuarkusTestResource(value = WolkvoxFalso.class, restrictToAnnotatedClass = false)
class CarpetaBasesTest {

    @Test
    void eligeLaCarpetaYLeeTxtYExcel() throws Exception {
        Path carpeta = Files.createDirectories(Path.of("target/prueba/otra-carpeta/bases"));
        Files.writeString(carpeta.resolve("PREDICTIVA_TXT_20261125.txt"), FlujoCargueTest.csv(FlujoCargueTest.PREDICTIVA, 3));
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(carpeta.resolve("PREVIEW_XLSX_20261125.xlsx"))) {
            var hoja = wb.createSheet("Base");
            String[] cols = FlujoCargueTest.PREVIEW.split("\t");
            var cab = hoja.createRow(0);
            for (int i = 0; i < cols.length; i++) cab.createCell(i).setCellValue(cols[i]);
            var fila = hoja.createRow(1);
            fila.createCell(0).setCellValue("Ana");
            fila.createCell(3).setCellValue(123456);
            fila.createCell(23).setCellValue(3001234567L);
            wb.write(out);
        }
        String anterior = given().when().get("/api/ajustes/carpeta-bases").then().statusCode(200).extract().path("ruta");
        try {
            given().when().get("/api/ajustes/explorar").then().statusCode(200).body("esRaiz", is(true)).body("carpetas", not(empty()));
            given().when().get("/api/ajustes/explorar?ruta=" + carpeta.getParent().toAbsolutePath()).then().statusCode(200)
                    .body("carpetas.nombre", hasItem("bases"));
            given().contentType(ContentType.JSON).body(Map.of("ruta", "Z:/no/existe/123")).when().put("/api/ajustes/carpeta-bases")
                    .then().statusCode(400).body("titulo", is("La carpeta no existe"));
            given().contentType(ContentType.JSON).body(Map.of("ruta", carpeta.toAbsolutePath().toString())).when().put("/api/ajustes/carpeta-bases")
                    .then().statusCode(200).body("ruta", endsWith("bases"));

            given().when().get("/api/archivos").then().statusCode(200)
                    .body("nombre", hasItems("PREDICTIVA_TXT_20261125.txt", "PREVIEW_XLSX_20261125.xlsx"));
            given().when().get("/api/archivos/PREDICTIVA_TXT_20261125.txt/revision").then().statusCode(200)
                    .body("columnas", is(36)).body("tipo", is("PREDICTIVO")).body("validos", is(3));
            given().when().get("/api/archivos/PREVIEW_XLSX_20261125.xlsx/revision").then().statusCode(200)
                    .body("columnas", is(35)).body("tipo", is("PREVIEW")).body("validos", is(1));
        } finally {
            given().contentType(ContentType.JSON).body(Map.of("ruta", Path.of(anterior).toAbsolutePath().toString()))
                    .when().put("/api/ajustes/carpeta-bases").then().statusCode(200);
        }
    }
}
