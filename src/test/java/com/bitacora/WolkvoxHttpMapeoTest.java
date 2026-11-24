package com.bitacora;

import com.bitacora.dominio.modelo.TipoMarcacion;
import com.bitacora.infraestructura.wolkvox.WolkvoxHttpAdaptador;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Las columnas de la plantilla CSV se traducen a los campos de add_record de Wolkvox. */
@QuarkusTest
@QuarkusTestResource(value = WolkvoxFalso.class, restrictToAnnotatedClass = false)
class WolkvoxHttpMapeoTest {

    @Inject WolkvoxHttpAdaptador http;

    @Test
    void mapeaColumnasDeLaPlantilla() {
        Map<String, String> fila = new LinkedHashMap<>();
        fila.put("Nombre", "Ana");
        fila.put("Apellido", "Pérez");
        fila.put("Tipo ID", "CC");
        fila.put("ID", "123");
        fila.put("Teléfono 1", "3001234567");
        fila.put("Tel2", "");
        fila.put("OPT3", "x");
        fila.put("Otro Tel", "6011234567");
        fila.put("ID Agente", "55");
        fila.put("Columna rara", "ignorar");
        assertEquals(Map.of("customer_name", "Ana", "customer_last_name", "Pérez", "id_type", "CC", "customer_id", "123",
                "tel1", "3001234567", "opt3", "x", "tel_extra", "6011234567"), http.aRegistroApi(fila, TipoMarcacion.PREDICTIVO));
        assertEquals(Map.of("customer_name", "Ana", "customer_last_name", "Pérez", "id_type", "CC", "customer_id", "123",
                "tel1", "3001234567", "opt3", "x", "agent_id", "55"), http.aRegistroApi(fila, TipoMarcacion.PREVIEW));
    }
}
