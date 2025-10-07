package org.example;

import org.zeromq.ZMQ;
import java.util.Objects;

public class AcotrPresamo {

    //  Conexion
    private static final String PUERTO_RECIBIR = "tcp://*:5556";      // Puerto donde GC se conecta
    private static final String PUERTO_GA = "tcp://localhost:5557";   // Puerto del Gestor de Almacenamiento (GA)

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

        // Consultar disponibilidad con GA
        socketGA.send("Disponibilidad?");
        String respuestaGA = socketGA.recvStr();
        System.out.println(" Respuesta del GA: " + respuestaGA);

        String respuestaFinal = manejarRespuestaGA(respuestaGA);
        responder.send(respuestaFinal);
    }

    // Respuestas del GA
    private String manejarRespuestaGA(String respuestaGA) {
        if (Objects.equals(respuestaGA, "SI")) {
            System.out.println(" Libro disponible. Préstamo confirmado.");
            return "Préstamo confirmado";
        } else if (Objects.equals(respuestaGA, "NO")) {
            System.out.println(" Libro no disponible. Solicitud rechazada.");
            return "Préstamo rechazado (no disponible)";
        } else {
            System.out.println("️ Respuesta desconocida del GA: " + respuestaGA);
            return "Error: respuesta desconocida del GA";
        }
    }

    // Cierre de sockets
    private void cerrarSockets() {
        responder.close();
        socketGA.close();
        context.term();
        System.out.println("\n Actor de préstamo finalizado correctamente.");
    }
}
