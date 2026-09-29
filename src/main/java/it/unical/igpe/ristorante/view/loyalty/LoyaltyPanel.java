package it.unical.igpe.ristorante.view.loyalty;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerDateModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import it.unical.igpe.ristorante.model.ModelEvent;
import it.unical.igpe.ristorante.model.ModelListener;
import it.unical.igpe.ristorante.model.RestaurantModel;
import it.unical.igpe.ristorante.model.ValidationException;
import it.unical.igpe.ristorante.model.loyalty.LoyaltyAccount;
import it.unical.igpe.ristorante.model.loyalty.LoyaltySummary;
import it.unical.igpe.ristorante.model.loyalty.LoyaltyVisit;
import it.unical.igpe.ristorante.view.common.Card;
import it.unical.igpe.ristorante.view.common.Palette;
import it.unical.igpe.ristorante.view.common.Theme;
import it.unical.igpe.ristorante.view.common.Ui;

/**
 * Programma fedeltà: generazione dei codici e registrazione delle visite.
 *
 * Visibile solo a chi ha il permesso MANAGE_LOYALTY, cioè agli amministratori
 * di primo livello (vedi Role.ADMIN).
 *
 * È volutamente la base soltanto: genera un codice per cliente e ne registra
 * lo storico delle visite (quando è venuto, quanto ha speso, quanto ha
 * pagato). Punti, soglie e sconti veri e propri si costruiranno sopra questo
 * storico in un secondo momento; qui si raccolgono solo i dati.
 */
public class LoyaltyPanel extends JPanel implements ModelListener {

    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final transient RestaurantModel model;

    private final AccountsTableModel accountsTableModel = new AccountsTableModel();
    private final JTable accountsTable = new JTable(accountsTableModel);
    private final VisitsTableModel visitsTableModel = new VisitsTableModel();
    private final JTable visitsTable = new JTable(visitsTableModel);

    // --- scheda cliente ---
    private final JLabel formTitle = new JLabel();
    private final JLabel codeValueLabel = new JLabel();
    private final JTextField nameField = new JTextField();
    private final JTextField phoneField = new JTextField();
    private final JTextField emailField = new JTextField();
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton deleteButton = Ui.danger("Elimina");

    // --- nuova visita ---
    private final JSpinner visitDateSpinner;
    private final JSpinner totalSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 100_000.0, 1.0));
    private final JSpinner paidSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 100_000.0, 1.0));
    private final JTextField visitNotesField = new JTextField();
    private final JLabel visitStatusLabel = new JLabel(" ");
    private final JLabel summaryLabel = new JLabel(" ");
    private final JButton addVisitButton = Ui.primary("Registra visita");
    private final JButton deleteVisitButton = Ui.danger("Elimina visita");

    /** Cliente mostrato nella scheda (null = nuovo cliente). */
    private transient LoyaltyAccount editing;
    private boolean reloadingAccounts;

    public LoyaltyPanel(RestaurantModel model) {
        this.model = model;

        Date today = Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
        visitDateSpinner = new JSpinner(new SpinnerDateModel(today, null, null, java.util.Calendar.DAY_OF_MONTH));
        visitDateSpinner.setEditor(new JSpinner.DateEditor(visitDateSpinner, "dd/MM/yyyy"));
        Ui.selectAllOnFocus(visitDateSpinner);
        totalSpinner.setEditor(new JSpinner.NumberEditor(totalSpinner, "0.00"));
        paidSpinner.setEditor(new JSpinner.NumberEditor(paidSpinner, "0.00"));
        Ui.selectAllOnFocus(totalSpinner);
        Ui.selectAllOnFocus(paidSpinner);

        setLayout(new BorderLayout(14, 12));
        setBackground(Palette.BG);
        setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));

        add(buildHeader(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildAccountForm(), BorderLayout.EAST);

        model.addListener(this);
        reloadAccounts();
        clearAccountForm();
    }

    /** Da chiamare quando la finestra si chiude: smette di ascoltare il Model. */
    public void detach() {
        model.removeListener(this);
    }

    // ------------------------------------------------------------------
    // Costruzione dell'interfaccia
    // ------------------------------------------------------------------

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));

        JPanel left = Ui.column();
        left.add(Ui.alignLeft(Ui.h1("Programma fedeltà")));
        left.add(Ui.vGap(4));
        left.add(Ui.alignLeft(Ui.muted(
                "Genera un codice per un cliente e registra le sue visite: data, conto e quanto ha pagato.")));
        header.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        JButton newAccount = Ui.primary("Nuovo cliente");
        newAccount.addActionListener(e -> {
            accountsTable.clearSelection();
            clearAccountForm();
            nameField.requestFocusInWindow();
        });
        right.add(newAccount);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JComponent buildCenter() {
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, buildAccountsTable(), buildVisitsSection());
        split.setOpaque(false);
        split.setBorder(null);
        split.setResizeWeight(0.55);
        split.setContinuousLayout(true);
        split.setDividerSize(10);
        return split;
    }

    private JComponent buildAccountsTable() {
        accountsTable.setRowHeight(34);
        accountsTable.setShowGrid(false);
        accountsTable.setIntercellSpacing(new Dimension(0, 1));
        accountsTable.setBackground(Palette.SURFACE);
        accountsTable.setForeground(Palette.TEXT);
        accountsTable.setFont(Theme.regular(13));
        accountsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        accountsTable.setSelectionBackground(Palette.alpha(Palette.ACCENT, 45));
        accountsTable.setSelectionForeground(Palette.TEXT);
        accountsTable.setFillsViewportHeight(true);
        accountsTable.getTableHeader().setFont(Theme.bold(11));
        accountsTable.getTableHeader().setReorderingAllowed(false);

        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getTableCellRendererComponent(JTable t, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 8));
                if (column == AccountsTableModel.COL_CODE && !isSelected) {
                    setForeground(Palette.ACCENT);
                    setFont(Theme.mono(java.awt.Font.BOLD, 12));
                } else if (!isSelected) {
                    setForeground(Palette.TEXT);
                    setFont(Theme.regular(13));
                }
                return this;
            }
        };
        for (int i = 0; i < accountsTableModel.getColumnCount(); i++) {
            accountsTable.getColumnModel().getColumn(i).setCellRenderer(renderer);
        }

        accountsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !reloadingAccounts) {
                int row = accountsTable.getSelectedRow();
                if (row >= 0) {
                    loadAccountForm(accountsTableModel.getAccountAt(row));
                }
            }
        });

        JScrollPane scroll = new JScrollPane(accountsTable);
        scroll.setBorder(BorderFactory.createLineBorder(Palette.BORDER));
        scroll.getViewport().setBackground(Palette.SURFACE);
        return scroll;
    }

    private JComponent buildVisitsSection() {
        Card card = new Card(new BorderLayout(0, 10));
        card.fill(Palette.SURFACE).border(Palette.BORDER);
        card.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JPanel top = Ui.column();
        top.add(Ui.alignLeft(Ui.h2("Visite")));
        top.add(Ui.vGap(4));
        summaryLabel.setFont(Theme.regular(12));
        summaryLabel.setForeground(Palette.TEXT_MUTED);
        top.add(Ui.alignLeft(summaryLabel));
        top.add(Ui.vGap(8));
        card.add(top, BorderLayout.NORTH);

        visitsTable.setRowHeight(30);
        visitsTable.setShowGrid(false);
        visitsTable.setIntercellSpacing(new Dimension(0, 1));
        visitsTable.setBackground(Palette.SURFACE);
        visitsTable.setForeground(Palette.TEXT);
        visitsTable.setFont(Theme.regular(13));
        visitsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        visitsTable.setSelectionBackground(Palette.alpha(Palette.ACCENT, 45));
        visitsTable.setFillsViewportHeight(true);
        visitsTable.getTableHeader().setFont(Theme.bold(11));
        visitsTable.getTableHeader().setReorderingAllowed(false);
        visitsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                deleteVisitButton.setEnabled(editing != null && visitsTable.getSelectedRow() >= 0);
            }
        });
        JScrollPane visitsScroll = new JScrollPane(visitsTable);
        visitsScroll.setBorder(BorderFactory.createLineBorder(Palette.BORDER));
        visitsScroll.getViewport().setBackground(Palette.SURFACE);
        card.add(visitsScroll, BorderLayout.CENTER);

        card.add(buildVisitForm(), BorderLayout.SOUTH);
        return card;
    }

    private JComponent buildVisitForm() {
        JPanel panel = Ui.column();
        panel.add(Ui.vGap(10));
        panel.add(Ui.alignLeft(Ui.separator()));
        panel.add(Ui.vGap(10));

        JPanel fields = new JPanel(new GridLayout(1, 4, 10, 0));
        fields.setOpaque(false);
        fields.setAlignmentX(LEFT_ALIGNMENT);
        fields.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
        fields.add(labeled("Data", visitDateSpinner));
        fields.add(labeled("Conto (€)", totalSpinner));
        fields.add(labeled("Pagato (€)", paidSpinner));
        fields.add(labeled("Note", visitNotesField));
        panel.add(fields);
        panel.add(Ui.vGap(8));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        addVisitButton.addActionListener(e -> addVisit());
        deleteVisitButton.addActionListener(e -> deleteVisit());
        buttons.add(addVisitButton);
        buttons.add(deleteVisitButton);
        panel.add(buttons);
        panel.add(Ui.vGap(4));

        visitStatusLabel.setFont(Theme.regular(11));
        panel.add(Ui.alignLeft(visitStatusLabel));
        return panel;
    }

    private JComponent buildAccountForm() {
        Card card = new Card(new BorderLayout());
        card.fill(Palette.SURFACE).border(Palette.BORDER);
        card.setPreferredSize(new Dimension(320, 10));
        card.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        JPanel body = Ui.column();
        formTitle.setFont(Theme.bold(15));
        formTitle.setForeground(Palette.TEXT);
        body.add(Ui.alignLeft(formTitle));
        body.add(Ui.vGap(10));

        codeValueLabel.setFont(Theme.mono(java.awt.Font.BOLD, 15));
        codeValueLabel.setForeground(Palette.ACCENT);
        body.add(Ui.alignLeft(codeValueLabel));
        body.add(Ui.vGap(14));

        body.add(labeled("Nominativo *", nameField));
        body.add(Ui.vGap(10));
        body.add(labeled("Telefono", phoneField));
        body.add(Ui.vGap(10));
        body.add(labeled("Email", emailField));
        body.add(Ui.vGap(14));

        statusLabel.setFont(Theme.regular(11));
        body.add(Ui.alignLeft(statusLabel));
        body.add(Ui.vGap(10));

        JPanel buttons = new JPanel(new GridLayout(1, 2, 8, 0));
        buttons.setOpaque(false);
        buttons.setMaximumSize(new Dimension(300, 38));
        deleteButton.addActionListener(e -> deleteAccount());
        JButton save = Ui.primary("Salva");
        save.addActionListener(e -> saveAccount());
        buttons.add(deleteButton);
        buttons.add(save);
        body.add(Ui.alignLeft(buttons));

        card.add(body, BorderLayout.NORTH);
        return card;
    }

    private JComponent labeled(String caption, JComponent field) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        JLabel label = new JLabel(caption);
        label.setFont(Theme.regular(11));
        label.setForeground(Palette.TEXT_MUTED);
        field.setPreferredSize(new Dimension(field.getPreferredSize().width, 34));
        panel.add(label, BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }

    // ------------------------------------------------------------------
    // Scheda cliente
    // ------------------------------------------------------------------

    private void setStatus(String text, Color color) {
        statusLabel.setText(text == null || text.isBlank() ? " " : Ui.wrapped(text, 280));
        statusLabel.setForeground(color);
    }

    private void setVisitStatus(String text, Color color) {
        visitStatusLabel.setText(text == null || text.isBlank() ? " " : text);
        visitStatusLabel.setForeground(color);
    }

    private void clearAccountForm() {
        editing = null;
        formTitle.setText("Nuovo cliente fedeltà");
        codeValueLabel.setText("Il codice viene generato al salvataggio");
        codeValueLabel.setForeground(Palette.TEXT_MUTED);
        codeValueLabel.setFont(Theme.regular(12));
        nameField.setText("");
        phoneField.setText("");
        emailField.setText("");
        deleteButton.setEnabled(false);
        setStatus(" ", Palette.TEXT_MUTED);
        clearVisitsSection();
    }

    private void loadAccountForm(LoyaltyAccount account) {
        editing = account;
        formTitle.setText("Scheda cliente");
        codeValueLabel.setText(account.getCode());
        codeValueLabel.setForeground(Palette.ACCENT);
        codeValueLabel.setFont(Theme.mono(java.awt.Font.BOLD, 15));
        nameField.setText(account.getGuestName());
        phoneField.setText(account.getPhone());
        emailField.setText(account.getEmail());
        deleteButton.setEnabled(true);
        setStatus(" ", Palette.TEXT_MUTED);
        reloadVisits();
    }

    private void saveAccount() {
        String name = nameField.getText().trim();
        String phone = phoneField.getText().trim();
        String email = emailField.getText().trim();
        try {
            if (editing == null) {
                LoyaltyAccount created = model.generateLoyaltyCode(name, phone, email);
                selectAccount(created.getId());
                setStatus("Codice generato: " + created.getCode(), Palette.OK);
            } else {
                LoyaltyAccount account = editing.copy();
                account.setGuestName(name);
                account.setPhone(phone);
                account.setEmail(email);
                model.saveLoyaltyAccount(account);
                selectAccount(account.getId());
                setStatus("Dati aggiornati.", Palette.OK);
            }
        } catch (ValidationException e) {
            setStatus(e.getMessage(), Palette.DANGER);
        }
    }

    private void deleteAccount() {
        if (editing == null) {
            setStatus("Seleziona prima un cliente dall'elenco.", Palette.DANGER);
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "Eliminare il cliente " + editing.getGuestName() + " (" + editing.getCode()
                        + ")? Anche le sue visite registrate andranno perse.",
                "Conferma", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            model.deleteLoyaltyAccount(editing);
            clearAccountForm();
            setStatus("Cliente eliminato.", Palette.OK);
        } catch (ValidationException e) {
            setStatus(e.getMessage(), Palette.DANGER);
        }
    }

    /** Seleziona la riga del cliente con l'id indicato, se presente. */
    private LoyaltyAccount selectAccount(int id) {
        for (int row = 0; row < accountsTableModel.getRowCount(); row++) {
            LoyaltyAccount a = accountsTableModel.getAccountAt(row);
            if (a.getId() == id) {
                accountsTable.setRowSelectionInterval(row, row);
                accountsTable.scrollRectToVisible(accountsTable.getCellRect(row, 0, true));
                return a;
            }
        }
        return null;
    }

    private void reloadAccounts() {
        List<AccountRow> rows = new ArrayList<>();
        for (LoyaltyAccount a : model.getLoyaltyAccounts()) {
            rows.add(new AccountRow(a, model.summarizeLoyaltyAccount(a.getId())));
        }
        accountsTableModel.setRows(rows);
    }

    // ------------------------------------------------------------------
    // Visite
    // ------------------------------------------------------------------

    private void clearVisitsSection() {
        visitsTableModel.setRows(new ArrayList<>());
        summaryLabel.setText("Seleziona un cliente per vedere le sue visite.");
        totalSpinner.setValue(0.0);
        paidSpinner.setValue(0.0);
        visitNotesField.setText("");
        addVisitButton.setEnabled(false);
        deleteVisitButton.setEnabled(false);
        setVisitStatus(" ", Palette.TEXT_MUTED);
    }

    private void reloadVisits() {
        if (editing == null) {
            clearVisitsSection();
            return;
        }
        visitsTableModel.setRows(model.getLoyaltyVisits(editing.getId()));
        LoyaltySummary summary = model.summarizeLoyaltyAccount(editing.getId());
        summaryLabel.setText(summary.getVisitCount() + " visite  ·  speso "
                + euro(summary.getTotalSpent()) + "  ·  pagato " + euro(summary.getTotalPaid())
                + (summary.getLastVisit() == null ? "" : "  ·  ultima il " + summary.getLastVisit().format(DATE)));
        addVisitButton.setEnabled(true);
        deleteVisitButton.setEnabled(false);
    }

    private void addVisit() {
        if (editing == null) {
            setVisitStatus("Seleziona prima un cliente.", Palette.DANGER);
            return;
        }
        Date value = (Date) visitDateSpinner.getValue();
        LocalDate visitDate = value.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        double total = (Double) totalSpinner.getValue();
        double paid = (Double) paidSpinner.getValue();
        try {
            model.addLoyaltyVisit(editing.getId(), visitDate, total, paid, visitNotesField.getText());
            totalSpinner.setValue(0.0);
            paidSpinner.setValue(0.0);
            visitNotesField.setText("");
            reloadVisits();
            setVisitStatus("Visita registrata.", Palette.OK);
        } catch (ValidationException e) {
            setVisitStatus(e.getMessage(), Palette.DANGER);
        }
    }

    private void deleteVisit() {
        int row = visitsTable.getSelectedRow();
        if (row < 0) {
            setVisitStatus("Seleziona prima una visita dall'elenco.", Palette.DANGER);
            return;
        }
        LoyaltyVisit visit = visitsTableModel.getVisitAt(row);
        try {
            model.deleteLoyaltyVisit(visit.getId());
            reloadVisits();
            setVisitStatus("Visita eliminata.", Palette.OK);
        } catch (ValidationException e) {
            setVisitStatus(e.getMessage(), Palette.DANGER);
        }
    }

    private static String euro(double amount) {
        return String.format("%.2f €", amount);
    }

    // ------------------------------------------------------------------
    // Reazione ai cambiamenti del Model
    // ------------------------------------------------------------------

    @Override
    public void onModelChanged(ModelEvent event) {
        if (!event.is(ModelEvent.Type.LOYALTY_CHANGED)) {
            return;
        }
        int selectedId = editing == null ? 0 : editing.getId();
        reloadingAccounts = true;
        try {
            reloadAccounts();
            editing = selectedId == 0 ? null : selectAccount(selectedId);
        } finally {
            reloadingAccounts = false;
        }
        if (selectedId != 0 && editing == null) {
            clearAccountForm();
            setStatus("Il cliente che stavi modificando è stato eliminato.", Palette.WARN);
            return;
        }
        reloadVisits();
    }

    // ------------------------------------------------------------------
    // Modelli delle tabelle
    // ------------------------------------------------------------------

    /** Un cliente accoppiato al riepilogo delle sue visite, calcolato una volta al caricamento. */
    private record AccountRow(LoyaltyAccount account, LoyaltySummary summary) {
    }

    private class AccountsTableModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        static final int COL_CODE = 0;

        private final String[] columns = {"Codice", "Nome", "Telefono", "Visite", "Speso totale", "Ultima visita"};
        private final List<AccountRow> rows = new ArrayList<>();

        void setRows(List<AccountRow> newRows) {
            rows.clear();
            rows.addAll(newRows);
            fireTableDataChanged();
        }

        LoyaltyAccount getAccountAt(int viewRow) {
            return rows.get(viewRow).account();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            AccountRow row = rows.get(rowIndex);
            LoyaltyAccount a = row.account();
            LoyaltySummary s = row.summary();
            return switch (columnIndex) {
                case 0 -> a.getCode();
                case 1 -> a.getGuestName();
                case 2 -> a.getPhone();
                case 3 -> s.getVisitCount();
                case 4 -> euro(s.getTotalSpent());
                case 5 -> s.getLastVisit() == null ? "mai" : s.getLastVisit().format(DATE);
                default -> "";
            };
        }
    }

    private static class VisitsTableModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        private final String[] columns = {"Data", "Conto", "Pagato", "Note"};
        private final List<LoyaltyVisit> rows = new ArrayList<>();

        void setRows(List<LoyaltyVisit> newRows) {
            rows.clear();
            rows.addAll(newRows);
            fireTableDataChanged();
        }

        LoyaltyVisit getVisitAt(int viewRow) {
            return rows.get(viewRow);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            LoyaltyVisit v = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> v.getVisitDate().format(DATE);
                case 1 -> euro(v.getTotalAmount());
                case 2 -> euro(v.getPaidAmount());
                case 3 -> v.getNotes();
                default -> "";
            };
        }
    }
}
