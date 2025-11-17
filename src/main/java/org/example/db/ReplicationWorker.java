package org.example.db;

public class ReplicationWorker implements Runnable {

    private final DatabaseManager databaseManager;
    private volatile boolean running = true;

    public ReplicationWorker(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        while (running && !Thread.currentThread().isInterrupted()) {
            int procesados = databaseManager.procesarColaReplicacion();
            try {
                long wait = procesados == 0
                        ? databaseManager.getReplicationPollIntervalMs()
                        : Math.max(250, databaseManager.getReplicationPollIntervalMs() / 2);
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }
}

