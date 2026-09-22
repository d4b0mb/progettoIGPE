package it.unical.igpe.ristorante.view;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;

import it.unical.igpe.ristorante.AppConfig;
import it.unical.igpe.ristorante.model.ModelEvent;
import it.unical.igpe.ristorante.model.ModelListener;
import it.unical.igpe.ristorante.model.Permission;
import it.unical.igpe.ristorante.model.Reservation;
import it.unical.igpe.ristorante.model.RestaurantModel;
import it.unical.igpe.ristorante.model.User;
import it.unical.igpe.ristorante.model.kitchen.Ticket;
import it.unical.igpe.ristorante.model.kitchen.TicketStatus;
import it.unical.igpe.ristorante.net.ConnectionState;
import it.unical.igpe.ristorante.net.KitchenClient;
import it.unical.igpe.ristorante.net.Message;
import it.unical.igpe.ristorante.net.MessageType;
import it.unical.igpe.ristorante.view.common.Badge;
import it.unical.igpe.ristorante.view.common.ClockLabel;
import it.unical.igpe.ristorante.view.common.Palette;
import it.unical.igpe.ristorante.view.common.StatusDot;
import it.unical.igpe.ristorante.view.common.Theme;
import it.unical.igpe.ristorante.view.common.Toast;
import it.unical.igpe.ristorante.view.common.Ui;
import it.unical.igpe.ristorante.view.floor.FloorPanel;
import it.unical.igpe.ristorante.view.kitchen.KitchenPanel;
import it.unical.igpe.ristorante.view.kitchen.ServicePanel;
import it.unical.igpe.ristorante.view.reservations.ReservationsPanel;
import it.unical.igpe.ristorante.view.users.UsersPanel;

/**
 * Finestra principale.
 *
 * Struttura: barra superiore con identità e orologio, barra di navigazione a
 * sinistra, area centrale a schede gestita da un CardLayout, barra di stato in
 * basso.
 *
 * Le sezioni visibili dipendono dai permessi dell'utente collegato: un utente
 * di terzo livello non vede nemmeno la voce "Utenti". Il controllo è comunque
 * ripetuto dentro il Model, perché nascondere un pulsante non è una misura
 * di sicurezza ma solo di chiarezza.
 *
 * La finestra possiede alcune risorse attive (timer, connessione al server,
 * iscrizioni al Model): shutdown() le rilascia tutte. È ciò che permette di
 * tornare alla schermata di accesso o di ricostruire la finestra con l'altro
 * tema senza lasciare in giro timer e listener di una finestra ormai chiusa.
 */
public class MainFrame extends JFrame implements ModelListener {

    private static final long serialVersionUID = 1L;

    public static final String VIEW_RESERVATIONS = "prenotazioni";
    public static final String VIEW_FLOOR = "sala";
    public static final String VIEW_SERVICE = "servizio";
    public static final String VIEW_KITCHEN = "cucina";
    public static final String VIEW_USERS = "utenti";

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final transient RestaurantModel model;
    private final transient KitchenClient client;
    private final boolean kitchenStation;

    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final JLabel viewTitle = new JLabel();
    private final JLabel statusLeft = new JLabel();
    private final JLabel statusRight = new JLabel();
    private final StatusDot connectionDot = new StatusDot(Palette.TEXT_MUTED, 8);
    private final JLabel connectionLabel = new JLabel("Non connesso");

    private final Timer statusTimer;
    private final Timer syncTimer;

    private NavRail nav;
    private ReservationsPanel reservationsPanel;
    private FloorPanel floorPanel;
    private ServicePanel servicePanel;
    private KitchenPanel kitchenPanel;
    private UsersPanel usersPanel;

    public MainFrame(RestaurantModel model) {
        this(model, null, null);
    }

    /**
     * @param initialView vista da mostrare all'apertura; null = quella della postazione
     * @param bounds      posizione e dimensione; null = finestra centrata
     */
    private MainFrame(RestaurantModel model, String initialView, Rectangle bounds) {
        this.model = model;
        this.client = new KitchenClient(model);
        this.kitchenStation = "Cucina".equalsIgnoreCase(AppConfig.getStation());

        User user = model.getCurrentUser();
        setTitle("RistoManager — " + (user == null ? "" : user.getFullName()
                + " (" + user.getRole().getLabel() + ")") + " · " + AppConfig.getStation());
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1180, 720));
        if (bounds != null) {
            setBounds(bounds);
        } else {
            setSize(1420, 880);
            setLocationRelativeTo(null);
        }

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Palette.BG);
        root.add(buildTopBar(), BorderLayout.NORTH);
        root.add(buildNav(), BorderLayout.WEST);
        root.add(buildContent(), BorderLayout.CENTER);
        root.add(buildStatusBar(), BorderLayout.SOUTH);
        setContentPane(root);

        model.addListener(this);
        installShortcuts();

        // La connessione al server comande è facoltativa: se il server non è
        // in esecuzione l'applicazione funziona lo stesso, e si collega da
        // sola appena il server parte.
        client.setServer(AppConfig.getServerHost(), AppConfig.getServerPort());
        client.setStation(AppConfig.getStation());
        client.setStateListener(this::onConnectionState);
        client.setMessageListener(this::onMessage);
        client.connectInBackground();
        client.startAutoReconnect();

        // Ogni 30 secondi si ricalcolano gli stati che dipendono dall'ora
        // (un tavolo prenotato diventa occupato o libero allo scadere della
        // fascia) senza che l'utente debba fare nulla.
        statusTimer = new Timer(30_000, e -> {
            model.refreshTableStatuses(LocalDateTime.now());
            model.fireEvent(ModelEvent.Type.RESERVATIONS_CHANGED, null);
        });
        statusTimer.start();

        // Ogni 5 secondi si controlla se un'altra postazione ha scritto nel
        // database. Costa una sola lettura (PRAGMA data_version) e le tabelle
        // si rileggono solo quando qualcosa è davvero cambiato.
        syncTimer = new Timer(5_000, e -> {
            try {
                model.syncWithDatabase();
            } catch (RuntimeException ex) {
                // database momentaneamente occupato: si riprova al prossimo colpo
                System.err.println("Allineamento non riuscito: " + ex.getMessage());
            }
        });
        syncTimer.start();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestExit();
            }
        });

        updateStatusBar();
        updateBadges();

        // La postazione scelta all'avvio decide quale schermata si apre per prima.
        String startView = initialView;
        if (startView == null && kitchenStation) {
            startView = VIEW_KITCHEN;
        }
        if (startView != null && nav.hasItem(startView)) {
            nav.select(startView);
        }
    }

    // ------------------------------------------------------------------
    // Costruzione dell'interfaccia
    // ------------------------------------------------------------------

    private JComponent buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout()) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Palette.SURFACE);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(Palette.BORDER);
                g2.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
                // Segno identitario: un piccolo tondo color accento.
                g2.setColor(Palette.ACCENT);
                g2.fillOval(20, getHeight() / 2 - 6, 12, 12);
                g2.dispose();
            }
        };
        bar.setPreferredSize(new Dimension(10, 58));
        bar.setOpaque(false);

        JPanel left = Ui.row();
        left.setBorder(BorderFactory.createEmptyBorder(0, 42, 0, 0));
        JLabel brand = new JLabel("RistoManager");
        brand.setFont(Theme.bold(15));
        brand.setForeground(Palette.TEXT);
        left.add(brand);

        JLabel divider = new JLabel("/");
        divider.setForeground(Palette.BORDER);
        left.add(divider);

        viewTitle.setFont(Theme.regular(13));
        viewTitle.setForeground(Palette.TEXT_MUTED);
        left.add(viewTitle);
        bar.add(centerVertically(left), BorderLayout.WEST);

        JPanel right = Ui.rowRight();
        right.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 18));

        connectionLabel.setFont(Theme.regular(12));
        connectionLabel.setForeground(Palette.TEXT_MUTED);
        connectionLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        connectionLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!client.isConnected()) {
                    client.connectInBackground();
                }
            }
        });
        right.add(connectionDot);
        right.add(connectionLabel);
        right.add(Ui.hGap(6));
        right.add(verticalSeparator());
        right.add(Ui.hGap(6));

        User user = model.getCurrentUser();
        if (user != null) {
            JLabel name = new JLabel(user.getFullName());
            name.setFont(Theme.bold(12));
            name.setForeground(Palette.TEXT);
            right.add(name);
            right.add(new Badge("TIER " + user.getRole().getTier(), roleColor(user)));
        }

        right.add(Ui.hGap(6));
        right.add(verticalSeparator());
        right.add(Ui.hGap(6));
        right.add(new ClockLabel());

        JButton logout = Ui.toolButton("Esci");
        logout.setToolTipText("Cambia utente oppure chiudi l'applicazione");
        logout.addActionListener(e -> requestExit());
        right.add(logout);

        bar.add(centerVertically(right), BorderLayout.EAST);
        return bar;
    }

    /**
     * FlowLayout dispone i componenti a partire dall'alto: incapsularli in un
     * GridBagLayout senza vincoli li centra verticalmente nella barra.
     */
    private JComponent centerVertically(JComponent component) {
        JPanel wrapper = new JPanel(new GridBagLayout());
        wrapper.setOpaque(false);
        wrapper.add(component);
        return wrapper;
    }

    private JComponent verticalSeparator() {
        JPanel p = new JPanel();
        p.setPreferredSize(new Dimension(1, 20));
        p.setBackground(Palette.BORDER);
        return p;
    }

    private Color roleColor(User user) {
        return switch (user.getRole()) {
            case ADMIN -> Palette.ACCENT;
            case OPERATOR -> Palette.TEAL;
            case VIEWER -> Palette.TEXT_MUTED;
        };
    }

    private JComponent buildNav() {
        nav = new NavRail(this::showView);
        nav.addSection("Servizio");
        if (model.can(Permission.VIEW_RESERVATIONS)) {
            nav.addItem(VIEW_RESERVATIONS, "Prenotazioni", Palette.ACCENT);
        }
        if (model.can(Permission.VIEW_FLOOR_PLAN)) {
            nav.addItem(VIEW_FLOOR, "Piantina sala", Palette.TEAL);
        }
        nav.addSection("Cucina");
        if (model.can(Permission.SEND_TICKETS)) {
            nav.addItem(VIEW_SERVICE, "Invio comande", Palette.INFO);
        }
        if (model.can(Permission.MANAGE_KITCHEN)) {
            nav.addItem(VIEW_KITCHEN, "Monitor cucina", Palette.VIP);
        }
        if (model.can(Permission.MANAGE_USERS)) {
            nav.addSection("Amministrazione");
            nav.addItem(VIEW_USERS, "Utenti", Palette.DANGER);
        }
        nav.addFiller();

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(10, 18, 14, 14));
        footer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));

        JButton themeToggle = Ui.toolButton(Theme.isDark() ? "Tema chiaro" : "Tema scuro");
        themeToggle.setToolTipText("Cambia tema: la scelta viene ricordata ai prossimi avvii");
        themeToggle.addActionListener(e -> toggleTheme());
        footer.add(themeToggle, BorderLayout.CENTER);
        nav.addFooter(footer);
        return nav;
    }

    private JComponent buildContent() {
        content.setBackground(Palette.BG);
        content.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        if (model.can(Permission.VIEW_RESERVATIONS)) {
            reservationsPanel = new ReservationsPanel(model, this);
            content.add(reservationsPanel, VIEW_RESERVATIONS);
        }
        if (model.can(Permission.VIEW_FLOOR_PLAN)) {
            floorPanel = new FloorPanel(model);
            content.add(floorPanel, VIEW_FLOOR);
        }
        if (model.can(Permission.SEND_TICKETS)) {
            servicePanel = new ServicePanel(model, client);
            content.add(servicePanel, VIEW_SERVICE);
        }
        if (model.can(Permission.MANAGE_KITCHEN)) {
            kitchenPanel = new KitchenPanel(model, client);
            content.add(kitchenPanel, VIEW_KITCHEN);
        }
        if (model.can(Permission.MANAGE_USERS)) {
            usersPanel = new UsersPanel(model);
            content.add(usersPanel, VIEW_USERS);
        }
        return content;
    }

    private JComponent buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Palette.SURFACE);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Palette.BORDER),
                BorderFactory.createEmptyBorder(5, 18, 5, 18)));

        statusLeft.setFont(Theme.regular(11));
        statusLeft.setForeground(Palette.TEXT_MUTED);
        statusRight.setFont(Theme.regular(11));
        statusRight.setForeground(Palette.TEXT_MUTED);
        statusRight.setHorizontalAlignment(SwingConstants.RIGHT);

        bar.add(statusLeft, BorderLayout.WEST);
        bar.add(statusRight, BorderLayout.EAST);
        return bar;
    }

    /** Ctrl+1, Ctrl+2, ... aprono le voci della barra laterale, nell'ordine in cui compaiono. */
    private void installShortcuts() {
        List<String> keys = nav.getKeys();
        for (int i = 0; i < keys.size() && i < 9; i++) {
            String key = keys.get(i);
            Ui.bindKey(getRootPane(), JComponent.WHEN_IN_FOCUSED_WINDOW,
                    "ctrl " + (i + 1), "vista-" + key, () -> nav.select(key));
        }
    }

    // ------------------------------------------------------------------
    // Navigazione, tema, uscita
    // ------------------------------------------------------------------

    public void showView(String key) {
        cards.show(content, key);
        // Se la vista è stata cambiata da codice, la barra laterale deve
        // seguirla. Il confronto evita il rimbalzo infinito fra select() e
        // showView(), che si richiamano a vicenda.
        if (nav != null && !key.equals(nav.getSelectedKey())) {
            nav.select(key);
        }
        viewTitle.setText(switch (key) {
            case VIEW_RESERVATIONS -> "Prenotazioni";
            case VIEW_FLOOR -> "Piantina della sala";
            case VIEW_SERVICE -> "Invio comande";
            case VIEW_KITCHEN -> "Monitor cucina";
            case VIEW_USERS -> "Gestione utenti";
            default -> "";
        });
    }

    /**
     * Cambia tema e ricostruisce la finestra.
     *
     * I colori della Palette vengono letti quando i componenti sono creati:
     * per applicare il nuovo tema ovunque si ricrea la finestra, con lo stesso
     * Model (quindi stessi dati e stesso utente), sulla stessa vista e nella
     * stessa posizione dello schermo.
     */
    private void toggleTheme() {
        if (!resolveUnsavedFloorPlan()) {
            return;
        }
        String view = nav.getSelectedKey();
        Rectangle bounds = getBounds();
        int state = getExtendedState();
        shutdown();
        Theme.toggle();
        MainFrame rebuilt = new MainFrame(model, view, bounds);
        rebuilt.setExtendedState(state);
        rebuilt.setVisible(true);
    }

    /** "Esci": si può cambiare utente (resta aperta la postazione) oppure chiudere tutto. */
    private void requestExit() {
        Object[] options = {"Cambia utente", "Chiudi RistoManager", "Annulla"};
        User user = model.getCurrentUser();
        int choice = JOptionPane.showOptionDialog(this,
                "Vuoi chiudere la sessione" + (user == null ? "" : " di " + user.getFullName())
                        + " o l'intera applicazione?",
                "Esci", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);
        if (choice != 0 && choice != 1) {
            return;
        }
        if (!resolveUnsavedFloorPlan()) {
            return;
        }
        shutdown();
        if (choice == 0) {
            model.logout();
            new LoginFrame(model).setVisible(true);
        } else {
            System.exit(0);
        }
    }

    /**
     * Se la piantina ha modifiche non salvate chiede che cosa farne.
     *
     * @return false se l'utente preferisce annullare l'operazione in corso
     */
    private boolean resolveUnsavedFloorPlan() {
        if (floorPanel == null || !floorPanel.hasUnsavedChanges()) {
            return true;
        }
        showView(VIEW_FLOOR);
        Object[] options = {"Salva", "Non salvare", "Annulla"};
        int choice = JOptionPane.showOptionDialog(this,
                "La piantina della sala ha modifiche non salvate.",
                "Modifiche non salvate", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[0]);
        return switch (choice) {
            case 0 -> floorPanel.save();
            case 1 -> {
                floorPanel.discardChanges();
                yield true;
            }
            default -> false;
        };
    }

    /** Rilascia tutto ciò che questa finestra ha avviato, poi la chiude. */
    private void shutdown() {
        statusTimer.stop();
        syncTimer.stop();
        client.disconnect();
        model.removeListener(this);
        if (reservationsPanel != null) {
            reservationsPanel.detach();
        }
        if (floorPanel != null) {
            floorPanel.detach();
        }
        if (servicePanel != null) {
            servicePanel.detach();
        }
        if (kitchenPanel != null) {
            kitchenPanel.detach();
        }
        if (usersPanel != null) {
            usersPanel.detach();
        }
        dispose();
    }

    // ------------------------------------------------------------------
    // Reazione ai cambiamenti
    // ------------------------------------------------------------------

    @Override
    public void onModelChanged(ModelEvent event) {
        updateStatusBar();
        updateBadges();
    }

    /** Pallini nella barra laterale: comande aperte in cucina, comande pronte in sala. */
    private void updateBadges() {
        if (nav == null) {
            return;
        }
        int open = 0;
        int ready = 0;
        for (Ticket t : model.getTickets()) {
            if (t.getStatus().isOpen()) {
                open++;
            } else if (t.getStatus() == TicketStatus.PRONTA) {
                ready++;
            }
        }
        if (nav.hasItem(VIEW_KITCHEN)) {
            nav.setBadge(VIEW_KITCHEN, open);
        }
        if (nav.hasItem(VIEW_SERVICE)) {
            nav.setBadge(VIEW_SERVICE, ready);
        }
    }

    /**
     * Notifiche per i messaggi che arrivano dalla rete mentre si fa altro: in
     * cucina l'arrivo di una comanda, in sala una comanda pronta da portare.
     * Il messaggio arriva già sull'EDT (vedi KitchenClient.handle).
     */
    private void onMessage(Message message) {
        Ticket ticket = message.getTicket();
        if (ticket == null) {
            return;
        }
        if (kitchenStation && message.getType() == MessageType.TICKET_NEW
                && model.can(Permission.MANAGE_KITCHEN)) {
            Toolkit.getDefaultToolkit().beep();
            String detail = ticket.getTotalQuantity() + " piatti"
                    + (ticket.hasGuestAllergens() ? " · ALLERGIE DICHIARATE" : "")
                    + (ticket.getPriority().getLevel() > 0 ? " · " + ticket.getPriority().getLabel() : "");
            Toast.show(this, "Nuova comanda " + ticket.getCode() + " · Tavolo " + ticket.getTableNumber(),
                    detail, ticket.hasGuestAllergens() ? Palette.DANGER : Palette.forPriority(ticket.getPriority()));
        } else if (!kitchenStation && message.getType() == MessageType.TICKET_UPDATE
                && ticket.getStatus() == TicketStatus.PRONTA && model.can(Permission.SEND_TICKETS)) {
            Toolkit.getDefaultToolkit().beep();
            Toast.show(this, "Pronta: " + ticket.getCode() + " · Tavolo " + ticket.getTableNumber(),
                    (ticket.getGuestName().isBlank() ? "" : ticket.getGuestName() + " · ")
                            + "da portare al tavolo", Palette.OK);
        }
    }

    private void onConnectionState(ConnectionState state) {
        connectionDot.setColor(switch (state) {
            case CONNESSO -> Palette.OK;
            case IN_CONNESSIONE -> Palette.WARN;
            case DISCONNESSO -> Palette.TEXT_MUTED;
            case ERRORE -> Palette.DANGER;
        });
        connectionLabel.setText(switch (state) {
            case CONNESSO -> "Comande in linea";
            case IN_CONNESSIONE -> "Connessione…";
            case DISCONNESSO -> "Comande non in linea";
            case ERRORE -> "Connessione persa";
        });
        connectionLabel.setToolTipText(state == ConnectionState.CONNESSO
                ? "Collegato al server comande " + client.getHost() + ":" + client.getPort()
                : "Server comande " + client.getHost() + ":" + client.getPort()
                        + " non raggiungibile. Si riprova da soli; clic per riprovare subito.");
        updateStatusBar();
    }

    private void updateStatusBar() {
        LocalDate today = LocalDate.now();
        List<Reservation> todays = model.getReservationsForDate(today);
        int covers = 0;
        for (Reservation r : todays) {
            if (r.getStatus().occupiesTable()) {
                covers += r.getPartySize();
            }
        }
        statusLeft.setText(model.getFloorPlan().getName()
                + "  •  " + model.getFloorPlan().getTables().size() + " tavoli  •  "
                + model.getFloorPlan().getTotalSeats() + " posti  •  "
                + todays.size() + " prenotazioni oggi  •  " + covers + " coperti attesi");
        statusRight.setText("Postazione " + AppConfig.getStation()
                + "  •  server " + client.getHost() + ":" + client.getPort()
                + "  •  aggiornato alle " + LocalTime.now().format(CLOCK));
    }

    public KitchenClient getClient() {
        return client;
    }
}
