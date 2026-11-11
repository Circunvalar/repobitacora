package com.bitacora;

import com.bitacora.dominio.modelo.NombreLista;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NombreListaTest {
    @Test
    void quitaFechaYExtension() {
        assertEquals("MASIVO_REINTEGRA_INCUMPLIDOS_WOLKVOX",
                NombreLista.base("MASIVO_REINTEGRA_INCUMPLIDOS_WOLKVOX_20261110.csv"));
        assertEquals(NombreLista.base("MASIVO_JCAP_Grupo1+High Value_WOLKVOX_20261111"),
                NombreLista.base("MASIVO_JCAP_Grupo1+High Value_WOLKVOX_20261112.csv"));
    }
}
