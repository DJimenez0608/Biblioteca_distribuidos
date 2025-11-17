# Sistema de Préstamos Distribuido

Solución basada en ZeroMQ y PostgreSQL que gestiona préstamos, renovaciones y devoluciones con replicación asíncrona entre dos bases (`library` y `librarybackup`).

## 1. Preparar bases de datos

1. Crear las bases en PostgreSQL:
   ```sql
   CREATE DATABASE library;
   CREATE DATABASE librarybackup;
   ```
2. Ejecutar `src/tables.sql` en **cada** base. El script crea el esquema, inserta 1000 libros (200 ya prestados) y deja los metadatos de replicación sincronizados.

Credenciales por defecto utilizadas en `src/main/resources/database.properties`:

| Propiedad | Valor |
|-----------|-------|
| `db.primary.url` | `jdbc:postgresql://localhost:5432/library` |
| `db.secondary.url` | `jdbc:postgresql://localhost:5432/librarybackup` |
| `db.*.user/password` | `postgres` / `postgres` |

Modifica el archivo para apuntar a tus servidores o usuarios.

## 2. Ejecutar procesos

Cada sede debe lanzar su propio `GC` y actores; los PS pueden correr en otro equipo.

```bash
./gradlew build

# Gestor de Almacenamiento (GA) + réplica
java -Dsede=SEDE1 -jar build/libs/Distribuidos-1.0-SNAPSHOT.jar GA

# Gestor de Carga (ej. sede 1)
java -Dsede=SEDE1 -jar build/libs/Distribuidos-1.0-SNAPSHOT.jar GC

# Actor de préstamos
java -Dsede=SEDE1 -jar build/libs/Distribuidos-1.0-SNAPSHOT.jar AcotrPresamo

# Actor devol/renov
java -jar build/libs/Distribuidos-1.0-SNAPSHOT.jar DevolucionRenovacion

# Proceso solicitante (archivo PS.txt)
java -jar build/libs/Distribuidos-1.0-SNAPSHOT.jar PS
```

El parámetro `-Dsede` permite identificar la sede y se propaga hasta la base de datos.

## 3. Monitoreo y métricas

Enviar la palabra `STATUS` al GA (por ejemplo usando `zmq_req` o adaptando un PS) muestra:

```
Activo=library, Réplica=librarybackup, Pendientes=0, Lag=0s, Degradado=false
```

El GA también registra en consola:

- Fallos y conmutaciones (`⚠️`).
- Aplicaciones de la cola de réplica (`♻️ Replicación aplicada: X eventos.`).
- Restablecimiento del primario (`✅ Primario sincronizado...`).

## 4. Simular fallas y recuperación

1. **Falla del primario**: detén PostgreSQL en `library`. La primera operación que falle provocará un failover automático al respaldo (`librarybackup`). Las nuevas operaciones se registran en la cola apuntando al primario.
2. **Reactivación**: levanta nuevamente `library`. El `ReplicationWorker` aplicará los eventos pendientes; cuando la cola quede vacía, el primario se actualiza y el sistema vuelve al flujo original (verás el mensaje `✅ Primario sincronizado`).
3. **Verificación**:
   - Ejecuta `STATUS` antes y después de la recuperación.
   - Compara los registros en ambas bases: `SELECT COUNT(*) FROM prestamos WHERE estado='ABIERTO';`

## 5. Escenarios de rendimiento

Para medir las opciones del enunciado:

| Variable | Cómo medir |
|----------|------------|
| Tiempo promedio de préstamo | Registrar `LocalDateTime` antes/después en PS o usar herramientas como JMeter apuntando al socket `tcp://localhost:5555`. |
| Cantidad de solicitudes en 2 min | Ejecutar scripts PS concurrentes (4, 6 y 10 procesos por sede) y contar respuestas en consola/log. |

Repite las pruebas en los dos diseños requeridos (p. ej., modo actual asíncrono vs. versión sincronizada) y grafica según la tabla del enunciado.

## 6. Limpieza / reinicio

- Para reiniciar únicamente el GA, detén el proceso; al iniciar de nuevo leerá `replication_meta` y continuará con el estado actual.
- Si necesitas reiniciar las colas, puedes vaciar `replication_queue` **solo cuando ambas bases estén sincronizadas**:
  ```sql
  DELETE FROM replication_queue WHERE estado='APLICADO';
  ```

