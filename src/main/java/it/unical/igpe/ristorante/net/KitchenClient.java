package it.unical.igpe.ristorante.net;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

import it.unical.igpe.ristorante.model.RestaurantModel;
import it.unical.igpe.ristorante.model.kitchen.Ticket;

/**
 * Lato client della comunicazione con il server delle comande.
 *
 * Il punto delicato di tutta questa classe è uno solo, ed è lo stesso
 * problema descritto nelle slide sulla programmazione concorrente: i componenti
 * grafici NON sono thread-safe e possono essere toccati soltanto dal thread
 * dell'interfaccia (l'Event Dispatch Thread).
 *
 * Qui la lettura dal socket avviene su un thread separato, perché è un'attesa
 * bloccante che congelerebbe la finestra. Ma quel thread non modifica mai il
 * Model direttamente: passa sempre da SwingUtilities.invokeLater, che accoda
 * l'operazione sull'EDT. In JavaFX si userebbe Platform.runLater: idea identica,
 * nome diverso.
 *
 * La connessione si mantiene da sola (startAutoReconnect): se il server non è
 * ancora partito, o si ferma durante il servizio, il client riprova ogni pochi
 * secondi. L'ordine in cui si avviano le postazioni quindi non conta.
 */
public class KitchenClient {

    /** Attesa fra un tentativo di riconnessione e il successivo. */
    private static final long RETRY_MILLIS = 3000;
    /** Tempo massimo per aprire la connessione e per ricevere l'intestazione. */
    private static final int CONNECT_TIMEOUT_MILLIS = 1500;

    private final RestaurantModel model;

    private volatile String host = "127.0.0.1";
    private volatile int port = KitchenServer.DEFAULT_PORT;
    private volatile String station = "Sala";

    private volatile Socket socket;
    private volatile ObjectOutputStream out;

    private volatile ConnectionState state = ConnectionState.DISCONNESSO;
    private volatile boolean autoReconnect;
    /** true dopo disconnect(): nessun tentativo deve più ricollegare il client. */
    private volatile boolean closed;
    /** Impedisce due tentativi di connessione contemporanei. */
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private Thread reconnectThread;

    private volatile Consumer<ConnectionState> stateListener;
    private volatile Consumer<Message> messageListener;

    public KitchenClient(RestaurantModel model) {
        this.model = model;
    }

    public void setStateListener(Consumer<ConnectionState> listener) {
        this.stateListener = listener;
        notifyState(state);
    }

    public void setMessageListener(Consumer<Message> listener) {
        this.messageListener = listener;
    }

    public void setServer(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public void setStation(String station) {
        this.station = station;
    }

    public ConnectionState getState() {
        return state;
    }

    public boolean isConnected() {
        return state == ConnectionState.CONNESSO;
    }

    /**
     * Un tentativo di connessione immediato, senza bloccare l'interfaccia.
     *
     * Se il server non risponde l'applicazione continua a funzionare in
     * locale: le prenotazioni e la piantina non dipendono dalla rete.
     */
    public void connectInBackground() {
        closed = false;
        Thread connector = new Thread(() -> connectNow(true), "kitchen-client-connect");
        connector.setDaemon(true);
        connector.start();
    }

    /**
     * Mantiene la connessione: se cade, o se il server non è ancora partito,
     * riprova da sola ogni RETRY_MILLIS millisecondi.
     *
     * È un thread e non un javax.swing.Timer perché il tentativo di
     * connessione è bloccante (fino al timeout) e sull'EDT congelerebbe la
     * finestra per tutto quel tempo. Il thread aspetta PRIMA di riprovare:
     * il primo tentativo è quello esplicito di connectInBackground().
     */
    public void startAutoReconnect() {
        if (autoReconnect) {
            return;
        }
        closed = false;
        autoReconnect = true;
        reconnectThread = new Thread(() -> {
            while (autoReconnect) {
                try {
                    Thread.sleep(RETRY_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
                if (autoReconnect && !isConnected()) {
                    connectNow(false);
                }
            }
        }, "kitchen-client-reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    /**
     * Un tentativo di connessione, bloccante: va eseguito fuori dall'EDT.
     *
     * @param announce se true mostra lo stato "in connessione". I tentativi
     *                 automatici non lo fanno, altrimenti l'indicatore in alto
     *                 lampeggerebbe ogni tre secondi finché il server è spento.
     */
    private void connectNow(boolean announce) {
        if (closed || isConnected() || !connecting.compareAndSet(false, true)) {
            return;
        }
        Socket s = new Socket();
        try {
            if (announce) {
                notifyState(ConnectionState.IN_CONNESSIONE);
            }
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            // Anche l'intestazione ha un tempo massimo: se all'altro capo c'è un
            // programma che non è il server comande, il costruttore di
            // ObjectInputStream resterebbe fermo ad aspettarla per sempre.
            s.setSoTimeout(CONNECT_TIMEOUT_MILLIS * 2);
            ObjectOutputStream o = new ObjectOutputStream(s.getOutputStream());
            o.flush();
            ObjectInputStream i = new ObjectInputStream(s.getInputStream());
            s.setSoTimeout(0);

            if (closed) {
                // disconnect() è arrivato mentre ci si collegava
                closeQuietly(s);
                return;
            }
            socket = s;
            out = o;
            notifyState(ConnectionState.CONNESSO);
            send(Message.hello(currentUserName(), station));
            startReader(s, i);
        } catch (IOException e) {
            closeQuietly(s);
            if (announce || state != ConnectionState.DISCONNESSO) {
                notifyState(ConnectionState.DISCONNESSO);
            }
        } finally {
            connecting.set(false);
        }
    }

    private void startReader(Socket s, ObjectInputStream input) {
        Thread reader = new Thread(() -> {
            ConnectionState end = ConnectionState.DISCONNESSO;
            try {
                while (!s.isClosed()) {
                    Object received = input.readObject();
                    if (received instanceof Message message) {
                        // Il messaggio arriva su questo thread, ma il Model e le
                        // View vanno toccati solo sull'EDT: si accoda li'.
                        SwingUtilities.invokeLater(() -> handle(message));
                    }
                }
            } catch (EOFException e) {
                // il server ha chiuso la connessione in modo ordinato
            } catch (IOException | ClassNotFoundException e) {
                end = ConnectionState.ERRORE;
            } finally {
                closeQuietly(s);
                // Si segnala la caduta solo se questa è ancora la connessione
                // corrente: una disconnessione voluta ha già aggiornato lo
                // stato, e non va trasformata in un "errore di rete".
                if (socket == s) {
                    socket = null;
                    out = null;
                    notifyState(closed ? ConnectionState.DISCONNESSO : end);
                }
            }
        }, "kitchen-client-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /** Eseguito sull'EDT: da qui in poi si può toccare il Model in sicurezza. */
    private void handle(Message message) {
        switch (message.getType()) {
            case SNAPSHOT -> model.replaceTickets(message.getTickets());
            case TICKET_NEW, TICKET_UPDATE -> {
                if (message.getTicket() != null) {
                    model.upsertTicket(message.getTicket());
                }
            }
            default -> { /* CHAT ed ERROR sono gestiti dal listener della view */ }
        }
        Consumer<Message> listener = messageListener;
        if (listener != null) {
            listener.accept(message);
        }
    }

    /**
     * Invia un messaggio al server.
     *
     * @return false se non c'è connessione o l'invio non è riuscito: chi
     *         chiama deve dirlo all'utente, perché la comanda NON è partita
     */
    public boolean send(Message message) {
        ObjectOutputStream o = out;
        Socket s = socket;
        if (o == null || s == null || s.isClosed()) {
            return false;
        }
        try {
            synchronized (o) {
                // Vedi la nota nel server: senza reset() verrebbe rispedita la
                // versione precedente di un oggetto già inviato una volta.
                o.reset();
                o.writeObject(message);
                o.flush();
            }
            return true;
        } catch (IOException e) {
            // Chiudendo il socket il thread di lettura si accorge della caduta
            // e aggiorna lo stato; il thread di riconnessione farà il resto.
            closeQuietly(s);
            return false;
        }
    }

    public boolean sendNewTicket(Ticket ticket) {
        return send(Message.newTicket(ticket, currentUserName()));
    }

    public boolean sendTicketUpdate(Ticket ticket) {
        return send(Message.updateTicket(ticket, currentUserName()));
    }

    /** Chiude la connessione e ferma i tentativi automatici. */
    public void disconnect() {
        closed = true;
        autoReconnect = false;
        Thread retry = reconnectThread;
        if (retry != null) {
            retry.interrupt();
        }
        Socket s = socket;
        socket = null;
        out = null;
        closeQuietly(s);
        notifyState(ConnectionState.DISCONNESSO);
    }

    private String currentUserName() {
        return model.getCurrentUser() == null ? "sconosciuto" : model.getCurrentUser().getFullName();
    }

    private void notifyState(ConnectionState newState) {
        this.state = newState;
        Consumer<ConnectionState> listener = stateListener;
        if (listener != null) {
            // Anche la notifica di stato aggiorna dei componenti grafici:
            // deve arrivare sull'EDT come tutto il resto.
            SwingUtilities.invokeLater(() -> listener.accept(newState));
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException e) {
            // in chiusura non serve fare altro
        }
    }
}
