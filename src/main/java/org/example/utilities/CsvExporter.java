package org.example.utilities;

import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.example.model.ClassifierEvaluation;
import org.example.model.Method;
import org.example.model.Metrics;
import org.example.model.Ticket;
import org.example.model.Version;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Classe responsabile delle le operazioni I/O su file CSV.
 */
public final class CsvExporter {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private CsvExporter() { }

    // ================================================================
    //                    RECORD PER DATI CSV
    // ================================================================

    /**
     * Rappresenta un file CSV letto in memoria.
     */
    public record CsvData(
            Map<String, Integer> columnIndex,
            List<String> headerNames,
            List<List<String>> rows
    ) {
        public String getValue(int rowIndex, String columnName) {
            Integer colIdx = columnIndex.get(columnName);
            if (colIdx == null || rowIndex < 0 || rowIndex >= rows.size()) {
                return "";
            }
            List<String> row = rows.get(rowIndex);
            return (colIdx < row.size()) ? row.get(colIdx) : "";
        }

        public int getRowCount() {
            return rows.size();
        }

        public boolean hasColumn(String name) {
            return columnIndex.containsKey(name);
        }
    }

    /**
     * Dati di refactoring (BEFORE/AFTER) per una feature.
     */
    public record RefactorDelta(double before, double after, double factor) {
        public static RefactorDelta compute(double before, double after) {
            double factor;
            if (Math.abs(before) > 1e-9) {
                factor = after / before;
            } else {
                factor = 0.0;
            }
            if (Double.isNaN(factor) || Double.isInfinite(factor)) {
                factor = 1.0;
            }
            if (factor < 0.0) {
                factor = 0.0;
            }
            return new RefactorDelta(before, after, factor);
        }
    }

    /**
     * Risultato di una valutazione What-If su un dataset.
     */
    public record WhatIfResult(
            String datasetName,
            int n,
            int actualBuggy,
            double expectedDefectsSum,
            int estimatedBuggyThreshold05,
            int estimatedBuggyClassify
    ) {}

    // ================================================================
    //                 LETTURA FILE CSV - GENERICA
    // ================================================================

    /**
     * Legge un file CSV e restituisce i dati strutturati.
     */
    public static CsvData readCsvFile(Path csvPath) throws IOException {
        List<String> lines = readAllLines(csvPath);
        if (lines.isEmpty()) {
            return new CsvData(Map.of(), List.of(), List.of());
        }

        List<String> headerNames = parseCsvLine(lines.get(0));
        Map<String, Integer> columnIndex = new HashMap<>();
        for (int i = 0; i < headerNames.size(); i++) {
            columnIndex.put(headerNames.get(i).trim(), i);
        }

        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line != null && !line.isBlank()) {
                rows.add(parseCsvLine(line));
            }
        }

        return new CsvData(columnIndex, headerNames, rows);
    }

    /**
     * Legge tutte le righe di un file CSV con validazione.
     */
    public static List<String> readAllLines(Path csvPath) throws IOException {
        if (!Files.exists(csvPath)) {
            throw new IOException("File CSV non trovato: " + csvPath);
        }
        List<String> lines = Files.readAllLines(csvPath, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IOException("File CSV vuoto: " + csvPath);
        }
        return lines;
    }

    /**
     * Valida che le colonne richieste siano presenti.
     */
    public static void validateColumns(CsvData data, Path filePath, String... requiredColumns) throws IOException {
        for (String col : requiredColumns) {
            if (!data.hasColumn(col)) {
                throw new IOException("Colonna mancante nel CSV: " + col + " (file: " + filePath + ")");
            }
        }
    }

    // ================================================================
    //              LETTURA SPECIALIZZATA - EVALUATIONS
    // ================================================================

    /**
     * Record per gli indici delle colonne di valutazione.
     */
    public record EvalColumnIndices(int proj, int iter, int model, int fs, int bal, int cost, int auc, int mcc) {
        public static EvalColumnIndices fromHeader(Map<String, Integer> idx) {
            return new EvalColumnIndices(
                    idx.getOrDefault("PROJ", -1),
                    requireColumnIndex(idx, "WF_ITER"),
                    requireColumnIndex(idx, "MODEL"),
                    requireColumnIndex(idx, "FEATURE_SELECTION"),
                    requireColumnIndex(idx, "BALANCING"),
                    requireColumnIndex(idx, "COST_SENSITIVE"),
                    requireColumnIndex(idx, "AUC"),
                    requireColumnIndex(idx, "MCC")
            );
        }
    }

    /**
     * Legge le valutazioni dei classificatori da un CSV.
     */
    public static List<ClassifierEvaluation> readEvaluationsCsv(String projectName, Path evalCsv) throws IOException {
        List<ClassifierEvaluation> out = new ArrayList<>();
        CsvData data = readCsvFile(evalCsv);
        if (data.getRowCount() == 0) return out;

        EvalColumnIndices colIdx = EvalColumnIndices.fromHeader(data.columnIndex());

        for (List<String> row : data.rows()) {
            ClassifierEvaluation ce = parseEvaluationRow(row, projectName, colIdx);
            if (ce != null) {
                out.add(ce);
            }
        }
        return out;
    }

    private static ClassifierEvaluation parseEvaluationRow(List<String> row, String projectName, EvalColumnIndices col) {
        int iter = safeInt(row, col.iter(), -1);
        if (iter < 0) return null;

        if (col.proj() >= 0) {
            String projInRow = safeStr(row, col.proj());
            if (!projInRow.isEmpty() && !projInRow.equalsIgnoreCase(projectName)) {
                return null;
            }
        }

        ClassifierEvaluation ce = new ClassifierEvaluation(projectName, iter);
        ce.setModel(safeStr(row, col.model()));
        ce.setFeatureSelection(safeStr(row, col.fs()));
        ce.setBalancing(safeStr(row, col.bal()));
        ce.setCostSensitive(safeStr(row, col.cost()));
        ce.setAuc(safeDouble(row, col.auc()));
        ce.setMcc(safeDouble(row, col.mcc()));

        return ce;
    }

    // ================================================================
    //              LETTURA SPECIALIZZATA - REFACTOR DELTA
    // ================================================================

    /**
     * Legge i dati di refactoring (BEFORE/AFTER) per una feature specifica.
     */
    public static RefactorDelta readRefactorDelta(Path refactorCsv, String aFeature) throws IOException {
        CsvData data = readCsvFile(refactorCsv);
        if (data.getRowCount() < 1) {
            throw new IllegalArgumentException("Refactor metrics CSV too short: " + refactorCsv);
        }

        if (!data.hasColumn("Tag")) {
            throw new IllegalArgumentException("Refactor CSV missing column: Tag");
        }
        if (!data.hasColumn(aFeature)) {
            throw new IllegalArgumentException("Refactor CSV missing column for AFeature: " + aFeature);
        }

        int iTag = data.columnIndex().get("Tag");
        int iFeat = data.columnIndex().get(aFeature);

        Double before = null;
        Double after = null;

        for (List<String> row : data.rows()) {
            String tag = safeStr(row, iTag);
            double v = parseDoubleOrNaN(safeStr(row, iFeat));

            if ("BEFORE".equalsIgnoreCase(tag)) before = v;
            if ("AFTER".equalsIgnoreCase(tag)) after = v;
        }

        if (before == null || after == null || Double.isNaN(before) || Double.isNaN(after)) {
            throw new IllegalStateException("Could not read BEFORE/AFTER values for " + aFeature + " from " + refactorCsv);
        }

        return RefactorDelta.compute(before, after);
    }

    // ================================================================
    //              SCRITTURA FILE CSV - GENERICA
    // ================================================================

    /**
     * Scrive un file CSV con header e righe.
     */
    public static void writeCsvFile(Path file, List<String> header, List<List<Object>> rows) throws IOException {
        Files.createDirectories(file.getParent());
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            pw.println(String.join(",", header));
            for (List<Object> row : rows) {
                pw.println(formatCsvRow(row));
            }
        }
    }

    /**
     * Builder per scrittura CSV incrementale.
     */
    public static class CsvWriter implements AutoCloseable {
        private final PrintWriter writer;

        public CsvWriter(Path file) throws IOException {
            Files.createDirectories(file.getParent());
            this.writer = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8));
        }

        public void writeHeader(String... columns) {
            writer.println(String.join(",", columns));
        }

        public void writeHeader(List<String> columns) {
            writer.println(String.join(",", columns));
        }

        public void writeRow(Object... values) {
            writer.println(formatCsvRow(Arrays.asList(values)));
        }

        public void writeRow(List<Object> values) {
            writer.println(formatCsvRow(values));
        }

        public void writeFormattedRow(String format, Object... args) {
            writer.printf(Locale.US, format + "%n", args);
        }

        @Override
        public void close() {
            writer.close();
        }
    }

    private static String formatCsvRow(List<Object> values) {
        StringJoiner joiner = new StringJoiner(",");
        for (Object v : values) {
            joiner.add(formatCsvValue(v));
        }
        return joiner.toString();
    }

    private static String formatCsvValue(Object value) {
        if (value == null) return "";
        if (value instanceof Double d) {
            if (Double.isNaN(d) || Double.isInfinite(d)) return "";
            return String.format(Locale.US, "%.6f", d);
        }
        if (value instanceof Integer || value instanceof Long) {
            return value.toString();
        }
        return escapeCsv(value.toString());
    }

    // ================================================================
    //           SCRITTURA SPECIALIZZATA - WHAT-IF RESULTS
    // ================================================================

    /**
     * Contesto per la scrittura dei risultati What-If.
     */
    public record WhatIfContext(
            String project,
            String aFeature,
            String method,
            String classifierSpec,
            RefactorDelta delta,
            double bPlusThreshold,
            double deltaExpectedProb,
            double relOnBPlusProb,
            double relOnAProb,
            int deltaEstimatedClassify
    ) {}

    /**
     * Scrive i risultati dell'analisi What-If.
     */
    public static void writeWhatIfResults(Path outPath, WhatIfContext ctx,
                                          WhatIfResult rA, WhatIfResult rBPlus,
                                          WhatIfResult rB, WhatIfResult rC) throws IOException {
        try (CsvWriter writer = new CsvWriter(outPath)) {
            writer.writeHeader(
                    "Project", "AFeature", "AFMethod", "BClassifier",
                    "RefBefore", "RefAfter", "RefFactor",
                    "BPlusThreshold", "Dataset",
                    "N", "ActualBuggy",
                    "ExpectedDefectsSum_Prob",
                    "EstimatedBuggy_Threshold05",
                    "EstimatedBuggy_Classify",
                    "DeltaExpectedProb_BPlus_to_B",
                    "RelDropProbOnBPlus",
                    "RelDropProbOnA_latest",
                    "DeltaEstimatedClassify_BPlus_to_B"
            );

            writeWhatIfRow(writer, ctx, rA);
            writeWhatIfRow(writer, ctx, rBPlus);
            writeWhatIfRow(writer, ctx, rB);
            writeWhatIfRow(writer, ctx, rC);
        }
    }

    private static void writeWhatIfRow(CsvWriter writer, WhatIfContext ctx, WhatIfResult r) {
        writer.writeFormattedRow(
                "%s,%s,%s,\"%s\",%.6f,%.6f,%.6f,%.6f,%s,%d,%d,%.6f,%d,%d,%.6f,%.6f,%.6f,%d",
                escapeCsvSimple(ctx.project()),
                escapeCsvSimple(ctx.aFeature()),
                escapeCsvSimple(ctx.method()),
                ctx.classifierSpec(),
                ctx.delta().before(),
                ctx.delta().after(),
                ctx.delta().factor(),
                ctx.bPlusThreshold(),
                escapeCsvSimple(r.datasetName()),
                r.n(),
                r.actualBuggy(),
                r.expectedDefectsSum(),
                r.estimatedBuggyThreshold05(),
                r.estimatedBuggyClassify(),
                ctx.deltaExpectedProb(),
                ctx.relOnBPlusProb(),
                ctx.relOnAProb(),
                ctx.deltaEstimatedClassify()
        );
    }

    private static String escapeCsvSimple(String s) {
        if (s == null) return "";
        return s.replace(",", " ");
    }

    // ================================================================
    //                 EXPORT DATASET PROGETTO
    // ================================================================

    public static void exportAll(String projectName,
                                 List<Version> versions,
                                 List<Ticket> tickets,
                                 List<Method> methods) throws IOException {

        if (projectName == null || projectName.isBlank()) {
            projectName = "PROJECT";
        }

        Path baseDir = Paths.get("output", "csv", projectName.toUpperCase(Locale.ROOT));
        Files.createDirectories(baseDir);

        exportVersions(baseDir.resolve("versions.csv"), versions);
        exportCommits(baseDir.resolve("commits.csv"), versions, tickets);
        exportTickets(baseDir.resolve("tickets.csv"), tickets);
        exportDataset(baseDir.resolve("dataset.csv"), methods);
    }

    private static void exportVersions(Path file, List<Version> versions) throws IOException {
        try (CsvWriter writer = new CsvWriter(file)) {
            writer.writeHeader("Index", "Id", "Name", "Date", "NumCommits");

            if (versions == null) return;

            versions.stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt(Version::getIndex))
                    .forEach(v -> writer.writeRow(
                            safeIndex(v),
                            nullSafe(v.getId()),
                            nullSafe(v.getName()),
                            formatDate(v.getDate()),
                            v.getCommitList() == null ? 0 : v.getCommitList().size()
                    ));
        }
    }

    private static void exportCommits(Path file, List<Version> versions, List<Ticket> tickets) throws IOException {
        try (CsvWriter writer = new CsvWriter(file)) {
            writer.writeHeader("Hash", "AuthorName", "AuthorEmail", "Date",
                    "VersionIndex", "VersionName", "TicketKeys", "ShortMessage");

            if (versions == null) return;

            Set<String> seenHashes = new HashSet<>();

            versions.stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt(Version::getIndex))
                    .forEach(v -> writeVersionCommits(writer, v, tickets, seenHashes));
        }
    }

    private static void writeVersionCommits(CsvWriter writer, Version v,
                                            List<Ticket> tickets, Set<String> seenHashes) {
        if (v.getCommitList() == null) return;

        for (RevCommit c : v.getCommitList()) {
            if (c == null) continue;
            String hash = c.getName();
            if (!seenHashes.add(hash)) continue;

            PersonIdent author = c.getAuthorIdent();
            writer.writeRow(
                    hash,
                    author != null ? nullSafe(author.getName()) : "",
                    author != null ? nullSafe(author.getEmailAddress()) : "",
                    formatDate(Instant.ofEpochSecond(c.getCommitTime()).atZone(ZoneOffset.UTC).toLocalDate()),
                    safeIndex(v),
                    nullSafe(v.getName()),
                    buildTicketKeysForCommit(c, tickets),
                    nullSafe(c.getShortMessage())
            );
        }
    }

    private static String buildTicketKeysForCommit(RevCommit commit, List<Ticket> tickets) {
        if (commit == null || tickets == null) return "";
        StringJoiner joiner = new StringJoiner("|");
        for (Ticket t : tickets) {
            if (t != null && t.getAssociatedCommits() != null && t.getAssociatedCommits().contains(commit)) {
                joiner.add(nullSafe(t.getKey()));
            }
        }
        return joiner.toString();
    }

    private static void exportTickets(Path file, List<Ticket> tickets) throws IOException {
        try (CsvWriter writer = new CsvWriter(file)) {
            writer.writeHeader("Key", "CreationDate", "ResolutionDate",
                    "OpeningVersionIndex", "OpeningVersionName",
                    "FixedVersionIndex", "FixedVersionName",
                    "InjectedVersionIndex", "InjectedVersionName",
                    "AffectedVersionIndices", "AffectedVersionNames",
                    "NumAssociatedCommits");

            if (tickets == null) return;

            for (Ticket t : tickets) {
                if (t == null) continue;
                writeTicketRow(writer, t);
            }
        }
    }

    private static void writeTicketRow(CsvWriter writer, Ticket t) {
        Version ov = t.getOpeningVersion();
        Version fv = t.getFixedVersion();
        Version iv = t.getInjectedVersion();

        String[] avInfo = buildAffectedVersionsInfo(t.getAffectedVersions());

        writer.writeRow(
                nullSafe(t.getKey()),
                formatDate(t.getCreationDate()),
                formatDate(t.getResolutionDate()),
                safeIndex(ov), ov != null ? nullSafe(ov.getName()) : "",
                safeIndex(fv), fv != null ? nullSafe(fv.getName()) : "",
                safeIndex(iv), iv != null ? nullSafe(iv.getName()) : "",
                avInfo[0], avInfo[1],
                t.getAssociatedCommits() == null ? 0 : t.getAssociatedCommits().size()
        );
    }

    private static String[] buildAffectedVersionsInfo(List<Version> affected) {
        if (affected == null || affected.isEmpty()) {
            return new String[]{"", ""};
        }

        StringJoiner indicesJoiner = new StringJoiner("|");
        StringJoiner namesJoiner = new StringJoiner("|");

        affected.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(Version::getIndex))
                .forEach(v -> {
                    indicesJoiner.add(Integer.toString(safeIndex(v)));
                    namesJoiner.add(nullSafe(v.getName()));
                });

        return new String[]{indicesJoiner.toString(), namesJoiner.toString()};
    }

    private static void exportDataset(Path file, List<Method> methods) throws IOException {
        try (CsvWriter writer = new CsvWriter(file)) {
            writer.writeHeader("VersionIndex", "VersionName", "MethodFQN",
                    "LOC", "NumParameters", "NumBranches", "NestingDepth",
                    "NumCodeSmells", "NumLocalVariables", "NumRevisions", "NumAuthors",
                    "TotalStmtAdded", "TotalStmtDeleted", "MaxChurn", "AvgChurn",
                    "HasFixHistory", "Buggy", "BodyHash");

            if (methods == null) return;

            List<Method> sorted = new ArrayList<>(methods);
            sorted.sort(Comparator
                    .comparingInt((Method m) -> safeIndex(m.getVersion()))
                    .thenComparing(m -> nullSafe(m.getFullyQualifiedName())));

            for (Method m : sorted) {
                if (m == null) continue;
                writeMethodRow(writer, m);
            }
        }
    }

    private static void writeMethodRow(CsvWriter writer, Method m) {
        Version v = m.getVersion();
        Metrics metrics = m.getMetrics();

        writer.writeRow(
                safeIndex(v),
                v != null ? nullSafe(v.getName()) : "",
                nullSafe(m.getFullyQualifiedName()),
                metrics != null ? metrics.getLoc() : 0,
                metrics != null ? metrics.getParameterCount() : 0,
                metrics != null ? metrics.getNumBranches() : 0,
                metrics != null ? metrics.getNestingDepth() : 0,
                metrics != null ? metrics.getNumCodeSmells() : 0,
                metrics != null ? metrics.getNumLocalVariables() : 0,
                metrics != null ? metrics.getNumRevisions() : 0,
                metrics != null ? metrics.getNumAuthors() : 0,
                metrics != null ? metrics.getTotalStmtAdded() : 0,
                metrics != null ? metrics.getTotalStmtDeleted() : 0,
                metrics != null ? metrics.getMaxChurn() : 0,
                metrics != null ? metrics.getAvgChurn() : 0.0,
                metrics != null ? metrics.getHasFixHistory() : 0,
                m.isBuggy() ? "yes" : "no",
                nullSafe(m.getBodyHash())
        );
    }

    // ================================================================
    //                    PARSING CSV
    // ================================================================

    /**
     * Parsa una riga CSV gestendo correttamente le virgolette.
     */
    public static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        if (line == null) return out;

        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);

            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else {
                if (ch == '"') {
                    inQuotes = true;
                } else if (ch == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(ch);
                }
            }
        }

        out.add(cur.toString());
        return out;
    }

    /**
     * Parsa l'header di un CSV e restituisce una mappa nome_colonna -> indice.
     */
    public static Map<String, Integer> parseHeader(String headerLine) {
        List<String> header = parseCsvLine(headerLine);
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            col.put(header.get(i).trim(), i);
        }
        return col;
    }

    // ================================================================
    //                    SAFE PARSING
    // ================================================================

    public static String safeStr(String[] arr, int i) {
        if (arr == null || i < 0 || i >= arr.length) return "";
        return arr[i] == null ? "" : arr[i].trim();
    }

    public static String safeStr(List<String> fields, int i) {
        if (fields == null || i < 0 || i >= fields.size()) return "";
        String s = fields.get(i);
        return s == null ? "" : s.trim();
    }

    public static int safeInt(String[] arr, int i, int def) {
        try {
            String s = safeStr(arr, i);
            if (s.isEmpty()) return def;
            return (int) Math.round(Double.parseDouble(s));
        } catch (NumberFormatException _) {
            return def;
        }
    }

    public static int safeInt(List<String> fields, int i, int def) {
        try {
            String s = safeStr(fields, i);
            if (s.isEmpty()) return def;
            return (int) Math.round(Double.parseDouble(s));
        } catch (NumberFormatException _) {
            return def;
        }
    }

    public static double safeDouble(String[] arr, int i) {
        try {
            String s = safeStr(arr, i);
            if (s.isEmpty()) return Double.NaN;
            return Double.parseDouble(s);
        } catch (NumberFormatException _) {
            return Double.NaN;
        }
    }

    public static double safeDouble(List<String> fields, int i) {
        try {
            String s = safeStr(fields, i);
            if (s.isEmpty()) return Double.NaN;
            return Double.parseDouble(s);
        } catch (NumberFormatException _) {
            return Double.NaN;
        }
    }

    public static Double parseDoubleSafe(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException _) {
            return null;
        }
    }

    public static Double parseDoubleSafe(List<String> fields, int idx) {
        if (fields == null || idx < 0 || idx >= fields.size()) return null;
        return parseDoubleSafe(fields.get(idx));
    }

    public static double parseDoubleOrNaN(String s) {
        Double d = parseDoubleSafe(s);
        return (d != null) ? d : Double.NaN;
    }

    public static Double parseBuggy(List<String> fields, int idx) {
        if (fields == null || idx < 0 || idx >= fields.size()) return null;
        String s = fields.get(idx);
        if (s == null) return null;
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.equals("yes")) return 1.0;
        if (s.equals("no")) return 0.0;
        return null;
    }

    // ================================================================
    //                    HELPER PRIVATI
    // ================================================================

    private static int safeIndex(Version v) {
        if (v == null) return 0;
        return Math.max(v.getIndex(), 0);
    }

    private static String nullSafe(String s) {
        return (s == null) ? "" : s;
    }

    private static String formatDate(LocalDate date) {
        return (date == null) ? "" : DATE_FORMAT.format(date);
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        boolean hasSpecial = value.contains("\"") || value.contains(",") ||
                value.contains("\n") || value.contains("\r");
        String escaped = value.replace("\"", "\"\"");
        return hasSpecial ? "\"" + escaped + "\"" : escaped;
    }

    private static int requireColumnIndex(Map<String, Integer> idx, String colName) {
        Integer v = idx.get(colName);
        if (v == null) {
            throw new IllegalArgumentException("CSV missing required column: " + colName);
        }
        return v;
    }

    public static String sanitizeFilename(String s) {
        if (s == null) return "x";
        return s.replaceAll("[^A-Za-z0-9_\\-.]", "_");
    }
}