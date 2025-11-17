package org.example;

import org.example.db.DatabaseManager;
import org.example.db.OperationResult;
import org.example.db.ReplicationWorker;
import org.zeromq.ZMQ;

public class GA {

    private static final int PUERTO = 5557;
    private static final String DEFAULT_SEDE = System.getProperty("sede", "SEDE1");
    private static final int DEFAULT_USUARIO = Integer.getInteger("ga.usuarioId", 1);

    private final DatabaseManager databaseManager = new DatabaseManager();
    private final ReplicationWorker replicationWorker = new ReplicationWorker(databaseManager);
    private Thread replicationThread;

    public static void main(String[] args) {
        new GA().iniciar();
    }

    public void iniciar() {
        replicationThread = new Thread(replicationWorker, "ga-replication-worker");
        replicationThread.setDaemon(true);
        replicationThread.start();

        ZMQ.Context context = ZMQ.context(1);
        ZMQ.Socket responder = context.socket(ZMQ.REP);
        responder.bind("tcp://*:" + PUERTO);

        System.out.println(" GA escuchando en puerto " + PUERTO + "...");

        while (!Thread.currentThread().isInterrupted()) {
            String solicitud = responder.recvStr();

            String respuesta = procesarSolicitud(solicitud);
            responder.send(respuesta, 0);
        }

        responder.close();
        context.term();
        replicationWorker.stop();
        if (replicationThread != null) {
            replicationThread.interrupt();
        }
    }

    private String procesarSolicitud(String solicitud) {
        if (solicitud == null || solicitud.isEmpty()) {
            return "Solicitud vacía o nula";
        }

        String[] partes = solicitud.trim().split("\\s+");
        String comando = partes[0].toUpperCase();

        return switch (comando) {
            case "PRESTAMO" -> manejarPrestamo(partes);
            case "DEVOLVER" -> manejarDevolucion(partes);
            case "RENOVAR" -> manejarRenovacion(partes);
            case "DISPONIBILIDAD?", "DISPONIBILIDAD" -> manejarDisponibilidad(partes);
            case "STATUS" -> databaseManager.obtenerEstadoReplica();
            default -> {
                System.out.println("GA: Solicitud desconocida -> " + solicitud);
                yield "Solicitud no reconocida";
            }
        };
    }

    private String manejarPrestamo(String[] partes) {
        if (partes.length < 3) {
            return "Formato inválido para PRESTAMO <codigo> <sede> [usuarioId]";
        }
        String codigo = partes[1];
        String sede = partes.length >= 3 ? partes[2] : DEFAULT_SEDE;
        int usuarioId = partes.length >= 4 ? parseEntero(partes[3], DEFAULT_USUARIO) : DEFAULT_USUARIO;

        OperationResult resultado = databaseManager.procesarPrestamo(codigo, sede, usuarioId);
        return resultado.message();
    }

    private String manejarDevolucion(String[] partes) {
        if (partes.length < 3) {
            return "Formato inválido para DEVOLVER <codigo> <sede>";
        }
        String codigo = partes[1];
        String sede = partes.length >= 3 ? partes[2] : DEFAULT_SEDE;
        OperationResult resultado = databaseManager.registrarDevolucion(codigo, sede);
        return resultado.message();
    }

    private String manejarRenovacion(String[] partes) {
        if (partes.length < 3) {
            return "Formato inválido para RENOVAR <codigo> <sede>";
        }
        String codigo = partes[1];
        String sede = partes.length >= 3 ? partes[2] : DEFAULT_SEDE;
        OperationResult resultado = databaseManager.registrarRenovacion(codigo, sede);
        return resultado.message();
    }

    private String manejarDisponibilidad(String[] partes) {
        if (partes.length < 2) {
            return "Formato inválido para DISPONIBILIDAD <codigo>";
        }
        String codigo = partes[1];
        OperationResult resultado = databaseManager.consultarDisponibilidad(codigo);
        return resultado.success() ? "SI" : "NO";
    }

    private int parseEntero(String valor, int porDefecto) {
        try {
            return Integer.parseInt(valor);
        } catch (NumberFormatException e) {
            return porDefecto;
        }
    }
}
