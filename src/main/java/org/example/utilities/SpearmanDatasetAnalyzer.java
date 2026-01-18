package org.example.utilities;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.example.utilities.CsvExporter.parseCsvLine;

/**
 * Legge dataset.csv e calcola Spearman (rho) tra feature "actionable"
 * e la colonna target "Buggy" (yes/no).
 */
public final class SpearmanDatasetAnalyzer {

    private static final List<String> ACTIONABLE_FEATURES = List.of(
            "LOC",
            "NumParameters",
            "NumBranches",
            "NestingDepth",
            "NumCodeSmells",
            "NumLocalVariables"
    );

    private static final String TARGET_COL = "Buggy";

    public static void computeCorrelation(String projectName) throws IOException {
        String proj = (projectName == null) ? "PROJECT" : projectName.trim().toUpperCase(Locale.ROOT);
        Path datasetPath = Paths.get("output", "csv", proj, "dataset.csv");

        if (!Files.exists(datasetPath)) {
            throw new IOException("dataset.csv non trovato. Atteso: " + datasetPath);
        }

        List<String> lines = Files.readAllLines(datasetPath, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IOException("dataset.csv vuoto: " + datasetPath);
        }

        // Header robusto (CSV con quoted fields)
        List<String> header = parseCsvLine(lines.getFirst());
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            col.put(header.get(i).trim(), i);
        }

        // Verifiche colonne
        for (String f : ACTIONABLE_FEATURES) {
            if (!col.containsKey(f)) {
                throw new IOException("Colonna feature mancante nel CSV: " + f + " (file: " + datasetPath + ")");
            }
        }
        if (!col.containsKey(TARGET_COL)) {
            throw new IOException("Colonna target mancante nel CSV: " + TARGET_COL + " (file: " + datasetPath + ")");
        }

        // Accumulo valori
        List<Double> y = new ArrayList<>(Math.max(0, lines.size() - 1));
        Map<String, List<Double>> xs = new LinkedHashMap<>();
        for (String f : ACTIONABLE_FEATURES) {
            xs.put(f, new ArrayList<>(Math.max(0, lines.size() - 1)));
        }

        for (int r = 1; r < lines.size(); r++) {
            String line = lines.get(r);
            if (line == null || line.isBlank()) continue;

            List<String> fields = parseCsvLine(line);

            Double yv = parseBuggy(fields, col.get(TARGET_COL));
            if (yv == null) {
                // se target mancante/strano, scartiamo la riga
                continue;
            }

            boolean ok = true;
            Map<String, Double> rowX = new HashMap<>();

            for (String f : ACTIONABLE_FEATURES) {
                int idx = col.get(f);
                Double xv = parseDoubleSafe(fields, idx);
                if (xv == null || Double.isNaN(xv) || Double.isInfinite(xv)) {
                    ok = false;
                    break;
                }
                rowX.put(f, xv);
            }

            if (!ok) continue;

            y.add(yv);
            for (String f : ACTIONABLE_FEATURES) {
                xs.get(f).add(rowX.get(f));
            }
        }

        if (y.size() < 2) {
            throw new IOException("Pochi dati validi per calcolare Spearman (righe valide=" + y.size() + "). File: " + datasetPath);
        }

        // Correlazioni
        String bestFeature = null;
        double bestRho = Double.NaN;

        System.out.println("Spearman (rho) vs target '" + TARGET_COL + "' su file: " + datasetPath);
        for (String f : ACTIONABLE_FEATURES) {
            double rho = spearman(xs.get(f), y);

            System.out.printf(Locale.ROOT, "  %-16s rho=% .6f |rho|=% .6f%n",
                    f, rho, Math.abs(rho));

            if (!Double.isNaN(rho)) {
                if (bestFeature == null || Math.abs(rho) > Math.abs(bestRho)) {
                    bestFeature = f;
                    bestRho = rho;
                }
            }
        }

        if (bestFeature == null) {
            System.out.println("Nessuna correlazione valida (tutte NaN).");
        } else {
            System.out.printf(Locale.ROOT,
                    "FEATURE PIU' CORRELATA (per |rho|): %s  rho=% .6f  |rho|=% .6f%n",
                    bestFeature, bestRho, Math.abs(bestRho));
        }
    }

    /* =========================
       =      CSV parsing       =
       ========================= */

    private static Double parseDoubleSafe(List<String> fields, int idx) {
        if (fields == null || idx < 0 || idx >= fields.size()) return null;
        String s = fields.get(idx);
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (Exception ex) {
            return null;
        }
    }

    private static Double parseBuggy(List<String> fields, int idx) {
        if (fields == null || idx < 0 || idx >= fields.size()) return null;
        String s = fields.get(idx);
        if (s == null) return null;
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.equals("yes")) return 1.0;
        if (s.equals("no")) return 0.0;
        return null;
    }

    /* =========================
       =   Spearman / Pearson   =
       ========================= */

    private static double spearman(List<Double> x, List<Double> y) {
        if (x == null || y == null || x.size() != y.size() || x.size() < 2) return Double.NaN;

        // Filtra coppie finite
        List<Double> xf = new ArrayList<>(x.size());
        List<Double> yf = new ArrayList<>(y.size());

        for (int i = 0; i < x.size(); i++) {
            double a = x.get(i);
            double b = y.get(i);
            if (Double.isFinite(a) && Double.isFinite(b)) {
                xf.add(a);
                yf.add(b);
            }
        }

        if (xf.size() < 2) return Double.NaN;

        List<Double> rx = rank(xf);
        List<Double> ry = rank(yf);
        return pearson(rx, ry);
    }

    private static List<Double> rank(List<Double> v) {
        int n = v.size();
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;

        Arrays.sort(idx, Comparator.comparingDouble(v::get));

        double[] r = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            double val = v.get(idx[i]);
            while (j < n && Double.compare(v.get(idx[j]), val) == 0) j++;

            // rank medio per tie: posizioni 1-based
            double avg = ((i + 1) + (double) j) / 2.0;
            for (int k = i; k < j; k++) r[idx[k]] = avg;
            i = j;
        }

        List<Double> out = new ArrayList<>(n);
        for (double d : r) out.add(d);
        return out;
    }

    private static double pearson(List<Double> a, List<Double> b) {
        int n = a.size();
        if (n < 2) return Double.NaN;

        double ma = 0.0, mb = 0.0;
        for (int i = 0; i < n; i++) {
            ma += a.get(i);
            mb += b.get(i);
        }
        ma /= n;
        mb /= n;

        double num = 0.0, da = 0.0, db = 0.0;
        for (int i = 0; i < n; i++) {
            double xa = a.get(i) - ma;
            double xb = b.get(i) - mb;
            num += xa * xb;
            da += xa * xa;
            db += xb * xb;
        }

        if (da == 0.0 || db == 0.0) return Double.NaN;
        return num / Math.sqrt(da * db);
    }
}
