package com.bitacora;

import com.bitacora.infraestructura.wolkvox.WolkvoxHttpAdaptador;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Las columnas de la plantilla CSV se traducen a los campos de add_record de Wolkvox. */
@QuarkusTest
class WolkvoxHttpMapeoTest {

    @Inject WolkvoxHttpAdaptador http;

    @Test
    @SuppressWarnings("unchecked")
    void mapeaColumnasDeLaPlantilla() throws Exception {
        Map<String, String> fila = new LinkedHashMap<>();
        fila.put("Nombre", "Ana");
        fila.put("Apellido", "Pérez");
        fila.put("Tipo ID", "CC");
        fila.put("ID", "123");
        fila.put("Teléfono 1", "3001234567");
        fila.put("Tel2", "");
        fila.put("OPT3", "x");
        fila.put("Otro Tel", "6011234567");
        fila.put("Columna rara", "ignorar");
        Method m = WolkvoxHttpAdaptador.class.getDeclaredMethod("aRegistroApi", Map.class);
        m.setAccessible(true);
        Object proxyTarget = http;
        Map<String, String> r = (Map<String, String>) m.invoke(unwrap(proxyTarget), fila);
        assertEquals(Map.of("customer_name", "Ana", "customer_last_name", "Pérez", "id_type", "CC", "customer_id", "123",
                "tel1", "3001234567", "opt3", "x", "tel_extra", "6011234567"), r);
    }

    private static Object unwrap(Object o) {
        if (o instanceof io.quarkus.arc.ClientProxy p) return p.arc_contextualInstance();
        return o;
    }
}
