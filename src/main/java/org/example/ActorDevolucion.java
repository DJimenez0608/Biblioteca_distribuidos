package org.example;

import org.zeromq.ZMQ;

public class ActorDevolucion {

    // Constantes de conexión
    /**
     * Pasa -Dactor.dev.pub=tcp://IP_DEL_GC:5560 o -Dactor.dev.ga=tcp://IP_DEL_GA:5557 si este actor vive en otra sede.
     */
    private static final String PUERTO_SUB_GC = System.getProperty("actor.dev.pub", "tcp://localhost:5560");
    private static final String PUERTO_REQ_GA = System.getProperty("actor.dev.ga", "tcp://localhost:5557");

    private ZMQ.Context context;
    private ZMQ.Socket subscriber; // Suscriptor de GC
    private ZMQ.Socket socketGA;   // Comunicador con GA

    public static void main(String[] args) {
        new ActorDevolucion().iniciar();
    }

    public void iniciar() {
        context = ZMQ.context(1);
        inicializarSockets();

        System.out.println(" ActorDevolucion conectado a GC (" + PUERTO_SUB_GC + ") y GA (" + PUERTO_REQ_GA + ")");
        System.out.println(" Suscrito al tópico: DEVOLUCION");

        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

        while (!Thread.currentThread().isInterrupted()) {
            procesarMensajes();
        }

        cerrarSockets();
    }

    //Sockets
    private void inicializarSockets() {
        // SUB para recibir mensajes del GC - solo DEVOLUCION
        subscriber = context.socket(ZMQ.SUB);
        subscriber.connect(PUERTO_SUB_GC);
        subscriber.subscribe("DEVOLUCION".getBytes());

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

        if ("DEVOLUCION".equals(topico)) {
            manejarDevolucion(contenido);
        } else {
            System.out.println(" Tópico desconocido: " + topico);
        }
    }

    // Manejo de devoluciones
    private void manejarDevolucion(String contenido) {
        System.out.println(" Procesando devolución -> " + contenido);
        socketGA.send(contenido.trim());
        String respGA = socketGA.recvStr();
        System.out.println(" GA respondió: " + respGA);
    }

    // Cierre
    private void cerrarSockets() {
        subscriber.close();
        socketGA.close();
        context.term();
        System.out.println("\n ActorDevolucion finalizado correctamente.");
    }
}

