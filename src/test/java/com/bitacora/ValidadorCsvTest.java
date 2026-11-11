package com.bitacora;

import com.bitacora.aplicacion.ValidadorCsv;
import com.bitacora.dominio.modelo.CsvLeido;
import com.bitacora.dominio.modelo.ResultadoValidacion;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ValidadorCsvTest {

    private final ValidadorCsv validador = new ValidadorCsv(new ValidadorCsv.Reglas(
            Set.of("id", "identificacion"), Set.of("nombre"),
            Pattern.compile("^(tel|telefono)\\d{1,2}$|^(otrotel|telextra)$"), 7, 15, 100, Map.of("opt7", 200)));

    private static Map<String, String> fila(String id, String nombre, String tel1, String tel2) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ID", id); m.put("Nombre", nombre); m.put("Tel1", tel1); m.put("Tel2", tel2);
        return m;
    }

    @Test
    void separaValidosDeNoValidos() {
        CsvLeido csv = new CsvLeido(List.of("ID", "Nombre", "Tel1", "Tel2"), List.of(
                fila("1", "Ana", "300 123-4567", ""),
                fila("2", "Luis", "abc", "123"),
                fila("", "Sin id", "3001234567", ""),
                fila("1", "Duplicado", "3001234567", "")), ',');

        ResultadoValidacion r = validador.validar(csv);

        assertTrue(r.estructuraValida());
        assertEquals(1, r.validos().size());
        assertEquals("3001234567", r.validos().get(0).get("Tel1"), "limpia espacios y guiones");
        assertEquals(3, r.invalidos().size());
        assertEquals(3, r.invalidos().get(0).linea());
        assertTrue(r.invalidos().get(0).motivo().contains("sin teléfono válido"));
        assertTrue(r.invalidos().get(2).motivo().contains("duplicada"));
    }

    @Test
    void reconoceEncabezadosConTildesYMayusculas() {
        CsvLeido csv = new CsvLeido(List.of("Identificación", "NOMBRE", "Teléfono 1"),
                List.of(new LinkedHashMap<>(Map.of("Identificación", "9", "NOMBRE", "Eva", "Teléfono 1", "6041234567"))), ';');
        assertEquals(1, validador.validar(csv).validos().size());
    }

    @Test
    void reportaEstructuraIncompleta() {
        CsvLeido csv = new CsvLeido(List.of("Nombre", "Ciudad"), List.of(), ',');
        ResultadoValidacion r = validador.validar(csv);
        assertFalse(r.estructuraValida());
        assertEquals(2, r.erroresEstructura().size());
    }
}
