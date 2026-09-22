package it.unical.igpe.ristorante.view;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import it.unical.igpe.ristorante.AppConfig;
import it.unical.igpe.ristorante.Main;
import it.unical.igpe.ristorante.persistence.Database;
import it.unical.igpe.ristorante.persistence.SeedData;
import it.unical.igpe.ristorante.view.common.Card;
import it.unical.igpe.ristorante.view.common.Palette;
import it.unical.igpe.ristorante.view.common.Theme;
import it.unical.igpe.ristorante.view.common.Ui;

/**
 * Schermata di avvio: si sceglie che cosa deve essere questa istanza.
 *
 * L'applicazione è pensata per girare in più copie contemporaneamente sulla
 * stessa macchina o su macchine diverse: un server, una o più postazioni di
 * sala, uno o più monitor di cucina. Questa finestra rende evidente
 * l'architettura invece di nasconderla dietro dei parametri da riga di comando.
 */
public class LauncherFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    public LauncherFrame() {
        setTitle("RistoManager");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 480);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(0, 20));
        root.setBackground(Palette.BG);
        root.setBorder(BorderFactory.createEmptyBorder(28, 30, 22, 30));

        JPanel header = Ui.column();
        JLabel title = new JLabel("RistoManager");
        title.setFont(Theme.bold(28));
        title.setForeground(Palette.ACCENT);
        header.add(Ui.alignLeft(title));
        header.add(Ui.vGap(6));
        header.add(Ui.alignLeft(Ui.muted(
                "Scegli il ruolo di questa postazione. Per la dimostrazione completa apri tre copie "
                + "del programma: il server, una postazione di sala e una di cucina.")));
        root.add(header, BorderLayout.NORTH);

        JPanel choices = new JPanel(new GridLayout(1, 3, 16, 0));
        choices.setOpaque(false);
        choices.add(choice("Server comande",
                "Smista le comande fra sala e cucina e ne conserva lo storico. "
                + "Le postazioni si collegano da sole appena parte.", Palette.ACCENT, this::startServer));
        choices.add(choice("Postazione di sala",
                "Prenotazioni, piantina della sala e invio delle comande alla cucina.",
                Palette.TEAL, () -> startClient("Sala")));
        choices.add(choice("Monitor di cucina",
                "Riceve le comande in tempo reale e ne segue lo stato di preparazione.",
                Palette.VIP, () -> startClient("Cucina")));
        root.add(choices, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        JLabel course = Ui.muted("Interfacce Grafiche e Programmazione ad Eventi");
        course.setFont(Theme.regular(11));
        footer.add(course, BorderLayout.WEST);

        JButton reset = Ui.toolButton("Ripristina dati di esempio…");
        reset.setToolTipText("Ricrea sala, utenti e prenotazioni dimostrative con le date di oggi");
        reset.addActionListener(e -> resetDemoData());
        footer.add(reset, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

        setContentPane(root);
    }

    /** Riquadro cliccabile, con effetto di sorvolo. */
    private JComponent choice(String title, String description, Color accent, Runnable action) {
        Card card = new Card(new BorderLayout(0, 10));
        card.fill(Palette.SURFACE).border(Palette.BORDER);
        card.setBorder(BorderFactory.createEmptyBorder(20, 18, 18, 18));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel name = new JLabel(title);
        name.setFont(Theme.bold(16));
        name.setForeground(accent);

        JLabel text = new JLabel(Ui.wrapped(description, 200));
        text.setFont(Theme.regular(12));
        text.setForeground(Palette.TEXT_MUTED);

        JLabel go = new JLabel("Avvia →");
        go.setFont(Theme.bold(12));
        go.setForeground(Palette.TEXT);

        card.add(name, BorderLayout.NORTH);
        card.add(text, BorderLayout.CENTER);
        card.add(go, BorderLayout.SOUTH);
        card.setPreferredSize(new Dimension(240, 200));

        card.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                action.run();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                card.fill(Palette.SURFACE_2).border(accent);
                card.repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                card.fill(Palette.SURFACE).border(Palette.BORDER);
                card.repaint();
            }
        });
        return card;
    }

    private void startServer() {
        Main.startServer();
        dispose();
    }

    private void startClient(String station) {
        Main.startClient(station);
        dispose();
    }

    /**
     * Riporta il database allo stato del primo avvio, con le date di oggi.
     *
     * Chiede conferma perché cancella anche tutto ciò che è stato inserito a
     * mano. Va fatto con le altre postazioni chiuse: quelle aperte terrebbero
     * in memoria i dati vecchi fino al prossimo allineamento.
     */
    private void resetDemoData() {
        int choice = JOptionPane.showConfirmDialog(this,
                "Prenotazioni, comande, utenti e piantina verranno cancellati e sostituiti\n"
                + "dai dati dimostrativi, con le date di oggi.\n\n"
                + "Chiudi prima le altre postazioni aperte. Procedere?",
                "Ripristina dati di esempio", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        try (Database database = new Database(AppConfig.getDatabaseFile())) {
            SeedData.resetDemoData(database);
            JOptionPane.showMessageDialog(this,
                    "Dati di esempio ripristinati. Utenti: admin, mrossi, gbianchi, stage.",
                    "Fatto", JOptionPane.INFORMATION_MESSAGE);
        } catch (RuntimeException e) {
            Main.showError(e);
        }
    }
}
