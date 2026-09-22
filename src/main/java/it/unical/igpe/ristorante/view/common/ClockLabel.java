package it.unical.igpe.ristorante.view.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import javax.swing.JLabel;
import javax.swing.Timer;

/**
 * Orologio sempre aggiornato, presente su ogni schermata.
 *
 * Usa javax.swing.Timer e NON un Thread: il Timer di Swing esegue il proprio
 * ascoltatore direttamente sull'Event Dispatch Thread, quindi aggiornare il
 * testo dell'etichetta è sicuro. Con un Thread normale bisognerebbe passare
 * da SwingUtilities.invokeLater, perché i componenti Swing non sono thread-safe.
 */
public class ClockLabel extends JLabel {

    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter FULL =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy  •  HH:mm:ss", Locale.ITALIAN);
    private static final DateTimeFormatter SHORT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ITALIAN);

    private final Timer timer;
    private final DateTimeFormatter formatter;

    public ClockLabel() {
        this(true);
    }

    public ClockLabel(boolean showDate) {
        this.formatter = showDate ? FULL : SHORT;
        setFont(Theme.regular(12));
        setForeground(Palette.TEXT_MUTED);
        update();

        // Un colpo al secondo: più che sufficiente per un orologio,
        // e trascurabile in termini di carico.
        timer = new Timer(1000, e -> update());
        timer.start();
    }

    private void update() {
        String text = LocalDateTime.now().format(formatter);
        // La prima lettera del giorno della settimana in maiuscolo.
        if (!text.isEmpty()) {
            text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        }
        setText(text);
    }

    /**
     * Fermare il timer quando il componente esce dalla gerarchia evita che
     * continui a girare (e a tenere vivo il riferimento) dopo la chiusura
     * della finestra.
     */
    @Override
    public void removeNotify() {
        timer.stop();
        super.removeNotify();
    }
}
