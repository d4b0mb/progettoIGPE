package it.unical.igpe.ristorante.net;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import it.unical.igpe.ristorante.model.kitchen.Ticket;
import it.unical.igpe.ristorante.model.kitchen.TicketStatus;
import it.unical.igpe.ristorante.persistence.Database;
import it.unical.igpe.ristorante.persistence.TicketDao;

/**
 * Server delle comande: è il punto di incontro fra le postazioni di sala e
 * quelle di cucina.
 *
 * Schema client/server classico, quello delle slide di rete:
 *   1. il server apre un ServerSocket su una porta e resta in attesa;
 *   2. accept() si blocca finché un client non si collega e restituisce un Socket;
 *   3. per OGNI client viene avviato un thread dedicato.
 *
 * Il thread per client è necessario perché la lettura da un socket è
 * bloccante: con un solo thread, il server resterebbe fermo ad ascoltare la
 * prima postazione e non accetterebbe più nessuno.
 *
 * Le comande sono conservate qui, in una lista sincronizzata, e salvate nel
 * database: il server è l'unica copia autorevole dello stato del servizio.
 */
public class KitchenServer {

    public static final int DEFAULT_PORT = 8421;

    private final int port;
    private final TicketDao ticketDao;

    /**
     * Le liste sono condivise fra il thread di accept e tutti i thread dei
     * client: senza sincronizzazione due postazioni che inviano una comanda
     * nello stesso istante potrebbero corrompere la lista.
     */
    private final List<ClientHandler> clients = Collections.synchronizedList(new ArrayList<>());
    private final List<Ticket> tickets = Collections.synchronizedList(new ArrayList<>());

    private ServerSocket serverSocket;
    private volatile boolean running;
    private int ticketCounter;
    private int clientSequence;

    /** Callback di log, usata dalla finestra del server per mostrare l'attività. */
    private volatile Consumer<String> logger = System.out::println;

    public KitchenServer(int port, Database database) {
        this.port = port;
        this.ticketDao = new TicketDao(database);
        this.tickets.addAll(ticketDao.findByDate(LocalDate.now()));
        this.ticketCounter = ticketDao.countToday();
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger;
    }

    private void log(String message) {
        Consumer<String> current = logger;
        if (current != null) {
            current.accept(message);
        }
    }

    /**
     * Apre la porta e avvia il ciclo di accettazione su un thread separato.
     *
     * La porta si apre QUI, sul thread chiamante, e non dentro il nuovo
     * thread: così chi avvia il server sa subito se qualcosa è andato storto
     * (tipicamente la porta è già occupata da un altro server) e può dirlo
     * all'utente, invece di mostrare "in esecuzione" un server che non ascolta
     * nessuno. Dopo stop() il server si può riavviare.
     *
     * @throws IOException se la porta non si può aprire
     */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        ServerSocket socket = new ServerSocket(port);
        serverSocket = socket;
        running = true;
        log("Server comande in ascolto sulla porta " + socket.getLocalPort());

        Thread acceptThread = new Thread(() -> acceptLoop(socket), "kitchen-server-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop(ServerSocket socket) {
        while (running && !socket.isClosed()) {
            try {
                // accept() è bloccante: il thread resta fermo qui finché
                // non arriva una richiesta di connessione.
                Socket client = socket.accept();
                ClientHandler handler = new ClientHandler(client);
                clients.add(handler);

                Thread clientThread = new Thread(handler, "client-" + (++clientSequence));
                clientThread.setDaemon(true);
                clientThread.start();
            } catch (IOException e) {
                // accept() fallisce anche quando stop() chiude il socket: in quel
                // caso running è già false e il ciclo termina senza messaggi.
                if (running) {
                    log("Errore del server: " + e.getMessage());
                    pause(200);
                }
            }
        }
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        synchronized (clients) {
            for (ClientHandler handler : clients) {
                handler.close();
            }
            clients.clear();
        }
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log("Chiusura del server non riuscita: " + e.getMessage());
        }
        log("Server arrestato.");
    }

    public boolean isRunning() {
        return running;
    }

    /** Porta richiesta alla creazione. */
    public int getPort() {
        return port;
    }

    /** Porta davvero in ascolto: differisce da getPort() solo se si è chiesta la porta 0. */
    public int getLocalPort() {
        ServerSocket socket = serverSocket;
        return (socket != null && !socket.isClosed()) ? socket.getLocalPort() : port;
    }

    public int getClientCount() {
        return clients.size();
    }

    public List<Ticket> getTicketsCopy() {
        synchronized (tickets) {
            return new ArrayList<>(tickets);
        }
    }

    /** Codice progressivo giornaliero: C-001, C-002, ... */
    private String nextTicketCode() {
        ticketCounter++;
        return String.format("C-%03d", ticketCounter);
    }

    /** Invia un messaggio a tutte le postazioni collegate. */
    private void broadcast(Message message) {
        synchronized (clients) {
            for (ClientHandler handler : new ArrayList<>(clients)) {
                handler.send(message);
            }
        }
    }

    /**
     * Nuova comanda da una postazione di sala.
     *
     * È synchronized perché più postazioni possono inviare nello stesso
     * istante, ognuna dal proprio thread. Senza, due comande potrebbero
     * ricevere lo stesso codice e, peggio, poiché i thread condividono
     * un'unica connessione JDBC, una potrebbe leggere l'id generato per
     * l'altra (getGeneratedKeys restituisce l'ultimo inserimento).
     */
    private synchronized void handleNewTicket(Ticket ticket) {
        if (ticket == null || ticket.getItems().isEmpty()) {
            log("Comanda vuota ignorata.");
            return;
        }
        ticket.setCode(nextTicketCode());
        ticket.setStatus(TicketStatus.NUOVA);
        try {
            ticketDao.insert(ticket);
        } catch (RuntimeException e) {
            log("Comanda non salvata su database: " + e.getMessage());
        }
        tickets.add(ticket);
        log("Nuova comanda " + ticket.getCode() + " — tavolo " + ticket.getTableNumber()
                + " — " + ticket.getItems().size() + " righe"
                + (ticket.hasGuestAllergens() ? " — ALLERGIE DICHIARATE" : "")
                + (ticket.hasAllergyConflicts() ? " — ATTENZIONE: piatti in conflitto" : ""));
        broadcast(Message.newTicket(ticket, "server"));
    }

    private synchronized void handleTicketUpdate(Ticket incoming) {
        if (incoming == null) {
            return;
        }
        Ticket stored = null;
        synchronized (tickets) {
            for (int i = 0; i < tickets.size(); i++) {
                if (tickets.get(i).getCode().equals(incoming.getCode())) {
                    tickets.set(i, incoming);
                    stored = incoming;
                    break;
                }
            }
        }
        if (stored == null) {
            log("Aggiornamento per una comanda sconosciuta: " + incoming.getCode());
            return;
        }
        try {
            ticketDao.updateStatus(stored);
        } catch (RuntimeException e) {
            log("Stato comanda non salvato: " + e.getMessage());
        }
        log("Comanda " + stored.getCode() + " -> " + stored.getStatus().getLabel());
        broadcast(Message.updateTicket(stored, "server"));
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Gestisce una singola postazione collegata.
     *
     * Implementa Runnable e non estende Thread: la classe rappresenta il
     * COMPITO da eseguire, non il thread stesso: è la stessa distinzione che
     * si fa a lezione fra Runnable e Thread.
     */
    private class ClientHandler implements Runnable {

        private final Socket socket;
        private ObjectOutputStream out;
        private ObjectInputStream in;
        private String name = "sconosciuto";

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                // ATTENZIONE all'ordine: ObjectOutputStream scrive un'intestazione
                // appena creato e ObjectInputStream la legge appena creato. Se le
                // due estremita' costruissero prima l'input, resterebbero entrambe
                // bloccate ad aspettare l'intestazione dell'altra: uno stallo.
                out = new ObjectOutputStream(socket.getOutputStream());
                out.flush();
                in = new ObjectInputStream(socket.getInputStream());

                // Stato iniziale completo per la postazione appena arrivata.
                send(Message.snapshot(getTicketsCopy()));

                while (!socket.isClosed()) {
                    Object received = in.readObject();
                    if (!(received instanceof Message message)) {
                        continue;
                    }
                    dispatch(message);
                }
            } catch (EOFException e) {
                // chiusura ordinata da parte del client
            } catch (IOException | ClassNotFoundException e) {
                if (running) {
                    log("Postazione " + name + " disconnessa: " + e.getMessage());
                }
            } finally {
                clients.remove(this);
                close();
                if (running) {
                    log("Postazione " + name + " scollegata (" + clients.size() + " collegate).");
                }
            }
        }

        private void dispatch(Message message) {
            switch (message.getType()) {
                case HELLO -> {
                    name = message.getSenderName() + " / " + message.getStation();
                    log("Postazione collegata: " + name + " (" + clients.size() + " collegate).");
                }
                case TICKET_NEW -> handleNewTicket(message.getTicket());
                case TICKET_UPDATE -> handleTicketUpdate(message.getTicket());
                case CHAT -> {
                    log("[chat] " + message.getSenderName() + ": " + message.getText());
                    broadcast(message);
                }
                default -> log("Messaggio ignorato: " + message.getType());
            }
        }

        void send(Message message) {
            if (out == null) {
                return;
            }
            try {
                synchronized (out) {
                    // reset() è indispensabile: ObjectOutputStream tiene in cache
                    // gli oggetti già inviati e, senza reset, rispedirebbe la
                    // vecchia versione di una comanda modificata invece di quella
                    // nuova. È un errore che non da' eccezioni: si vede solo
                    // perché la cucina non aggiorna mai lo stato.
                    out.reset();
                    out.writeObject(message);
                    out.flush();
                }
            } catch (IOException e) {
                log("Invio a " + name + " fallito: " + e.getMessage());
                close();
            }
        }

        void close() {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                // niente di utile da fare in chiusura
            }
        }
    }
}
