package org.example.controller;

import org.example.model.Ticket;
import org.example.model.Version;
import org.json.JSONException;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Proportion:
 * - stima IV SOLO quando IV è null
 * - se OV==FV e IV è null => IV = OV
 * - cold start filtrato per resolutionDate (donor consistent tickets)
 */
public class Proportion {

    private static final int MIN_PROPORTIONS_FOR_INCREMENT = 5;

    // Donor projects
    private static final List<String> DONORS = List.of("AVRO", "SYNCOPE", "TAJO", "ZOOKEEPER");

    private static final double MIN_PROPORTION = 0.0;
    private static final double MAX_PROPORTION = 10.0;
    private static final int MIN_CONSISTENT_TICKETS = 5;

    private static final double DEFAULT_FALLBACK_PROPORTION = 0.5;

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
            if (!isProcessableTicket(t)) {
                continue;
            }

            // Guard: se IV presente ma incoerente, azzera e stima
            normalizeInjectedVersionIfIncoherent(t);

            if (t.hasIV()) {
                // Ticket con IV già nota: aggiorna storico proporzioni
                addProportion(t);
            } else {
                // Stima SOLO quando IV è null
                estimateInjectedVersion(t, versions);
            }
        }

        return tickets;
    }

    /* =========================================================
       =                    Core helpers                        =
       ========================================================= */

    private static boolean isProcessableTicket(Ticket t) {
        return t != null && t.getOpeningVersion() != null && t.getFixedVersion() != null;
    }

    private static void normalizeInjectedVersionIfIncoherent(Ticket t) {
        if (!t.hasIV()) {
            return;
        }
        // Se non possiamo calcolare una proporzione valida, l'IV è da considerarsi sporca -> va stimata
        if (computeTicketProportion(t) == null) {
            t.setInjectedVersion(null);
        }
    }

    private void estimateInjectedVersion(Ticket t, List<Version> versions) {
        int ov = t.getOpeningVersion().getIndex();
        int fv = t.getFixedVersion().getIndex();

        // FIX outlier: se OV == FV e IV è sconosciuta, scelta neutra => IV = OV
        if (ov == fv) {
            t.setInjectedVersion(t.getOpeningVersion());
            return;
        }

        double p = (proportionList.size() < MIN_PROPORTIONS_FOR_INCREMENT)
                ? coldStart(t.getResolutionDate())
                : increment();

        int estIVIndex = obtainIV(p, t);
        estIVIndex = clampOneBasedIndex(estIVIndex, versions.size());

        t.setInjectedVersion(versions.get(estIVIndex - 1));
    }

    private static int clampOneBasedIndex(int oneBasedIndex, int size) {
        //size dovrebbe essere >0 perché versions è non vuota
        int max = Math.max(size, 1);
        return Math.clamp(oneBasedIndex, 1, max);
    }

    private void addProportion(Ticket t) {
        Double p = computeTicketProportion(t);
        if (p == null || isOutlierStatic(p)) {
            return;
        }
        proportionList.add(p);
        totalProportion += p;
    }

    /**
     * Calcolo P come:
     * P = (FV - IV) / (FV - OV), con:
     * - lifecycle coerente: IV <= OV <= FV
     * - se FV == OV: denominatore 0 -> usa differenza (FV - IV)
     */
    private static Double computeTicketProportion(Ticket t) {
        if (t == null || t.getInjectedVersion() == null || t.getOpeningVersion() == null || t.getFixedVersion() == null) {
            return null;
        }

        int iv = t.getInjectedVersion().getIndex();
        int ov = t.getOpeningVersion().getIndex();
        int fv = t.getFixedVersion().getIndex();

        // lifecycle coerente: IV <= OV <= FV
        if (fv < ov || iv > ov) {
            return null;
        }

        // evita div-by-zero (come prima)
        if (fv == ov) {
            return (double) (fv - iv);
        }

        return (double) (fv - iv) / (double) (fv - ov);
    }

    private double increment() {
        return proportionList.isEmpty()
                ? DEFAULT_FALLBACK_PROPORTION
                : totalProportion /proportionList.size();
    }

    /* =========================================================
       =                    Cold start                          =
       ========================================================= */

    private double coldStart(LocalDate resolutionDate) {
        List<Double> donorMeans = new ArrayList<>();

        for (String project : DONORS) {
            Double mean = tryComputeDonorMean(project, resolutionDate);
            if (mean != null) {
                donorMeans.add(mean);
            }
        }

        return donorMeans.isEmpty() ? DEFAULT_FALLBACK_PROPORTION : median(donorMeans);
    }

    private Double tryComputeDonorMean(String project, LocalDate cutoffResolutionDate) {
        try {
            JiraRetriever jr = new JiraRetriever(project);
            List<Version> versions = jr.retrieveVersions();
            List<Ticket> tickets = jr.retrieveTickets(versions);

            List<Double> ps = collectValidDonorProportions(tickets, cutoffResolutionDate);
            if (ps.size() < MIN_CONSISTENT_TICKETS) {
                return null;
            }
            return mean(ps);

        } catch (IOException | JSONException _) {
            // ignora donor failures
            return null;
        }
    }

    private static List<Double> collectValidDonorProportions(List<Ticket> tickets, LocalDate cutoffResolutionDate) {
        List<Double> ps = new ArrayList<>();
        if (tickets == null || tickets.isEmpty()) {
            return ps;
        }

        for (Ticket t : tickets) {
            if (isEligibleDonorTicket(t, cutoffResolutionDate)) {
                Double p = computeTicketProportion(t);
                if (p != null && !isOutlierStatic(p)) {
                    ps.add(p);
                }
            }
        }
        return ps;
    }

    private static boolean isEligibleDonorTicket(Ticket t, LocalDate cutoffResolutionDate) {
        if (t == null) {
            return false;
        }
        if (!t.hasIV()) {
            return false; // donor: usa solo IV note (seed)
        }
        LocalDate r = t.getResolutionDate();
        if (r == null) {
            return false;
        }
        // Usa solo ticket risolti prima/alla stessa data (se cutoff non null)
        return cutoffResolutionDate == null || !r.isAfter(cutoffResolutionDate);
    }

    private static double mean(List<Double> values) {
        double s = 0.0;
        for (double v : values) {
            s += v;
        }
        return s /values.size();
    }

    private static double median(List<Double> values) {
        values.sort(Double::compareTo);
        int n = values.size();
        return ((n & 1) == 1)
                ? values.get(n / 2)
                : (values.get((n / 2) - 1) + values.get(n / 2)) / 2.0;
    }

    /* =========================================================
       =                    IV estimation                        =
       ========================================================= */

    int obtainIV(double proportion, Ticket ticket) {
        int ov = ticket.getOpeningVersion().getIndex();
        int fv = ticket.getFixedVersion().getIndex();

        // FIX: niente backshift quando OV==FV
        if (ov == fv) {
            return ov;
        }

        int raw = (int) Math.floor(fv - proportion * (fv - ov));

        // equivalente a max(1, raw) ma usando Math.clamp
        int estimatedIV = Math.clamp(raw, 1, Integer.MAX_VALUE);

        // equivalente a Math.min(ov, estimatedIV) senza Math.min
        return Math.min(estimatedIV, ov);
    }

    /* =========================================================
       =                    Outliers                             =
       ========================================================= */

    private static boolean isOutlierStatic(double p) {
        return p < MIN_PROPORTION || p > MAX_PROPORTION || Double.isNaN(p) || Double.isInfinite(p);
    }
}
