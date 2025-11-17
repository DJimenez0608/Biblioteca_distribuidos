package org.example;

import org.zeromq.ZMQ;

public class GC {

    /**
     * Cambia las direcciones con -Dgc.ps=..., -Dgc.pub=..., -Dgc.prestamo=...
     * cuando debas conectar a un GA/actor remoto (por ejemplo desde otra sede).
     */
    private static final String PUERTO_PS = System.getProperty("gc.ps", "tcp://*:5555");
    private static final String PUERTO_PUBLICADOR = System.getProperty("gc.pub", "tcp://*:5560");
    private static final String PUERTO_PRESTAMO = System.getProperty("gc.prestamo", "tcp://localhost:5556");
    private static final String SEDE = System.getProperty("sede", "SEDE1");
    private static final String DEFAULT_USUARIO = System.getProperty("gc.usuarioId", "1");

    private ZMQ.Context context;
    private ZMQ.Socket socketPS;
    private ZMQ.Socket publicador;
    private ZMQ.Socket actorPrestamo;

    public static void main(String[] args) throws InterruptedException {
        new GC().iniciar();
    }

    public void iniciar() throws InterruptedException {
        context = ZMQ.context(1);

        inicializarSockets();

        System.out.println(" GC escuchando solicitudes en " + PUERTO_PS + "...");

        while (!Thread.currentThread().isInterrupted()) {
            String solicitud = socketPS.recvStr();
            System.out.println(" Solicitud recibida: " + solicitud);

            String respuesta = procesarSolicitud(solicitud);
            socketPS.send(respuesta, 0);

            Thread.sleep(100);
        }

        cerrarSockets();
    }

    // inicializar sockets
    private void inicializarSockets() {
        socketPS = context.socket(ZMQ.REP);
        socketPS.bind(PUERTO_PS);

        publicador = context.socket(ZMQ.PUB);
        publicador.bind(PUERTO_PUBLICADOR);

        actorPrestamo = context.socket(ZMQ.REQ);
        actorPrestamo.connect(PUERTO_PRESTAMO);
    }

    // Procesamiento de solicitudes
    private String procesarSolicitud(String solicitud) {
        if (solicitud == null || solicitud.isEmpty()) {
            return "Solicitud vacía o nula";
        }

        String[] partes = solicitud.split(":");
        String tipo = partes[0].toUpperCase();
        String codigo = partes.length > 1 ? partes[1] : "";
        String usuarioId = partes.length > 2 ? partes[2] : DEFAULT_USUARIO;

        return switch (tipo) {
            case "DEVOLVER" -> manejarDevolucion(codigo);
            case "RENOVAR" -> manejarRenovacion(codigo);
            case "PRESTAMO" -> manejarPrestamo(codigo, usuarioId);
            default -> {
                System.out.println("️ Solicitud no reconocida: " + solicitud);
                yield "Solicitud no reconocida";
            }
        };
    }



    //  Devolución
    private String manejarDevolucion(String codigo) {
        System.out.println(" Procesando devolución...");
        String payload = String.format("DEVOLVER %s %s", codigo, SEDE);
        publicador.send("DEVOLUCION " + payload);
        System.out.println(" Publicado en canal DEVOLUCION: " + payload);
        return "Devolución aceptada y enviada al actor para actualizar la BD.";
    }

    //  Renovación
    private String manejarRenovacion(String codigo) {
        System.out.println(" Procesando renovación...");
        String payload = String.format("RENOVAR %s %s", codigo, SEDE);
        publicador.send("RENOVACION " + payload);
        System.out.println(" Publicado en canal RENOVACION: " + payload);
        return "Renovación aceptada; la nueva fecha se confirmará cuando GA actualice la BD.";
    }

    //  Préstamo
    private String manejarPrestamo(String codigo, String usuarioId) {
        System.out.println(" Procesando préstamo...");
        String payload = String.format("PRESTAMO:%s:%s:%s", codigo, SEDE, usuarioId);
        actorPrestamo.send(payload, 0);
        String respuestaPrestamo = actorPrestamo.recvStr();
        System.out.println(" Respuesta del actor de préstamo: " + respuestaPrestamo);
        return respuestaPrestamo;
    }

    //Cierre de socket
    private void cerrarSockets() {
        socketPS.close();
        publicador.close();
        actorPrestamo.close();
        context.term();
        System.out.println(" Sockets cerrados correctamente.");
    }
}
