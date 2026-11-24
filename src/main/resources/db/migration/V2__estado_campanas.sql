-- Última foto conocida de cada campaña en Wolkvox (se actualiza con el escaneo periódico)
CREATE TABLE campana_estado (
    campana_id      VARCHAR(50)  PRIMARY KEY,
    nombre          VARCHAR(200),
    tipo            VARCHAR(20),
    activa          BOOLEAN,
    registros       INT,
    desde           TIMESTAMP,
    ultima_revision TIMESTAMP    NOT NULL
);

ALTER TABLE evento_campana ADD COLUMN campana_nombre VARCHAR(200);
CREATE INDEX ix_evento_momento ON evento_campana (momento);
