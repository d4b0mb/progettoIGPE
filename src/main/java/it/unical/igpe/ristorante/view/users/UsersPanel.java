package it.unical.igpe.ristorante.view.users;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import it.unical.igpe.ristorante.model.ModelEvent;
import it.unical.igpe.ristorante.model.ModelListener;
import it.unical.igpe.ristorante.model.Permission;
import it.unical.igpe.ristorante.model.RestaurantModel;
import it.unical.igpe.ristorante.model.Role;
import it.unical.igpe.ristorante.model.User;
import it.unical.igpe.ristorante.model.ValidationException;
import it.unical.igpe.ristorante.view.common.Card;
import it.unical.igpe.ristorante.view.common.Palette;
import it.unical.igpe.ristorante.view.common.Theme;
import it.unical.igpe.ristorante.view.common.Ui;

/**
 * Gestione degli utenti: visibile solo a chi ha il permesso MANAGE_USERS,
 * cioe' agli amministratori di primo livello.
 *
 * Il pannello mostra anche, in chiaro, quali permessi comporta ciascun ruolo:
 * assegnare un livello di accesso senza sapere cosa concede è il modo più
 * rapido per dare a un tirocinante il potere di cancellare l'archivio.
 */
public class UsersPanel extends JPanel implements ModelListener {

    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final transient RestaurantModel model;
    private final UserTableModel tableModel = new UserTableModel();
    private final JTable table = new JTable(tableModel);

    private final JLabel formTitle = new JLabel();
    private final JTextField fullNameField = new JTextField();
    private final JTextField usernameField = new JTextField();
    private final JPasswordField passwordField = new JPasswordField();
    private final JComboBox<Role> roleCombo = new JComboBox<>(Role.values());
    private final JCheckBox activeBox = new JCheckBox("Utente attivo", true);
    private final JLabel permissionsLabel = new JLabel();
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton deleteButton = Ui.danger("Elimina");

    /** Utente mostrato nella scheda (null = nuovo utente). Non viene mai modificato direttamente. */
    private transient User editing;
    /** true mentre l'elenco si ricarica e la riga viene riselezionata dal programma. */
    private boolean reloadingTable;

    public UsersPanel(RestaurantModel model) {
        this.model = model;

        setLayout(new BorderLayout(14, 12));
        setBackground(Palette.BG);
        setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));

        add(buildHeader(), BorderLayout.NORTH);
        add(buildTable(), BorderLayout.CENTER);
        add(buildForm(), BorderLayout.EAST);

        model.addListener(this);
        reload();
        clearForm();
    }

    /** Da chiamare quando la finestra si chiude: smette di ascoltare il Model. */
    public void detach() {
        model.removeListener(this);
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));

        JPanel left = Ui.column();
        left.add(Ui.alignLeft(Ui.h1("Gestione utenti")));
        left.add(Ui.vGap(4));
        left.add(Ui.alignLeft(Ui.muted(
                "Le password sono memorizzate solo come hash BCrypt: nemmeno un amministratore può rileggerle.")));
        header.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        JButton newUser = Ui.primary("Nuovo utente");
        newUser.addActionListener(e -> {
            table.clearSelection();
            clearForm();
            fullNameField.requestFocusInWindow();
        });
        right.add(newUser);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JComponent buildTable() {
        table.setRowHeight(36);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setBackground(Palette.SURFACE);
        table.setForeground(Palette.TEXT);
        table.setFont(Theme.regular(13));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setSelectionBackground(Palette.alpha(Palette.ACCENT, 45));
        table.setSelectionForeground(Palette.TEXT);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setFont(Theme.bold(11));
        table.getTableHeader().setReorderingAllowed(false);

        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getTableCellRendererComponent(JTable t, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 8));
                if (column == UserTableModel.COL_ROLE && !isSelected) {
                    User u = tableModel.getUserAt(row);
                    setForeground(switch (u.getRole()) {
                        case ADMIN -> Palette.ACCENT;
                        case OPERATOR -> Palette.TEAL;
                        case VIEWER -> Palette.TEXT_MUTED;
                    });
                } else if (column == UserTableModel.COL_ACTIVE && !isSelected) {
                    setForeground("Attivo".equals(value) ? Palette.OK : Palette.DANGER);
                } else if (!isSelected) {
                    setForeground(Palette.TEXT);
                }
                return this;
            }
        };
        for (int i = 0; i < tableModel.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setCellRenderer(renderer);
        }

        table.getSelectionModel().addListSelectionListener(e -> {
            // Durante un ricaricamento la riselezione la fa il programma: la
            // scheda non va ricompilata, o si perderebbe ciò che si sta scrivendo.
            if (!e.getValueIsAdjusting() && !reloadingTable) {
                int row = table.getSelectedRow();
                if (row >= 0) {
                    loadForm(tableModel.getUserAt(row));
                }
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(Palette.BORDER));
        scroll.getViewport().setBackground(Palette.SURFACE);
        return scroll;
    }

    private JComponent buildForm() {
        Card card = new Card(new BorderLayout());
        card.fill(Palette.SURFACE).border(Palette.BORDER);
        card.setPreferredSize(new Dimension(340, 10));
        card.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        JPanel body = Ui.column();
        formTitle.setFont(Theme.bold(15));
        formTitle.setForeground(Palette.TEXT);
        body.add(Ui.alignLeft(formTitle));
        body.add(Ui.vGap(14));

        body.add(labeled("Nome e cognome", fullNameField));
        body.add(Ui.vGap(10));
        body.add(labeled("Nome utente", usernameField));
        body.add(Ui.vGap(10));
        body.add(labeled("Password", passwordField));
        body.add(Ui.vGap(4));
        body.add(Ui.alignLeft(Ui.muted(Ui.wrapped(
                "Almeno 6 caratteri. Per un utente esistente, lascia vuoto per non cambiarla.", 290))));
        body.add(Ui.vGap(12));

        roleCombo.addActionListener(e -> updatePermissionsLabel());
        body.add(labeled("Livello di accesso", roleCombo));
        body.add(Ui.vGap(8));

        permissionsLabel.setFont(Theme.regular(11));
        permissionsLabel.setForeground(Palette.TEXT_MUTED);
        body.add(Ui.alignLeft(permissionsLabel));
        body.add(Ui.vGap(12));

        activeBox.setOpaque(false);
        activeBox.setForeground(Palette.TEXT);
        activeBox.setFont(Theme.regular(12));
        body.add(Ui.alignLeft(activeBox));
        body.add(Ui.vGap(14));

        statusLabel.setFont(Theme.regular(11));
        body.add(Ui.alignLeft(statusLabel));
        body.add(Ui.vGap(10));

        JPanel buttons = new JPanel(new GridLayout(1, 2, 8, 0));
        buttons.setOpaque(false);
        buttons.setMaximumSize(new Dimension(320, 38));
        deleteButton.addActionListener(e -> deleteUser());
        JButton save = Ui.primary("Salva");
        save.addActionListener(e -> saveUser());
        buttons.add(deleteButton);
        buttons.add(save);
        body.add(Ui.alignLeft(buttons));

        card.add(body, BorderLayout.NORTH);
        return card;
    }

    private JComponent labeled(String caption, JComponent field) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(320, 58));
        JLabel label = new JLabel(caption);
        label.setFont(Theme.regular(11));
        label.setForeground(Palette.TEXT_MUTED);
        field.setPreferredSize(new Dimension(300, 34));
        panel.add(label, BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }

    private void updatePermissionsLabel() {
        Role role = (Role) roleCombo.getSelectedItem();
        if (role == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("<html>Tier ").append(role.getTier()).append(" consente di:<br>");
        for (Permission p : role.getPermissions()) {
            sb.append("· ").append(p.getLabel()).append("<br>");
        }
        permissionsLabel.setText(sb.append("</html>").toString());
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text == null || text.isBlank() ? " " : Ui.wrapped(text, 290));
        statusLabel.setForeground(color);
    }

    private boolean isCurrentUser(User user) {
        User current = model.getCurrentUser();
        return user != null && current != null && user.getId() == current.getId();
    }

    private void clearForm() {
        editing = null;
        formTitle.setText("Nuovo utente");
        fullNameField.setText("");
        usernameField.setText("");
        passwordField.setText("");
        roleCombo.setSelectedItem(Role.OPERATOR);
        roleCombo.setEnabled(true);
        activeBox.setSelected(true);
        activeBox.setEnabled(true);
        deleteButton.setEnabled(false);
        setStatus(" ", Palette.TEXT_MUTED);
        updatePermissionsLabel();
    }

    private void loadForm(User user) {
        editing = user;
        boolean self = isCurrentUser(user);
        formTitle.setText(self ? "Il tuo profilo" : "Scheda utente");
        fullNameField.setText(user.getFullName());
        usernameField.setText(user.getUsername());
        passwordField.setText("");
        roleCombo.setSelectedItem(user.getRole());
        activeBox.setSelected(user.isActive());
        // Il Model non permette di cambiare il proprio livello né di
        // disattivarsi: lo si mostra subito disabilitando i campi, invece di
        // far scoprire la regola con un errore al momento del salvataggio.
        roleCombo.setEnabled(!self);
        activeBox.setEnabled(!self);
        deleteButton.setEnabled(!self);
        setStatus(self ? "È l'utente con cui hai effettuato l'accesso: livello e stato non si possono cambiare da qui."
                : " ", Palette.TEXT_MUTED);
        updatePermissionsLabel();
    }

    /**
     * Salvataggio della scheda.
     *
     * Si compila una COPIA dell'utente selezionato: se il Model rifiuta (nome
     * già usato, ultimo amministratore...) l'elenco resta com'era, invece di
     * mostrare dati che nel database non ci sono.
     */
    private void saveUser() {
        User user = (editing == null) ? new User() : editing.copy();
        user.setFullName(fullNameField.getText().trim());
        user.setUsername(usernameField.getText().trim());
        user.setRole((Role) roleCombo.getSelectedItem());
        user.setActive(activeBox.isSelected());

        try {
            model.saveUser(user, new String(passwordField.getPassword()));
            User saved = selectUser(user.getId());
            if (saved != null) {
                loadForm(saved);   // anche per svuotare il campo password
            }
            setStatus("Utente salvato.", Palette.OK);
        } catch (ValidationException e) {
            setStatus(e.getMessage(), Palette.DANGER);
        }
    }

    private void deleteUser() {
        if (editing == null) {
            setStatus("Seleziona prima un utente dall'elenco.", Palette.DANGER);
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "Eliminare l'utente " + editing.getUsername() + "?",
                "Conferma", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            model.deleteUser(editing);
            clearForm();
            setStatus("Utente eliminato.", Palette.OK);
        } catch (ValidationException e) {
            setStatus(e.getMessage(), Palette.DANGER);
        }
    }

    /** Seleziona la riga dell'utente con l'id indicato e lo restituisce (null se non c'è). */
    private User selectUser(int id) {
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            User u = tableModel.getUserAt(row);
            if (u.getId() == id) {
                table.setRowSelectionInterval(row, row);
                table.scrollRectToVisible(table.getCellRect(row, 0, true));
                return u;
            }
        }
        return null;
    }

    private void reload() {
        tableModel.setRows(new ArrayList<>(model.getUsers()));
    }

    /**
     * L'elenco si ricarica anche quando scrive un'altra postazione, cioè
     * spesso durante il servizio. La scheda che si sta compilando non viene
     * toccata: cambia solo l'oggetto a cui si riferisce, quello nuovo con lo
     * stesso id.
     */
    @Override
    public void onModelChanged(ModelEvent event) {
        if (event.is(ModelEvent.Type.USERS_CHANGED)) {
            int selectedId = editing == null ? 0 : editing.getId();
            reloadingTable = true;
            try {
                reload();
                editing = selectedId == 0 ? null : selectUser(selectedId);
            } finally {
                reloadingTable = false;
            }
            if (selectedId != 0 && editing == null) {
                clearForm();
                setStatus("L'utente che stavi modificando è stato eliminato.", Palette.WARN);
            }
        }
    }

    /** Modello dati della tabella degli utenti. */
    private class UserTableModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        static final int COL_ROLE = 2;
        static final int COL_ACTIVE = 3;

        private final String[] columns = {"Nome", "Utente", "Livello", "Stato", "Ultimo accesso"};
        private final List<User> rows = new ArrayList<>();

        void setRows(List<User> users) {
            rows.clear();
            rows.addAll(users);
            fireTableDataChanged();
        }

        User getUserAt(int viewRow) {
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
            User u = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> u.getFullName() + (isCurrentUser(u) ? "  (tu)" : "");
                case 1 -> u.getUsername();
                case 2 -> u.getRole().toString();
                case 3 -> u.isActive() ? "Attivo" : "Disattivato";
                case 4 -> u.getLastLoginAt() == null ? "mai" : u.getLastLoginAt().format(STAMP);
                default -> "";
            };
        }
    }
}
