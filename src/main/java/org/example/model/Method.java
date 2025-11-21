package org.example.model;

import org.eclipse.jgit.revwalk.RevCommit;

import java.util.ArrayList;
import java.util.List;

/**
 * Rappresenta un metodo Java in una specifica Version.
 * Contiene l'identità del metodo, la Version e tutte le metriche associate.
 */
public class Method {

    /** path del file + "/" + signature (nomeMetodo(tipoParam1, tipoParam2, ...)) */
    private final String fullyQualifiedName;

    /** Versione del progetto a cui appartiene questa istanza del metodo. */
    private final Version version;

    /** Oggetto che contiene tutte le metriche statiche + di processo. */
    private final Metrics metrics;

    /** Commit che hanno modificato questo metodo (fino a questa Version). */
    private final List<RevCommit> commits = new ArrayList<>();

    /** Hash del body normalizzato, usato per rilevare modifiche tra versioni. */
    private String bodyHash;

    /** Etichetta di buggyness per questa istanza del metodo. */
    private boolean buggy;

    public Method(String fullyQualifiedName, Version version) {
        this.fullyQualifiedName = fullyQualifiedName;
        this.version = version;
        this.metrics = new Metrics();
    }

    public String getFullyQualifiedName() {
        return fullyQualifiedName;
    }

    public Version getVersion() {
        return version;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public List<RevCommit> getCommits() {
        return commits;
    }

    public String getBodyHash() {
        return bodyHash;
    }

    public void setBodyHash(String bodyHash) {
        this.bodyHash = bodyHash;
    }

    public boolean isBuggy() {
        return buggy;
    }

    public void setBuggy(boolean buggy) {
        this.buggy = buggy;
    }
}
