package org.example.controller;

import org.example.model.Ticket;
import org.example.model.Version;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static java.lang.Math.max;

public class Proportion {

    private final List<Double> proportionList = new ArrayList<>();
    private double totalProportion = 0.0;

    private static final int MIN_PROPORTIONS_FOR_INCREMENT = 5;
    private static final List<String> DONORS = List.of("AVRO", "SYNCOPE", "STORM", "ZOOKEEPER");

    /** Processa in ordine di risoluzione; usa cold-start finché poche proporzioni, poi increment. */
    public List<Ticket> processProportion(List<Ticket> tickets, List<Version> versions) {
        if (tickets == null || versions == null || versions.isEmpty()) return tickets;

        tickets.sort(Comparator.comparing(Ticket::getResolutionDate));

        for (Ticket t : tickets) {
            // Se IV noto (da JIRA/AV), aggiorna la serie e continua
            if (t.hasIV()) {
                addProportion(t);
                continue;
            }
            // Stima proporzione: cold-start (donors) se poche osservazioni, poi media cumulativa
            double p = (proportionList.size() < MIN_PROPORTIONS_FOR_INCREMENT)
                    ? coldStart(t.getResolutionDate())
                    : increment();

            int estIV = obtainIV(p, t);

            // Applica IV stimato (senza aggiornare la media: niente feedback loop)
            if (estIV >= 1 && estIV <= versions.size()) {
                t.setInjectedVersion(versions.get(estIV - 1));
            }
        }
        return tickets;
    }

    /** Aggiunge una proporzione calcolata da un ticket con IV/OV/FV noti. */
    private void addProportion(Ticket ticket) {
        if (ticket.getInjectedVersion() == null || ticket.getOpeningVersion() == null || ticket.getFixedVersion() == null) return;
        int ov = ticket.getOpeningVersion().getIndex();
        int fv = ticket.getFixedVersion().getIndex();
        int iv = ticket.getInjectedVersion().getIndex();
        int denom = (ov == fv) ? 1 : (fv - ov);
        double p = (double) (fv - iv) / (double) denom;
        proportionList.add(p);
        totalProportion += p;
    }

    /** Media cumulativa delle proporzioni osservate (increment). */
    private double increment() {
        return totalProportion / Math.max(1, proportionList.size());
    }

    /** Cold-start: mediana delle medie dei progetti donatori, filtrando ticket consistenti fino a resolutionDate. */
    private double coldStart(LocalDate resolutionDate) {
        List<Double> donorMeans = new ArrayList<>();
        for (String project : DONORS) {
            try {
                JiraRetriever jiraRetriever = new JiraRetriever(project);
                List<Version> versionList = jiraRetriever.retrieveVersions();
                List<Ticket> ticketList= jiraRetriever.retrieveTickets(versionList);

                // ticket "consistenti": IV noto e risolti entro la data corrente
                List<Ticket> consistent = new ArrayList<>();
                for (Ticket t : ticketList) {
                    if (t.hasIV()
                            && t.getResolutionDate() != null
                            && !t.getResolutionDate().isAfter(resolutionDate)) {
                        consistent.add(t);
                    }
                }
                if (consistent.size() >= 5) {
                    double sum = 0.0;
                    for (Ticket t : consistent) {
                        int ov = t.getOpeningVersion().getIndex();
                        int fv = t.getFixedVersion().getIndex();
                        int iv = t.getInjectedVersion().getIndex();
                        int denom = (ov == fv) ? 1 : (fv - ov);
                        double p = (double) (fv - iv) / (double) denom;
                        sum += p;
                    }
                    donorMeans.add(sum / consistent.size());
                }
            } catch (Exception ignored) {
                // robustezza: se un donatore fallisce lo saltiamo
            }
        }
        if (donorMeans.isEmpty()) return 0.5; // fallback sobrio
        donorMeans.sort(Double::compareTo);
        int n = donorMeans.size();
        return (n % 2 == 1) ? donorMeans.get(n / 2)
                : (donorMeans.get(n / 2 - 1) + donorMeans.get(n / 2)) / 2.0;
    }

    /** Stima IV con clamp a [1, OV] per evitare IV future rispetto a OV. */
    private int obtainIV(double proportion, Ticket ticket) {
        if (ticket.getOpeningVersion() == null || ticket.getFixedVersion() == null) return 1;
        int ov = ticket.getOpeningVersion().getIndex();
        int fv = ticket.getFixedVersion().getIndex();
        int estimatedIV = (ov != fv)
                ? max(1, (int) Math.floor(fv - proportion * (fv - ov)))
                : max(1, (int) Math.floor(fv - proportion));
        return Math.min(ov, estimatedIV);
    }
}
