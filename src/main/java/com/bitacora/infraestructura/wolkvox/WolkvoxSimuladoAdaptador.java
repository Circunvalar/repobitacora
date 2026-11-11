package com.bitacora.infraestructura.wolkvox;

import com.bitacora.dominio.modelo.Campana;
import com.bitacora.dominio.modelo.MetricaHora;
import com.bitacora.dominio.modelo.ResultadoLote;
import com.bitacora.dominio.modelo.TipoMarcacion;
import com.bitacora.dominio.puerto.WolkvoxPuerto;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wolkvox simulado para probar el flujo completo sin token. Los números son inventados
 * pero estables (dependen de la campaña y la hora), para que las pantallas se vean reales.
 */
@ApplicationScoped
public class WolkvoxSimuladoAdaptador implements WolkvoxPuerto {

    private static final Logger LOG = Logger.getLogger(WolkvoxSimuladoAdaptador.class);

    private final List<Campana> campanas = List.of(
            new Campana("15315", "JCAP_PDS_2", TipoMarcacion.PREDICTIVO),
            new Campana("23592", "PREVIEW_INBOUND", TipoMarcacion.PREVIEW),
            new Campana("30830", "REINTEGRA_INCUMPL_ASIGNA_BOLSA", TipoMarcacion.PREVIEW),
            new Campana("30831", "REINTEGRA_RECORDACION (ACUERDO)", TipoMarcacion.PREVIEW),
            new Campana("30832", "REINTEGRA_VOLVER_A_LLAMAR", TipoMarcacion.PREVIEW),
            new Campana("30849", "REINTEGRA_INCUMPLIDOS", TipoMarcacion.PREVIEW));

    private final Map<String, Boolean> activas = new ConcurrentHashMap<>();

    @Override
    public List<Campana> campanas() {
        return campanas;
    }

    @Override
    public ResultadoLote cargarRegistros(Campana campana, List<Map<String, String>> lote) {
        // Simula que Wolkvox rechaza ~1 % (por ejemplo, teléfonos en lista de no llamar).
        int rechazados = lote.size() / 100;
        LOG.infof("[simulado] %d registros enviados a la campaña %s", lote.size(), campana.id());
        return new ResultadoLote(lote.size() - rechazados, rechazados, "simulado");
    }

    @Override
    public void iniciarCampana(Campana campana) {
        activas.put(campana.id(), true);
        LOG.infof("[simulado] campaña %s iniciada", campana.id());
    }

    @Override
    public void detenerCampana(Campana campana) {
        activas.put(campana.id(), false);
        LOG.infof("[simulado] campaña %s detenida", campana.id());
    }

    @Override
    public List<MetricaHora> metricasPorHora(Campana campana, LocalDateTime desde, LocalDateTime hasta) {
        List<MetricaHora> r = new ArrayList<>();
        for (LocalDateTime h = desde; h.isBefore(hasta); h = h.plusHours(1)) {
            Random rnd = new Random(Objects.hash(campana.id(), h));
            int agentes = 3 + rnd.nextInt(12);
            int barridos = agentes * (campana.tipo() == TipoMarcacion.PREDICTIVO ? 40 : 15) + rnd.nextInt(30);
            r.add(new MetricaHora(campana.id(), h, agentes, barridos, (int) (barridos * (0.4 + rnd.nextDouble() * 0.3))));
        }
        return r;
    }

    @Override
    public int gestionadosDeLista(Campana campana, String nombreLista, LocalDateTime desde, LocalDateTime hasta) {
        return metricasPorHora(campana, desde.withMinute(0).withSecond(0).withNano(0), hasta).stream()
                .mapToInt(MetricaHora::gestionados).sum();
    }
}
