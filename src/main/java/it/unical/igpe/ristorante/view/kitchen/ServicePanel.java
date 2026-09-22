package it.unical.igpe.ristorante.view.kitchen;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;

import it.unical.igpe.ristorante.model.Allergen;
import it.unical.igpe.ristorante.model.ModelEvent;
import it.unical.igpe.ristorante.model.ModelListener;
import it.unical.igpe.ristorante.model.Reservation;
import it.unical.igpe.ristorante.model.RestaurantModel;
import it.unical.igpe.ristorante.model.floor.RestaurantTable;
import it.unical.igpe.ristorante.model.kitchen.Course;
import it.unical.igpe.ristorante.model.kitchen.Ticket;
import it.unical.igpe.ristorante.model.kitchen.TicketItem;
import it.unical.igpe.ristorante.model.kitchen.TicketPriority;
import it.unical.igpe.ristorante.model.kitchen.TicketStatus;
import it.unical.igpe.ristorante.net.KitchenClient;
import it.unical.igpe.ristorante.view.common.Badge;
import it.unical.igpe.ristorante.view.common.Card;
import it.unical.igpe.ristorante.view.common.Palette;
import it.unical.igpe.ristorante.view.common.Theme;
import it.unical.igpe.ristorante.view.common.Toast;
import it.unical.igpe.ristorante.view.common.Ui;

/**
 * Postazione di sala: composizione e invio delle comande alla cucina.
 *
 * Il flusso è quello reale: si sceglie il tavolo, si aggiungono i piatti dal
 * menu, si assegna una priorità e si invia. Gli allergeni non si scrivono a
 * mano: quelli CONTENUTI arrivano dal piatto scelto, quelli a cui il cliente è
 * ALLERGICO dalla scheda della prenotazione. Sono tenuti separati, perché
 * l'informazione che conta è il loro incrocio: i piatti che il cliente non
 * può mangiare vengono evidenziati in rosso già nel menu.
 */
public class ServicePanel extends JPanel implements ModelListener {

    private static final long serialVersionUID = 1L;

    private final transient RestaurantModel model;
    private final transient KitchenClient client;

    private final JComboBox<RestaurantTable> tableCombo = new JComboBox<>();
    private final JComboBox<TicketPriority> priorityCombo =
            new JComboBox<>(TicketPriority.values());
    private final JComboBox<Object> courseFilter = new JComboBox<>();
    private final JList<Menu.Dish> menuList = new JList<>();
    private final JTextField noteField = new JTextField();

    private final JPanel draftItems = Ui.column();
    private final JLabel draftSummary = new JLabel();
    private final JLabel guestLabel = new JLabel();
    private final JPanel sentBoard = new JPanel();

    /** La comanda in composizione, non ancora inviata. */
    private transient Ticket draft = new Ticket();
    /** Tavolo a cui è intestata la comanda: serve a capire quando cambia davvero. */
    private int currentTableId = -1;
    /** true mentre si ricarica la tendina dei tavoli, che nel frattempo emette eventi. */
    private boolean refreshingTables;
    /** true se la priorità attuale l'ha alzata il programma (cliente fidelizzato), non il cameriere. */
    private boolean priorityRaisedAutomatically;
    /** true mentre è il programma a cambiare la priorità: così non la si scambia per una scelta del cameriere. */
    private boolean settingPriority;

    public ServicePanel(RestaurantModel model, KitchenClient client) {
        this.model = model;
        this.client = client;

        setLayout(new BorderLayout(14, 12));
        setBackground(Palette.BG);
        setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));

        add(buildHeader(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildSentColumn(), BorderLayout.EAST);

        model.addListener(this);
        refreshTables();
        refreshDraft();
        refreshSent();
    }

    /** Da chiamare quando la finestra si chiude: smette di ascoltare il Model. */
    public void detach() {
        model.removeListener(this);
    }

    // ------------------------------------------------------------------

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        left.add(Ui.h1("Invio comande"));
        header.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(Ui.muted("Tavolo"));
        tableCombo.setPreferredSize(new Dimension(290, 34));
        tableCombo.setRenderer(new TableRenderer());
        tableCombo.addActionListener(e -> onTableChanged());
        right.add(tableCombo);
        right.add(Ui.muted("Priorità"));
        priorityCombo.setPreferredSize(new Dimension(180, 34));
        priorityCombo.setRenderer(new PriorityRenderer());
        priorityCombo.addActionListener(e -> {
            if (!settingPriority) {
                priorityRaisedAutomatically = false;
            }
            draft.setPriority((TicketPriority) priorityCombo.getSelectedItem());
            refreshDraft();
        });
        right.add(priorityCombo);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JComponent buildCenter() {
        JPanel center = new JPanel(new BorderLayout(14, 0));
        center.setOpaque(false);
        center.add(buildMenuCard(), BorderLayout.CENTER);
        center.add(buildDraftCard(), BorderLayout.EAST);
        return center;
    }

    private JComponent buildMenuCard() {
        Card card = new Card(new BorderLayout(0, 10));
        card.fill(Palette.SURFACE).border(Palette.BORDER);
        card.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JPanel top = new JPanel(new BorderLayout(10, 0));
        top.setOpaque(false);
        top.add(Ui.h2("Menu"), BorderLayout.WEST);

        courseFilter.addItem("Tutte le portate");
        for (Course course : Course.values()) {
            courseFilter.addItem(course);
        }
        courseFilter.setPreferredSize(new Dimension(180, 32));
        courseFilter.addActionListener(e -> refreshMenu());
        top.add(courseFilter, BorderLayout.EAST);
        card.add(top, BorderLayout.NORTH);

        menuList.setCellRenderer(new DishRenderer());
        menuList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        menuList.setBackground(Palette.SURFACE);
        menuList.setFixedCellHeight(40);
        menuList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    addSelectedDish();
                }
            }
        });
        refreshMenu();

        JScrollPane scroll = new JScrollPane(menuList);
        scroll.setBorder(BorderFactory.createLineBorder(Palette.BORDER));
        card.add(scroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bottom.setOpaque(false);
        JButton add = Ui.secondary("Aggiungi alla comanda");
        add.addActionListener(e -> addSelectedDish());
        bottom.add(add);
        bottom.add(Ui.muted("oppure doppio clic · in rosso: allergie del cliente"));
        card.add(bottom, BorderLayout.SOUTH);
        return card;
    }

    private JComponent buildDraftCard() {
        Card card = new Card(new BorderLayout(0, 10));
        card.fill(Palette.SURFACE).border(Palette.BORDER);
        card.setPreferredSize(new Dimension(360, 10));
        card.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JPanel top = Ui.column();
        top.add(Ui.alignLeft(Ui.h2("Comanda in composizione")));
        top.add(Ui.vGap(4));
        guestLabel.setFont(Theme.regular(12));
        guestLabel.setForeground(Palette.TEXT_MUTED);
        top.add(Ui.alignLeft(guestLabel));
        card.add(top, BorderLayout.NORTH);

        draftItems.setOpaque(false);
        JScrollPane scroll = new JScrollPane(draftItems);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        card.add(scroll, BorderLayout.CENTER);

        JPanel bottom = Ui.column();
        bottom.add(Ui.alignLeft(Ui.separator()));
        bottom.add(Ui.vGap(8));

        draftSummary.setFont(Theme.regular(12));
        draftSummary.setForeground(Palette.TEXT_MUTED);
        bottom.add(Ui.alignLeft(draftSummary));
        bottom.add(Ui.vGap(8));

        noteField.setPreferredSize(new Dimension(320, 34));
        noteField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        noteField.putClientProperty("JTextField.placeholderText",
                "Nota per la cucina (facoltativa)");
        bottom.add(Ui.alignLeft(noteField));
        bottom.add(Ui.vGap(10));

        JPanel buttons = new JPanel(new GridLayout(1, 2, 8, 0));
        buttons.setOpaque(false);
        buttons.setMaximumSize(new Dimension(330, 40));
        JButton clear = Ui.toolButton("Svuota");
        clear.addActionListener(e -> {
            draft = newDraft();
            updateGuestInfo(false);
        });
        JButton send = Ui.primary("Invia in cucina");
        send.addActionListener(e -> sendDraft());
        buttons.add(clear);
        buttons.add(send);
        bottom.add(Ui.alignLeft(buttons));

        card.add(bottom, BorderLayout.SOUTH);
        return card;
    }

    private JComponent buildSentColumn() {
        Card card = new Card(new BorderLayout(0, 10));
        card.fill(Palette.SURFACE).border(Palette.BORDER);
        card.setPreferredSize(new Dimension(352, 10));
        card.setBorder(BorderFactory.createEmptyBorder(16, 12, 16, 12));

        card.add(Ui.h2("Comande inviate"), BorderLayout.NORTH);

        sentBoard.setLayout(new BoxLayout(sentBoard, BoxLayout.Y_AXIS));
        sentBoard.setOpaque(false);
        JScrollPane scroll = new JScrollPane(sentBoard);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    // ------------------------------------------------------------------
    // Dati
    // ------------------------------------------------------------------

    private Ticket newDraft() {
        Ticket ticket = new Ticket();
        ticket.setPriority((TicketPriority) priorityCombo.getSelectedItem());
        noteField.setText("");
        return ticket;
    }

    private void refreshTables() {
        Object previous = tableCombo.getSelectedItem();
        refreshingTables = true;
        tableCombo.removeAllItems();
        List<RestaurantTable> tables = new ArrayList<>(model.getFloorPlan().getTables());
        tables.sort((a, b) -> Integer.compare(a.getNumber(), b.getNumber()));
        for (RestaurantTable t : tables) {
            tableCombo.addItem(t);
        }
        // Si riseleziona per id e non per oggetto: dopo un annulla nella
        // piantina i tavoli sono oggetti nuovi, anche se rappresentano gli stessi.
        if (previous instanceof RestaurantTable p) {
            for (int i = 0; i < tableCombo.getItemCount(); i++) {
                if (tableCombo.getItemAt(i).getId() == p.getId()) {
                    tableCombo.setSelectedIndex(i);
                    break;
                }
            }
        }
        refreshingTables = false;
        onTableChanged();
    }

    private void onTableChanged() {
        if (refreshingTables) {
            return;
        }
        RestaurantTable table = (RestaurantTable) tableCombo.getSelectedItem();
        int id = table == null ? -1 : table.getId();
        boolean changed = id != currentTableId;
        currentTableId = id;
        updateGuestInfo(changed);
    }

    /**
     * Collega la comanda al tavolo scelto e alla prenotazione che lo occupa.
     *
     * Le allergie dichiarate nella prenotazione passano alla comanda: sono
     * un'informazione data al telefono giorni prima, ma serve in cucina, ore
     * dopo, a chi con il cliente non ha mai parlato.
     *
     * @param tableChanged true solo se il cameriere ha appena scelto un altro tavolo
     */
    private void updateGuestInfo(boolean tableChanged) {
        RestaurantTable table = (RestaurantTable) tableCombo.getSelectedItem();
        Reservation r = table == null ? null
                : model.findReservationForTable(table.getId(), LocalDateTime.now());
        if (table == null) {
            draft.setGuestAllergens(null);
            guestLabel.setText("Nessun tavolo in piantina.");
            guestLabel.setForeground(Palette.TEXT_MUTED);
        } else if (r == null) {
            draft.setTableNumber(table.getNumber());
            draft.setReservationId(0);
            draft.setGuestName("");
            draft.setGuestAllergens(null);
            guestLabel.setText("Tavolo " + table.getNumber() + " — nessuna prenotazione in corso.");
            guestLabel.setForeground(Palette.TEXT_MUTED);
        } else {
            draft.setTableNumber(table.getNumber());
            draft.setReservationId(r.getId());
            draft.setGuestName(r.getGuestName());
            draft.setGuestAllergens(r.getAllergens());
            StringBuilder sb = new StringBuilder(escape(r.getGuestName()))
                    .append(" · ").append(r.getPartySize()).append(" coperti");
            if (r.hasAllergens()) {
                sb.append(" · <b>allergie: ").append(codes(r.getAllergens())).append("</b>");
            }
            guestLabel.setText(Ui.wrapped(sb.toString(), 320));
            guestLabel.setForeground(r.hasAllergens() ? Palette.DANGER : Palette.TEXT_MUTED);
        }

        // Al cliente fidelizzato si propone una priorità più alta, ma solo quando
        // si sceglie il tavolo: se il cameriere la cambia a mano, la sua scelta
        // resta. Se invece l'aveva alzata il programma e si passa a un tavolo
        // qualunque, torna normale da sola invece di "trascinarsi" al tavolo dopo.
        if (tableChanged) {
            boolean loyal = r != null && r.isLoyaltyMember();
            if (loyal && draft.getPriority() == TicketPriority.NORMALE) {
                setPriorityAutomatically(TicketPriority.ALTA, true);
            } else if (!loyal && priorityRaisedAutomatically) {
                setPriorityAutomatically(TicketPriority.NORMALE, false);
            }
        }
        refreshDraft();
        menuList.repaint();
    }

    private void setPriorityAutomatically(TicketPriority priority, boolean raised) {
        settingPriority = true;
        priorityCombo.setSelectedItem(priority);
        settingPriority = false;
        priorityRaisedAutomatically = raised;
    }

    private void refreshMenu() {
        Object selected = courseFilter.getSelectedItem();
        List<Menu.Dish> dishes = (selected instanceof Course course)
                ? Menu.byCourse(course) : Menu.all();
        DefaultListModel<Menu.Dish> listModel = new DefaultListModel<>();
        for (Menu.Dish dish : dishes) {
            listModel.addElement(dish);
        }
        menuList.setModel(listModel);
    }

    private void addSelectedDish() {
        Menu.Dish dish = menuList.getSelectedValue();
        if (dish == null) {
            return;
        }
        // Se il piatto è già in comanda si incrementa la quantita' invece di
        // aggiungere una seconda riga identica.
        for (TicketItem item : draft.getItems()) {
            if (item.getName().equals(dish.getName())) {
                item.setQuantity(item.getQuantity() + 1);
                refreshDraft();
                return;
            }
        }
        draft.addItem(dish.toTicketItem(1));
        refreshDraft();
    }

    private void refreshDraft() {
        draftItems.removeAll();

        if (draft.getItems().isEmpty()) {
            JLabel empty = Ui.muted("<html>Nessun piatto selezionato.<br>"
                    + "Scegli dal menu a sinistra.</html>");
            draftItems.add(Ui.vGap(20));
            draftItems.add(Ui.alignLeft(empty));
        } else {
            for (TicketItem item : new ArrayList<>(draft.getItems())) {
                draftItems.add(Ui.alignLeft(buildDraftRow(item)));
                draftItems.add(Ui.vGap(4));
            }
        }

        int conflicts = 0;
        for (TicketItem item : draft.getItems()) {
            if (!draft.conflictsOf(item).isEmpty()) {
                conflicts++;
            }
        }
        StringBuilder summary = new StringBuilder(draft.getTotalQuantity() + " piatti");
        Set<Allergen> contained = draft.getAllAllergens();
        if (!contained.isEmpty()) {
            summary.append("  ·  contiene: ").append(codes(contained));
        }
        if (conflicts > 0) {
            draftSummary.setText("<html>" + summary + "<br><b>" + conflicts
                    + (conflicts == 1 ? " piatto contiene" : " piatti contengono")
                    + " allergeni dichiarati dal cliente</b></html>");
            draftSummary.setForeground(Palette.DANGER);
        } else {
            draftSummary.setText(summary.toString());
            draftSummary.setForeground(Palette.TEXT_MUTED);
        }

        draftItems.revalidate();
        draftItems.repaint();
    }

    private JComponent buildDraftRow(TicketItem item) {
        Set<Allergen> conflicts = draft.conflictsOf(item);
        boolean danger = !conflicts.isEmpty();

        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(330, 34));

        JLabel name = new JLabel(item.getQuantity() + "× " + item.getName());
        name.setFont(danger ? Theme.bold(12) : Theme.regular(12));
        name.setForeground(danger ? Palette.DANGER : Palette.TEXT);
        if (danger) {
            name.setToolTipText("Contiene " + codes(conflicts) + ": il cliente ha dichiarato di non poterlo mangiare");
        }
        row.add(name, BorderLayout.CENTER);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        controls.setOpaque(false);

        if (item.hasAllergens()) {
            controls.add(new Badge(danger ? codes(conflicts) : item.getAllergenCodes(), Palette.DANGER, danger));
        }

        JButton minus = tinyButton("−");
        minus.addActionListener(e -> {
            if (item.getQuantity() > 1) {
                item.setQuantity(item.getQuantity() - 1);
            } else {
                draft.removeItem(item);
            }
            refreshDraft();
        });
        JButton plus = tinyButton("+");
        plus.addActionListener(e -> {
            item.setQuantity(item.getQuantity() + 1);
            refreshDraft();
        });
        JButton remove = tinyButton("×");
        remove.setForeground(Palette.DANGER);
        remove.addActionListener(e -> {
            draft.removeItem(item);
            refreshDraft();
        });

        controls.add(minus);
        controls.add(plus);
        controls.add(remove);
        row.add(controls, BorderLayout.EAST);
        return row;
    }

    private JButton tinyButton(String text) {
        JButton button = new JButton(text);
        button.setFont(Theme.bold(12));
        button.setFocusPainted(false);
        button.setPreferredSize(new Dimension(28, 24));
        button.setBorder(BorderFactory.createEmptyBorder());
        button.setBackground(Palette.SURFACE_2);
        button.setForeground(Palette.TEXT);
        return button;
    }

    /**
     * Invio della comanda.
     *
     * La comanda parte SENZA codice: è il server ad assegnarlo, così due
     * postazioni che inviano nello stesso momento non possono generare due
     * comande con lo stesso numero.
     */
    private void sendDraft() {
        if (draft.getItems().isEmpty()) {
            JOptionPane.showMessageDialog(this, "La comanda è vuota.",
                    "Niente da inviare", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (tableCombo.getSelectedItem() == null) {
            JOptionPane.showMessageDialog(this, "Scegli prima il tavolo.",
                    "Tavolo mancante", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!client.isConnected()) {
            JOptionPane.showMessageDialog(this,
                    "Il server delle comande non è raggiungibile.\n\n"
                    + "Avvia il server (RistoManager → Server comande): questa postazione\n"
                    + "si ricollega da sola entro pochi secondi. La comanda resta qui.",
                    "Cucina non raggiungibile", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (draft.hasAllergyConflicts() && !confirmAllergyConflicts()) {
            return;
        }

        draft.setNote(noteField.getText().trim());
        draft.setCreatedAt(LocalDateTime.now());
        draft.setCreatedBy(model.getCurrentUser() == null ? ""
                : model.getCurrentUser().getFullName());

        if (!client.sendNewTicket(draft)) {
            JOptionPane.showMessageDialog(this,
                    "Invio non riuscito: la connessione si è appena interrotta.\n"
                    + "La comanda è ancora qui: riprova fra qualche secondo.",
                    "Comanda non inviata", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Toast.show(this, "Comanda inviata · Tavolo " + draft.getTableNumber(),
                draft.getTotalQuantity() + " piatti"
                        + (draft.hasGuestAllergens() ? " · allergie segnalate alla cucina" : ""),
                Palette.INFO);
        draft = newDraft();
        updateGuestInfo(false);
    }

    /** Un'unica conferma esplicita, con l'elenco dei piatti in conflitto. */
    private boolean confirmAllergyConflicts() {
        StringBuilder list = new StringBuilder();
        for (TicketItem item : draft.getItems()) {
            Set<Allergen> conflicts = draft.conflictsOf(item);
            if (!conflicts.isEmpty()) {
                list.append("   •  ").append(item.getName())
                    .append("  (").append(codes(conflicts)).append(")\n");
            }
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "Il cliente ha dichiarato allergie a ingredienti contenuti in:\n\n" + list
                + "\nInviare comunque? In cucina questi piatti saranno evidenziati in rosso.",
                "Attenzione: allergie", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return choice == JOptionPane.YES_OPTION;
    }

    private void refreshSent() {
        sentBoard.removeAll();
        List<Ticket> tickets = new ArrayList<>(model.getTickets());
        tickets.removeIf(t -> t.getStatus() == TicketStatus.SERVITA
                || t.getStatus() == TicketStatus.ANNULLATA);
        Collections.sort(tickets);

        if (tickets.isEmpty()) {
            sentBoard.add(Ui.vGap(16));
            sentBoard.add(Ui.alignLeft(Ui.muted(
                    "<html>Nessuna comanda in corso.<br>Le comande inviate compaiono qui<br>"
                    + "con il loro stato in tempo reale.</html>")));
        } else {
            for (Ticket ticket : tickets) {
                TicketCard card = new TicketCard(ticket, TicketCard.Mode.SALA,
                        newStatus -> markServed(ticket, newStatus));
                card.setAlignmentX(LEFT_ALIGNMENT);
                sentBoard.add(card);
                sentBoard.add(Ui.vGap(10));
            }
        }
        sentBoard.revalidate();
        sentBoard.repaint();
    }

    private void markServed(Ticket ticket, TicketStatus newStatus) {
        if (!client.isConnected()) {
            JOptionPane.showMessageDialog(this,
                    "Il server delle comande non è raggiungibile: lo stato non può essere\n"
                    + "aggiornato adesso. Riprova appena la connessione torna.",
                    "Cucina non raggiungibile", JOptionPane.WARNING_MESSAGE);
            return;
        }
        ticket.advanceTo(newStatus);
        client.sendTicketUpdate(ticket);
        refreshSent();
    }

    @Override
    public void onModelChanged(ModelEvent event) {
        if (event.is(ModelEvent.Type.TICKETS_CHANGED)) {
            refreshSent();
        } else if (event.is(ModelEvent.Type.FLOOR_PLAN_CHANGED)) {
            refreshTables();
        } else if (event.is(ModelEvent.Type.RESERVATIONS_CHANGED)) {
            // Una prenotazione nuova o modificata (anche da un'altra postazione)
            // può cambiare chi è seduto al tavolo e quali allergie dichiara.
            updateGuestInfo(false);
            tableCombo.repaint();
        }
    }

    // ------------------------------------------------------------------
    // Utilità
    // ------------------------------------------------------------------

    private Set<Allergen> conflictsFor(Menu.Dish dish) {
        Set<Allergen> conflicts = EnumSet.noneOf(Allergen.class);
        if (dish != null) {
            conflicts.addAll(dish.getAllergens());
            conflicts.retainAll(draft.getGuestAllergens());
        }
        return conflicts;
    }

    private static String codes(Set<Allergen> allergens) {
        StringBuilder sb = new StringBuilder();
        for (Allergen a : allergens) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(a.getShortCode());
        }
        return sb.toString();
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------
    // Renderer
    // ------------------------------------------------------------------

    /**
     * Riga del menu: nome, prezzo e sigle degli allergeni.
     *
     * È una classe interna (non statica) perché deve conoscere la comanda in
     * composizione: un piatto che contiene ciò a cui il cliente del tavolo
     * scelto è allergico viene disegnato in rosso, prima ancora di aggiungerlo.
     */
    private class DishRenderer extends JPanel implements ListCellRenderer<Menu.Dish> {

        private static final long serialVersionUID = 1L;

        private transient Menu.Dish dish;
        private boolean selected;

        @Override
        public Component getListCellRendererComponent(JList<? extends Menu.Dish> list,
                Menu.Dish value, int index, boolean isSelected, boolean cellHasFocus) {
            this.dish = value;
            this.selected = isSelected;
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Set<Allergen> conflicts = conflictsFor(dish);
            boolean danger = !conflicts.isEmpty();

            g2.setColor(selected ? Palette.alpha(Palette.ACCENT, 40) : Palette.SURFACE);
            g2.fillRect(0, 0, getWidth(), getHeight());
            if (danger) {
                g2.setColor(Palette.alpha(Palette.DANGER, 26));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(Palette.DANGER);
                g2.fillRect(0, 0, 3, getHeight());
            }
            g2.setColor(Palette.alpha(Palette.BORDER, 120));
            g2.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);

            if (dish == null) {
                g2.dispose();
                return;
            }

            g2.setFont(Theme.regular(13));
            g2.setColor(danger ? Palette.DANGER : Palette.TEXT);
            g2.drawString(dish.getName(), 12, 18);

            if (danger) {
                g2.setFont(Theme.bold(11));
                g2.setColor(Palette.DANGER);
                g2.drawString("Allergia del cliente: " + codes(conflicts), 12, 32);
            } else {
                g2.setFont(Theme.regular(11));
                g2.setColor(Palette.TEXT_MUTED);
                g2.drawString(dish.getCourse().getLabel(), 12, 32);
            }

            String price = String.format("%.2f €", dish.getPrice());
            g2.setFont(Theme.bold(12));
            g2.setColor(Palette.ACCENT);
            int priceWidth = g2.getFontMetrics().stringWidth(price);
            g2.drawString(price, getWidth() - priceWidth - 14, 18);

            if (!dish.getAllergens().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Allergen a : dish.getAllergens()) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(a.getShortCode());
                }
                g2.setFont(Theme.bold(10));
                g2.setColor(danger ? Palette.DANGER : Palette.alpha(Palette.DANGER, 170));
                int width = g2.getFontMetrics().stringWidth(sb.toString());
                g2.drawString(sb.toString(), getWidth() - width - 14, 32);
            }
            g2.dispose();
        }
    }

    /** Voce della tendina dei tavoli: numero e, se c'è, chi è seduto adesso. */
    private class TableRenderer extends DefaultListCellRenderer {

        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof RestaurantTable t) {
                Reservation r = model.findReservationForTable(t.getId(), LocalDateTime.now());
                setText("Tavolo " + t.getNumber() + "  ·  "
                        + (r == null ? t.getSeats() + " posti, libero"
                                     : r.getGuestName() + " (" + r.getPartySize() + ")"));
                if (r != null && r.hasAllergens() && !isSelected) {
                    setForeground(Palette.DANGER);
                }
            }
            return this;
        }
    }

    /** Voce della tendina delle priorità, con il proprio colore. */
    private static class PriorityRenderer extends DefaultListCellRenderer {

        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof TicketPriority priority) {
                setText(priority.getLabel());
                Color color = Palette.forPriority(priority);
                setForeground(isSelected ? Palette.TEXT : color);
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 3, 0, 0, color),
                        BorderFactory.createEmptyBorder(4, 8, 4, 4)));
            }
            return this;
        }
    }
}
