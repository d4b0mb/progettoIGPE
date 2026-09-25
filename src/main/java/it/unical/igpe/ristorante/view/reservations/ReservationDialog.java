package it.unical.igpe.ristorante.view.reservations;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Window;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerDateModel;
import javax.swing.SpinnerNumberModel;

import it.unical.igpe.ristorante.model.Allergen;
import it.unical.igpe.ristorante.model.Permission;
import it.unical.igpe.ristorante.model.Reservation;
import it.unical.igpe.ristorante.model.ReservationStatus;
import it.unical.igpe.ristorante.model.RestaurantModel;
import it.unical.igpe.ristorante.model.ValidationException;
import it.unical.igpe.ristorante.model.floor.RestaurantTable;
import it.unical.igpe.ristorante.view.common.Palette;
import it.unical.igpe.ristorante.view.common.Theme;
import it.unical.igpe.ristorante.view.common.Ui;

/**
 * Finestra di inserimento e modifica di una prenotazione.
 *
 * È una finestra MODALE: finché è aperta il resto dell'applicazione non
 * riceve eventi. È la scelta giusta qui, perché modificare la stessa
 * prenotazione da due finestre contemporaneamente non avrebbe senso.
 *
 * La finestra non salva niente da sola: prepara un oggetto Reservation e lo
 * consegna al Model, che è l'unico a validare e a scrivere. Se il Model
 * rifiuta, l'errore viene mostrato e la finestra resta aperta con i dati
 * inseriti, senza far riscrivere tutto all'utente.
 */
public class ReservationDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private static final String NO_TABLE = "Da assegnare";

    private final transient RestaurantModel model;
    /**
     * La prenotazione su cui lavora la finestra. In modifica è una COPIA:
     * se il Model rifiuta il salvataggio e poi si chiude la finestra, la
     * prenotazione nell'elenco resta esattamente com'era.
     */
    private final transient Reservation reservation;
    private final boolean creating;

    private final JTextField nameField = new JTextField();
    private final JTextField phoneField = new JTextField();
    private final JTextField emailField = new JTextField();
    private final JTextField loyaltyField = new JTextField();
    private final JTextArea notesArea = new JTextArea(3, 20);

    private final JSpinner dateSpinner;
    private final JSpinner timeSpinner;
    private final JSpinner durationSpinner = new JSpinner(new SpinnerNumberModel(120, 30, 360, 15));
    private final JSpinner partySpinner = new JSpinner(new SpinnerNumberModel(2, 1, 40, 1));
    private final JSpinner highChairSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 10, 1));
    private final JSpinner strollerSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 6, 1));

    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEEE", Locale.ITALIAN);

    private final JComboBox<Object> tableCombo = new JComboBox<>();
    private final JLabel tableCaption = new JLabel("Tavolo");
    private final JLabel tableHint = new JLabel(" ");
    private final JLabel dateCaption = new JLabel("Data *");
    private final JComboBox<ReservationStatus> statusCombo =
            new JComboBox<>(ReservationStatus.values());

    private final JLabel errorLabel = new JLabel(" ");
    private final Map<Allergen, JCheckBox> allergenBoxes = new EnumMap<>(Allergen.class);

    private boolean saved;
    /** true mentre si caricano i campi: i loro eventi non devono ricalcolare i tavoli a metà. */
    private boolean loading = true;
    /** true mentre la tendina dei tavoli viene riempita da codice. */
    private boolean refreshingTables;

    public ReservationDialog(Window owner, RestaurantModel model, Reservation existing) {
        super(owner, existing == null ? "Nuova prenotazione" : "Modifica prenotazione",
                ModalityType.APPLICATION_MODAL);
        this.model = model;
        this.creating = (existing == null);
        this.reservation = creating ? new Reservation() : existing.copy();

        LocalDateTime initial = reservation.getDateTime() != null
                ? reservation.getDateTime()
                : LocalDateTime.now().withMinute(0).withSecond(0).withNano(0).plusHours(1);

        Date initialDate = Date.from(initial.atZone(ZoneId.systemDefault()).toInstant());
        dateSpinner = new JSpinner(new SpinnerDateModel(initialDate, null, null, Calendar.DAY_OF_MONTH));
        // Solo cifre e "/" nel campo: il giorno della settimana (variabile in
        // lunghezza: "lunedì" contro "mercoledì") sposta la posizione delle
        // cifre e delle "/" mentre si scrive se è dentro il testo digitabile.
        // Va mostrato a parte, non impastato nel formato di modifica.
        dateSpinner.setEditor(new JSpinner.DateEditor(dateSpinner, "dd/MM/yyyy"));
        timeSpinner = new JSpinner(new SpinnerDateModel(initialDate, null, null, Calendar.MINUTE));
        timeSpinner.setEditor(new JSpinner.DateEditor(timeSpinner, "HH:mm"));

        // Il primo carattere digitato deve sostituire il valore esistente, non
        // accodarsi: senza questo, cliccare nel campo e scrivere subito una
        // nuova data/ora/numero lo appende invece di sostituirlo.
        Ui.selectAllOnFocus(dateSpinner);
        Ui.selectAllOnFocus(timeSpinner);
        Ui.selectAllOnFocus(durationSpinner);
        Ui.selectAllOnFocus(partySpinner);
        Ui.selectAllOnFocus(highChairSpinner);
        Ui.selectAllOnFocus(strollerSpinner);
        updateDateCaption();

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setContentPane(buildContent());
        pack();
        setSize(new Dimension(760, Math.min(800, getHeight() + 20)));
        setLocationRelativeTo(owner);

        loadFromReservation();
        loading = false;
        refreshTableCombo();

        Ui.bindKey(getRootPane(), JComponent.WHEN_IN_FOCUSED_WINDOW, "ESCAPE", "chiudi", this::dispose);
    }

    // ------------------------------------------------------------------

    private JComponent buildContent() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Palette.BG);
        root.setBorder(BorderFactory.createEmptyBorder(20, 22, 16, 22));

        JPanel header = Ui.column();
        header.add(Ui.alignLeft(Ui.h1(creating ? "Nuova prenotazione" : "Modifica prenotazione")));
        header.add(Ui.vGap(4));
        header.add(Ui.alignLeft(Ui.muted(
                "I campi con l'asterisco sono obbligatori. Il tavolo può essere assegnato anche in un secondo momento.")));
        header.add(Ui.vGap(16));
        root.add(header, BorderLayout.NORTH);

        JPanel form = Ui.column();

        form.add(section("Cliente"));
        form.add(twoColumns(
                labeled("Nominativo *", nameField),
                labeled("Telefono *", phoneField)));
        form.add(Ui.vGap(10));
        form.add(twoColumns(
                labeled("Email", emailField),
                labeled("Codice fedeltà", loyaltyField)));
        form.add(Ui.vGap(18));

        form.add(section("Quando"));
        form.add(threeColumns(
                dateField(),
                labeled("Ora *", timeSpinner),
                labeled("Durata (min)", durationSpinner)));
        form.add(Ui.vGap(18));

        form.add(section("Coperti"));
        form.add(threeColumns(
                labeled("Persone *", partySpinner),
                labeled("Seggioloni", highChairSpinner),
                labeled("Spazi passeggino", strollerSpinner)));
        form.add(Ui.vGap(10));
        form.add(twoColumns(tableField(), labeled("Stato", statusCombo)));
        form.add(Ui.vGap(4));
        tableHint.setFont(Theme.regular(11));
        tableHint.setForeground(Palette.WARN);
        form.add(Ui.alignLeft(tableHint));
        form.add(Ui.vGap(14));

        form.add(section("Allergeni e intolleranze"));
        form.add(buildAllergenGrid());
        form.add(Ui.vGap(18));

        form.add(section("Note per la sala e la cucina"));
        notesArea.setLineWrap(true);
        notesArea.setWrapStyleWord(true);
        notesArea.setFont(Theme.regular(13));
        JScrollPane notesScroll = new JScrollPane(notesArea);
        notesScroll.setPreferredSize(new Dimension(620, 70));
        notesScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
        notesScroll.setBorder(BorderFactory.createLineBorder(Palette.BORDER));
        form.add(Ui.alignLeft(notesScroll));

        JScrollPane formScroll = new JScrollPane(form);
        formScroll.setBorder(null);
        formScroll.getViewport().setBackground(Palette.BG);
        formScroll.getVerticalScrollBar().setUnitIncrement(16);
        root.add(formScroll, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));

        errorLabel.setFont(Theme.regular(12));
        errorLabel.setForeground(Palette.DANGER);
        footer.add(errorLabel, BorderLayout.CENTER);

        JPanel buttons = Ui.rowRight();
        JButton cancel = Ui.secondary("Annulla");
        cancel.setToolTipText("Chiudi senza salvare (Esc)");
        cancel.addActionListener(e -> dispose());
        JButton save = Ui.primary(creating ? "Inserisci prenotazione" : "Salva modifiche");
        save.addActionListener(e -> onSave());
        save.setEnabled(model.can(Permission.EDIT_RESERVATIONS));
        buttons.add(cancel);
        buttons.add(save);
        footer.add(buttons, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(save);

        // Cambiare data, ora, durata o numero di coperti cambia i tavoli disponibili.
        dateSpinner.addChangeListener(e -> refreshTableCombo());
        dateSpinner.addChangeListener(e -> updateDateCaption());
        timeSpinner.addChangeListener(e -> refreshTableCombo());
        partySpinner.addChangeListener(e -> refreshTableCombo());
        durationSpinner.addChangeListener(e -> refreshTableCombo());

        return root;
    }

    /** Il campo Data, con etichetta che mostra il giorno della settimana corrispondente. */
    private JComponent dateField() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        dateCaption.setFont(Theme.regular(11));
        dateCaption.setForeground(Palette.TEXT_MUTED);
        dateSpinner.setPreferredSize(new Dimension(180, 34));
        panel.add(dateCaption, BorderLayout.NORTH);
        panel.add(dateSpinner, BorderLayout.CENTER);
        return panel;
    }

    /** La tendina dei tavoli, con un'intestazione che dice quanti ce ne sono liberi. */
    private JComponent tableField() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        tableCaption.setFont(Theme.regular(11));
        tableCaption.setForeground(Palette.TEXT_MUTED);
        tableCombo.setPreferredSize(new Dimension(180, 34));
        tableCombo.setRenderer(new TableRenderer());
        tableCombo.addActionListener(e -> {
            if (!refreshingTables) {
                tableHint.setText(" ");
            }
        });
        panel.add(tableCaption, BorderLayout.NORTH);
        panel.add(tableCombo, BorderLayout.CENTER);
        return panel;
    }

    private JComponent section(String title) {
        JPanel panel = Ui.column();
        panel.add(Ui.alignLeft(Ui.sectionTitle(title)));
        panel.add(Ui.vGap(8));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        return panel;
    }

    private JComponent labeled(String label, JComponent field) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        JLabel caption = new JLabel(label);
        caption.setFont(Theme.regular(11));
        caption.setForeground(Palette.TEXT_MUTED);
        field.setPreferredSize(new Dimension(180, 34));
        panel.add(caption, BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }

    private JComponent twoColumns(JComponent a, JComponent b) {
        JPanel panel = new JPanel(new GridLayout(1, 2, 14, 0));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        panel.add(a);
        panel.add(b);
        return panel;
    }

    private JComponent threeColumns(JComponent a, JComponent b, JComponent c) {
        JPanel panel = new JPanel(new GridLayout(1, 3, 14, 0));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        panel.add(a);
        panel.add(b);
        panel.add(c);
        return panel;
    }

    /** I 14 allergeni obbligatori, disposti su una griglia di caselle. */
    private JComponent buildAllergenGrid() {
        JPanel grid = new JPanel(new GridLayout(0, 4, 8, 4));
        grid.setOpaque(false);
        grid.setAlignmentX(LEFT_ALIGNMENT);
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
        for (Allergen allergen : Allergen.values()) {
            JCheckBox box = new JCheckBox(allergen.getLabel());
            box.setFont(Theme.regular(12));
            box.setForeground(Palette.TEXT);
            box.setOpaque(false);
            allergenBoxes.put(allergen, box);
            grid.add(box);
        }
        return grid;
    }

    // ------------------------------------------------------------------

    private void loadFromReservation() {
        nameField.setText(nullSafe(reservation.getGuestName()));
        phoneField.setText(nullSafe(reservation.getPhone()));
        emailField.setText(nullSafe(reservation.getEmail()));
        loyaltyField.setText(nullSafe(reservation.getLoyaltyId()));
        notesArea.setText(nullSafe(reservation.getNotes()));

        if (!creating) {
            durationSpinner.setValue(reservation.getDurationMinutes());
            partySpinner.setValue(reservation.getPartySize());
            highChairSpinner.setValue(reservation.getHighChairs());
            strollerSpinner.setValue(reservation.getStrollerSpaces());
            statusCombo.setSelectedItem(reservation.getStatus());
        }
        for (Allergen allergen : Allergen.values()) {
            allergenBoxes.get(allergen).setSelected(reservation.getAllergens().contains(allergen));
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Ricalcola l'elenco dei tavoli proponibili.
     *
     * Vengono mostrati solo i tavoli abbastanza capienti e liberi nella fascia
     * oraria scelta: così l'errore di sovrapposizione, in condizioni normali,
     * non si presenta nemmeno. La validazione lato Model resta comunque, perché
     * fra l'apertura della finestra e il salvataggio un'altra postazione
     * potrebbe aver preso lo stesso tavolo.
     *
     * Resta selezionato il tavolo scelto finora (all'apertura, quello già
     * assegnato) se è ancora fra quelli disponibili; se non lo è più, lo si
     * dice sotto la tendina invece di cambiarlo in silenzio.
     */
    private void refreshTableCombo() {
        if (loading) {
            return;
        }
        Object previous = tableCombo.getSelectedItem();
        int wantedId;
        if (previous instanceof RestaurantTable t) {
            wantedId = t.getId();
        } else if (previous == null) {
            wantedId = reservation.getTableId();
        } else {
            wantedId = 0;   // "Da assegnare" scelto esplicitamente
        }

        Reservation probe = new Reservation();
        probe.setId(reservation.getId());
        probe.setDateTime(readDateTime());
        probe.setDurationMinutes((Integer) durationSpinner.getValue());
        probe.setPartySize((Integer) partySpinner.getValue());
        List<RestaurantTable> available = model.findAvailableTables(probe);

        refreshingTables = true;
        tableCombo.removeAllItems();
        tableCombo.addItem(NO_TABLE);
        RestaurantTable toSelect = null;
        for (RestaurantTable t : available) {
            tableCombo.addItem(t);
            if (t.getId() == wantedId) {
                toSelect = t;
            }
        }
        tableCombo.setSelectedItem(toSelect != null ? toSelect : NO_TABLE);
        refreshingTables = false;

        tableCaption.setText("Tavolo  ·  " + (available.isEmpty() ? "nessuno libero"
                : available.size() + (available.size() == 1 ? " libero" : " liberi")) + " in questa fascia");

        if (wantedId != 0 && toSelect == null) {
            RestaurantTable lost = model.getFloorPlan().findTableById(wantedId);
            tableHint.setText(lost == null
                    ? "Il tavolo assegnato non esiste più in piantina: scegline un altro."
                    : "Il tavolo " + lost.getNumber()
                        + " non è disponibile per questi coperti o in questo orario.");
        } else {
            tableHint.setText(" ");
        }
    }

    /** Aggiorna l'etichetta del campo Data con il giorno della settimana scelto. */
    private void updateDateCaption() {
        Date value = (Date) dateSpinner.getValue();
        LocalDateTime day = LocalDateTime.ofInstant(value.toInstant(), ZoneId.systemDefault());
        dateCaption.setText("Data *  ·  " + day.format(WEEKDAY));
    }

    private LocalDateTime readDateTime() {
        Date date = (Date) dateSpinner.getValue();
        Date time = (Date) timeSpinner.getValue();
        LocalDateTime day = LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
        LocalDateTime hour = LocalDateTime.ofInstant(time.toInstant(), ZoneId.systemDefault());
        return day.withHour(hour.getHour()).withMinute(hour.getMinute()).withSecond(0).withNano(0);
    }

    private void onSave() {
        // I dati del modulo vengono riversati sulla copia...
        reservation.setGuestName(nameField.getText().trim());
        reservation.setPhone(phoneField.getText().trim());
        reservation.setEmail(emailField.getText().trim());
        reservation.setLoyaltyId(loyaltyField.getText().trim());
        reservation.setNotes(notesArea.getText().trim());
        reservation.setDateTime(readDateTime());
        reservation.setDurationMinutes((Integer) durationSpinner.getValue());
        reservation.setPartySize((Integer) partySpinner.getValue());
        reservation.setHighChairs((Integer) highChairSpinner.getValue());
        reservation.setStrollerSpaces((Integer) strollerSpinner.getValue());
        reservation.setStatus((ReservationStatus) statusCombo.getSelectedItem());

        Object selectedTable = tableCombo.getSelectedItem();
        reservation.setTableId(selectedTable instanceof RestaurantTable t ? t.getId() : 0);

        Set<Allergen> allergens = EnumSet.noneOf(Allergen.class);
        for (var entry : allergenBoxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                allergens.add(entry.getKey());
            }
        }
        reservation.setAllergens(allergens);

        // ...e il salvataggio è delegato al Model, che valida e persiste.
        try {
            model.saveReservation(reservation);
            saved = true;
            dispose();
        } catch (ValidationException e) {
            errorLabel.setText(Ui.wrapped(e.getMessage(), 360));
            focusField(e.getField());
        }
    }

    /** Porta il cursore sul campo che ha causato l'errore, se identificabile. */
    private void focusField(String field) {
        if (field == null) {
            return;
        }
        switch (field) {
            case "guestName" -> nameField.requestFocusInWindow();
            case "phone" -> phoneField.requestFocusInWindow();
            case "email" -> emailField.requestFocusInWindow();
            case "dateTime" -> dateSpinner.requestFocusInWindow();
            case "duration" -> durationSpinner.requestFocusInWindow();
            case "partySize" -> partySpinner.requestFocusInWindow();
            case "highChairs" -> highChairSpinner.requestFocusInWindow();
            case "strollerSpaces" -> strollerSpinner.requestFocusInWindow();
            case "tableId" -> tableCombo.requestFocusInWindow();
            default -> { /* campo non associato a un componente */ }
        }
    }

    public boolean isSaved() {
        return saved;
    }

    /** La prenotazione salvata (per una modifica, la copia che ha preso il posto dell'originale). */
    public Reservation getReservation() {
        return reservation;
    }

    /** Voce della tendina: numero, posti ed eventuale nome del tavolo. */
    private static class TableRenderer extends DefaultListCellRenderer {

        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof RestaurantTable t) {
                setText("Tavolo " + t.getNumber() + "  ·  " + t.getSeats() + " posti"
                        + (t.isAccessible() ? "  ·  accessibile" : "")
                        + (t.getName().isBlank() ? "" : "  ·  " + t.getName()));
            }
            return this;
        }
    }
}
