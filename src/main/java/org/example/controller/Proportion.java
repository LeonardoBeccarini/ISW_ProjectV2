package org.example.controller;

import org.example.model.Ticket;
import org.example.model.Version;
import org.json.JSONException;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static java.lang.Math.max;

/**
 * Proportion (reference-like) con le tue richieste:
 * - stima IV SOLO quando IV è null
 * - FIX outlier: se OV==FV e IV è null => IV = OV (evita backshift sulla release precedente)
 * - cold start filtrato per resolutionDate (donor consistent tickets)
 */
public class Proportion {

    private static final int MIN_PROPORTIONS_FOR_INCREMENT = 5;

    // Donor (reference)
    private static final List<String> DONORS = List.of("AVRO", "SYNCOPE", "STORM", "ZOOKEEPER");

    private static final double MIN_PROPORTION = 0.0;
    private static final double MAX_PROPORTION = 10.0;
    private static final int MIN_CONSISTENT_TICKETS = 5;

    private final List<Double> proportionList = new ArrayList<>();
    private double totalProportion = 0.0;

    public List<Ticket> processProportion(List<Ticket> tickets, List<Version> versions) {
        if (tickets == null || versions == null || versions.isEmpty()) {
            return new ArrayList<>();
        }

        tickets.sort(Comparator.comparing(
                Ticket::getResolutionDate,
                Comparator.nullsLast(Comparator.naturalOrder())
        ));

        for (Ticket t : tickets) {
            if (t == null || t.getOpeningVersion() == null || t.getFixedVersion() == null) {
                continue;
            }

            // Guard: se IV presente ma incoerente (può capitare per dati sporchi), azzera e stima
            if (t.hasIV() && computeTicketProportion(t) == null) {
                t.setInjectedVersion(null);
            }

            // Stima SOLO quando IV è null
            if (!t.hasIV()) {
                int ov = t.getOpeningVersion().getIndex();
                int fv = t.getFixedVersion().getIndex();

                // FIX outlier: se OV == FV e IV è sconosciuta, scelta neutra => IV = OV
                // (evita che fv - p spinga indietro e marchi massivamente la release precedente)
                if (ov == fv) {
                    t.setInjectedVersion(t.getOpeningVersion());
                    continue;
                }

                double p = (proportionList.size() < MIN_PROPORTIONS_FOR_INCREMENT)
                        ? coldStart(t.getResolutionDate())
                        : increment();

                int estIVIndex = obtainIV(p, t);
                estIVIndex = Math.max(1, Math.min(estIVIndex, versions.size()));

                t.setInjectedVersion(versions.get(estIVIndex - 1));
                continue;
            }

            // Ticket con IV già nota: aggiorna storico proporzioni
            addProportion(t);
        }

        return tickets;
    }

    private void addProportion(Ticket t) {
        Double p = computeTicketProportion(t);
        if (p == null || isOutlier(p)) return;

        proportionList.add(p);
        totalProportion += p;
    }

    private static Double computeTicketProportion(Ticket t) {
        if (t.getInjectedVersion() == null || t.getOpeningVersion() == null || t.getFixedVersion() == null) {
            return null;
        }

        int iv = t.getInjectedVersion().getIndex();
        int ov = t.getOpeningVersion().getIndex();
        int fv = t.getFixedVersion().getIndex();

        // lifecycle coerente: IV <= OV <= FV
        if (fv < ov || iv > ov) return null;

        // come reference: evita div-by-zero (equivalente a denominatore=1)
        if (fv == ov) {
            return (double) (fv - iv);
        }

        return (double) (fv - iv) / (double) (fv - ov);
    }

    private double increment() {
        return proportionList.isEmpty() ? 0.5 : totalProportion / proportionList.size();
    }

    private double coldStart(LocalDate resolutionDate) {
        List<Double> donorMeans = new ArrayList<>();

        for (String project : DONORS) {
            try {
                JiraRetriever jr = new JiraRetriever(project);
                List<Version> versions = jr.retrieveVersions();
                List<Ticket> tickets = jr.retrieveTickets(versions);

                List<Double> ps = new ArrayList<>();
                for (Ticket t : tickets) {
                    if (t == null) continue;
                    if (!t.hasIV()) continue; // donor: usa solo IV note (seed)
                    if (t.getResolutionDate() == null) continue;

                    // Reference-like: solo ticket risolti prima/alla stessa data
                    if (resolutionDate != null && t.getResolutionDate().isAfter(resolutionDate)) continue;

                    Double p = computeTicketProportion(t);
                    if (p == null) continue;
                    if (!isOutlierStatic(p)) ps.add(p);
                }

                if (ps.size() >= MIN_CONSISTENT_TICKETS) {
                    donorMeans.add(mean(ps));
                }
            } catch (IOException | JSONException e) {
                // ignore donor failures
            }
        }

        return donorMeans.isEmpty() ? 0.5 : median(donorMeans);
    }

    private static double mean(List<Double> values) {
        double s = 0.0;
        for (double v : values) s += v;
        return s / (double) values.size();
    }

    private static double median(List<Double> values) {
        values.sort(Double::compareTo);
        int n = values.size();
        return (n % 2 == 1)
                ? values.get(n / 2)
                : (values.get(n / 2 - 1) + values.get(n / 2)) / 2.0;
    }

    int obtainIV(double proportion, Ticket ticket) {
        int ov = ticket.getOpeningVersion().getIndex();
        int fv = ticket.getFixedVersion().getIndex();

        // FIX: niente backshift quando OV==FV
        if (ov == fv) {
            return ov;
        }

        int estimatedIV = max(1, (int) Math.floor(fv - proportion * (fv - ov)));

        // IV non può essere dopo OV
        return Math.min(ov, estimatedIV);
    }

    private boolean isOutlier(double p) {
        return isOutlierStatic(p);
    }

    private static boolean isOutlierStatic(double p) {
        return p < MIN_PROPORTION || p > MAX_PROPORTION || Double.isNaN(p) || Double.isInfinite(p);
    }
}
