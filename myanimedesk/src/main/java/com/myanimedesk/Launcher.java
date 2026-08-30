package com.myanimedesk;

/**
 * Entry point separato da JavaFX, necessario per avviare correttamente
 * l'applicazione non modulare distribuita come JAR con dipendenze.
 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        App.main(args);
    }
}
