package it.unical.igpe.ristorante.view.floor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import it.unical.igpe.ristorante.model.ModelEvent;
import it.unical.igpe.ristorante.model.ModelListener;
import it.unical.igpe.ristorante.model.Permission;
import it.unical.igpe.ristorante.model.Reservation;
import it.unical.igpe.ristorante.model.RestaurantModel;
import it.unical.igpe.ristorante.model.ValidationException;
import it.unical.igpe.ristorante.model.floor.FloorElement;
import it.unical.igpe.ristorante.model.floor.FloorPlan;
import it.unical.igpe.ristorante.model.floor.Obstacle;
import it.unical.igpe.ristorante.model.floor.ObstacleType;
import it.unical.igpe.ristorante.model.floor.RestaurantTable;
import it.unical.igpe.ristorante.model.floor.TableShape;
import it.unical.igpe.ristorante.model.floor.TableStatus;
import it.unical.igpe.ristorante.view.common.Badge;
import it.unical.igpe.ristorante.view.common.Card;
import it.unical.igpe.ristorante.view.common.Palette;
import it.unical.igpe.ristorante.view.common.StatusDot;
import it.unical.igpe.ristorante.view.common.Theme;
import it.unical.igpe.ristorante.view.common.Toast;
import it.unical.igpe.ristorante.view.common.Ui;

/**
 * Editor della sala: barra dei comandi, tela di disegno e pannello delle
 * proprietà dell'elemento selezionato.
 *
 * Chi non ha il permesso di modificare la piantina vede esattamente la stessa
 * schermata ma in sola lettura: gli strumenti di disegno sono nascosti e i
 * campi disabilitati. La piantina resta comunque consultabile, perché sapere
 * dov'è il tavolo 7 serve anche a chi non può spostarlo.
 *
 * Annulla/ripeti funziona per istantanee (è il pattern Memento): dopo ogni
 * modifica completata si registra una copia serializzata della piantina, e
 * annullare vuol dire ripristinare la copia precedente. Confrontare
 * l'istantanea attuale con quella dell'ultimo salvataggio dice anche, senza
 * altri flag da tenere allineati, se ci sono modifiche da salvare.
 */
public class FloorPanel extends JPanel implements ModelListener {

    private static final long serialVersionUID = 1L;

    /** Passi di annulla conservati: oltre questo numero si scartano i più vecchi. */
    private static final int MAX_HISTORY = 100;

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d/M HH:mm");

    private final transient RestaurantModel model;
    private final FloorCanvas canvas;
    private final boolean canEdit;

    private final JPanel propertiesBody = Ui.column();
    private final JLabel warningLabel = new JLabel(" ");
    private final JLabel cursorLabel = new JLabel(" ");
    private final Badge dirtyBadge = new Badge("MODIFICHE NON SALVATE", Palette.WARN);
    private final Map<ToolMode, JToggleButton> toolButtons = new EnumMap<>(ToolMode.class);
    private JLabel titleLabel;
    private JButton undoButton;
    private JButton redoButton;
    private JButton revertButton;

    // --- annulla / ripeti -------------------------------------------------
    private final transient Deque<byte[]> undoStack = new ArrayDeque<>();
    private final transient Deque<byte[]> redoStack = new ArrayDeque<>();
    /** Istantanea dopo l'ultima modifica registrata. */
    private byte[] lastCommitted;
    /** Istantanea uguale a ciò che è salvato nel database. */
    private byte[] savedState;

    // --- campi dell'elemento selezionato, seguono anche il trascinamento --
    private JLabel elementSummary;
    private JSpinner xField;
    private JSpinner yField;
    private JSpinner wField;
    private JSpinner hField;
    private JSpinner rotationField;
    private JSpinner seatsField;
    private JLabel currentStatusLabel;

    /** Evita che l'aggiornamento dei campi rilanci a sua volta una modifica. */
    private boolean updatingFields;

    public FloorPanel(RestaurantModel model) {
        this.model = model;
        this.canEdit = model.can(Permission.EDIT_FLOOR_PLAN);
        this.canvas = new FloorCanvas(model);
        canvas.setEditable(canEdit);

        setLayout(new BorderLayout(14, 12));
        setBackground(Palette.BG);
        setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildCanvasArea(), BorderLayout.CENTER);
        add(buildProperties(), BorderLayout.EAST);

        canvas.setSelectionListener(this::showProperties);
        canvas.setChangeListener(this::onCanvasChanged);
        canvas.setEditListener(this::onCanvasEdited);
        canvas.setToolListener(this::onToolChanged);
        canvas.setDeleteHandler(this::deleteElement);
        canvas.setCursorListener(this::updateCursorLabel);

        installShortcuts();
        model.addListener(this);
        resetHistory();
        showProperties(null);
        updateCursorLabel();
    }

    /** Da chiamare quando la finestra si chiude: smette di ascoltare il Model. */
    public void detach() {
        model.removeListener(this);
    }

    // ------------------------------------------------------------------
    // Barra dei comandi
    // ------------------------------------------------------------------

    /**
     * Barra superiore: nome della sala, stato del salvataggio e comandi che
     * cambiano la piantina (annulla, ripeti, ripristina, salva). I comandi di
     * sola visualizzazione stanno invece nella striscia sotto la tela.
     */
    private JComponent buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(10, 8));
        bar.setOpaque(false);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        titleLabel = Ui.h2(model.getFloorPlan().getName());
        left.add(titleLabel);
        if (canEdit) {
            dirtyBadge.setVisible(false);
            left.add(dirtyBadge);
        } else {
            left.add(new Badge("SOLA LETTURA", Palette.TEXT_MUTED));
            left.add(Ui.muted("Il tuo ruolo non consente di modificare la disposizione della sala."));
        }
        bar.add(left, BorderLayout.WEST);

        if (canEdit) {
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            right.setOpaque(false);

            undoButton = Ui.toolButton("Annulla");
            undoButton.setToolTipText("Annulla l'ultima modifica (Ctrl+Z)");
            undoButton.addActionListener(e -> undo());

            redoButton = Ui.toolButton("Ripeti");
            redoButton.setToolTipText("Ripeti la modifica annullata (Ctrl+Y)");
            redoButton.addActionListener(e -> redo());

            revertButton = Ui.toolButton("Ripristina salvata");
            revertButton.setToolTipText("Scarta le modifiche non ancora salvate");
            revertButton.addActionListener(e -> confirmRevert());

            JButton save = Ui.primary("Salva piantina");
            save.setToolTipText("Salva la piantina nel database (Ctrl+S)");
            save.addActionListener(e -> save());

            right.add(undoButton);
            right.add(redoButton);
            right.add(revertButton);
            right.add(save);
            bar.add(right, BorderLayout.EAST);
        }
        return bar;
    }

    /** Colonna verticale degli strumenti di disegno, a sinistra della tela. */
    private JComponent buildToolPalette() {
        JPanel palette = new JPanel();
        palette.setLayout(new BoxLayout(palette, BoxLayout.Y_AXIS));
        palette.setOpaque(false);
        palette.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        ButtonGroup group = new ButtonGroup();
        palette.add(paletteTitle("Strumento"));
        palette.add(toolToggle(ToolMode.SELEZIONE, group, true));

        palette.add(paletteTitle("Tavoli"));
        palette.add(toolToggle(ToolMode.TAVOLO_RETT, group, false));
        palette.add(toolToggle(ToolMode.TAVOLO_QUAD, group, false));
        palette.add(toolToggle(ToolMode.TAVOLO_TONDO, group, false));

        palette.add(paletteTitle("Struttura"));
        palette.add(toolToggle(ToolMode.MURO, group, false));
        palette.add(toolToggle(ToolMode.COLONNA, group, false));
        palette.add(toolToggle(ToolMode.PORTA, group, false));
        palette.add(toolToggle(ToolMode.FINESTRA, group, false));
        palette.add(toolToggle(ToolMode.SCALE, group, false));

        palette.add(paletteTitle("Zone"));
        palette.add(toolToggle(ToolMode.CUCINA, group, false));
        palette.add(toolToggle(ToolMode.BAGNO, group, false));
        palette.add(toolToggle(ToolMode.BAR, group, false));
        palette.add(toolToggle(ToolMode.INGRESSO, group, false));
        palette.add(toolToggle(ToolMode.DEPOSITO, group, false));

        palette.add(Box.createVerticalGlue());

        // Con la finestra alla dimensione minima gli strumenti non entrano tutti
        // in altezza: invece di tagliare gli ultimi, la colonna scorre.
        JScrollPane scroll = new JScrollPane(palette,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(158, 10));
        return scroll;
    }

    private JComponent paletteTitle(String text) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setAlignmentX(LEFT_ALIGNMENT);
        wrapper.setMaximumSize(new Dimension(140, 24));
        wrapper.setBorder(BorderFactory.createEmptyBorder(10, 2, 4, 0));
        wrapper.add(Ui.sectionTitle(text), BorderLayout.WEST);
        return wrapper;
    }

    private JToggleButton toolToggle(ToolMode mode, ButtonGroup group, boolean selected) {
        JToggleButton button = new JToggleButton(mode.getLabel());
        button.setFont(Theme.regular(12));
        button.setFocusPainted(false);
        button.setSelected(selected);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setAlignmentX(LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(140, 30));
        button.setPreferredSize(new Dimension(140, 30));
        button.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 8));
        if (mode != ToolMode.SELEZIONE) {
            button.setToolTipText("Clic sulla sala per una misura standard, trascina per disegnare. Esc per annullare.");
        }
        button.addActionListener(e -> canvas.setTool(mode));
        group.add(button);
        toolButtons.put(mode, button);
        return button;
    }

    /** La tela è tornata da sola a un altro strumento: la colonna la segue. */
    private void onToolChanged(ToolMode mode) {
        JToggleButton button = toolButtons.get(mode);
        if (button != null) {
            button.setSelected(true);
        }
    }

    private JComponent buildCanvasArea() {
        Card card = new Card(new BorderLayout());
        card.fill(Palette.SURFACE).border(Palette.BORDER);
        card.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        if (canEdit) {
            card.add(buildToolPalette(), BorderLayout.WEST);
        }
        card.add(canvas, BorderLayout.CENTER);
        card.add(buildViewStrip(), BorderLayout.SOUTH);
        return card;
    }

    /**
     * Striscia sotto la tela: posizione del puntatore in metri e comandi di
     * visualizzazione (griglia, sedie, zoom). Non modificano la sala, per
     * questo non stanno accanto a "Salva".
     */
    private JComponent buildViewStrip() {
        JPanel strip = new JPanel(new BorderLayout());
        strip.setOpaque(false);
        strip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Palette.BORDER),
                BorderFactory.createEmptyBorder(4, 10, 4, 8)));

        cursorLabel.setFont(Theme.mono(Font.PLAIN, 11));
        cursorLabel.setForeground(Palette.TEXT_MUTED);
        strip.add(cursorLabel, BorderLayout.WEST);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        controls.setOpaque(false);

        JCheckBox grid = viewCheckBox("Griglia", canvas.isShowGrid());
        grid.addActionListener(e -> canvas.setShowGrid(grid.isSelected()));
        JCheckBox chairs = viewCheckBox("Sedie", canvas.isShowChairs());
        chairs.addActionListener(e -> canvas.setShowChairs(chairs.isSelected()));

        JButton zoomOut = Ui.toolButton("−");
        zoomOut.setToolTipText("Riduci (anche con la rotella del mouse)");
        zoomOut.addActionListener(e ->
                canvas.zoomAt(1 / 1.2, canvas.getWidth() / 2, canvas.getHeight() / 2));
        JButton zoomIn = Ui.toolButton("+");
        zoomIn.setToolTipText("Ingrandisci (anche con la rotella del mouse)");
        zoomIn.addActionListener(e ->
                canvas.zoomAt(1.2, canvas.getWidth() / 2, canvas.getHeight() / 2));
        JButton fit = Ui.toolButton("Adatta");
        fit.setToolTipText("Mostra tutta la sala");
        fit.addActionListener(e -> canvas.fitToWindow());

        controls.add(grid);
        controls.add(chairs);
        controls.add(zoomOut);
        controls.add(zoomIn);
        controls.add(fit);
        strip.add(controls, BorderLayout.EAST);
        return strip;
    }

    private JCheckBox viewCheckBox(String text, boolean selected) {
        JCheckBox box = new JCheckBox(text, selected);
        box.setOpaque(false);
        box.setForeground(Palette.TEXT_MUTED);
        box.setFont(Theme.regular(12));
        return box;
    }

    private void updateCursorLabel() {
        cursorLabel.setText(String.format("x %6.2f m   y %6.2f m   ·   %.0f px/m",
                canvas.getCursorMeterX(), canvas.getCursorMeterY(), canvas.getScale()));
    }

    /**
     * Scorciatoie. Annulla, ripeti e duplica valgono quando si lavora sulla
     * tela; "salva" ovunque nel pannello, anche mentre si scrive in un campo.
     */
    private void installShortcuts() {
        if (!canEdit) {
            return;
        }
        Ui.bindKey(canvas, JComponent.WHEN_FOCUSED, "ctrl Z", "annulla", this::undo);
        Ui.bindKey(canvas, JComponent.WHEN_FOCUSED, "ctrl Y", "ripeti", this::redo);
        Ui.bindKey(canvas, JComponent.WHEN_FOCUSED, "ctrl shift Z", "ripeti-bis", this::redo);
        Ui.bindKey(canvas, JComponent.WHEN_FOCUSED, "ctrl D", "duplica", () -> {
            if (canvas.getSelected() != null) {
                duplicate(canvas.getSelected());
            }
        });
        Ui.bindKey(this, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, "ctrl S", "salva", () -> save());
    }

    // ------------------------------------------------------------------
    // Pannello proprietà
    // ------------------------------------------------------------------

    private JComponent buildProperties() {
        Card card = new Card(new BorderLayout());
        card.fill(Palette.SURFACE).border(Palette.BORDER);
        card.setPreferredSize(new Dimension(348, 10));
        card.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        propertiesBody.setOpaque(false);
        JScrollPane scroll = new JScrollPane(propertiesBody);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        card.add(scroll, BorderLayout.CENTER);

        warningLabel.setFont(Theme.regular(11));
        warningLabel.setForeground(Palette.WARN);
        card.add(warningLabel, BorderLayout.SOUTH);
        return card;
    }

    private void showProperties(FloorElement element) {
        updatingFields = true;
        propertiesBody.removeAll();
        elementSummary = null;
        xField = null;
        yField = null;
        wField = null;
        hField = null;
        rotationField = null;
        seatsField = null;
        currentStatusLabel = null;

        if (element == null) {
            buildRoomProperties();
        } else {
            buildElementProperties(element);
        }

        propertiesBody.revalidate();
        propertiesBody.repaint();
        updatingFields = false;
        updateWarnings(element);
    }

    /** Proprietà della sala, mostrate quando non c'è nessuna selezione. */
    private void buildRoomProperties() {
        FloorPlan plan = model.getFloorPlan();
        propertiesBody.add(Ui.alignLeft(Ui.h2("Sala")));
        propertiesBody.add(Ui.vGap(4));
        propertiesBody.add(Ui.alignLeft(Ui.muted(Ui.wrapped(
                "Nessun elemento selezionato. Qui si imposta la dimensione reale del locale.", 268))));
        propertiesBody.add(Ui.vGap(16));

        JTextField nameField = liveTextField(plan.getName(), text -> {
            plan.setName(text);
            titleLabel.setText(text);
        });
        propertiesBody.add(labeled("Nome della sala", nameField));
        propertiesBody.add(Ui.vGap(10));

        JSpinner width = meterSpinner(plan.getWidthMeters(), 2, 80);
        width.addChangeListener(e -> applyIfEditing(() -> {
            plan.setWidthMeters(doubleOf(width));
            canvas.fitToWindow();
        }));
        JSpinner height = meterSpinner(plan.getHeightMeters(), 2, 80);
        height.addChangeListener(e -> applyIfEditing(() -> {
            plan.setHeightMeters(doubleOf(height));
            canvas.fitToWindow();
        }));
        width.setEnabled(canEdit);
        height.setEnabled(canEdit);
        propertiesBody.add(twoFields("Larghezza (m)", width, "Profondità (m)", height));
        propertiesBody.add(Ui.vGap(10));

        double step = plan.getGridStepMeters();
        JSpinner grid = new JSpinner(new SpinnerNumberModel(step, Math.min(0.05, step), Math.max(1.0, step), 0.05));
        grid.setEditor(new JSpinner.NumberEditor(grid, "0.00"));
        grid.setEnabled(canEdit);
        grid.addChangeListener(e -> applyIfEditing(() -> plan.setGridStepMeters(doubleOf(grid))));
        propertiesBody.add(labeled("Passo della griglia (m)", grid));
        propertiesBody.add(Ui.vGap(18));

        propertiesBody.add(Ui.alignLeft(Ui.sectionTitle("Riepilogo")));
        propertiesBody.add(Ui.vGap(8));
        propertiesBody.add(statRow("Superficie", String.format("%.1f m²", plan.getRoomAreaSquareMeters())));
        propertiesBody.add(statRow("Tavoli", String.valueOf(plan.getTables().size())));
        propertiesBody.add(statRow("Posti totali", String.valueOf(plan.getTotalSeats())));
        propertiesBody.add(statRow("Superficie occupata",
                String.format("%.0f%%", 100 * plan.getOccupancyRatio())));
        propertiesBody.add(statRow("Elementi", String.valueOf(plan.getElements().size())));

        propertiesBody.add(Ui.vGap(18));
        propertiesBody.add(Ui.alignLeft(Ui.sectionTitle("Legenda stati")));
        propertiesBody.add(Ui.vGap(8));
        for (TableStatus status : TableStatus.values()) {
            propertiesBody.add(legendRow(status.getLabel(), Palette.forTableStatus(status)));
        }

        if (canEdit) {
            propertiesBody.add(Ui.vGap(18));
            propertiesBody.add(Ui.alignLeft(Ui.sectionTitle("Comandi rapidi")));
            propertiesBody.add(Ui.vGap(6));
            propertiesBody.add(Ui.alignLeft(Ui.muted(
                    "<html>Trascina per disegnare · Esc torna a «Seleziona»<br>"
                    + "Rotella: zoom · Tasto destro: sposta la vista<br>"
                    + "Frecce: sposta (Maiusc: di un passo di griglia)<br>"
                    + "R: ruota · Canc: elimina · Ctrl+D: duplica<br>"
                    + "Ctrl+Z / Ctrl+Y: annulla / ripeti · Ctrl+S: salva</html>")));
        }
    }

    private void buildElementProperties(FloorElement element) {
        boolean isTable = element instanceof RestaurantTable;

        propertiesBody.add(Ui.alignLeft(Ui.h2(isTable
                ? "Tavolo " + ((RestaurantTable) element).getNumber()
                : element.getKind())));
        propertiesBody.add(Ui.vGap(4));
        elementSummary = Ui.muted(summaryOf(element));
        propertiesBody.add(Ui.alignLeft(elementSummary));
        propertiesBody.add(Ui.vGap(16));

        JTextField nameField = liveTextField(element.getName(), element::setName);
        propertiesBody.add(labeled("Etichetta", nameField));
        propertiesBody.add(Ui.vGap(10));

        JSpinner x = meterSpinner(element.getXMeters(), -10, 100);
        JSpinner y = meterSpinner(element.getYMeters(), -10, 100);
        x.addChangeListener(e -> applyIfEditing(() -> element.setXMeters(doubleOf(x))));
        y.addChangeListener(e -> applyIfEditing(() -> element.setYMeters(doubleOf(y))));
        xField = x;
        yField = y;
        propertiesBody.add(twoFields("Centro X (m)", x, "Centro Y (m)", y));
        propertiesBody.add(Ui.vGap(10));

        JSpinner w = meterSpinner(element.getWidthMeters(), 0.1, 40);
        JSpinner h = meterSpinner(element.getHeightMeters(), 0.1, 40);
        w.addChangeListener(e -> applyIfEditing(() -> {
            element.setWidthMeters(doubleOf(w));
            if (element instanceof RestaurantTable t) {
                t.setSeats(t.suggestedSeats());
            }
        }));
        h.addChangeListener(e -> applyIfEditing(() -> {
            element.setHeightMeters(doubleOf(h));
            if (element instanceof RestaurantTable t) {
                t.setSeats(t.suggestedSeats());
            }
        }));
        wField = w;
        hField = h;
        propertiesBody.add(twoFields("Larghezza (m)", w, "Profondità (m)", h));
        propertiesBody.add(Ui.vGap(10));

        // La rotazione è sempre in [0, 360): il limite superiore 360 è quindi
        // sicuro, mentre il 359 di prima rifiutava un elemento ruotato a 359,5°.
        JSpinner rotation = new JSpinner(new SpinnerNumberModel(
                element.getRotationDegrees(), 0.0, 360.0, 5.0));
        rotation.setEditor(new JSpinner.NumberEditor(rotation, "0.#"));
        rotation.addChangeListener(e ->
                applyIfEditing(() -> element.setRotationDegrees(doubleOf(rotation))));
        rotationField = rotation;
        propertiesBody.add(labeled("Rotazione (gradi)", rotation));
        propertiesBody.add(Ui.vGap(16));

        for (JSpinner spinner : new JSpinner[] {x, y, w, h, rotation}) {
            spinner.setEnabled(canEdit);
        }

        if (isTable) {
            buildTableProperties((RestaurantTable) element);
        } else {
            buildObstacleProperties((Obstacle) element);
        }

        if (canEdit) {
            propertiesBody.add(Ui.vGap(18));
            propertiesBody.add(Ui.alignLeft(Ui.separator()));
            propertiesBody.add(Ui.vGap(12));

            JPanel actions = new JPanel(new GridLayout(2, 2, 6, 6));
            actions.setOpaque(false);
            actions.setMaximumSize(new Dimension(280, 76));

            JButton front = Ui.toolButton("Porta avanti");
            front.addActionListener(e -> {
                model.getFloorPlan().bringToFront(element);
                canvas.repaint();
                commit();
            });
            JButton back = Ui.toolButton("Porta dietro");
            back.addActionListener(e -> {
                model.getFloorPlan().sendToBack(element);
                canvas.repaint();
                commit();
            });
            JButton duplicate = Ui.toolButton("Duplica");
            duplicate.setToolTipText("Ctrl+D");
            duplicate.addActionListener(e -> duplicate(element));
            JButton remove = Ui.danger("Elimina");
            remove.setToolTipText("Canc");
            remove.addActionListener(e -> deleteElement(element));

            actions.add(front);
            actions.add(back);
            actions.add(duplicate);
            actions.add(remove);
            propertiesBody.add(Ui.alignLeft(actions));
        }
    }

    private void buildTableProperties(RestaurantTable table) {
        propertiesBody.add(Ui.alignLeft(Ui.sectionTitle("Tavolo")));
        propertiesBody.add(Ui.vGap(8));

        JSpinner number = intSpinner(table.getNumber(), 1, 999);
        number.addChangeListener(e -> applyIfEditing(() -> table.setNumber(intOf(number))));
        JSpinner seats = intSpinner(table.getSeats(), 1, 40);
        seats.addChangeListener(e -> applyIfEditing(() -> table.setSeats(intOf(seats))));
        seatsField = seats;
        propertiesBody.add(twoFields("Numero", number, "Posti", seats));
        propertiesBody.add(Ui.vGap(10));

        JComboBox<TableShape> shape = new JComboBox<>(TableShape.values());
        shape.setSelectedItem(table.getShape());
        shape.addActionListener(e -> applyIfEditing(() -> {
            table.setShape((TableShape) shape.getSelectedItem());
            table.setSeats(table.suggestedSeats());
        }));
        propertiesBody.add(labeled("Forma", shape));
        propertiesBody.add(Ui.vGap(10));

        // Solo gli stati che decide l'operatore: libero, prenotato e occupato
        // sono calcolati dalle prenotazioni, e sceglierli a mano non avrebbe
        // effetto (il ricalcolo successivo li sovrascriverebbe).
        JComboBox<TableStatus> service = new JComboBox<>(new TableStatus[] {
                TableStatus.LIBERO, TableStatus.DA_PULIRE, TableStatus.FUORI_SERVIZIO});
        service.setRenderer(new ServiceStatusRenderer());
        service.setSelectedItem(table.getServiceStatus());
        service.addActionListener(e -> applyIfEditing(() -> {
            table.setServiceStatus((TableStatus) service.getSelectedItem());
            model.refreshTableStatuses(LocalDateTime.now());
            updateCurrentStatus(table);
        }));
        propertiesBody.add(labeled("Disponibilità", service));
        propertiesBody.add(Ui.vGap(4));
        currentStatusLabel = new JLabel();
        currentStatusLabel.setFont(Theme.regular(11));
        updateCurrentStatus(table);
        propertiesBody.add(Ui.alignLeft(currentStatusLabel));
        propertiesBody.add(Ui.vGap(10));

        JCheckBox accessible = new JCheckBox("Postazione accessibile", table.isAccessible());
        accessible.setOpaque(false);
        accessible.setForeground(Palette.TEXT);
        accessible.setFont(Theme.regular(12));
        accessible.addActionListener(e ->
                applyIfEditing(() -> table.setAccessible(accessible.isSelected())));
        propertiesBody.add(Ui.alignLeft(accessible));

        number.setEnabled(canEdit);
        seats.setEnabled(canEdit);
        shape.setEnabled(canEdit);
        service.setEnabled(canEdit);
        accessible.setEnabled(canEdit);

        propertiesBody.add(Ui.vGap(8));
        propertiesBody.add(Ui.alignLeft(Ui.muted("Posti suggeriti dalle misure: "
                + table.suggestedSeats())));

        // Prenotazione attiva su questo tavolo in questo momento
        LocalDateTime now = LocalDateTime.now();
        Reservation current = model.findReservationForTable(table.getId(), now);
        propertiesBody.add(Ui.vGap(16));
        propertiesBody.add(Ui.alignLeft(Ui.sectionTitle("Adesso al tavolo")));
        propertiesBody.add(Ui.vGap(6));
        if (current == null) {
            propertiesBody.add(Ui.alignLeft(Ui.muted("Nessuna prenotazione attiva.")));
        } else {
            propertiesBody.add(Ui.alignLeft(Ui.value(current.getGuestName())));
            propertiesBody.add(Ui.vGap(2));
            propertiesBody.add(Ui.alignLeft(Ui.muted(current.getDateTime().toLocalTime()
                    + " · " + current.getPartySize() + " coperti · "
                    + current.getStatus().getLabel())));
            if (current.hasAllergens()) {
                propertiesBody.add(Ui.vGap(6));
                JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
                chips.setOpaque(false);
                chips.setAlignmentX(LEFT_ALIGNMENT);
                chips.setMaximumSize(new Dimension(280, 80));
                for (var a : current.getAllergens()) {
                    chips.add(new Badge(a.getShortCode(), Palette.DANGER));
                }
                propertiesBody.add(chips);
            }
        }

        // Le prossime prenotazioni: prima di spostare o togliere un tavolo
        // conviene sapere chi lo aspetta.
        List<Reservation> later = new ArrayList<>();
        for (Reservation r : model.findUpcomingReservationsForTable(table.getId(), now)) {
            if (r.getDateTime().isAfter(now)) {
                later.add(r);
            }
        }
        propertiesBody.add(Ui.vGap(14));
        propertiesBody.add(Ui.alignLeft(Ui.sectionTitle("Più tardi")));
        propertiesBody.add(Ui.vGap(6));
        if (later.isEmpty()) {
            propertiesBody.add(Ui.alignLeft(Ui.muted("Nessuna prenotazione in programma.")));
        } else {
            for (int i = 0; i < Math.min(4, later.size()); i++) {
                Reservation r = later.get(i);
                propertiesBody.add(Ui.alignLeft(Ui.muted(r.getDateTime().format(WHEN) + "  ·  "
                        + r.getGuestName() + " (" + r.getPartySize() + ")")));
                propertiesBody.add(Ui.vGap(2));
            }
            if (later.size() > 4) {
                propertiesBody.add(Ui.alignLeft(Ui.muted("… e altre " + (later.size() - 4))));
            }
        }
    }

    private void updateCurrentStatus(RestaurantTable table) {
        if (currentStatusLabel == null) {
            return;
        }
        currentStatusLabel.setText("Adesso: " + table.getStatus().getLabel().toLowerCase());
        currentStatusLabel.setForeground(Palette.forTableStatus(table.getStatus()));
    }

    private void buildObstacleProperties(Obstacle obstacle) {
        propertiesBody.add(Ui.alignLeft(Ui.sectionTitle("Elemento non prenotabile")));
        propertiesBody.add(Ui.vGap(8));

        JComboBox<ObstacleType> type = new JComboBox<>(ObstacleType.values());
        type.setSelectedItem(obstacle.getType());
        type.setEnabled(canEdit);
        type.addActionListener(e ->
                applyIfEditing(() -> obstacle.setType((ObstacleType) type.getSelectedItem())));
        propertiesBody.add(labeled("Tipo", type));
        propertiesBody.add(Ui.vGap(10));
        propertiesBody.add(Ui.alignLeft(Ui.muted(obstacle.getType().isStructural()
                ? "Elemento strutturale: delimita lo spazio della sala."
                : "Zona funzionale: area di servizio non prenotabile.")));
    }

    // ------------------------------------------------------------------
    // Utilità di costruzione dei campi
    // ------------------------------------------------------------------

    /**
     * Campo numerico in metri.
     *
     * I limiti si allargano quanto basta per contenere il valore attuale:
     * SpinnerNumberModel lancia IllegalArgumentException se il valore iniziale
     * è fuori dai limiti, e bastava selezionare un elemento trascinato lontano
     * dalla sala (o un muro più lungo di 40 m) per far fallire la costruzione
     * dell'intero pannello proprietà.
     */
    private JSpinner meterSpinner(double value, double min, double max) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(
                value, Math.min(min, value), Math.max(max, value), 0.05));
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "0.00"));
        return spinner;
    }

    private JSpinner intSpinner(int value, int min, int max) {
        return new JSpinner(new SpinnerNumberModel(value, Math.min(min, value), Math.max(max, value), 1));
    }

    private static double doubleOf(JSpinner spinner) {
        return ((Number) spinner.getValue()).doubleValue();
    }

    private static int intOf(JSpinner spinner) {
        return ((Number) spinner.getValue()).intValue();
    }

    /**
     * Campo di testo che applica la modifica mentre si scrive, così non si
     * perde nulla se si clicca altrove senza premere Invio, e che registra un
     * solo passo di annulla quando si esce dal campo (o si preme Invio),
     * invece di uno per ogni lettera.
     */
    private JTextField liveTextField(String value, Consumer<String> apply) {
        JTextField field = new JTextField(value);
        field.setEnabled(canEdit);
        field.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                if (!updatingFields && canEdit) {
                    apply.accept(field.getText());
                    canvas.repaint();
                }
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                changed();
            }
        });
        field.addActionListener(e -> commit());
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                commit();
            }
        });
        return field;
    }

    private JComponent labeled(String caption, JComponent field) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(280, 56));
        JLabel label = new JLabel(caption);
        label.setFont(Theme.regular(11));
        label.setForeground(Palette.TEXT_MUTED);
        field.setPreferredSize(new Dimension(130, 32));
        panel.add(label, BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }

    private JComponent twoFields(String c1, JComponent f1, String c2, JComponent f2) {
        JPanel panel = new JPanel(new GridLayout(1, 2, 8, 0));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(280, 56));
        panel.add(labeled(c1, f1));
        panel.add(labeled(c2, f2));
        return panel;
    }

    private JComponent statRow(String caption, String value) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(280, 22));
        row.add(Ui.muted(caption), BorderLayout.WEST);
        row.add(Ui.value(value), BorderLayout.EAST);
        return row;
    }

    private JComponent legendRow(String caption, Color color) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(280, 24));
        row.add(new StatusDot(color, 9));
        row.add(Ui.muted(caption));
        return row;
    }

    private static String summaryOf(FloorElement element) {
        return String.format("%.2f × %.2f m  ·  %.2f m²  ·  %.0f°",
                element.getWidthMeters(), element.getHeightMeters(),
                element.getAreaSquareMeters(), element.getRotationDegrees());
    }

    /**
     * Esegue la modifica solo se proviene davvero dall'utente, poi la registra.
     *
     * Quando il pannello riallinea i campi (dopo un trascinamento, o dopo che
     * un campo ne ha cambiato un altro) i JSpinner emettono a loro volta un
     * evento di cambiamento: senza questo controllo si innescherebbe un ciclo
     * fra tela e pannello.
     */
    private void applyIfEditing(Runnable action) {
        if (updatingFields || !canEdit) {
            return;
        }
        action.run();
        canvas.repaint();
        syncFieldsFromElement();
        updateWarnings(canvas.getSelected());
        commit();
    }

    /**
     * Riallinea i campi al valore attuale dell'elemento selezionato.
     *
     * Serve durante il trascinamento sulla tela (i campi seguono il mouse) e
     * quando un campo ne modifica altri: un tavolo tondo che si allarga cambia
     * anche la profondità e il numero di posti suggerito.
     */
    private void syncFieldsFromElement() {
        FloorElement element = canvas.getSelected();
        if (element == null) {
            return;
        }
        boolean previous = updatingFields;
        updatingFields = true;
        try {
            setSpinner(xField, element.getXMeters());
            setSpinner(yField, element.getYMeters());
            setSpinner(wField, element.getWidthMeters());
            setSpinner(hField, element.getHeightMeters());
            setSpinner(rotationField, element.getRotationDegrees());
            if (element instanceof RestaurantTable table) {
                setSpinner(seatsField, table.getSeats());
            }
            if (elementSummary != null) {
                elementSummary.setText(summaryOf(element));
            }
        } finally {
            updatingFields = previous;
        }
    }

    /** Imposta il valore allargando i limiti se serve, e rispettando il tipo del modello. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setSpinner(JSpinner spinner, double value) {
        if (spinner == null) {
            return;
        }
        SpinnerNumberModel m = (SpinnerNumberModel) spinner.getModel();
        Number v = (m.getNumber() instanceof Integer)
                ? Integer.valueOf((int) Math.round(value)) : Double.valueOf(value);
        Comparable min = m.getMinimum();
        Comparable max = m.getMaximum();
        if (min != null && min.compareTo(v) > 0) {
            m.setMinimum((Comparable) v);
        }
        if (max != null && max.compareTo(v) < 0) {
            m.setMaximum((Comparable) v);
        }
        m.setValue(v);
    }

    /** Nella tendina della disponibilità, LIBERO si legge "In servizio". */
    private static class ServiceStatusRenderer extends DefaultListCellRenderer {

        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value == TableStatus.LIBERO) {
                setText("In servizio");
            }
            return this;
        }
    }

    // ------------------------------------------------------------------
    // Modifiche e annulla/ripeti
    // ------------------------------------------------------------------

    private void duplicate(FloorElement source) {
        if (!canEdit) {
            return;
        }
        FloorElement copy;
        if (source instanceof RestaurantTable t) {
            RestaurantTable clone = new RestaurantTable(model.getFloorPlan().nextTableNumber(),
                    t.getXMeters() + 0.5, t.getYMeters() + 0.5,
                    t.getWidthMeters(), t.getHeightMeters(), t.getShape());
            clone.setSeats(t.getSeats());
            clone.setAccessible(t.isAccessible());
            clone.setRotationDegrees(t.getRotationDegrees());
            copy = clone;
        } else {
            Obstacle o = (Obstacle) source;
            Obstacle clone = new Obstacle(o.getType(), o.getXMeters() + 0.5, o.getYMeters() + 0.5,
                    o.getWidthMeters(), o.getHeightMeters());
            clone.setName(o.getName());
            clone.setRotationDegrees(o.getRotationDegrees());
            copy = clone;
        }
        model.getFloorPlan().add(copy);
        canvas.setSelected(copy);
        commit();
        onCanvasChanged();
    }

    /**
     * Elimina un elemento. Se è un tavolo con prenotazioni in programma lo si
     * dice prima: dopo il salvataggio quelle prenotazioni resteranno senza
     * tavolo, ed è bene che sia una scelta consapevole.
     */
    private void deleteElement(FloorElement element) {
        if (!canEdit || element == null) {
            return;
        }
        if (element instanceof RestaurantTable table) {
            List<Reservation> upcoming =
                    model.findUpcomingReservationsForTable(table.getId(), LocalDateTime.now());
            if (!upcoming.isEmpty()) {
                Reservation first = upcoming.get(0);
                int choice = JOptionPane.showConfirmDialog(this,
                        "Il tavolo " + table.getNumber() + " ha " + upcoming.size()
                        + (upcoming.size() == 1 ? " prenotazione" : " prenotazioni") + " in programma"
                        + " (la prima: " + first.getGuestName() + ", "
                        + first.getDateTime().format(WHEN) + ").\n\n"
                        + "Se lo elimini, al salvataggio della piantina queste prenotazioni\n"
                        + "resteranno senza tavolo e andranno riassegnate. Eliminare comunque?",
                        "Tavolo con prenotazioni", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.YES_OPTION) {
                    return;
                }
            }
        }
        model.getFloorPlan().remove(element);
        canvas.setSelected(null);
        commit();
        onCanvasChanged();
    }

    /**
     * Registra lo stato attuale come un passo annullabile.
     *
     * Ogni modifica completata (fine di un trascinamento, un campo cambiato,
     * un elemento aggiunto o tolto) passa di qui. Se l'istantanea è identica
     * all'ultima registrata non è cambiato nulla e non si crea un passo vuoto.
     */
    private void commit() {
        if (!canEdit) {
            return;
        }
        byte[] now = model.getFloorPlan().toBytes();
        if (Arrays.equals(now, lastCommitted)) {
            return;
        }
        undoStack.push(lastCommitted);
        while (undoStack.size() > MAX_HISTORY) {
            undoStack.removeLast();
        }
        redoStack.clear();
        lastCommitted = now;
        updateHistoryState();
        model.notifyFloorPlanChanged();
    }

    private void undo() {
        if (!canEdit || undoStack.isEmpty()) {
            return;
        }
        commit();   // un campo di testo ancora in modifica diventa prima un passo a sé
        redoStack.push(lastCommitted);
        restore(undoStack.pop());
    }

    private void redo() {
        if (!canEdit || redoStack.isEmpty()) {
            return;
        }
        undoStack.push(lastCommitted);
        restore(redoStack.pop());
    }

    private void restore(byte[] state) {
        FloorElement current = canvas.getSelected();
        int selectedId = current == null ? 0 : current.getId();
        model.getFloorPlan().restoreFrom(FloorPlan.fromBytes(state));
        lastCommitted = state;
        model.refreshTableStatuses(LocalDateTime.now());
        titleLabel.setText(model.getFloorPlan().getName());
        canvas.selectById(selectedId);
        canvas.repaint();
        updateHistoryState();
        model.notifyFloorPlanChanged();
    }

    /** Azzera la cronologia: lo stato attuale diventa quello "salvato". */
    private void resetHistory() {
        undoStack.clear();
        redoStack.clear();
        lastCommitted = model.getFloorPlan().toBytes();
        savedState = lastCommitted;
        updateHistoryState();
    }

    private void updateHistoryState() {
        if (!canEdit) {
            return;
        }
        boolean dirty = !Arrays.equals(lastCommitted, savedState);
        undoButton.setEnabled(!undoStack.isEmpty());
        redoButton.setEnabled(!redoStack.isEmpty());
        revertButton.setEnabled(dirty);
        if (dirtyBadge.isVisible() != dirty) {
            dirtyBadge.setVisible(dirty);
            dirtyBadge.getParent().revalidate();
        }
    }

    /** true se la piantina è stata modificata dopo l'ultimo salvataggio. */
    public boolean hasUnsavedChanges() {
        if (!canEdit) {
            return false;
        }
        commit();
        return !Arrays.equals(lastCommitted, savedState);
    }

    /**
     * Salva la piantina.
     *
     * @return true se il salvataggio è riuscito
     */
    public boolean save() {
        if (!canEdit) {
            return false;
        }
        commit();
        try {
            int detached = model.saveFloorPlan();
            savedState = model.getFloorPlan().toBytes();
            lastCommitted = savedState;
            updateHistoryState();

            FloorPlan plan = model.getFloorPlan();
            Toast.show(this, "Piantina salvata",
                    plan.getTables().size() + " tavoli · " + plan.getTotalSeats() + " posti", Palette.OK);
            if (detached > 0) {
                JOptionPane.showMessageDialog(this,
                        (detached == 1 ? "Una prenotazione usava" : detached + " prenotazioni usavano")
                        + " un tavolo che non c'è più, ed è rimasta «da assegnare».\n"
                        + "Riassegnala dall'elenco delle prenotazioni.",
                        "Prenotazioni senza tavolo", JOptionPane.WARNING_MESSAGE);
            }
            return true;
        } catch (ValidationException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(),
                    "Operazione non consentita", JOptionPane.WARNING_MESSAGE);
            return false;
        }
    }

    /** Scarta le modifiche non salvate e torna alla piantina del database. */
    public void discardChanges() {
        model.reloadFloorPlan();
        titleLabel.setText(model.getFloorPlan().getName());
        resetHistory();
        canvas.setSelected(null);
        canvas.fitToWindow();
    }

    private void confirmRevert() {
        if (!hasUnsavedChanges()) {
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "Scartare tutte le modifiche fatte dall'ultimo salvataggio?",
                "Ripristina piantina salvata", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            discardChanges();
            Toast.show(this, "Piantina ripristinata", "Le modifiche non salvate sono state scartate",
                    Palette.INFO);
        }
    }

    // ------------------------------------------------------------------
    // Reazione agli eventi
    // ------------------------------------------------------------------

    private void updateWarnings(FloorElement element) {
        if (element == null) {
            warningLabel.setText(" ");
            return;
        }
        List<String> problems = new ArrayList<>();
        if (!model.getFloorPlan().isInsideRoom(element)) {
            problems.add("Esce dal perimetro della sala.");
        }
        int overlaps = model.getFloorPlan().findOverlaps(element).size();
        if (overlaps > 0) {
            problems.add("Si sovrappone a " + (overlaps == 1 ? "un altro elemento." : overlaps + " altri elementi."));
        }
        if (element instanceof RestaurantTable table && isNumberDuplicated(table)) {
            problems.add("Il numero " + table.getNumber() + " è usato anche da un altro tavolo.");
        }
        warningLabel.setForeground(problems.isEmpty() ? Palette.TEXT_MUTED : Palette.WARN);
        warningLabel.setText(problems.isEmpty() ? " " : Ui.wrapped(String.join(" ", problems), 300));
    }

    private boolean isNumberDuplicated(RestaurantTable table) {
        for (RestaurantTable other : model.getFloorPlan().getTables()) {
            if (other != table && other.getNumber() == table.getNumber()) {
                return true;
            }
        }
        return false;
    }

    /** A ogni passo di un trascinamento: aggiornamenti leggeri, niente cronologia. */
    private void onCanvasChanged() {
        canvas.repaint();
        syncFieldsFromElement();
        updateWarnings(canvas.getSelected());
    }

    /** Fine di un gesto sulla tela: la modifica diventa un passo annullabile. */
    private void onCanvasEdited() {
        syncFieldsFromElement();
        updateWarnings(canvas.getSelected());
        commit();
    }

    @Override
    public void onModelChanged(ModelEvent event) {
        if (event.is(ModelEvent.Type.RESERVATIONS_CHANGED)
                || event.is(ModelEvent.Type.FLOOR_PLAN_CHANGED)) {
            canvas.repaint();
            if (canvas.getSelected() instanceof RestaurantTable table) {
                updateCurrentStatus(table);
            }
        }
    }

    /** Esposto per le altre viste che vogliono centrare la sala. */
    public FloorCanvas getCanvas() {
        return canvas;
    }
}
