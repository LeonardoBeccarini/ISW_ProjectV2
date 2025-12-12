package org.example.controller;

import org.example.model.Ticket;
import org.example.model.Version;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static java.lang.Math.max;

public class Proportion {

    private static final int MIN_PROPORTIONS_FOR_INCREMENT = 5;
    // Donors suggeriti basati sull'ecosistema Apache
    private static final List<String> DONORS = List.of("AVRO", "SYNCOPE", "CAMEL", "ZOOKEEPER");

    // Soglie per filtrare proportion anomale
    private static final double MIN_PROPORTION = 0.0;
    private static final double MAX_PROPORTION = 10.0;
    private static final int MIN_CONSISTENT_TICKETS = 5;

    private final List<Double> proportionList = new ArrayList<>();
    private double totalProportion = 0.0;

    public List<Ticket> processProportion(List<Ticket> tickets, List<Version> versions) {
        if (tickets == null || versions == null || versions.isEmpty()) {
            return new ArrayList<>();
        }

        // Ordine cronologico necessario per Proportion_Incremental
        tickets.sort(Comparator.comparing(Ticket::getResolutionDate));

        for (Ticket t : tickets) {
            if (t.getOpeningVersion() == null || t.getFixedVersion() == null) {
                continue;
            }

            // Se IV è già noto (training set), impariamo P
            if (t.hasIV()) {
                addProportion(t);
                continue;
            }

            // Se IV manca, lo stimiamo
            // Usa ColdStart se pochi dati (<5), altrimenti Incremental (media storica)
            double p = (proportionList.size() < MIN_PROPORTIONS_FOR_INCREMENT)
                    ? coldStart(t.getResolutionDate())
                    : increment();

            int estIV = obtainIV(p, t);

            // Applica IV con validazione range, fallback su OV se stima invalida
            if (estIV >= 1 && estIV <= versions.size()) {
                t.setInjectedVersion(versions.get(estIV - 1));
            } else {
                t.setInjectedVersion(t.getOpeningVersion());
            }
        }
        return tickets;
    }

    private void addProportion(Ticket ticket) {
        int ov = ticket.getOpeningVersion().getIndex();
        int fv = ticket.getFixedVersion().getIndex();
        int iv = ticket.getInjectedVersion().getIndex();

        if (iv > ov) return; // Dato inconsistente

        // Formula: P = (FV - IV) / (FV - OV)
        double denom = (ov == fv) ? 1.0 : (double) (fv - ov);
        double p = (fv - iv) / denom;

        if (!isOutlier(p)) {
            proportionList.add(p);
            totalProportion += p;
        }
    }

    private double increment() {
        return proportionList.isEmpty() ? 0.5 : totalProportion / proportionList.size();
    }

    private double coldStart(LocalDate resolutionDate) {
        List<Double> donorMeans = new ArrayList<>();

        for (String project : DONORS) {
            try {
                JiraRetriever jiraRetriever = new JiraRetriever(project);
                List<Version> versionList = jiraRetriever.retrieveVersions();
                List<Ticket> ticketList = jiraRetriever.retrieveTickets(versionList);

                List<Double> validProportions = new ArrayList<>();

                // Calcola media P per il donatore usando solo ticket validi e antecedenti
                for (Ticket t : ticketList) {
                    if (t.hasIV() && t.getResolutionDate() != null && !t.getResolutionDate().isAfter(resolutionDate)) {
                        int ov = t.getOpeningVersion().getIndex();
                        int fv = t.getFixedVersion().getIndex();
                        int iv = t.getInjectedVersion().getIndex();

                        if (iv > ov) continue;

                        double denom = (ov == fv) ? 1.0 : (double) (fv - ov);
                        double p = (fv - iv) / denom;

                        if (!isOutlier(p)) validProportions.add(p);
                    }
                }

                if (validProportions.size() >= MIN_CONSISTENT_TICKETS) {
                    double sum = validProportions.stream().mapToDouble(Double::doubleValue).sum();
                    donorMeans.add(sum / validProportions.size());
                }

            } catch (Exception e) {
                // Ignora donatore in caso di errore
            }
        }

        return donorMeans.isEmpty() ? 0.5 : calculateMedian(donorMeans);
    }

    private int obtainIV(double proportion, Ticket ticket) {
        int ov = ticket.getOpeningVersion().getIndex();
        int fv = ticket.getFixedVersion().getIndex();

        // Formula inversa: IV = FV - P * (FV - OV)
        int estimatedIV;
        if (ov == fv) {
            estimatedIV = max(1, (int) Math.floor(fv - proportion));
        } else {
            estimatedIV = max(1, (int) Math.floor(fv - proportion * (fv - ov)));
        }

        // Vincolo: IV non può essere successivo a OV
        return Math.min(ov, estimatedIV);
    }

    private boolean isOutlier(double p) {
        return p < MIN_PROPORTION || p > MAX_PROPORTION || Double.isNaN(p) || Double.isInfinite(p);
    }

    private double calculateMedian(List<Double> values) {
        if (values.isEmpty()) return 0.5;
        values.sort(Double::compareTo);
        int n = values.size();
        return (n % 2 == 1)
                ? values.get(n / 2)
                : (values.get(n / 2 - 1) + values.get(n / 2)) / 2.0;
    }
}