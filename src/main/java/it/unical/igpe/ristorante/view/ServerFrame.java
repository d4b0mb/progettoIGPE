package it.unical.igpe.ristorante.view;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import it.unical.igpe.ristorante.net.KitchenServer;
import it.unical.igpe.ristorante.view.common.Card;
import it.unical.igpe.ristorante.view.common.ClockLabel;
import it.unical.igpe.ristorante.view.common.Palette;
import it.unical.igpe.ristorante.view.common.StatusDot;
import it.unical.igpe.ristorante.view.common.Theme;
import it.unical.igpe.ristorante.view.common.Ui;

/**
 * Finestra di controllo del server delle comande.
 *
 * Mostra in tempo reale chi si collega e che cosa transita: è la prova visibile
 * che sala e cucina stanno parlando attraverso dei socket e non attraverso una
 * variabile condivisa nello stesso processo.
 *
 * Attenzione al punto delicato: il log viene scritto dai thread del server, ma
 * la JTextArea è un componente Swing e va toccata solo dall'Event Dispatch
 * Thread. Per questo ogni riga passa da SwingUtilities.invokeLater.
 */
public class ServerFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final transient KitchenServer server;
    private final JTextArea logArea = new JTextArea();
    private final JLabel portLabel = new JLabel("—");
    private final JLabel clientsLabel = new JLabel("0");
    private final JLabel ticketsLabel = new JLabel("0");
    private final StatusDot dot = new StatusDot(Palette.TEXT_MUTED, 10);
    private final JLabel stateLabel = new JLabel("Arrestato");
    private final JButton toggleButton = Ui.toolButton("Arresta server");
    private final JLabel addressLabel = new JLabel(" ");
    /** Indirizzi di rete di questa macchina, letti una volta sola all'apertura. */
    private final String addresses = lanAddresses();

    public ServerFrame(KitchenServer server) {
        this.server = server;

        setTitle("RistoManager — Server comande");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 580);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(Palette.BG);
        root.setBorder(BorderFactory.createEmptyBorder(20, 22, 18, 22));
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildLog(), BorderLayout.CENTER);
        setContentPane(root);

        // Il logger del server viene richiamato da thread diversi: si accoda
        // sempre sull'EDT prima di scrivere sull'area di testo.
        server.setLogger(line -> SwingUtilities.invokeLater(() -> append(line)));

        Timer refresh = new Timer(1000, e -> refreshState());
        refresh.start();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                server.stop();
            }
        });

        // La porta si apre quando la finestra è già visibile, così un eventuale
        // messaggio di errore ha una finestra a cui appoggiarsi.
        SwingUtilities.invokeLater(this::startServer);
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout(0, 14));
        header.setOpaque(false);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);

        JPanel left = Ui.column();
        left.add(Ui.alignLeft(Ui.h1("Server comande")));
        left.add(Ui.vGap(4));
        left.add(Ui.alignLeft(Ui.muted(
                "Smista le comande fra le postazioni di sala e i monitor di cucina.")));
        left.add(Ui.vGap(2));
        addressLabel.setFont(Theme.regular(11));
        addressLabel.setForeground(Palette.TEXT_MUTED);
        left.add(Ui.alignLeft(addressLabel));
        top.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(dot);
        stateLabel.setFont(Theme.bold(12));
        right.add(stateLabel);
        right.add(new ClockLabel(false));
        top.add(right, BorderLayout.EAST);
        header.add(top, BorderLayout.NORTH);

        JPanel stats = new JPanel(new GridLayout(1, 3, 12, 0));
        stats.setOpaque(false);
        stats.setPreferredSize(new Dimension(10, 78));
        stats.add(stat("Porta in ascolto", portLabel, Palette.ACCENT));
        stats.add(stat("Postazioni collegate", clientsLabel, Palette.TEAL));
        stats.add(stat("Comande della giornata", ticketsLabel, Palette.INFO));
        header.add(stats, BorderLayout.CENTER);
        return header;
    }

    private JComponent stat(String caption, JLabel value, Color color) {
        Card card = new Card(new BorderLayout(0, 2));
        card.fill(Palette.SURFACE).border(Palette.BORDER);
        card.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        value.setFont(Theme.bold(24));
        value.setForeground(color);
        card.add(Ui.sectionTitle(caption), BorderLayout.NORTH);
        card.add(value, BorderLayout.CENTER);
        return card;
    }

    private JComponent buildLog() {
        Card card = new Card(new BorderLayout(0, 8));
        card.fill(Palette.SURFACE).border(Palette.BORDER);
        card.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(Ui.sectionTitle("Registro attività"), BorderLayout.WEST);

        toggleButton.addActionListener(e -> toggleServer());
        top.add(toggleButton, BorderLayout.EAST);
        card.add(top, BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setFont(Theme.mono(Font.PLAIN, 12));
        logArea.setBackground(Palette.BG);
        logArea.setForeground(Palette.TEXT);
        logArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createLineBorder(Palette.BORDER));
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    /**
     * Apre la porta. Se è già occupata (di solito da un altro server avviato
     * prima) lo si dice chiaramente, invece di mostrare un server "in
     * esecuzione" che in realtà non ascolta nessuno.
     */
    private void startServer() {
        try {
            server.start();
            append("Pronto. Le postazioni di sala e cucina si collegano da sole.");
        } catch (IOException e) {
            append("Impossibile aprire la porta " + server.getPort() + ": " + e.getMessage());
            JOptionPane.showMessageDialog(this,
                    "Il server non è partito: la porta " + server.getPort() + " non è disponibile.\n\n"
                    + "Probabilmente un altro server comande è già in esecuzione.\n"
                    + "Chiudilo, oppure avvia questo su un'altra porta, ad esempio:\n"
                    + "    java -jar ristomanager.jar server 8422",
                    "Porta occupata", JOptionPane.ERROR_MESSAGE);
        }
        refreshState();
    }

    private void toggleServer() {
        if (server.isRunning()) {
            server.stop();
        } else {
            startServer();
        }
        refreshState();
    }

    private void refreshState() {
        boolean running = server.isRunning();
        dot.setColor(running ? Palette.OK : Palette.TEXT_MUTED);
        stateLabel.setText(running ? "In esecuzione" : "Arrestato");
        stateLabel.setForeground(running ? Palette.OK : Palette.TEXT_MUTED);
        toggleButton.setText(running ? "Arresta server" : "Avvia server");
        portLabel.setText(running ? String.valueOf(server.getLocalPort()) : "—");
        addressLabel.setText(running
                ? "Da un altro computer collegarsi a: " + addresses + ", porta " + server.getLocalPort()
                : "Server fermo: le postazioni aspettano e si ricollegano da sole al riavvio.");
        clientsLabel.setText(String.valueOf(server.getClientCount()));
        ticketsLabel.setText(String.valueOf(server.getTicketsCopy().size()));
    }

    private void append(String line) {
        logArea.append("[" + LocalTime.now().format(TIME) + "]  " + line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    /**
     * Indirizzi IPv4 di questa macchina sulla rete locale: sono quelli da
     * scrivere sulle postazioni che girano su altri computer.
     */
    private static String lanAddresses() {
        List<String> result = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(ni.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLinkLocalAddress()) {
                        result.add(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException e) {
            // nessuna interfaccia leggibile: resta solo l'indirizzo locale
        }
        return result.isEmpty() ? "127.0.0.1 (solo questo computer)" : String.join(", ", result);
    }
}
