package org.example.model;

import org.eclipse.jgit.revwalk.RevCommit;

import java.time.LocalDate;
import java.util.List;

public class Ticket {
    private String key;
    private final LocalDate creationDate;
    private final LocalDate resolutionDate;
    private final List<Version> affectedVersions;
    private Version injectedVersion;
    private Version openingVersion;
    private Version fixedVersion;
    private List<RevCommit> associatedCommits;

    public Ticket(String key, LocalDate creationDate, LocalDate resolutionDate, List<Version> affectedVersions) {
        this.key = key;
        this.creationDate = creationDate;
        this.resolutionDate = resolutionDate;
        this.affectedVersions = affectedVersions;
    }


    public boolean hasIV() {
        return injectedVersion != null;
    }

    public String getKey() {
        return key;
    }

    public LocalDate getCreationDate() {
        return creationDate;
    }

    public LocalDate getResolutionDate() {
        return resolutionDate;
    }

    public List<RevCommit> getAssociatedCommits() {
        return associatedCommits;
    }

    public void setAssociatedCommits(List<RevCommit> associatedCommits) {
        this.associatedCommits = associatedCommits;
    }

    public Version getInjectedVersion() {
        return injectedVersion;
    }

    public Version getOpeningVersion() {
        return openingVersion;
    }

    public Version getFixedVersion() {
        return fixedVersion;
    }

    public List<Version> getAffectedVersions() {
        return affectedVersions;
    }

    /**
     * Imposta una IV "temporanea" a partire dalla prima AV, solo se coerente.
     * <p>
     * Regola (option 2): se la IV candidata non è valida rispetto a OV/FV,
     * lasciamo IV=null così Proportion farà la stima.
     */
    public void setInjectedVersionTemp() {
        // default: nessuna IV nota
        this.injectedVersion = null;

        if (affectedVersions == null || affectedVersions.isEmpty()) {
            return;
        }

        Version candidate = affectedVersions.getFirst();
        if (candidate == null) {
            return;
        }

        // senza OV/FV non possiamo validare -> forziamo la stima
        if (openingVersion == null || fixedVersion == null) {
            return;
        }

        int iv = candidate.getIndex();
        int ov = openingVersion.getIndex();
        int fv = fixedVersion.getIndex();

        // indici non inizializzati? meglio stimare.
        if (iv <= 0 || ov <= 0 || fv <= 0) {
            return;
        }

        // coerenza minima: IV deve essere <= OV e <= FV (FV può essere == OV)
        if (iv <= ov && iv <= fv) {
            this.injectedVersion = candidate;
        }
    }

    public void setKey(String key) {
        this.key = key;
    }

    public void setInjectedVersion(Version injectedVersion) {
        this.injectedVersion = injectedVersion;
    }

    public void setOpeningVersion(Version openingVersion) {
        this.openingVersion = openingVersion;
    }

    public void setFixedVersion(Version fixedVersion) {
        this.fixedVersion = fixedVersion;
    }

}
