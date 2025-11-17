-- Script de creación y carga inicial para las bases de datos `library` y `librarybackup`.
-- Ejecútalo en cada base para garantizar que ambas comiencen con el mismo estado.

BEGIN;

CREATE TABLE IF NOT EXISTS usuarios (
    id SERIAL PRIMARY KEY,
    nombre VARCHAR(120) NOT NULL,
    tipo VARCHAR(20) NOT NULL DEFAULT 'ESTUDIANTE'
);

CREATE TABLE IF NOT EXISTS libros (
    id SERIAL PRIMARY KEY,
    codigo VARCHAR(20) UNIQUE NOT NULL,
    nombre VARCHAR(200) NOT NULL,
    autor VARCHAR(150) NOT NULL,
    sede VARCHAR(20) NOT NULL,
    ejemplares_totales INT NOT NULL CHECK (ejemplares_totales > 0),
    ejemplares_disponibles INT NOT NULL CHECK (ejemplares_disponibles >= 0),
    renovaciones_permitidas INT NOT NULL DEFAULT 2,
    actualizado_en TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS prestamos (
    id SERIAL PRIMARY KEY,
    libro_id INT NOT NULL REFERENCES libros (id),
    usuario_id INT NOT NULL REFERENCES usuarios (id),
    sede VARCHAR(20) NOT NULL,
    fecha_inicio DATE NOT NULL,
    fecha_fin DATE NOT NULL,
    renovaciones INT NOT NULL DEFAULT 0,
    estado VARCHAR(15) NOT NULL DEFAULT 'ABIERTO',
    CONSTRAINT prestamos_estado_chk CHECK (estado IN ('ABIERTO', 'CERRADO'))
);

CREATE TABLE IF NOT EXISTS replication_queue (
    id BIGSERIAL PRIMARY KEY,
    operation_type VARCHAR(20) NOT NULL,
    libro_codigo VARCHAR(20) NOT NULL,
    payload JSONB NOT NULL,
    target_db VARCHAR(40) NOT NULL,
    estado VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    applied_at TIMESTAMP NULL
);

CREATE TABLE IF NOT EXISTS replication_meta (
    id SMALLINT PRIMARY KEY DEFAULT 1,
    active_db VARCHAR(40) NOT NULL,
    standby_db VARCHAR(40) NOT NULL,
    degraded_mode BOOLEAN NOT NULL DEFAULT FALSE,
    last_failover TIMESTAMP NULL
);

INSERT INTO usuarios (nombre, tipo)
SELECT 'Usuario ' || gs, CASE WHEN gs % 5 = 0 THEN 'PROFESOR' ELSE 'ESTUDIANTE' END
FROM generate_series(1, 50) gs
ON CONFLICT (id) DO NOTHING;

WITH libro_data AS (
    SELECT
        gs,
        LPAD(gs::text, 5, '0') AS codigo,
        'Libro #' || gs AS nombre,
        'Autor #' || (gs % 120) AS autor,
        CASE WHEN gs % 2 = 0 THEN 'SEDE1' ELSE 'SEDE2' END AS sede,
        1 + (gs % 3) AS ejemplares_totales
    FROM generate_series(1, 1000) gs
)
INSERT INTO libros (codigo, nombre, autor, sede, ejemplares_totales, ejemplares_disponibles)
SELECT
    codigo,
    nombre,
    autor,
    sede,
    ejemplares_totales,
    CASE
        WHEN gs <= 200 THEN GREATEST(ejemplares_totales - 1, 0)
        ELSE ejemplares_totales
    END AS ejemplares_disponibles
FROM libro_data
ON CONFLICT (codigo) DO NOTHING;

WITH prestamos_seed AS (
    SELECT
        l.id AS libro_id,
        l.codigo,
        ((gs - 1) % 50) + 1 AS usuario_id,
        CASE WHEN gs <= 50 THEN 'SEDE1' ELSE 'SEDE2' END AS sede,
        CURRENT_DATE - INTERVAL '7 days' AS fecha_inicio,
        CURRENT_DATE + INTERVAL '7 days' AS fecha_fin
    FROM libros l
    JOIN generate_series(1, 200) gs
        ON l.codigo = LPAD(gs::text, 5, '0')
)
INSERT INTO prestamos (libro_id, usuario_id, sede, fecha_inicio, fecha_fin, renovaciones, estado)
SELECT libro_id, usuario_id, sede, fecha_inicio, fecha_fin, 0, 'ABIERTO'
FROM prestamos_seed
ON CONFLICT DO NOTHING;

INSERT INTO replication_meta (id, active_db, standby_db, degraded_mode)
VALUES (1, 'library', 'librarybackup', FALSE)
ON CONFLICT (id) DO UPDATE
    SET active_db = EXCLUDED.active_db,
        standby_db = EXCLUDED.standby_db,
        degraded_mode = EXCLUDED.degraded_mode;

COMMIT;