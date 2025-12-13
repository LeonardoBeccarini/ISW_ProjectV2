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

public class Proportion {

    private static final int MIN_PROPORTIONS_FOR_INCREMENT = 5;

    private static final List<String> DONORS = List.of("AVRO", "SYNCOPE", "CAMEL", "ZOOKEEPER");

    private static final double MIN_PROPORTION = 0.0;
    private static final double MAX_PROPORTION = 10.0;

    private static final int MIN_CONSISTENT_TICKETS = 5;

    private static Double cachedColdStart = null;

    private final List<Double> proportionList = new ArrayList<>();
    private double totalProportion = 0.0;

    public List<Ticket> processProportion(List<Ticket> tickets, List<Version> versions) {
        if (tickets == null || versions == null || versions.isEmpty()) {
            return new ArrayList<>();
        }

        tickets.sort(Comparator.comparing(Ticket::getResolutionDate));

        for (Ticket t : tickets) {
            if (t == null || t.getOpeningVersion() == null || t.getFixedVersion() == null) continue;

            if (t.hasIV()) {
                addProportion(t);
                continue;
            }

            double p = (proportionList.size() < MIN_PROPORTIONS_FOR_INCREMENT)
                    ? coldStart(t.getResolutionDate())
                    : increment();

            int estIVIndex = obtainIV(p, t);
            estIVIndex = Math.max(1, Math.min(estIVIndex, versions.size()));

            t.setInjectedVersion(versions.get(estIVIndex - 1));
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
        if (t.getInjectedVersion() == null || t.getOpeningVersion() == null || t.getFixedVersion() == null) return null;

        int iv = t.getInjectedVersion().getIndex();
        int ov = t.getOpeningVersion().getIndex();
        int fv = t.getFixedVersion().getIndex();

        if (fv < ov || ov < iv) return null;

        if (fv == ov) return (double) (fv - iv);
        return (double) (fv - iv) / (double) (fv - ov);
    }

    private double increment() {
        return proportionList.isEmpty() ? 0.5 : totalProportion / proportionList.size();
    }

    private double coldStart(LocalDate resolutionDate) {
        if (cachedColdStart != null) return cachedColdStart;

        List<Double> donorMeans = new ArrayList<>();
        for (String project : DONORS) {
            try {
                JiraRetriever jr = new JiraRetriever(project);
                List<Version> v = jr.retrieveVersions();
                List<Ticket> tickets = jr.retrieveTickets(v);

                List<Double> ps = new ArrayList<>();
                for (Ticket t : tickets) {
                    if (!t.hasIV()) continue;
                    Double p = computeTicketProportion(t);
                    if (p == null) continue;
                    if (!isOutlierStatic(p)) ps.add(p);
                }

                if (ps.size() >= MIN_CONSISTENT_TICKETS) {
                    donorMeans.add(mean(ps));
                }
            } catch (IOException | JSONException e) {
                // ignore
            }
        }

        cachedColdStart = donorMeans.isEmpty() ? 0.5 : median(donorMeans);
        return cachedColdStart;
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

        int estimatedIV;
        if (ov == fv) {
            estimatedIV = max(1, (int) Math.floor(fv - proportion));
        } else {
            estimatedIV = max(1, (int) Math.floor(fv - proportion * (fv - ov)));
        }

        return Math.min(ov, estimatedIV);
    }

    private boolean isOutlier(double p) {
        return isOutlierStatic(p);
    }

    private static boolean isOutlierStatic(double p) {
        return p < MIN_PROPORTION || p > MAX_PROPORTION || Double.isNaN(p) || Double.isInfinite(p);
    }
}
