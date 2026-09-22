package it.unical.igpe.ristorante.view.floor;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import it.unical.igpe.ristorante.model.Allergen;
import it.unical.igpe.ristorante.model.Reservation;
import it.unical.igpe.ristorante.model.RestaurantModel;
import it.unical.igpe.ristorante.model.floor.FloorElement;
import it.unical.igpe.ristorante.model.floor.FloorPlan;
import it.unical.igpe.ristorante.model.floor.Obstacle;
import it.unical.igpe.ristorante.model.floor.ObstacleType;
import it.unical.igpe.ristorante.model.floor.RestaurantTable;
import it.unical.igpe.ristorante.model.floor.TableShape;
import it.unical.igpe.ristorante.view.common.Palette;
import it.unical.igpe.ristorante.view.common.Theme;

/**
 * Pianta della sala vista dall'alto: disegno e manipolazione diretta.
 *
 * È il cuore grafico del progetto ed è costruito esattamente secondo lo schema
 * dell'esempio MVC delle slide: un pannello che ridefinisce paintComponent e si
 * disegna leggendo il Model, più dei listener del mouse e della tastiera che
 * traducono i gesti dell'utente in modifiche sul Model.
 *
 * Il punto chiave dell'intero editor è che il modello è misurato in METRI e
 * mai in pixel. Il disegno applica una sola conversione (metri -> pixel) in
 * funzione dello zoom; di conseguenza le proporzioni restano corrette a ogni
 * livello di ingrandimento e all'utente si possono mostrare misure reali,
 * confrontabili con quelle della sala vera.
 *
 * La tela non decide da sola che cosa è una "modifica completa": avvisa il
 * pannello che la contiene (editListener) alla fine di ogni gesto, e sarà il
 * pannello a registrare il passo per annulla/ripeti.
 */
public class FloorCanvas extends JPanel {

    private static final long serialVersionUID = 1L;

    /** Spessore della fascia dei righelli, in pixel. */
    private static final int RULER = 22;
    /** Lato delle maniglie di ridimensionamento, in pixel. */
    private static final int HANDLE = 8;
    /** Dimensione minima creabile trascinando, in metri. */
    private static final double MIN_SIZE = 0.30;

    /** Maniglie di trasformazione dell'elemento selezionato. */
    private enum Handle { NW, N, NE, E, SE, S, SW, W, ROTATE }

    private final transient RestaurantModel model;

    private double scale = 60;          // pixel per metro
    private double originX = 40;        // posizione in pixel dell'origine della sala
    private double originY = 40;
    /** true dopo uno zoom o uno spostamento manuale: da lì in poi la vista non si adatta più da sola. */
    private boolean userMovedView;

    private ToolMode tool = ToolMode.SELEZIONE;
    private boolean editable = true;
    private boolean showGrid = true;
    private boolean showChairs = true;
    private boolean showDimensions = true;

    private FloorElement selected;
    private FloorElement hovered;

    // Stato del trascinamento in corso
    private boolean dragging;
    private boolean panning;
    /** true se il trascinamento in corso ha davvero cambiato qualcosa. */
    private boolean modified;
    private Handle activeHandle;
    private transient Point2D pressMeters;
    private transient Point2D pressPixels;
    private Rectangle2D.Double originalBounds;
    private double lastMouseMetersX;
    private double lastMouseMetersY;

    // Creazione di un nuovo elemento per trascinamento
    private boolean creating;
    private transient Point2D createStart;
    private transient Point2D createEnd;

    private transient Consumer<FloorElement> selectionListener;
    private transient Runnable changeListener;
    private transient Runnable editListener;
    private transient Consumer<ToolMode> toolListener;
    private transient Consumer<FloorElement> deleteHandler;
    private transient Runnable cursorListener;

    public FloorCanvas(RestaurantModel model) {
        this.model = model;
        setBackground(Palette.BG);
        setFocusable(true);
        installListeners();

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                // Finché l'utente non ha zoomato o spostato la vista a mano, la
                // sala si adatta al pannello: quando compare la prima volta,
                // quando si massimizza la finestra, quando la si stringe.
                if (!userMovedView && getWidth() > 0 && getHeight() > 0) {
                    fitToWindow();
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Conversioni metri <-> pixel
    // ------------------------------------------------------------------

    private double toPixelX(double meters) { return originX + meters * scale; }

    private double toPixelY(double meters) { return originY + meters * scale; }

    private double toMeterX(double pixels) { return (pixels - originX) / scale; }

    private double toMeterY(double pixels) { return (pixels - originY) / scale; }

    private FloorPlan plan() { return model.getFloorPlan(); }

    /** Riporta la sala al centro e la ingrandisce fino a riempire il pannello. */
    public void fitToWindow() {
        double availableW = Math.max(50, getWidth() - RULER - 60);
        double availableH = Math.max(50, getHeight() - RULER - 60);
        double sx = availableW / plan().getWidthMeters();
        double sy = availableH / plan().getHeightMeters();
        scale = Math.max(8, Math.min(sx, sy));
        originX = RULER + (getWidth() - RULER - plan().getWidthMeters() * scale) / 2.0;
        originY = RULER + (getHeight() - RULER - plan().getHeightMeters() * scale) / 2.0;
        userMovedView = false;
        repaint();
        fireCursor();
    }

    /**
     * Zoom centrato sul puntatore.
     *
     * Si memorizza il punto in METRI sotto il cursore, si cambia la scala e si
     * ricalcola l'origine perché quel punto torni sotto lo stesso pixel: è il
     * motivo per cui lo zoom sembra "seguire" il mouse invece di scappare via.
     */
    public void zoomAt(double factor, int pixelX, int pixelY) {
        double mx = toMeterX(pixelX);
        double my = toMeterY(pixelY);
        scale = Math.max(8, Math.min(320, scale * factor));
        originX = pixelX - mx * scale;
        originY = pixelY - my * scale;
        userMovedView = true;
        repaint();
        fireCursor();
    }

    // ------------------------------------------------------------------
    // Disegno
    // ------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        drawRoom(g2);
        if (showGrid) {
            drawGrid(g2);
        }
        for (FloorElement element : plan().getElements()) {
            if (element instanceof Obstacle obstacle) {
                drawObstacle(g2, obstacle);
            }
        }
        for (FloorElement element : plan().getElements()) {
            if (element instanceof RestaurantTable table) {
                drawTable(g2, table);
            }
        }
        if (creating && createStart != null && createEnd != null) {
            drawCreationPreview(g2);
        }
        if (selected != null) {
            drawSelection(g2, selected);
        }
        drawRulers(g2);
        drawScaleBar(g2);
        g2.dispose();
    }

    private void drawRoom(Graphics2D g2) {
        double x = toPixelX(0);
        double y = toPixelY(0);
        double w = plan().getWidthMeters() * scale;
        double h = plan().getHeightMeters() * scale;

        // Ombra esterna, per staccare il pavimento dallo sfondo.
        g2.setColor(Palette.alpha(Color.BLACK, 60));
        g2.fill(new RoundRectangle2D.Double(x + 4, y + 5, w, h, 6, 6));

        g2.setColor(Palette.ROOM_FILL);
        g2.fill(new Rectangle2D.Double(x, y, w, h));

        g2.setColor(Palette.WALL);
        g2.setStroke(new BasicStroke(3f));
        g2.draw(new Rectangle2D.Double(x, y, w, h));
        g2.setStroke(new BasicStroke(1f));
    }

    /**
     * Griglia di aggancio: linee sottili a ogni passo, linee più marcate a
     * ogni metro. Se lo zoom è basso le linee sottili verrebbero a meno di due
     * pixel l'una dall'altra e si trasformerebbero in una macchia: in quel caso
     * si disegnano solo quelle dei metri.
     */
    private void drawGrid(Graphics2D g2) {
        double step = plan().getGridStepMeters();
        boolean drawMinor = step * scale >= 6;

        Shape clip = g2.getClip();
        g2.clip(new Rectangle2D.Double(toPixelX(0), toPixelY(0),
                plan().getWidthMeters() * scale, plan().getHeightMeters() * scale));

        if (drawMinor) {
            g2.setColor(Palette.GRID_MINOR);
            for (double m = 0; m <= plan().getWidthMeters() + 1e-9; m += step) {
                double px = toPixelX(m);
                g2.draw(new java.awt.geom.Line2D.Double(px, toPixelY(0), px,
                        toPixelY(plan().getHeightMeters())));
            }
            for (double m = 0; m <= plan().getHeightMeters() + 1e-9; m += step) {
                double py = toPixelY(m);
                g2.draw(new java.awt.geom.Line2D.Double(toPixelX(0), py,
                        toPixelX(plan().getWidthMeters()), py));
            }
        }

        g2.setColor(Palette.GRID_MAJOR);
        for (int m = 0; m <= (int) Math.ceil(plan().getWidthMeters()); m++) {
            double px = toPixelX(m);
            g2.draw(new java.awt.geom.Line2D.Double(px, toPixelY(0), px,
                    toPixelY(plan().getHeightMeters())));
        }
        for (int m = 0; m <= (int) Math.ceil(plan().getHeightMeters()); m++) {
            double py = toPixelY(m);
            g2.draw(new java.awt.geom.Line2D.Double(toPixelX(0), py,
                    toPixelX(plan().getWidthMeters()), py));
        }
        g2.setClip(clip);
    }

    private void drawObstacle(Graphics2D g2, Obstacle obstacle) {
        AffineTransform saved = g2.getTransform();
        applyElementTransform(g2, obstacle);

        Rectangle2D.Double b = obstacle.getBounds();
        double x = toPixelX(b.x);
        double y = toPixelY(b.y);
        double w = b.width * scale;
        double h = b.height * scale;

        Color color = Palette.forObstacle(obstacle.getType());
        boolean structural = obstacle.getType().isStructural();

        if (obstacle.getType() == ObstacleType.COLONNA) {
            g2.setColor(Palette.WALL);
            g2.fill(new Ellipse2D.Double(x, y, w, h));
            g2.setColor(Palette.alpha(Palette.TEXT, 60));
            g2.draw(new Ellipse2D.Double(x, y, w, h));
        } else if (structural) {
            g2.setColor(color);
            g2.fill(new Rectangle2D.Double(x, y, w, h));
            if (obstacle.getType() == ObstacleType.PORTA
                    || obstacle.getType() == ObstacleType.FINESTRA) {
                g2.setColor(Palette.BG);
                g2.setStroke(new BasicStroke(Math.max(1f, (float) (w * 0.12))));
                g2.draw(new java.awt.geom.Line2D.Double(x + w / 2, y, x + w / 2, y + h));
                g2.setStroke(new BasicStroke(1f));
            }
        } else {
            // Zona funzionale: riempimento tenue, bordo tratteggiato, etichetta.
            g2.setColor(color);
            g2.fill(new RoundRectangle2D.Double(x, y, w, h, 8, 8));
            g2.setColor(Palette.alpha(Palette.TEXT, 70));
            g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10f, new float[]{6f, 5f}, 0f));
            g2.draw(new RoundRectangle2D.Double(x, y, w, h, 8, 8));
            g2.setStroke(new BasicStroke(1f));

            String label = obstacle.getName() == null || obstacle.getName().isBlank()
                    ? obstacle.getType().getLabel() : obstacle.getName();
            g2.setFont(Theme.bold(Math.max(9, (int) Math.min(13, h / 4))));
            FontMetrics fm = g2.getFontMetrics();
            if (fm.stringWidth(label) < w - 8) {
                g2.setColor(Palette.alpha(Palette.TEXT, 190));
                g2.drawString(label, (float) (x + (w - fm.stringWidth(label)) / 2),
                        (float) (y + h / 2 + fm.getAscent() / 2.0 - 2));
            }
        }
        g2.setTransform(saved);
    }

    /**
     * Disegno di un tavolo: sagoma colorata secondo lo stato, sedie tutt'intorno,
     * numero al centro e misure reali sotto.
     *
     * Le sedie non sono decorazione: rendono immediatamente leggibile quanti
     * coperti ha il tavolo e quanto spazio serve realmente intorno ad esso, che
     * è la domanda vera quando si dispone una sala.
     */
    private void drawTable(Graphics2D g2, RestaurantTable table) {
        AffineTransform saved = g2.getTransform();
        applyElementTransform(g2, table);

        Rectangle2D.Double b = table.getBounds();
        double x = toPixelX(b.x);
        double y = toPixelY(b.y);
        double w = b.width * scale;
        double h = b.height * scale;

        Color statusColor = Palette.forTableStatus(table.getStatus());

        if (showChairs) {
            drawChairs(g2, table, statusColor);
        }

        Shape shape = (table.getShape() == TableShape.ROUND)
                ? new Ellipse2D.Double(x, y, w, h)
                : new RoundRectangle2D.Double(x, y, w, h, Math.min(10, w / 6), Math.min(10, h / 6));

        g2.setColor(Palette.mix(Palette.SURFACE_3, statusColor, 0.28));
        g2.fill(shape);
        g2.setColor(statusColor);
        g2.setStroke(new BasicStroke(2f));
        g2.draw(shape);
        g2.setStroke(new BasicStroke(1f));

        // Numero del tavolo
        String label = "T" + table.getNumber();
        int fontSize = (int) Math.max(9, Math.min(20, Math.min(w, h) / 2.6));
        g2.setFont(Theme.bold(fontSize));
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(Palette.TEXT);
        g2.drawString(label, (float) (x + (w - fm.stringWidth(label)) / 2),
                (float) (y + h / 2 + fm.getAscent() / 2.0 - 3));

        // Posti a sedere
        String seats = table.getSeats() + " posti";
        int smallSize = (int) Math.max(8, Math.min(11, Math.min(w, h) / 5.5));
        g2.setFont(Theme.regular(smallSize));
        fm = g2.getFontMetrics();
        if (fm.stringWidth(seats) < w - 4) {
            g2.setColor(Palette.TEXT_MUTED);
            g2.drawString(seats, (float) (x + (w - fm.stringWidth(seats)) / 2),
                    (float) (y + h / 2 + fontSize / 2.0 + smallSize));
        }

        if (table.isAccessible()) {
            g2.setColor(Palette.INFO);
            g2.fill(new Ellipse2D.Double(x + w - 12, y + 4, 8, 8));
        }
        g2.setTransform(saved);

        if (showDimensions && (table == selected || table == hovered)) {
            drawDimensionLabel(g2, table);
        }
    }

    /** Sedie disposte lungo il perimetro del tavolo. */
    private void drawChairs(Graphics2D g2, RestaurantTable table, Color color) {
        double chairLength = 0.42;
        double chairDepth = 0.12;
        double gap = 0.06;

        g2.setColor(Palette.alpha(color, 110));

        if (table.getShape() == TableShape.ROUND) {
            double radius = table.getWidthMeters() / 2.0 + gap + chairDepth / 2.0;
            int n = Math.max(1, table.getSeats());
            for (int i = 0; i < n; i++) {
                double angle = 2 * Math.PI * i / n - Math.PI / 2;
                double cx = table.getXMeters() + radius * Math.cos(angle);
                double cy = table.getYMeters() + radius * Math.sin(angle);
                AffineTransform saved = g2.getTransform();
                g2.rotate(angle + Math.PI / 2, toPixelX(cx), toPixelY(cy));
                g2.fill(new RoundRectangle2D.Double(
                        toPixelX(cx) - chairLength * scale / 2,
                        toPixelY(cy) - chairDepth * scale / 2,
                        chairLength * scale, chairDepth * scale, 4, 4));
                g2.setTransform(saved);
            }
            return;
        }

        // Tavolo rettangolare o quadrato: si costruisce l'elenco delle posizioni
        // disponibili sui quattro lati e si occupano nell'ordine finché i posti
        // dichiarati non sono esauriti.
        List<double[]> slots = new ArrayList<>();  // x, y, orizzontale?
        double w = table.getWidthMeters();
        double h = table.getHeightMeters();
        double cx = table.getXMeters();
        double cy = table.getYMeters();
        int perTop = Math.max(1, (int) Math.floor(w / (chairLength + 0.08)));
        int perSide = Math.max(1, (int) Math.floor(h / (chairLength + 0.08)));

        for (int i = 0; i < perTop; i++) {
            double px = cx - w / 2 + w * (i + 0.5) / perTop;
            slots.add(new double[]{px, cy - h / 2 - gap - chairDepth / 2, 1});
            slots.add(new double[]{px, cy + h / 2 + gap + chairDepth / 2, 1});
        }
        for (int i = 0; i < perSide; i++) {
            double py = cy - h / 2 + h * (i + 0.5) / perSide;
            slots.add(new double[]{cx - w / 2 - gap - chairDepth / 2, py, 0});
            slots.add(new double[]{cx + w / 2 + gap + chairDepth / 2, py, 0});
        }

        int drawn = 0;
        for (double[] slot : slots) {
            if (drawn >= table.getSeats()) {
                break;
            }
            boolean horizontal = slot[2] == 1;
            double cw = (horizontal ? chairLength : chairDepth) * scale;
            double ch = (horizontal ? chairDepth : chairLength) * scale;
            g2.fill(new RoundRectangle2D.Double(
                    toPixelX(slot[0]) - cw / 2, toPixelY(slot[1]) - ch / 2, cw, ch, 4, 4));
            drawn++;
        }
    }

    /** Applica al contesto grafico la rotazione dell'elemento, attorno al suo centro. */
    private void applyElementTransform(Graphics2D g2, FloorElement element) {
        if (element.getRotationDegrees() != 0) {
            g2.rotate(Math.toRadians(element.getRotationDegrees()),
                    toPixelX(element.getXMeters()), toPixelY(element.getYMeters()));
        }
    }

    // ------------------------------------------------------------------
    // Selezione, maniglie e quote
    // ------------------------------------------------------------------

    private void drawSelection(Graphics2D g2, FloorElement element) {
        Shape outline = worldOutlineInPixels(element);

        g2.setColor(Palette.SELECTION);
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{5f, 4f}, 0f));
        g2.draw(outline);
        g2.setStroke(new BasicStroke(1f));

        // Segnalazione di errore: fuori dalla sala oppure sovrapposto ad altro.
        boolean outside = !plan().isInsideRoom(element);
        boolean overlapping = !plan().findOverlaps(element).isEmpty();
        if (outside || overlapping) {
            g2.setColor(Palette.alpha(Palette.DANGER, 60));
            g2.fill(outline);
        }

        if (!editable) {
            return;
        }
        for (Handle handle : Handle.values()) {
            Point2D p = handlePosition(element, handle);
            if (p == null) {
                continue;
            }
            if (handle == Handle.ROTATE) {
                g2.setColor(Palette.ACCENT);
                g2.fill(new Ellipse2D.Double(p.getX() - 5, p.getY() - 5, 10, 10));
            } else {
                g2.setColor(Palette.BG);
                g2.fill(new Rectangle2D.Double(p.getX() - HANDLE / 2.0, p.getY() - HANDLE / 2.0,
                        HANDLE, HANDLE));
                g2.setColor(Palette.SELECTION);
                g2.draw(new Rectangle2D.Double(p.getX() - HANDLE / 2.0, p.getY() - HANDLE / 2.0,
                        HANDLE, HANDLE));
            }
        }
        drawDimensionLabel(g2, element);
    }

    private Shape worldOutlineInPixels(FloorElement element) {
        AffineTransform toPixels = new AffineTransform();
        toPixels.translate(originX, originY);
        toPixels.scale(scale, scale);
        return toPixels.createTransformedShape(element.getWorldOutline());
    }

    /**
     * Riquadro con le misure reali dell'elemento.
     *
     * Mostra larghezza e altezza in metri, il rapporto fra i due lati e quanta
     * parte della sala occupa: sono le tre informazioni che servono per capire
     * se un tavolo "ci sta" davvero, e nessuna delle tre si può dedurre
     * guardando dei pixel.
     */
    private void drawDimensionLabel(Graphics2D g2, FloorElement element) {
        Rectangle2D bounds = worldOutlineInPixels(element).getBounds2D();

        String size = String.format("%.2f × %.2f m", element.getWidthMeters(), element.getHeightMeters());
        double ratio = element.getWidthMeters() / Math.max(0.001, element.getHeightMeters());
        String extra = String.format("rapporto %.2f : 1  ·  %.1f%% della sala",
                ratio, 100.0 * element.getAreaSquareMeters() / plan().getRoomAreaSquareMeters());
        if (element instanceof RestaurantTable t) {
            extra = t.getSeats() + " posti  ·  " + extra;
        }
        if (element.getRotationDegrees() != 0) {
            size += String.format("  ·  %.0f°", element.getRotationDegrees());
        }

        g2.setFont(Theme.bold(11));
        FontMetrics fmBold = g2.getFontMetrics();
        g2.setFont(Theme.regular(10));
        FontMetrics fmSmall = g2.getFontMetrics();

        int width = Math.max(fmBold.stringWidth(size), fmSmall.stringWidth(extra)) + 16;
        int height = 34;
        double bx = bounds.getCenterX() - width / 2.0;
        double by = bounds.getMinY() - height - 10;
        if (by < RULER + 4) {
            by = bounds.getMaxY() + 10;
        }
        bx = Math.max(RULER + 2, Math.min(bx, getWidth() - width - 4));

        g2.setColor(Palette.alpha(Palette.SURFACE, 240));
        g2.fill(new RoundRectangle2D.Double(bx, by, width, height, 7, 7));
        g2.setColor(Palette.alpha(Palette.SELECTION, 140));
        g2.draw(new RoundRectangle2D.Double(bx, by, width, height, 7, 7));

        g2.setFont(Theme.bold(11));
        g2.setColor(Palette.TEXT);
        g2.drawString(size, (float) (bx + 8), (float) (by + 14));
        g2.setFont(Theme.regular(10));
        g2.setColor(Palette.TEXT_MUTED);
        g2.drawString(extra, (float) (bx + 8), (float) (by + 27));
    }

    private void drawCreationPreview(Graphics2D g2) {
        Rectangle2D.Double r = normalized(createStart, createEnd);
        double x = toPixelX(r.x);
        double y = toPixelY(r.y);
        double w = r.width * scale;
        double h = r.height * scale;

        g2.setColor(Palette.alpha(Palette.ACCENT, 60));
        g2.fill(new Rectangle2D.Double(x, y, w, h));
        g2.setColor(Palette.ACCENT);
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{5f, 4f}, 0f));
        g2.draw(new Rectangle2D.Double(x, y, w, h));
        g2.setStroke(new BasicStroke(1f));

        String text = String.format("%.2f × %.2f m", r.width, r.height);
        g2.setFont(Theme.bold(11));
        g2.setColor(Palette.TEXT);
        g2.drawString(text, (float) (x + 4), (float) (y - 6));
    }

    /** Righelli graduati in metri lungo i due bordi. */
    private void drawRulers(Graphics2D g2) {
        g2.setColor(Palette.SURFACE);
        g2.fillRect(0, 0, getWidth(), RULER);
        g2.fillRect(0, 0, RULER, getHeight());
        g2.setColor(Palette.BORDER);
        g2.drawLine(0, RULER, getWidth(), RULER);
        g2.drawLine(RULER, 0, RULER, getHeight());

        g2.setFont(Theme.regular(9));
        g2.setColor(Palette.TEXT_MUTED);
        int stepMeters = scale < 24 ? 2 : 1;

        for (int m = 0; m <= (int) Math.ceil(plan().getWidthMeters()); m += stepMeters) {
            double px = toPixelX(m);
            if (px < RULER || px > getWidth()) {
                continue;
            }
            g2.drawLine((int) px, RULER - 5, (int) px, RULER - 1);
            g2.drawString(String.valueOf(m), (float) px + 2, 12);
        }
        for (int m = 0; m <= (int) Math.ceil(plan().getHeightMeters()); m += stepMeters) {
            double py = toPixelY(m);
            if (py < RULER || py > getHeight()) {
                continue;
            }
            g2.drawLine(RULER - 5, (int) py, RULER - 1, (int) py);
            g2.drawString(String.valueOf(m), 3, (float) py - 2);
        }

        g2.setColor(Palette.SURFACE);
        g2.fillRect(0, 0, RULER, RULER);
        g2.setColor(Palette.TEXT_MUTED);
        g2.setFont(Theme.bold(8));
        g2.drawString("m", 7, 14);
    }

    /** Barra di scala in basso a destra: quanti pixel misura un metro adesso. */
    private void drawScaleBar(Graphics2D g2) {
        double meters = scale > 90 ? 0.5 : (scale > 34 ? 1 : 2);
        int lengthPx = (int) (meters * scale);
        int x = getWidth() - lengthPx - 24;
        int y = getHeight() - 22;

        g2.setColor(Palette.alpha(Palette.SURFACE, 220));
        g2.fill(new RoundRectangle2D.Double(x - 10, y - 16, lengthPx + 20, 28, 6, 6));

        g2.setColor(Palette.TEXT);
        g2.setStroke(new BasicStroke(1.6f));
        g2.drawLine(x, y, x + lengthPx, y);
        g2.drawLine(x, y - 4, x, y + 4);
        g2.drawLine(x + lengthPx, y - 4, x + lengthPx, y + 4);
        g2.setStroke(new BasicStroke(1f));

        g2.setFont(Theme.regular(10));
        String text = (meters == 0.5 ? "0,5" : String.valueOf((int) meters)) + " m";
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, x + (lengthPx - fm.stringWidth(text)) / 2f, y - 6);
    }

    // ------------------------------------------------------------------
    // Maniglie
    // ------------------------------------------------------------------

    /** Posizione in pixel di una maniglia, rotazione dell'elemento compresa. */
    private Point2D handlePosition(FloorElement e, Handle handle) {
        Rectangle2D.Double b = e.getBounds();
        double cx = e.getXMeters();
        double cy = e.getYMeters();

        double mx;
        double my;
        switch (handle) {
            case NW -> { mx = b.x; my = b.y; }
            case N -> { mx = cx; my = b.y; }
            case NE -> { mx = b.getMaxX(); my = b.y; }
            case E -> { mx = b.getMaxX(); my = cy; }
            case SE -> { mx = b.getMaxX(); my = b.getMaxY(); }
            case S -> { mx = cx; my = b.getMaxY(); }
            case SW -> { mx = b.x; my = b.getMaxY(); }
            case W -> { mx = b.x; my = cy; }
            case ROTATE -> { mx = cx; my = b.y - 26 / scale; }
            default -> { return null; }
        }
        Point2D p = new Point2D.Double(mx, my);
        e.getTransform().transform(p, p);
        return new Point2D.Double(toPixelX(p.getX()), toPixelY(p.getY()));
    }

    private Handle handleAt(FloorElement e, int px, int py) {
        if (e == null || !editable) {
            return null;
        }
        for (Handle handle : Handle.values()) {
            Point2D p = handlePosition(e, handle);
            if (p != null && p.distance(px, py) <= HANDLE) {
                return handle;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Interazione
    // ------------------------------------------------------------------

    private void installListeners() {
        MouseAdapter mouse = new MouseAdapter() {

            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                pressPixels = e.getPoint();
                pressMeters = new Point2D.Double(toMeterX(e.getX()), toMeterY(e.getY()));
                modified = false;

                // Tasto centrale o tasto destro: spostamento della vista.
                if (SwingUtilities.isMiddleMouseButton(e) || SwingUtilities.isRightMouseButton(e)) {
                    panning = true;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    return;
                }

                if (editable && tool.isDrawing()) {
                    creating = true;
                    createStart = snapPoint(pressMeters);
                    createEnd = createStart;
                    return;
                }

                Handle handle = handleAt(selected, e.getX(), e.getY());
                if (handle != null) {
                    activeHandle = handle;
                    originalBounds = selected.getBounds();
                    dragging = true;
                    return;
                }

                FloorElement hit = plan().findAt(pressMeters.getX(), pressMeters.getY());
                setSelected(hit);
                if (hit != null && editable) {
                    dragging = true;
                    originalBounds = hit.getBounds();
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                double mx = toMeterX(e.getX());
                double my = toMeterY(e.getY());
                lastMouseMetersX = mx;
                lastMouseMetersY = my;
                fireCursor();

                if (panning) {
                    originX += e.getX() - pressPixels.getX();
                    originY += e.getY() - pressPixels.getY();
                    pressPixels = e.getPoint();
                    userMovedView = true;
                    repaint();
                    return;
                }
                if (creating) {
                    createEnd = snapPoint(new Point2D.Double(mx, my));
                    repaint();
                    return;
                }
                if (!dragging || selected == null || !editable) {
                    return;
                }

                if (activeHandle == Handle.ROTATE) {
                    rotateTo(mx, my, e.isShiftDown());
                } else if (activeHandle != null) {
                    resizeBy(mx, my);
                } else {
                    moveTo(mx, my);
                }
                modified = true;
                repaint();
                fireChanged();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (creating) {
                    finishCreation();
                } else if (dragging && modified) {
                    fireEdited();
                }
                dragging = false;
                panning = false;
                modified = false;
                activeHandle = null;
                setCursor(tool.isDrawing()
                        ? Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                        : Cursor.getDefaultCursor());
                repaint();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                lastMouseMetersX = toMeterX(e.getX());
                lastMouseMetersY = toMeterY(e.getY());
                fireCursor();

                FloorElement under = plan().findAt(lastMouseMetersX, lastMouseMetersY);
                if (under != hovered) {
                    hovered = under;
                    setToolTipText(tooltipFor(under));
                    repaint();
                }

                if (tool.isDrawing() && editable) {
                    setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                    return;
                }
                Handle handle = handleAt(selected, e.getX(), e.getY());
                if (handle != null) {
                    setCursor(cursorFor(handle));
                } else if (under != null && editable) {
                    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                } else {
                    setCursor(Cursor.getDefaultCursor());
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                // Si usa la rotazione "precisa": con un touchpad un piccolo
                // movimento vale meno di uno scatto e getWheelRotation()
                // restituisce 0, che con il confronto "< 0 ingrandisci,
                // altrimenti rimpicciolisci" diventava sempre uno zoom indietro.
                double rotation = e.getPreciseWheelRotation();
                if (rotation == 0) {
                    return;
                }
                zoomAt(Math.pow(1.12, -rotation), e.getX(), e.getY());
            }
        };

        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    cancelOrDeselect();
                    return;
                }
                // Le combinazioni con Ctrl (annulla, duplica...) sono gestite dal pannello.
                if (selected == null || !editable || e.isControlDown()) {
                    return;
                }
                double step = e.isShiftDown() ? plan().getGridStepMeters() : 0.05;
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_LEFT -> selected.moveBy(-step, 0);
                    case KeyEvent.VK_RIGHT -> selected.moveBy(step, 0);
                    case KeyEvent.VK_UP -> selected.moveBy(0, -step);
                    case KeyEvent.VK_DOWN -> selected.moveBy(0, step);
                    case KeyEvent.VK_R -> selected.setRotationDegrees(
                            selected.getRotationDegrees() + (e.isShiftDown() ? -15 : 15));
                    case KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE -> {
                        requestDelete(selected);
                        return;
                    }
                    default -> {
                        return;
                    }
                }
                e.consume();
                repaint();
                fireChanged();
                fireEdited();
            }
        });
    }

    private Cursor cursorFor(Handle handle) {
        return switch (handle) {
            case NW -> Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR);
            case N -> Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR);
            case NE -> Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR);
            case E -> Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR);
            case SE -> Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR);
            case S -> Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR);
            case SW -> Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR);
            case W -> Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR);
            case ROTATE -> Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
        };
    }

    private String tooltipFor(FloorElement element) {
        if (!(element instanceof RestaurantTable table)) {
            return element == null ? null : element.getKind();
        }
        Reservation r = model.findReservationForTable(table.getId(), LocalDateTime.now());
        StringBuilder sb = new StringBuilder("<html><b>Tavolo ").append(table.getNumber())
                .append("</b> — ").append(table.getSeats()).append(" posti<br>")
                .append(String.format("%.2f × %.2f m", table.getWidthMeters(), table.getHeightMeters()))
                .append("<br>Stato: ").append(table.getStatus().getLabel());
        if (r != null) {
            sb.append("<br><br><b>").append(escape(r.getGuestName())).append("</b><br>")
              .append(r.getDateTime().toLocalTime()).append(" · ")
              .append(r.getPartySize()).append(" coperti");
            if (r.hasAllergens()) {
                StringBuilder names = new StringBuilder();
                for (Allergen a : r.getAllergens()) {
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    names.append(a.getLabel());
                }
                sb.append("<br><font color='").append(Palette.hex(Palette.DANGER))
                  .append("'><b>Allergie:</b> ").append(names).append("</font>");
            }
        }
        return sb.append("</html>").toString();
    }

    /** Il testo dei suggerimenti è HTML: un nome con "<" o "&" non deve romperlo. */
    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------
    // Trasformazioni
    // ------------------------------------------------------------------

    private void moveTo(double mouseMetersX, double mouseMetersY) {
        double dx = mouseMetersX - pressMeters.getX();
        double dy = mouseMetersY - pressMeters.getY();
        double newCenterX = originalBounds.getCenterX() + dx;
        double newCenterY = originalBounds.getCenterY() + dy;
        selected.setXMeters(plan().snap(newCenterX));
        selected.setYMeters(plan().snap(newCenterY));
    }

    /**
     * Ridimensionamento tramite maniglia, con rotazione qualsiasi.
     *
     * Lo spostamento del mouse viene ruotato ALL'INDIETRO dell'angolo
     * dell'elemento: si ottiene così uno spostamento nel sistema di
     * riferimento dell'oggetto, dove le maniglie sono di nuovo semplici bordi
     * sinistro/destro/alto/basso. Al termine il nuovo centro viene riportato
     * nel sistema della sala con la rotazione diretta.
     */
    private void resizeBy(double mouseMetersX, double mouseMetersY) {
        double dxWorld = mouseMetersX - pressMeters.getX();
        double dyWorld = mouseMetersY - pressMeters.getY();

        double angle = Math.toRadians(-selected.getRotationDegrees());
        double dx = dxWorld * Math.cos(angle) - dyWorld * Math.sin(angle);
        double dy = dxWorld * Math.sin(angle) + dyWorld * Math.cos(angle);

        double left = -originalBounds.width / 2;
        double top = -originalBounds.height / 2;
        double right = originalBounds.width / 2;
        double bottom = originalBounds.height / 2;

        switch (activeHandle) {
            case NW -> { left += dx; top += dy; }
            case N -> top += dy;
            case NE -> { right += dx; top += dy; }
            case E -> right += dx;
            case SE -> { right += dx; bottom += dy; }
            case S -> bottom += dy;
            case SW -> { left += dx; bottom += dy; }
            case W -> left += dx;
            default -> { return; }
        }

        double newWidth = Math.max(MIN_SIZE, plan().snap(right - left));
        double newHeight = Math.max(MIN_SIZE, plan().snap(bottom - top));
        double localCenterX = (left + right) / 2;
        double localCenterY = (top + bottom) / 2;

        double rot = Math.toRadians(selected.getRotationDegrees());
        double worldCenterX = originalBounds.getCenterX()
                + localCenterX * Math.cos(rot) - localCenterY * Math.sin(rot);
        double worldCenterY = originalBounds.getCenterY()
                + localCenterX * Math.sin(rot) + localCenterY * Math.cos(rot);

        selected.setWidthMeters(newWidth);
        selected.setHeightMeters(newHeight);
        selected.setXMeters(worldCenterX);
        selected.setYMeters(worldCenterY);

        // Il numero di posti segue la dimensione; resta comunque modificabile
        // a mano dal pannello delle proprietà.
        if (selected instanceof RestaurantTable table) {
            table.setSeats(table.suggestedSeats());
        }
    }

    private void rotateTo(double mouseMetersX, double mouseMetersY, boolean snapToSteps) {
        double angle = Math.toDegrees(Math.atan2(
                mouseMetersY - selected.getYMeters(),
                mouseMetersX - selected.getXMeters())) + 90;
        if (snapToSteps) {
            angle = Math.round(angle / 15.0) * 15.0;
        }
        selected.setRotationDegrees(angle);
    }

    private Point2D snapPoint(Point2D meters) {
        return new Point2D.Double(plan().snap(meters.getX()), plan().snap(meters.getY()));
    }

    private Rectangle2D.Double normalized(Point2D a, Point2D b) {
        double x = Math.min(a.getX(), b.getX());
        double y = Math.min(a.getY(), b.getY());
        double w = Math.abs(a.getX() - b.getX());
        double h = Math.abs(a.getY() - b.getY());
        return new Rectangle2D.Double(x, y, w, h);
    }

    /**
     * Conclude la creazione per trascinamento e inserisce l'elemento nel Model.
     *
     * Subito dopo si torna allo strumento "Seleziona": se lo strumento di
     * disegno restasse attivo, il clic successivo per spostare l'elemento
     * appena creato ne disegnerebbe invece un altro sopra.
     */
    private void finishCreation() {
        creating = false;
        if (createStart == null || createEnd == null) {
            return;
        }
        Rectangle2D.Double r = normalized(createStart, createEnd);

        // Un semplice clic senza trascinamento crea un elemento di misura
        // predefinita: è più rapido che disegnarlo ogni volta.
        if (r.width < MIN_SIZE || r.height < MIN_SIZE) {
            double[] defaults = defaultSize(tool);
            r = new Rectangle2D.Double(createStart.getX(), createStart.getY(), defaults[0], defaults[1]);
        }

        FloorElement created;
        if (tool.createsTable()) {
            TableShape shape = tool.getTableShape();
            double w = r.width;
            double h = (shape == TableShape.ROUND || shape == TableShape.SQUARE) ? r.width : r.height;
            RestaurantTable table = new RestaurantTable(plan().nextTableNumber(),
                    r.getCenterX(), r.getCenterY(), w, h, shape);
            table.setSeats(table.suggestedSeats());
            created = table;
        } else if (tool.createsObstacle()) {
            created = new Obstacle(tool.getObstacleType(), r.getCenterX(), r.getCenterY(),
                    r.width, r.height);
        } else {
            return;
        }

        plan().add(created);
        createStart = null;
        createEnd = null;
        changeTool(ToolMode.SELEZIONE);
        setSelected(created);
        fireChanged();
        fireEdited();
    }

    private double[] defaultSize(ToolMode mode) {
        return switch (mode) {
            case TAVOLO_RETT -> new double[]{1.40, 0.90};
            case TAVOLO_QUAD -> new double[]{0.90, 0.90};
            case TAVOLO_TONDO -> new double[]{1.10, 1.10};
            case MURO -> new double[]{2.00, 0.15};
            case COLONNA -> new double[]{0.50, 0.50};
            case PORTA, FINESTRA -> new double[]{0.90, 0.15};
            case SCALE -> new double[]{2.00, 1.20};
            default -> new double[]{2.50, 2.00};
        };
    }

    /** Esc: annulla il disegno in corso, poi torna a "Seleziona", poi deseleziona. */
    private void cancelOrDeselect() {
        if (creating) {
            creating = false;
            createStart = null;
            createEnd = null;
        } else if (tool.isDrawing()) {
            changeTool(ToolMode.SELEZIONE);
        } else {
            setSelected(null);
        }
        repaint();
    }

    private void requestDelete(FloorElement element) {
        if (deleteHandler != null) {
            deleteHandler.accept(element);
            return;
        }
        plan().remove(element);
        setSelected(null);
        fireChanged();
        fireEdited();
    }

    private void changeTool(ToolMode mode) {
        setTool(mode);
        if (toolListener != null) {
            toolListener.accept(mode);
        }
    }

    // ------------------------------------------------------------------
    // Accessori
    // ------------------------------------------------------------------

    public void setSelected(FloorElement element) {
        this.selected = element;
        if (selectionListener != null) {
            selectionListener.accept(element);
        }
        repaint();
    }

    public FloorElement getSelected() {
        return selected;
    }

    /**
     * Seleziona l'elemento con l'id indicato, o nessuno se non esiste più.
     * Serve dopo annulla/ripeti: gli oggetti sono stati ricreati, gli id no.
     */
    public void selectById(int id) {
        FloorElement found = null;
        if (id != 0) {
            for (FloorElement e : plan().getElements()) {
                if (e.getId() == id) {
                    found = e;
                    break;
                }
            }
        }
        hovered = null;
        setSelected(found);
    }

    public void setTool(ToolMode tool) {
        this.tool = tool;
        if (!tool.isDrawing()) {
            creating = false;
        }
        setCursor(tool.isDrawing()
                ? Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                : Cursor.getDefaultCursor());
    }

    public ToolMode getTool() {
        return tool;
    }

    public void setEditable(boolean editable) {
        this.editable = editable;
        repaint();
    }

    public boolean isEditable() {
        return editable;
    }

    public void setShowGrid(boolean showGrid) {
        this.showGrid = showGrid;
        repaint();
    }

    public boolean isShowGrid() {
        return showGrid;
    }

    public void setShowChairs(boolean showChairs) {
        this.showChairs = showChairs;
        repaint();
    }

    public boolean isShowChairs() {
        return showChairs;
    }

    public void setShowDimensions(boolean showDimensions) {
        this.showDimensions = showDimensions;
        repaint();
    }

    public void setSelectionListener(Consumer<FloorElement> listener) {
        this.selectionListener = listener;
    }

    /** Chiamato a ogni passo di un trascinamento: per aggiornamenti leggeri. */
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    /** Chiamato quando una modifica è completa (fine del gesto): per annulla/ripeti. */
    public void setEditListener(Runnable listener) {
        this.editListener = listener;
    }

    /** Chiamato quando la tela cambia strumento da sola (dopo un disegno, con Esc). */
    public void setToolListener(Consumer<ToolMode> listener) {
        this.toolListener = listener;
    }

    /** Chi deve eliminare un elemento (per chiedere conferma, se serve). */
    public void setDeleteHandler(Consumer<FloorElement> handler) {
        this.deleteHandler = handler;
    }

    /** Chiamato quando cambiano la posizione del puntatore o lo zoom. */
    public void setCursorListener(Runnable listener) {
        this.cursorListener = listener;
    }

    private void fireChanged() {
        if (changeListener != null) {
            changeListener.run();
        }
    }

    private void fireEdited() {
        if (editListener != null) {
            editListener.run();
        }
    }

    private void fireCursor() {
        if (cursorListener != null) {
            cursorListener.run();
        }
    }

    public double getScale() {
        return scale;
    }

    public double getCursorMeterX() {
        return lastMouseMetersX;
    }

    public double getCursorMeterY() {
        return lastMouseMetersY;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(760, 520);
    }
}
