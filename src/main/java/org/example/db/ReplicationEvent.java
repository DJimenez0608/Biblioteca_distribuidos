package org.example.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;

public record ReplicationEvent(long id, String operationType, String libroCodigo, String targetDb, JsonNode payload) {

    public static ReplicationEvent fromResultSet(ResultSet rs, ObjectMapper mapper) throws SQLException {
        long id = rs.getLong("id");
        String tipo = rs.getString("operation_type");
        String codigo = rs.getString("libro_codigo");
        String target = rs.getString("target_db");
        String payloadText = rs.getString("payload");
        try {
            JsonNode payload = mapper.readTree(payloadText);
            return new ReplicationEvent(id, tipo, codigo, target, payload);
        } catch (IOException e) {
            throw new SQLException("No se pudo deserializar el payload: " + payloadText, e);
        }
    }
}

