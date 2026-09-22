package it.unical.igpe.ristorante.view.reservations;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.TableRowSorter;

import it.unical.igpe.ristorante.model.ModelEvent;
import it.unical.igpe.ristorante.model.ModelListener;
import it.unical.igpe.ristorante.model.Permission;
import it.unical.igpe.ristorante.model.Reservation;
import it.unical.igpe.ristorante.model.ReservationStatus;
import it.unical.igpe.ristorante.model.RestaurantModel;
import it.unical.igpe.ristorante.model.ValidationException;
import it.unical.igpe.ristorante.model.floor.RestaurantTable;
import it.unical.igpe.ristorante.view.common.Badge;
import it.unical.igpe.ristorante.view.common.Card;
import it.unical.igpe.ristorante.view.common.Palette;
import it.unical.igpe.ristorante.view.common.Theme;
import it.unical.igpe.ristorante.view.common.Ui;

/**
 * Schermata delle prenotazioni: elenco, filtri, riepilogo della giornata e
 * pannello di dettaglio con le azioni sulla prenotazione selezionata.
 *
 * Implementa ModelListener: quando il Model segnala un cambiamento la tabella
 * si ricarica da sola. Nessun'altra parte del programma deve ricordarsi di
 * chiamare "aggiorna la tabella" dopo aver salvato qualcosa.
 */
public class ReservationsPanel extends JPanel implements ModelListener {

    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ITALIAN);

    private final transient RestaurantModel model;
    private final Window owner;

    private final ReservationTableModel tableModel = new ReservationTableModel();
    private final JTable table = new JTable(tableModel) {
        private static final long serialVersionUID = 1L;

        /** Con la tabella vuota si spiega il perché, invece di un riquadro vuoto. */
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getRowCount() == 0) {
                paintEmptyMessage(g, getWidth());
            }
        }
    };
    private final transient TableRowSorter<ReservationTableModel> sorter = new TableRowSorter<>(tableModel);

    private final JTextField searchField = new JTextField();
    private final JLabel dateLabel = new JLabel();
    private final JToggleButton allDatesButton = new JToggleButton("Tutte le date");

    private final JLabel kpiCount = new JLabel("0");
    private final JLabel kpiCovers = new JLabel("0");
    private final JLabel kpiChairs = new JLabel("0");
    private final JLabel kpiAllergens = new JLabel("0");

    private final DetailPane detail = new DetailPane();

    private LocalDate currentDate = LocalDate.now();

    public ReservationsPanel(RestaurantModel model, Window owner) {
        this.model = model;
        this.owner = owner;

        setLayout(new BorderLayout());
        setBackground(Palette.BG);
        setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));

        add(buildHeader(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        installShortcuts();

        model.addListener(this);
        reload();
    }

    /** Da chiamare quando la finestra si chiude: smette di ascoltare il Model. */
    public void detach() {
        model.removeListener(this);
    }

    // ------------------------------------------------------------------
    // Intestazione
    // ------------------------------------------------------------------

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 14, 0));

        // Riga 1: navigazione fra le giornate e comandi
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);

        JPanel left = Ui.row();
        JButton prev = Ui.toolButton("<");
        prev.setToolTipText("Giorno precedente");
        JButton next = Ui.toolButton(">");
        next.setToolTipText("Giorno successivo");
        JButton today = Ui.toolButton("Oggi");

        dateLabel.setFont(Theme.bold(17));
        dateLabel.setForeground(Palette.TEXT);
        dateLabel.setPreferredSize(new Dimension(260, 26));

        // Un pulsante a due stati e non un pulsante semplice: si deve vedere
        // se l'elenco mostra una sola giornata o tutto l'archivio.
        allDatesButton.setFont(Theme.regular(12));
        allDatesButton.setFocusPainted(false);
        allDatesButton.setToolTipText("Mostra le prenotazioni di tutti i giorni");

        prev.addActionListener(e -> goToDate(currentDate.minusDays(1)));
        next.addActionListener(e -> goToDate(currentDate.plusDays(1)));
        today.addActionListener(e -> goToDate(LocalDate.now()));
        allDatesButton.addActionListener(e -> reload());

        left.add(prev);
        left.add(next);
        left.add(Ui.hGap(6));
        left.add(dateLabel);
        left.add(today);
        left.add(allDatesButton);
        top.add(left, BorderLayout.WEST);

        JPanel right = Ui.rowRight();
        searchField.setPreferredSize(new Dimension(250, 34));
        searchField.putClientProperty("JTextField.placeholderText", "Cerca nome, telefono, codice… (Ctrl+F)");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        right.add(searchField);

        JButton newButton = Ui.primary("Nuova prenotazione");
        newButton.setEnabled(model.can(Permission.EDIT_RESERVATIONS));
        newButton.setToolTipText(newButton.isEnabled()
                ? "Inserisci una nuova prenotazione (Ctrl+N)"
                : "Il tuo ruolo non consente di inserire prenotazioni");
        newButton.addActionListener(e -> openDialog(null));
        right.add(newButton);

        top.add(right, BorderLayout.EAST);
        header.add(top, BorderLayout.NORTH);

        // Riga 2: indicatori di sintesi
        JPanel kpis = new JPanel(new GridLayout(1, 4, 12, 0));
        kpis.setOpaque(false);
        kpis.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
        kpis.setPreferredSize(new Dimension(10, 88));
        kpis.add(kpiCard("Prenotazioni", kpiCount, Palette.ACCENT));
        kpis.add(kpiCard("Coperti attesi", kpiCovers, Palette.TEAL));
        kpis.add(kpiCard("Seggioloni e passeggini", kpiChairs, Palette.INFO));
        kpis.add(kpiCard("Con allergeni", kpiAllergens, Palette.DANGER));
        header.add(kpis, BorderLayout.CENTER);

        return header;
    }

    private JComponent kpiCard(String caption, JLabel valueLabel, Color color) {
        Card card = new Card(new BorderLayout(0, 2));
        card.fill(Palette.SURFACE).border(Palette.BORDER);
        card.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        JLabel title = Ui.sectionTitle(caption);
        valueLabel.setFont(Theme.bold(26));
        valueLabel.setForeground(color);

        // BorderLayout invece di BoxLayout: la cifra grande ha bisogno di tutta
        // l'altezza rimanente, altrimenti viene tagliata sotto la linea di base.
        card.add(title, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);
        return card;
    }

    // ------------------------------------------------------------------
    // Tabella e dettaglio
    // ------------------------------------------------------------------

    private JComponent buildCenter() {
        JPanel center = new JPanel(new BorderLayout(14, 0));
        center.setOpaque(false);

        table.setRowSorter(sorter);
        table.setRowHeight(38);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setBackground(Palette.SURFACE);
        table.setFillsViewportHeight(true);
        table.setDefaultRenderer(Reservation.class, new ReservationCellRenderer(model));

        table.getTableHeader().setFont(Theme.bold(11));
        table.getTableHeader().setBackground(Palette.SURFACE_2);
        table.getTableHeader().setForeground(Palette.TEXT_MUTED);
        table.getTableHeader().setReorderingAllowed(false);

        int[] widths = {70, 250, 80, 190, 130, 140, 150, 140};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        // Ogni colonna contiene lo stesso oggetto Reservation: senza un
        // comparatore dedicato l'ordinamento userebbe toString() e sarebbe
        // privo di senso per orario, coperti o tavolo.
        sorter.setComparator(ReservationTableModel.COL_TIME,
                Comparator.comparing(Reservation::getDateTime,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        sorter.setComparator(ReservationTableModel.COL_GUEST,
                Comparator.comparing(r -> ((Reservation) r).getGuestName(),
                        String.CASE_INSENSITIVE_ORDER));
        sorter.setComparator(ReservationTableModel.COL_PARTY,
                Comparator.comparingInt(r -> ((Reservation) r).getPartySize()));
        sorter.setComparator(ReservationTableModel.COL_EXTRA,
                Comparator.comparingInt(r -> ((Reservation) r).getHighChairs()
                        + ((Reservation) r).getStrollerSpaces()));
        sorter.setComparator(ReservationTableModel.COL_TABLE,
                Comparator.comparingInt(this::tableNumberOf));
        sorter.setComparator(ReservationTableModel.COL_STATUS,
                Comparator.comparing(r -> ((Reservation) r).getStatus().ordinal()));
        sorter.setComparator(ReservationTableModel.COL_ALLERGENS,
                Comparator.comparingInt(r -> ((Reservation) r).getAllergens().size()));
        sorter.setComparator(ReservationTableModel.COL_CONTACT,
                Comparator.comparing(r -> String.valueOf(((Reservation) r).getPhone())));

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                detail.show(selectedReservation());
            }
        });

        // Doppio clic sulla riga: apre la modifica.
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    editSelected();
                }
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(Palette.BORDER));
        scroll.getViewport().setBackground(Palette.SURFACE);

        center.add(scroll, BorderLayout.CENTER);
        center.add(detail, BorderLayout.EAST);
        return center;
    }

    /**
     * Scorciatoie da tastiera. Invio e Canc agiscono sulla riga selezionata;
     * Ctrl+N e Ctrl+F valgono in tutta la finestra, ma solo mentre questa
     * schermata è quella visibile.
     */
    private void installShortcuts() {
        Ui.bindKey(table, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, "ENTER", "modifica",
                this::editSelected);
        Ui.bindKey(table, JComponent.WHEN_FOCUSED, "DELETE", "elimina", () -> {
            Reservation r = selectedReservation();
            if (r != null && model.can(Permission.DELETE_RESERVATIONS)) {
                deleteReservation(r);
            }
        });
        Ui.bindKey(this, JComponent.WHEN_IN_FOCUSED_WINDOW, "ctrl N", "nuova-prenotazione", () -> {
            if (isShowing() && model.can(Permission.EDIT_RESERVATIONS)) {
                openDialog(null);
            }
        });
        Ui.bindKey(this, JComponent.WHEN_IN_FOCUSED_WINDOW, "ctrl F", "cerca", () -> {
            if (isShowing()) {
                searchField.requestFocusInWindow();
                searchField.selectAll();
            }
        });
    }

    private int tableNumberOf(Object value) {
        Reservation r = (Reservation) value;
        RestaurantTable t = model.getFloorPlan().findTableById(r.getTableId());
        return t == null ? Integer.MAX_VALUE : t.getNumber();
    }

    private Reservation selectedReservation() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        return tableModel.getReservationAt(table.convertRowIndexToModel(viewRow));
    }

    // ------------------------------------------------------------------
    // Dati
    // ------------------------------------------------------------------

    private boolean showAllDates() {
        return allDatesButton.isSelected();
    }

    private void goToDate(LocalDate date) {
        allDatesButton.setSelected(false);
        currentDate = date;
        reload();
    }

    private void reload() {
        List<Reservation> rows = showAllDates()
                ? new ArrayList<>(model.getReservations())
                : model.getReservationsForDate(currentDate);
        rows.sort(Comparator.naturalOrder());

        Reservation previouslySelected = selectedReservation();
        tableModel.setRows(rows);
        applyFilter();
        updateKpis(rows);

        String label = showAllDates() ? "Tutte le prenotazioni" : capitalize(currentDate.format(DAY));
        if (!showAllDates() && currentDate.equals(LocalDate.now())) {
            label = "Oggi, " + currentDate.getDayOfMonth() + " "
                    + currentDate.getMonth().getDisplayName(TextStyle.FULL, Locale.ITALIAN);
        }
        dateLabel.setText(label);

        // Si prova a mantenere selezionata la stessa prenotazione di prima.
        if (previouslySelected != null && selectReservation(previouslySelected.getId())) {
            return;
        }
        detail.show(null);
    }

    /** Seleziona una prenotazione per id; false se non è fra le righe visibili. */
    private boolean selectReservation(int id) {
        int modelRow = -1;
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            if (tableModel.getReservationAt(i).getId() == id) {
                modelRow = i;
                break;
            }
        }
        if (modelRow < 0) {
            return false;
        }
        int viewRow = table.convertRowIndexToView(modelRow);
        if (viewRow < 0) {
            return false;
        }
        table.setRowSelectionInterval(viewRow, viewRow);
        table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
        return true;
    }

    private void updateKpis(List<Reservation> rows) {
        int covers = 0;
        int extras = 0;
        int withAllergens = 0;
        for (Reservation r : rows) {
            if (r.getStatus().occupiesTable()) {
                covers += r.getPartySize();
            }
            extras += r.getHighChairs() + r.getStrollerSpaces();
            if (r.hasAllergens()) {
                withAllergens++;
            }
        }
        kpiCount.setText(String.valueOf(rows.size()));
        kpiCovers.setText(String.valueOf(covers));
        kpiChairs.setText(String.valueOf(extras));
        kpiAllergens.setText(String.valueOf(withAllergens));
    }

    /**
     * Filtro testuale.
     *
     * RowFilter agisce sulla VISTA della tabella e non sui dati: le righe
     * nascoste restano nel modello, quindi cancellare il testo cercato le fa
     * ricomparire senza bisogno di rileggere il database.
     */
    private void applyFilter() {
        String query = searchField.getText().trim().toLowerCase(Locale.ITALIAN);
        if (query.isEmpty()) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(new RowFilter<ReservationTableModel, Integer>() {
                @Override
                public boolean include(Entry<? extends ReservationTableModel, ? extends Integer> entry) {
                    Reservation r = entry.getModel().getReservationAt(entry.getIdentifier());
                    if (r == null) {
                        return false;
                    }
                    return contains(r.getGuestName(), query)
                            || contains(r.getPhone(), query)
                            || contains(r.getEmail(), query)
                            || contains(r.getLoyaltyId(), query)
                            || contains(r.getNotes(), query);
                }
            });
        }
        table.repaint();
    }

    private void paintEmptyMessage(Graphics g, int width) {
        String text;
        if (!searchField.getText().isBlank()) {
            text = "Nessuna prenotazione corrisponde alla ricerca.";
        } else if (showAllDates()) {
            text = "Nessuna prenotazione in archivio.";
        } else if (!currentDate.isBefore(LocalDate.now()) && model.can(Permission.EDIT_RESERVATIONS)) {
            text = "Nessuna prenotazione per questa giornata. Ctrl+N per inserirne una.";
        } else {
            text = "Nessuna prenotazione per questa giornata.";
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setFont(Theme.regular(13));
        g2.setColor(Palette.TEXT_MUTED);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, Math.max(12, (width - fm.stringWidth(text)) / 2), 56);
        g2.dispose();
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ITALIAN).contains(needle);
    }

    private static String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // ------------------------------------------------------------------
    // Azioni
    // ------------------------------------------------------------------

    private void editSelected() {
        Reservation r = selectedReservation();
        if (r != null && model.can(Permission.EDIT_RESERVATIONS)) {
            openDialog(r);
        }
    }

    private void openDialog(Reservation existing) {
        ReservationDialog dialog = new ReservationDialog(owner, model, existing);
        dialog.setVisible(true);
        if (dialog.isSaved()) {
            Reservation saved = dialog.getReservation();
            if (saved.getDateTime() != null && !showAllDates()) {
                currentDate = saved.getDateTime().toLocalDate();
            }
            reload();
            selectReservation(saved.getId());
        }
    }

    /**
     * Cambio di stato dal pannello di dettaglio.
     *
     * Si modifica una copia: se il Model rifiuta il salvataggio, la
     * prenotazione nell'elenco non resta con uno stato che nessuno ha salvato.
     */
    private void changeStatus(Reservation reservation, ReservationStatus status) {
        Reservation updated = reservation.copy();
        updated.setStatus(status);
        try {
            model.saveReservation(updated);
        } catch (ValidationException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(),
                    "Operazione non consentita", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void deleteReservation(Reservation reservation) {
        int choice = JOptionPane.showConfirmDialog(this,
                "Eliminare definitivamente la prenotazione di " + reservation.getGuestName() + "?",
                "Conferma eliminazione", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            model.deleteReservation(reservation);
        } catch (ValidationException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(),
                    "Operazione non consentita", JOptionPane.WARNING_MESSAGE);
        }
    }

    @Override
    public void onModelChanged(ModelEvent event) {
        if (event.is(ModelEvent.Type.RESERVATIONS_CHANGED)
                || event.is(ModelEvent.Type.FLOOR_PLAN_CHANGED)) {
            reload();
        }
    }

    // ------------------------------------------------------------------
    // Pannello di dettaglio
    // ------------------------------------------------------------------

    /** Colonna di destra: tutti i campi della prenotazione selezionata. */
    private class DetailPane extends Card {

        private static final long serialVersionUID = 1L;

        private final JPanel body = Ui.column();

        DetailPane() {
            super(new BorderLayout());
            fill(Palette.SURFACE).border(Palette.BORDER);
            setPreferredSize(new Dimension(330, 10));
            setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
            body.setOpaque(false);
            add(body, BorderLayout.NORTH);
        }

        void show(Reservation r) {
            body.removeAll();

            if (r == null) {
                JLabel empty = new JLabel("<html><div style='text-align:center;'>"
                        + "Seleziona una prenotazione<br>per vederne i dettagli.</div></html>",
                        SwingConstants.CENTER);
                empty.setFont(Theme.regular(12));
                empty.setForeground(Palette.TEXT_MUTED);
                empty.setAlignmentX(LEFT_ALIGNMENT);
                body.add(Ui.vGap(40));
                body.add(empty);
                revalidate();
                repaint();
                return;
            }

            JLabel name = new JLabel(r.getGuestName());
            name.setFont(Theme.bold(18));
            name.setForeground(Palette.TEXT);
            body.add(Ui.alignLeft(name));
            body.add(Ui.vGap(4));

            String when = r.getDateTime() == null ? "—"
                    : capitalize(r.getDateTime().format(
                            DateTimeFormatter.ofPattern("EEEE d MMMM • HH:mm", Locale.ITALIAN)));
            body.add(Ui.alignLeft(Ui.muted(when + "  ·  " + r.getDurationMinutes() + " min")));
            body.add(Ui.vGap(16));

            RestaurantTable t = model.getFloorPlan().findTableById(r.getTableId());
            String tableText;
            if (t != null) {
                tableText = "T" + t.getNumber() + " · " + t.getSeats() + " posti"
                        + (t.isAccessible() ? " · accessibile" : "");
            } else if (r.getTableId() != 0) {
                tableText = "Tavolo rimosso dalla piantina: da riassegnare";
            } else {
                tableText = "Da assegnare";
            }
            body.add(Ui.alignLeft(Ui.field("Tavolo", tableText)));
            body.add(Ui.vGap(12));
            body.add(Ui.alignLeft(Ui.field("Coperti", r.getPartySize() + " persone")));
            body.add(Ui.vGap(12));
            body.add(Ui.alignLeft(Ui.field("Seggioloni / passeggini",
                    r.getHighChairs() + " / " + r.getStrollerSpaces())));
            body.add(Ui.vGap(12));
            body.add(Ui.alignLeft(Ui.field("Telefono", r.getPhone())));
            body.add(Ui.vGap(12));
            body.add(Ui.alignLeft(Ui.field("Email", r.getEmail())));
            body.add(Ui.vGap(12));
            body.add(Ui.alignLeft(Ui.field("Codice fedeltà",
                    r.isLoyaltyMember() ? r.getLoyaltyId() : "Cliente non registrato")));
            body.add(Ui.vGap(12));

            if (r.hasAllergens()) {
                body.add(Ui.alignLeft(Ui.sectionTitle("Allergeni dichiarati")));
                body.add(Ui.vGap(6));
                JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
                chips.setOpaque(false);
                chips.setMaximumSize(new Dimension(300, 200));
                for (var a : r.getAllergens()) {
                    chips.add(new Badge(a.getLabel(), Palette.DANGER));
                }
                body.add(Ui.alignLeft(chips));
                body.add(Ui.vGap(12));
            }

            if (r.getNotes() != null && !r.getNotes().isBlank()) {
                body.add(Ui.alignLeft(Ui.sectionTitle("Note")));
                body.add(Ui.vGap(4));
                JLabel notes = new JLabel(Ui.wrapped(r.getNotes(), 250));
                notes.setFont(Theme.regular(12));
                notes.setForeground(Palette.TEXT);
                body.add(Ui.alignLeft(notes));
                body.add(Ui.vGap(12));
            }

            body.add(Ui.alignLeft(Ui.muted("Inserita da " + r.getCreatedBy() + " il "
                    + (r.getCreatedAt() == null ? "—"
                        : r.getCreatedAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))))));
            body.add(Ui.vGap(16));
            body.add(Ui.alignLeft(Ui.separator()));
            body.add(Ui.vGap(14));

            body.add(Ui.alignLeft(buildActions(r)));

            revalidate();
            repaint();
        }

        private JComponent buildActions(Reservation r) {
            JPanel actions = new JPanel();
            actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
            actions.setOpaque(false);
            actions.setMaximumSize(new Dimension(300, 220));

            boolean canEdit = model.can(Permission.EDIT_RESERVATIONS);
            boolean canDelete = model.can(Permission.DELETE_RESERVATIONS);
            ReservationStatus status = r.getStatus();

            JPanel statusRow = new JPanel(new GridLayout(1, 3, 6, 0));
            statusRow.setOpaque(false);
            statusRow.setMaximumSize(new Dimension(300, 34));
            statusRow.add(actionButton("Conferma", "Il cliente ha confermato",
                    canEdit && status == ReservationStatus.ATTESA,
                    () -> changeStatus(r, ReservationStatus.CONFERMATA)));
            statusRow.add(actionButton("Arrivato", "Il cliente è al tavolo",
                    canEdit && status.occupiesTable() && status != ReservationStatus.ARRIVATA,
                    () -> changeStatus(r, ReservationStatus.ARRIVATA)));
            statusRow.add(actionButton("Completa", "Il cliente ha lasciato il tavolo",
                    canEdit && status == ReservationStatus.ARRIVATA,
                    () -> changeStatus(r, ReservationStatus.COMPLETATA)));
            actions.add(Ui.alignLeft(statusRow));
            actions.add(Ui.vGap(8));

            JPanel editRow = new JPanel(new GridLayout(1, 3, 6, 0));
            editRow.setOpaque(false);
            editRow.setMaximumSize(new Dimension(300, 34));

            JButton edit = Ui.secondary("Modifica");
            edit.setEnabled(canEdit);
            edit.setToolTipText("Anche con Invio o doppio clic sulla riga");
            edit.addActionListener(e -> openDialog(r));
            editRow.add(edit);
            editRow.add(actionButton("Disdici", "Il cliente ha annullato la prenotazione",
                    canEdit && status.occupiesTable(),
                    () -> changeStatus(r, ReservationStatus.ANNULLATA)));
            editRow.add(actionButton("No-show", "Il cliente non si è presentato",
                    canEdit && (status == ReservationStatus.ATTESA || status == ReservationStatus.CONFERMATA),
                    () -> changeStatus(r, ReservationStatus.NO_SHOW)));
            actions.add(Ui.alignLeft(editRow));
            actions.add(Ui.vGap(8));

            JButton delete = Ui.danger("Elimina definitivamente");
            delete.setMaximumSize(new Dimension(300, 34));
            delete.setEnabled(canDelete);
            delete.setToolTipText(canDelete ? "Anche con il tasto Canc"
                    : "Solo un amministratore può eliminare una prenotazione");
            delete.addActionListener(e -> deleteReservation(r));
            actions.add(Ui.alignLeft(delete));

            return actions;
        }

        private JButton actionButton(String text, String tooltip, boolean enabled, Runnable action) {
            JButton button = Ui.toolButton(text);
            button.setToolTipText(tooltip);
            button.setEnabled(enabled);
            button.addActionListener(e -> action.run());
            return button;
        }
    }
}
