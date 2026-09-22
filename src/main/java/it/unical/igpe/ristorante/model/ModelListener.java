package it.unical.igpe.ristorante.model;

/**
 * Interfaccia che una View implementa per essere avvisata dei cambiamenti
 * del Model. È l'applicazione del pattern Observer descritto nelle slide
 * sull'MVC.
 */
public interface ModelListener {

    void onModelChanged(ModelEvent event);
}
