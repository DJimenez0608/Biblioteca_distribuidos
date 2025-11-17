package org.example;

import org.zeromq.ZMQ;
import java.util.Objects;

public class AcotrPresamo {

    //  Conexion
    private static final String PUERTO_RECIBIR = System.getProperty("actor.prestamo.bind", "tcp://*:5556");      // Puerto donde GC se conecta
    /**
     * Si este actor corre en otra máquina, pásale -Dactor.prestamo.ga=tcp://IP_DEL_GA:5557
     */
    private static final String PUERTO_GA = System.getProperty("actor.prestamo.ga", "tcp://localhost:5557");

    private ZMQ.Context context;
    private ZMQ.Socket responder;  // Para recibir solicitudes desde GC
    private ZMQ.Socket socketGA;   // Para consultar disponibilidad con GA

    public static void main(String[] args) {
        new AcotrPresamo().iniciar();
    }

    public void iniciar() {
        context = ZMQ.context(1);
        inicializarSockets();

        System.out.println(" Actor de Préstamo activo en " + PUERTO_RECIBIR + "...");
        System.out.println(" Conectado a GA en " + PUERTO_GA);

        while (!Thread.currentThread().isInterrupted()) {
            procesarSolicitudes();
        }

        cerrarSockets();
    }

    //Sockets
    private void inicializarSockets() {
        responder = context.socket(ZMQ.REP);
        responder.bind(PUERTO_RECIBIR);

        socketGA = context.socket(ZMQ.REQ);
        socketGA.connect(PUERTO_GA);
    }

    // Solicitudes
    private void procesarSolicitudes() {
        String solicitud = responder.recvStr();
        System.out.println("\n Solicitud recibida del GC: " + solicitud);

        String[] partes = solicitud.split(":");
        if (partes.length < 2) {
            responder.send("Formato inválido en solicitud de préstamo.");
            return;
        }

        String codigo = partes[1];
        String sede = partes.length >= 3 ? partes[2] : "SEDE1";
        String usuarioId = partes.length >= 4 ? partes[3] : "1";

        String payload = String.format("PRESTAMO %s %s %s", codigo, sede, usuarioId);
        socketGA.send(payload);
        String respuestaGA = socketGA.recvStr();
        System.out.println(" Respuesta del GA: " + respuestaGA);

        responder.send(respuestaGA);
    }

    // Cierre de sockets
    private void cerrarSockets() {
        responder.close();
        socketGA.close();
        context.term();
        System.out.println("\n Actor de préstamo finalizado correctamente.");
    }
}
