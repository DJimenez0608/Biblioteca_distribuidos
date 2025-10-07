package org.example;

import org.zeromq.ZMQ;

public class GA {

    private static final int PUERTO = 5557;

    public static void main(String[] args) {
        new GA().iniciar();
    }

    public void iniciar() {
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
    }

    //  Función principal de procesamiento
    private String procesarSolicitud(String solicitud) {
        if (solicitud == null || solicitud.isEmpty()) {
            return "Solicitud vacía o nula";
        }

        if (solicitud.startsWith("Disponibilidad?")) {
            return manejarDisponibilidad(solicitud);

        } else if (solicitud.startsWith("DEVOLVER")) {
            return manejarDevolucion(solicitud);

        } else if (solicitud.startsWith("RENOVAR")) {
            return manejarRenovacion(solicitud);

        } else {
            System.out.println("GA:  Solicitud desconocida -> " + solicitud);
            return "Solicitud no reconocida";
        }
    }

    //  Función para manejar disponibilidad
    private String manejarDisponibilidad(String solicitud) {
        System.out.println("GA:  Se consultó disponibilidad de libro -> " + solicitud);
        // Aquí podrías agregar lógica para consultar una base de datos o archivo
        return "SI";
    }

    //  Función para manejar devoluciones
    private String manejarDevolucion(String solicitud) {
        System.out.println("GA:  Se registró devolución -> " + solicitud);
        // Lógica para registrar devolución
        return "Devolución registrada";
    }

    //  Función para manejar renovaciones
    private String manejarRenovacion(String solicitud) {
        System.out.println("GA:  Se registró renovación -> " + solicitud);
        // Lógica para registrar renovación
        return "Renovación registrada";
    }
}
