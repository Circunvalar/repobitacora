package com.bitacora.infraestructura.persistencia;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ajuste")
public class AjusteEntidad {
    @Id
    public String clave;
    public String valor;
}
