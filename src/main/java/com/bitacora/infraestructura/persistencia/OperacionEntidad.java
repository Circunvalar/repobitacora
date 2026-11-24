package com.bitacora.infraestructura.persistencia;

import com.bitacora.dominio.modelo.Conexion;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Campaña principal: un cliente u operación con su propio servidor de Wolkvox y su token. */
@Entity
@Table(name = "operacion")
public class OperacionEntidad {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String nombre;
    /** Número del servidor: los dígitos de wvXXXX.wolkvox.com. */
    public String servidor;
    public String token;
    public boolean archivada;
    @Column(name = "token_valido") public Boolean tokenValido;
    /** Campañas que el token ve en el servidor (en la última validación). */
    @Column(name = "campanas_servidor") public Integer campanasServidor;
    @Column(name = "ultima_validacion") public LocalDateTime ultimaValidacion;
    @Column(name = "ultimo_error", length = 1000) public String ultimoError;
    @Column(name = "ultimo_escaneo") public LocalDateTime ultimoEscaneo;
    public LocalDateTime creado;

    public Conexion conexion() {
        return new Conexion(servidor, token);
    }
}
