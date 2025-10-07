package org.example;

import org.zeromq.ZMQ;

public class DevolucionRenovacion {

    // Constantes de conexión
    private static final String PUERTO_SUB_GC = "tcp://localhost:5560";
    private static final String PUERTO_REQ_GA = "tcp://localhost:5557";

    private ZMQ.Context context;
    private ZMQ.Socket subscriber; // Suscriptor de GC
    private ZMQ.Socket socketGA;   // Comunicador con GA

    public static void main(String[] args) {
        new DevolucionRenovacion().iniciar();
    }


    public void iniciar() {
        context = ZMQ.context(1);
        inicializarSockets();

        System.out.println(" DevolucionRenovacion conectado a GC (" + PUERTO_SUB_GC + ") y GA (" + PUERTO_REQ_GA + ")");

        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

        while (!Thread.currentThread().isInterrupted()) {
            procesarMensajes();
        }

        cerrarSockets();
    }

    //Sockets
    private void inicializarSockets() {
        // SUB para recibir mensajes del GC
        subscriber = context.socket(ZMQ.SUB);
        subscriber.connect(PUERTO_SUB_GC);
        subscriber.subscribe("DEVOLUCION".getBytes());
        subscriber.subscribe("RENOVACION".getBytes());

        // REQ para enviar confirmación al GA
        socketGA = context.socket(ZMQ.REQ);
        socketGA.connect(PUERTO_REQ_GA);
    }

    // Solicitudes
    private void procesarMensajes() {
        String mensajeCompleto = subscriber.recvStr();
        System.out.println("\n Mensaje recibido del GC: " + mensajeCompleto);

        // Separar tópico del contenido
        String[] partes = mensajeCompleto.split(" ", 2);
        String topico = partes[0];
        String contenido = partes.length > 1 ? partes[1] : "";

        if (topico.equals("DEVOLUCION")) {
            manejarDevolucion(contenido);

        } else if (topico.equals("RENOVACION")) {
            manejarRenovacion(contenido);

        } else {
            System.out.println("⚠️ Tópico desconocido: " + topico);
        }
    }

    // Manejo de devoluciones
    private void manejarDevolucion(String contenido) {
        System.out.println(" Procesando devolución -> " + contenido);
        socketGA.send("DEVOLVER " + contenido);
        String respGA = socketGA.recvStr();
        System.out.println(" GA respondió: " + respGA);
    }

    //  Manejo de renovaciones
    private void manejarRenovacion(String contenido) {
        System.out.println(" Procesando renovación -> " + contenido);
        socketGA.send("RENOVAR " + contenido);
        String respGA = socketGA.recvStr();
        System.out.println(" GA respondió: " + respGA);
    }

    // Cierre
    private void cerrarSockets() {
        subscriber.close();
        socketGA.close();
        context.term();
        System.out.println("\n DevolucionRenovacion finalizado correctamente.");
    }
}
