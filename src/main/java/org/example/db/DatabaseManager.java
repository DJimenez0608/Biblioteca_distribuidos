package org.example.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class DatabaseManager {

    static {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("No se encontró el driver de PostgreSQL", e);
        }
    }

    private final DatabaseEndpoint primaryEndpoint;
    private final DatabaseEndpoint secondaryEndpoint;
    private volatile DatabaseEndpoint activeEndpoint;
    private volatile DatabaseEndpoint replicaEndpoint;
    private final AtomicBoolean degradedMode = new AtomicBoolean(false);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long pollIntervalMs;
    private final int replicationBatchSize;

    public DatabaseManager() {
        DatabaseConfig config = new DatabaseConfig();
        this.primaryEndpoint = config.getPrimaryEndpoint();
        this.secondaryEndpoint = config.getSecondaryEndpoint();
        this.activeEndpoint = primaryEndpoint;
        this.replicaEndpoint = secondaryEndpoint;
        this.pollIntervalMs = config.getReplicationPollInterval();
        this.replicationBatchSize = config.getReplicationBatchSize();
    }

    public OperationResult procesarPrestamo(String codigoLibro, String sede, int usuarioId) {
        return executeWithFailover(conn -> realizarPrestamo(conn, codigoLibro, sede, usuarioId, true));
    }

    public OperationResult registrarDevolucion(String codigoLibro, String sede) {
        return executeWithFailover(conn -> realizarDevolucion(conn, codigoLibro, sede, true));
    }

    public OperationResult registrarRenovacion(String codigoLibro, String sede) {
        return executeWithFailover(conn -> realizarRenovacion(conn, codigoLibro, sede, true));
    }

    public OperationResult consultarDisponibilidad(String codigoLibro) {
        return executeWithFailover(conn -> verificarDisponibilidad(conn, codigoLibro));
    }

    public boolean estaEnModoDegradado() {
        return degradedMode.get();
    }

    public long getReplicationPollIntervalMs() {
        return pollIntervalMs;
    }

    public int getReplicationBatchSize() {
        return replicationBatchSize;
    }

    public String obtenerEstadoReplica() {
        String sql = "SELECT COUNT(*) AS pendientes, " +
                "COALESCE(EXTRACT(EPOCH FROM (NOW() - MIN(created_at))), 0) AS atraso " +
                "FROM replication_queue WHERE estado = 'PENDIENTE'";
        long pendientes = 0;
        long atraso = 0;
        try (Connection conn = abrirConexion(activeEndpoint);
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                pendientes = rs.getLong("pendientes");
                atraso = Math.round(rs.getDouble("atraso"));
            }
        } catch (SQLException e) {
            return "Estado de réplica no disponible: " + e.getMessage();
        }
        return String.format("Activo=%s, Réplica=%s, Pendientes=%d, Lag=%ds, Degradado=%s",
                activeEndpoint.name(),
                replicaEndpoint.name(),
                pendientes,
                atraso,
                degradedMode.get());
    }

    public int procesarColaReplicacion() {
        String sql = "SELECT id, operation_type, libro_codigo, payload, target_db FROM replication_queue " +
                "WHERE estado = 'PENDIENTE' ORDER BY id LIMIT ?";

        try (Connection conn = abrirConexion(activeEndpoint);
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, replicationBatchSize);
            ResultSet rs = stmt.executeQuery();
            List<ReplicationEvent> eventos = new ArrayList<>();
            while (rs.next()) {
                eventos.add(ReplicationEvent.fromResultSet(rs, objectMapper));
            }

            int procesados = 0;
            for (ReplicationEvent evento : eventos) {
                if (aplicarEventoEnReplica(evento)) {
                    marcarEventoAplicado(conn, evento.id());
                    procesados++;
                } else {
                    break;
                }
            }
            if (procesados > 0) {
                System.out.println("♻️  Replicación aplicada: " + procesados + " eventos.");
            }
            return procesados;
        } catch (SQLException e) {
            System.err.println(" Error al leer cola de replicación: " + e.getMessage());
            return 0;
        } finally {
            intentarRestaurarPrimario();
        }
    }

    private boolean aplicarEventoEnReplica(ReplicationEvent evento) {
        DatabaseEndpoint destino = resolverEndpointPorNombre(evento.targetDb());
        if (destino == null) {
            return true;
        }

        try (Connection conn = abrirConexion(destino)) {
            conn.setAutoCommit(false);
            JsonNode payload = evento.payload();
            String codigo = payload.path("codigo").asText();
            String sede = payload.path("sede").asText("SEDE1");
            int usuarioId = payload.path("usuarioId").asInt(1);

            OperationResult resultado = switch (evento.operationType()) {
                case "PRESTAMO" -> realizarPrestamo(conn, codigo, sede, usuarioId, false);
                case "DEVOLVER" -> realizarDevolucion(conn, codigo, sede, false);
                case "RENOVAR" -> realizarRenovacion(conn, codigo, sede, false);
                default -> OperationResult.error("Operación no soportada en réplica: " + evento.operationType());
            };

            if (!resultado.success()) {
                conn.rollback();
                System.err.println(" No se pudo replicar evento " + evento.id() + ": " + resultado.message());
                return false;
            }

            conn.commit();
            return true;
        } catch (SQLException e) {
            System.err.println(" Error al replicar evento " + evento.id() + ": " + e.getMessage());
            return false;
        }
    }

    private void marcarEventoAplicado(Connection conn, long eventoId) throws SQLException {
        PreparedStatement stmt = conn.prepareStatement(
                "UPDATE replication_queue SET estado = 'APLICADO', applied_at = NOW() WHERE id = ?");
        stmt.setLong(1, eventoId);
        stmt.executeUpdate();
    }

    private DatabaseEndpoint resolverEndpointPorNombre(String nombre) {
        if (nombre == null) return null;
        if (primaryEndpoint.name().equalsIgnoreCase(nombre)) {
            return primaryEndpoint;
        }
        if (secondaryEndpoint.name().equalsIgnoreCase(nombre)) {
            return secondaryEndpoint;
        }
        return null;
    }

    private void intentarRestaurarPrimario() {
        if (!degradedMode.get() || replicaEndpoint != primaryEndpoint) {
            return;
        }
        if (!colaSinPendientesPara(primaryEndpoint.name())) {
            return;
        }
        try (Connection conn = abrirConexion(primaryEndpoint)) {
            conn.setAutoCommit(false);
            actualizarMetaEn(conn, primaryEndpoint.name(), secondaryEndpoint.name(), false);
            conn.commit();
            this.activeEndpoint = primaryEndpoint;
            this.replicaEndpoint = secondaryEndpoint;
            degradedMode.set(false);
            System.out.println("✅ Primario sincronizado. Se restablece el flujo principal.");
        } catch (SQLException e) {
            System.err.println(" No se pudo reconectar al primario: " + e.getMessage());
        }
    }

    private boolean colaSinPendientesPara(String targetDb) {
        String sql = "SELECT COUNT(*) AS pendientes FROM replication_queue WHERE estado = 'PENDIENTE' AND target_db = ?";
        try (Connection conn = abrirConexion(activeEndpoint);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, targetDb);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getLong("pendientes") == 0;
            }
        } catch (SQLException e) {
            System.err.println(" No se pudo verificar la cola de réplica: " + e.getMessage());
        }
        return false;
    }

    private OperationResult executeWithFailover(SqlOperation operation) {
        DatabaseEndpoint endpoint = activeEndpoint;
        try (Connection conn = abrirConexion(endpoint)) {
            conn.setAutoCommit(false);
            OperationResult result = operation.run(conn);
            conn.commit();
            return result;
        } catch (SQLException primaryEx) {
            boolean failover = intentarFailover(endpoint, primaryEx);
            if (!failover) {
                throw new RuntimeException("Error al ejecutar operación", primaryEx);
            }
            try (Connection conn = abrirConexion(activeEndpoint)) {
                conn.setAutoCommit(false);
                OperationResult result = operation.run(conn);
                conn.commit();
                return result;
            } catch (SQLException secondaryEx) {
                throw new RuntimeException("Error tras failover", secondaryEx);
            }
        }
    }

    private boolean intentarFailover(DatabaseEndpoint endpoint, Exception cause) {
        if (endpoint != activeEndpoint) {
            return false;
        }

        System.err.println("⚠️  Error con " + endpoint + ": " + cause.getMessage());
        System.err.println("   Intentando conmutar al respaldo...");
        DatabaseEndpoint nuevoActivo = endpoint == primaryEndpoint ? secondaryEndpoint : primaryEndpoint;
        DatabaseEndpoint nuevoReplica = endpoint == primaryEndpoint ? primaryEndpoint : secondaryEndpoint;

        try (Connection connNuevo = abrirConexion(nuevoActivo)) {
            boolean modoDegradado = (nuevoActivo == secondaryEndpoint);
            degradedMode.set(modoDegradado);
            this.activeEndpoint = nuevoActivo;
            this.replicaEndpoint = nuevoReplica;
            actualizarMetaEn(connNuevo, nuevoActivo.name(), nuevoReplica.name(), modoDegradado);
            System.err.println("   Failover exitoso. Nuevo activo: " + nuevoActivo);
            return true;
        } catch (SQLException e) {
            System.err.println("   No se pudo abrir conexión al respaldo: " + e.getMessage());
            return false;
        }
    }

    private OperationResult realizarPrestamo(Connection conn, String codigoLibro, String sede, int usuarioId, boolean registrarReplica) throws SQLException {
        PreparedStatement libroStmt = conn.prepareStatement(
                "SELECT id, ejemplares_disponibles FROM libros WHERE codigo = ? FOR UPDATE");
        libroStmt.setString(1, codigoLibro);

        ResultSet rsLibro = libroStmt.executeQuery();
        if (!rsLibro.next()) {
            return OperationResult.error("El libro " + codigoLibro + " no existe.");
        }

        int libroId = rsLibro.getInt("id");
        int disponibles = rsLibro.getInt("ejemplares_disponibles");
        if (disponibles <= 0) {
            return OperationResult.error("Préstamo rechazado: no hay ejemplares disponibles.");
        }

        PreparedStatement updateLibro = conn.prepareStatement(
                "UPDATE libros SET ejemplares_disponibles = ejemplares_disponibles - 1, actualizado_en = NOW(), sede = ? WHERE id = ?");
        updateLibro.setString(1, sede);
        updateLibro.setInt(2, libroId);
        updateLibro.executeUpdate();

        PreparedStatement insertPrestamo = conn.prepareStatement(
                "INSERT INTO prestamos (libro_id, usuario_id, sede, fecha_inicio, fecha_fin, estado) VALUES (?,?,?,?,?, 'ABIERTO')",
                Statement.RETURN_GENERATED_KEYS);
        LocalDate inicio = LocalDate.now();
        LocalDate fin = inicio.plusWeeks(2);
        insertPrestamo.setInt(1, libroId);
        insertPrestamo.setInt(2, usuarioId);
        insertPrestamo.setString(3, sede);
        insertPrestamo.setDate(4, Date.valueOf(inicio));
        insertPrestamo.setDate(5, Date.valueOf(fin));
        insertPrestamo.executeUpdate();

        if (registrarReplica) {
            ResultSet keys = insertPrestamo.getGeneratedKeys();
            long prestamoId = keys.next() ? keys.getLong(1) : -1;
            Map<String, Object> payload = new HashMap<>();
            payload.put("sede", sede);
            payload.put("usuarioId", usuarioId);
            payload.put("prestamoId", prestamoId);
            payload.put("fechaFin", fin.toString());
            registrarEventoReplica(conn, "PRESTAMO", codigoLibro, payload);
        }

        return OperationResult.ok("Préstamo confirmado. Fecha de entrega: " + fin);
    }

    private OperationResult realizarDevolucion(Connection conn, String codigoLibro, String sede, boolean registrarReplica) throws SQLException {
        PreparedStatement prestamoStmt = conn.prepareStatement(
                "SELECT p.id, p.libro_id FROM prestamos p INNER JOIN libros l ON l.id = p.libro_id " +
                        "WHERE l.codigo = ? AND p.estado = 'ABIERTO' ORDER BY p.fecha_inicio LIMIT 1 FOR UPDATE");
        prestamoStmt.setString(1, codigoLibro);

        ResultSet rsPrestamo = prestamoStmt.executeQuery();
        if (!rsPrestamo.next()) {
            return OperationResult.error("No hay préstamos abiertos para el libro " + codigoLibro);
        }

        int prestamoId = rsPrestamo.getInt("id");
        int libroId = rsPrestamo.getInt("libro_id");

        PreparedStatement cerrarPrestamo = conn.prepareStatement(
                "UPDATE prestamos SET estado = 'CERRADO', fecha_fin = CURRENT_DATE WHERE id = ?");
        cerrarPrestamo.setInt(1, prestamoId);
        cerrarPrestamo.executeUpdate();

        PreparedStatement actualizarLibro = conn.prepareStatement(
                "UPDATE libros SET ejemplares_disponibles = ejemplares_disponibles + 1, actualizado_en = NOW(), sede = ? WHERE id = ?");
        actualizarLibro.setString(1, sede);
        actualizarLibro.setInt(2, libroId);
        actualizarLibro.executeUpdate();

        if (registrarReplica) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("sede", sede);
            payload.put("prestamoId", prestamoId);
            registrarEventoReplica(conn, "DEVOLVER", codigoLibro, payload);
        }

        return OperationResult.ok("Devolución registrada correctamente.");
    }

    private OperationResult realizarRenovacion(Connection conn, String codigoLibro, String sede, boolean registrarReplica) throws SQLException {
        PreparedStatement prestamoStmt = conn.prepareStatement(
                "SELECT p.id, p.renovaciones, l.renovaciones_permitidas FROM prestamos p " +
                        "INNER JOIN libros l ON l.id = p.libro_id " +
                        "WHERE l.codigo = ? AND p.estado = 'ABIERTO' ORDER BY p.fecha_inicio LIMIT 1 FOR UPDATE");
        prestamoStmt.setString(1, codigoLibro);

        ResultSet rsPrestamo = prestamoStmt.executeQuery();
        if (!rsPrestamo.next()) {
            return OperationResult.error("No existe un préstamo vigente para el libro " + codigoLibro);
        }

        int prestamoId = rsPrestamo.getInt("id");
        int renovacionesActuales = rsPrestamo.getInt("renovaciones");
        int renovacionesPermitidas = rsPrestamo.getInt("renovaciones_permitidas");

        if (renovacionesActuales >= renovacionesPermitidas) {
            return OperationResult.error("Renovación rechazada: se alcanzó el máximo permitido.");
        }

        PreparedStatement renovar = conn.prepareStatement(
                "UPDATE prestamos SET renovaciones = renovaciones + 1, fecha_fin = fecha_fin + INTERVAL '7 days' WHERE id = ?");
        renovar.setInt(1, prestamoId);
        renovar.executeUpdate();

        PreparedStatement actualizarLibro = conn.prepareStatement(
                "UPDATE libros SET actualizado_en = NOW(), sede = ? WHERE id = (SELECT libro_id FROM prestamos WHERE id = ?)");
        actualizarLibro.setString(1, sede);
        actualizarLibro.setInt(2, prestamoId);
        actualizarLibro.executeUpdate();

        if (registrarReplica) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("sede", sede);
            payload.put("prestamoId", prestamoId);
            registrarEventoReplica(conn, "RENOVAR", codigoLibro, payload);
        }

        return OperationResult.ok("Renovación aceptada. Nueva fecha: una semana adicional.");
    }

    private OperationResult verificarDisponibilidad(Connection conn, String codigoLibro) throws SQLException {
        PreparedStatement stmt = conn.prepareStatement(
                "SELECT ejemplares_disponibles FROM libros WHERE codigo = ?");
        stmt.setString(1, codigoLibro);

        ResultSet rs = stmt.executeQuery();
        if (!rs.next()) {
            return OperationResult.error("El libro solicitado no existe.");
        }

        int disponibles = rs.getInt("ejemplares_disponibles");
        if (disponibles > 0) {
            return OperationResult.ok("SI");
        }
        return OperationResult.error("NO");
    }

    private void actualizarMetaEn(Connection conn, String activeName, String standbyName, boolean degradado) throws SQLException {
        String sql = "INSERT INTO replication_meta (id, active_db, standby_db, degraded_mode, last_failover) VALUES (1, ?, ?, ?, ?) " +
                "ON CONFLICT (id) DO UPDATE SET active_db = EXCLUDED.active_db, standby_db = EXCLUDED.standby_db, " +
                "degraded_mode = EXCLUDED.degraded_mode, " +
                "last_failover = CASE WHEN EXCLUDED.last_failover IS NULL THEN replication_meta.last_failover ELSE EXCLUDED.last_failover END";
        PreparedStatement stmt = conn.prepareStatement(sql);
        stmt.setString(1, activeName);
        stmt.setString(2, standbyName);
        stmt.setBoolean(3, degradado);
        if (degradado) {
            stmt.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
        } else {
            stmt.setNull(4, Types.TIMESTAMP);
        }
        stmt.executeUpdate();
    }

    private void registrarEventoReplica(Connection conn, String tipo, String codigoLibro, Map<String, Object> extras) throws SQLException {
        if (replicaEndpoint == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>(extras);
        payload.put("codigo", codigoLibro);
        payload.put("sede", payload.getOrDefault("sede", "SEDE1"));

        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new SQLException("No se pudo serializar el payload del evento", e);
        }

        PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO replication_queue (operation_type, libro_codigo, payload, target_db) VALUES (?,?,?,?)");
        stmt.setString(1, tipo);
        stmt.setString(2, codigoLibro);
        stmt.setObject(3, jsonPayload, Types.OTHER);
        stmt.setString(4, replicaEndpoint.name());
        stmt.executeUpdate();
    }

    private Connection abrirConexion(DatabaseEndpoint endpoint) throws SQLException {
        return DriverManager.getConnection(endpoint.url(), endpoint.user(), endpoint.password());
    }

    @FunctionalInterface
    private interface SqlOperation {
        OperationResult run(Connection connection) throws SQLException;
    }
}

