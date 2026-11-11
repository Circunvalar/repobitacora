package com.bitacora.infraestructura.persistencia;

import com.bitacora.dominio.modelo.EstadoCargue;
import com.bitacora.dominio.modelo.EstadoDescarga;
import com.bitacora.dominio.modelo.TipoEstrategia;
import com.bitacora.dominio.modelo.TipoMarcacion;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Una fila de la bitácora: un cargue de una lista a una campaña. */
@Entity
@Table(name = "cargue")
public class CargueEntidad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public LocalDate fecha;
    @Column(name = "hora_inicio_plan") public LocalTime horaInicioPlan;
    @Column(name = "hora_fin_plan") public LocalTime horaFinPlan;
    @Column(name = "inicio_campana") public LocalDateTime inicioCampana;
    @Column(name = "fin_campana") public LocalDateTime finCampana;

    @Column(name = "campana_id") public String campanaId;
    @Column(name = "campana_nombre") public String campanaNombre;
    @Enumerated(EnumType.STRING) @Column(name = "tipo_marcacion") public TipoMarcacion tipoMarcacion;

    @Column(name = "nombre_lista") public String nombreLista;
    @Column(name = "lista_base") public String listaBase;
    public String archivo;
    public String huella;

    @Enumerated(EnumType.STRING) public TipoEstrategia estrategia;
    public int remarcacion;
    public String jefe;
    @Column(name = "nombre_estrategia") public String nombreEstrategia;
    public String observacion;

    @Column(name = "registros_archivo") public int registrosArchivo;
    @Column(name = "registros_no_validos") public int registrosNoValidos;
    @Column(name = "registros_cargados") public int registrosCargados;
    @Column(name = "rechazados_wolkvox") public int rechazadosWolkvox;
    @Column(name = "total_gestionado") public Integer totalGestionado;
    @Column(name = "archivo_rechazados") public String archivoRechazados;

    @Enumerated(EnumType.STRING) @Column(name = "descarga_informe") public EstadoDescarga descargaInforme = EstadoDescarga.PDT;
    @Enumerated(EnumType.STRING) public EstadoCargue estado = EstadoCargue.PENDIENTE;
    @Column(name = "inicio_cargue") public LocalDateTime inicioCargue;
    @Column(name = "fin_cargue") public LocalDateTime finCargue;
    @Column(length = 1000) public String error;
    public LocalDateTime creado;

    /** % GESTIÓN = TOTAL GESTIONADO ÷ REGISTROS CARGADOS. */
    public Integer porcentajeGestion() {
        if (totalGestionado == null || registrosCargados <= 0) return null;
        return (int) Math.round(totalGestionado * 100.0 / registrosCargados);
    }
}
