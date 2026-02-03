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
 * Classe responsabile delle operazioni I/O su file CSV.
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
            double f = computeFactor(before, after);
            return new RefactorDelta(before, after, f);
        }

        private static double computeFactor(double before, double after) {
            if (Math.abs(before) <= 1e-9) return 0.0;
            double f = after / before;
            if (Double.isNaN(f) || Double.isInfinite(f)) return 1.0;
            return Math.max(f, 0.0);
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

    // ================================================================
    //                 LETTURA FILE CSV
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

    private static List<String> readAllLines(Path csvPath) throws IOException {
        if (!Files.exists(csvPath)) {
            throw new IOException(String.format("File CSV non trovato: %s", csvPath));
        }
        List<String> lines = Files.readAllLines(csvPath, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IOException(String.format("File CSV vuoto: %s", csvPath));
        }
        return lines;
    }

    /**
     * Valida che le colonne richieste siano presenti.
     */
    public static void validateColumns(CsvData data, Path filePath, String... requiredColumns) throws IOException {
        for (String col : requiredColumns) {
            if (!data.hasColumn(col)) {
                throw new IOException(String.format("Colonna mancante nel CSV: %s (file: %s)", col, filePath));
            }
        }
    }

    /**
     * Legge le valutazioni dei classificatori da un CSV.
     */
    public static List<ClassifierEvaluation> readEvaluationsCsv(String projectName, Path evalCsv) throws IOException {
        List<ClassifierEvaluation> out = new ArrayList<>();
        CsvData data = readCsvFile(evalCsv);
        if (data.getRowCount() == 0) return out;

        int[] colIdx = getEvalColumnIndices(data.columnIndex());

        for (List<String> row : data.rows()) {
            ClassifierEvaluation ce = parseEvaluationRow(row, projectName, colIdx);
            if (ce != null) {
                out.add(ce);
            }
        }
        return out;
    }

    private static int[] getEvalColumnIndices(Map<String, Integer> idx) {
        return new int[] {
                idx.getOrDefault("PROJ", -1),
                requireColumnIndex(idx, "WF_ITER"),
                requireColumnIndex(idx, "MODEL"),
                requireColumnIndex(idx, "FEATURE_SELECTION"),
                requireColumnIndex(idx, "BALANCING"),
                requireColumnIndex(idx, "COST_SENSITIVE"),
                requireColumnIndex(idx, "AUC"),
                requireColumnIndex(idx, "MCC")
        };
    }

    private static ClassifierEvaluation parseEvaluationRow(List<String> row, String projectName, int[] col) {
        int iter = safeInt(row, col[1], -1);
        if (iter < 0) return null;

        if (col[0] >= 0) {
            String projInRow = safeStr(row, col[0]);
            if (!projInRow.isEmpty() && !projInRow.equalsIgnoreCase(projectName)) {
                return null;
            }
        }

        ClassifierEvaluation ce = new ClassifierEvaluation(projectName, iter);
        ce.setModel(safeStr(row, col[2]));
        ce.setFeatureSelection(safeStr(row, col[3]));
        ce.setBalancing(safeStr(row, col[4]));
        ce.setCostSensitive(safeStr(row, col[5]));
        ce.setAuc(safeDouble(row, col[6]));
        ce.setMcc(safeDouble(row, col[7]));

        return ce;
    }

    /**
     * Legge i dati di refactoring (BEFORE/AFTER) per una feature specifica.
     */
    public static RefactorDelta readRefactorDelta(Path refactorCsv, String aFeature) throws IOException {
        CsvData data = readCsvFile(refactorCsv);
        if (data.getRowCount() < 1) {
            throw new IllegalArgumentException(String.format("Refactor metrics CSV too short: %s", refactorCsv));
        }

        if (!data.hasColumn("Tag")) {
            throw new IllegalArgumentException("Refactor CSV missing column: Tag");
        }
        if (!data.hasColumn(aFeature)) {
            throw new IllegalArgumentException(String.format("Refactor CSV missing column for AFeature: %s", aFeature));
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
            throw new IllegalStateException(String.format("Could not read BEFORE/AFTER values for %s from %s", aFeature, refactorCsv));
        }

        return RefactorDelta.compute(before, after);
    }

    // ================================================================
    //                    SCRITTURA WHAT-IF RESULTS
    // ================================================================

    /**
     * Scrive i risultati dell'analisi What-If.
     */
    public static void writeWhatIfResults(Path outPath, WhatIfContext ctx,
                                          WhatIfResult rA, WhatIfResult rBPlus,
                                          WhatIfResult rB, WhatIfResult rC) throws IOException {
        Files.createDirectories(outPath.getParent());
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outPath, StandardCharsets.UTF_8))) {
            writer.println("Project,AFeature,AFMethod,BClassifier,RefBefore,RefAfter,RefFactor," +
                    "BPlusThreshold,Dataset,N,ActualBuggy,ExpectedDefectsSum_Prob," +
                    "EstimatedBuggy_Threshold05,EstimatedBuggy_Classify," +
                    "DeltaExpectedProb_BPlus_to_B,RelDropProbOnBPlus,RelDropProbOnA_latest," +
                    "DeltaEstimatedClassify_BPlus_to_B");

            writeWhatIfRow(writer, ctx, rA);
            writeWhatIfRow(writer, ctx, rBPlus);
            writeWhatIfRow(writer, ctx, rB);
            writeWhatIfRow(writer, ctx, rC);
        }
    }

    private static void writeWhatIfRow(PrintWriter writer, WhatIfContext ctx, WhatIfResult r) {
        writer.printf(Locale.US, "%s,%s,%s,\"%s\",%.6f,%.6f,%.6f,%.6f,%s,%d,%d,%.6f,%d,%d,%.6f,%.6f,%.6f,%d%n",
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

    // ================================================================
    //                 EXPORT DATASET PROGETTO
    // ================================================================

    public static void exportAll(String projectName,
                                 List<Version> versions,
                                 List<Ticket> tickets,
                                 List<Method> methods) throws IOException {

        String projName = (projectName == null || projectName.isBlank()) ? "PROJECT" : projectName;
        Path baseDir = Paths.get("output", "csv", projName.toUpperCase(Locale.ROOT));
        Files.createDirectories(baseDir);

        exportVersions(baseDir.resolve("versions.csv"), versions);
        exportCommits(baseDir.resolve("commits.csv"), versions, tickets);
        exportTickets(baseDir.resolve("tickets.csv"), tickets);
        exportDataset(baseDir.resolve("dataset.csv"), methods);
    }

    private static void exportVersions(Path file, List<Version> versions) throws IOException {
        Files.createDirectories(file.getParent());
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            writer.println("Index,Id,Name,Date,NumCommits");

            if (versions == null) return;

            versions.stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt(Version::getIndex))
                    .forEach(v -> writer.println(formatVersionRow(v)));
        }
    }

    private static String formatVersionRow(Version v) {
        if (v == null) {
            return "0,,,,0";
        }
        return String.format(Locale.US, "%d,%s,%s,%s,%d",
                safeIndex(v),
                escapeCsv(nullSafe(v.getId())),
                escapeCsv(nullSafe(v.getName())),
                formatDate(v.getDate()),
                v.getCommitList() == null ? 0 : v.getCommitList().size());
    }

    private static void exportCommits(Path file, List<Version> versions, List<Ticket> tickets) throws IOException {
        Files.createDirectories(file.getParent());
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            writer.println("Hash,AuthorName,AuthorEmail,Date,VersionIndex,VersionName,TicketKeys,ShortMessage");

            if (versions == null) return;

            Set<String> seenHashes = new HashSet<>();
            versions.stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt(Version::getIndex))
                    .forEach(v -> writeVersionCommits(writer, v, tickets, seenHashes));
        }
    }

    private static void writeVersionCommits(PrintWriter writer, Version v,
                                            List<Ticket> tickets, Set<String> seenHashes) {
        if (v.getCommitList() == null) return;

        for (RevCommit c : v.getCommitList()) {
            if (c == null || !seenHashes.add(c.getName())) continue;

            PersonIdent author = c.getAuthorIdent();
            writer.println(formatCommitRow(c, author, v, tickets));
        }
    }

    private static String formatCommitRow(RevCommit c, PersonIdent author, Version v, List<Ticket> tickets) {
        String versionName = (v != null) ? nullSafe(v.getName()) : "";
        return String.format("%s,%s,%s,%s,%d,%s,%s,%s",
                c.getName(),
                escapeCsv(author != null ? nullSafe(author.getName()) : ""),
                escapeCsv(author != null ? nullSafe(author.getEmailAddress()) : ""),
                formatDate(Instant.ofEpochSecond(c.getCommitTime()).atZone(ZoneOffset.UTC).toLocalDate()),
                safeIndex(v),
                escapeCsv(versionName),
                escapeCsv(buildTicketKeysForCommit(c, tickets)),
                escapeCsv(nullSafe(c.getShortMessage())));
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
        Files.createDirectories(file.getParent());
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            writer.println("Key,CreationDate,ResolutionDate,OpeningVersionIndex,OpeningVersionName," +
                    "FixedVersionIndex,FixedVersionName,InjectedVersionIndex,InjectedVersionName," +
                    "AffectedVersionIndices,AffectedVersionNames,NumAssociatedCommits");

            if (tickets == null) return;

            for (Ticket t : tickets) {
                if (t == null) continue;
                writer.println(formatTicketRow(t));
            }
        }
    }

    private static String formatTicketRow(Ticket t) {
        Version ov = t.getOpeningVersion();
        Version fv = t.getFixedVersion();
        Version iv = t.getInjectedVersion();
        String[] avInfo = buildAffectedVersionsInfo(t.getAffectedVersions());

        return String.format("%s,%s,%s,%d,%s,%d,%s,%d,%s,%s,%s,%d",
                escapeCsv(nullSafe(t.getKey())),
                formatDate(t.getCreationDate()),
                formatDate(t.getResolutionDate()),
                safeIndex(ov), escapeCsv(ov != null ? nullSafe(ov.getName()) : ""),
                safeIndex(fv), escapeCsv(fv != null ? nullSafe(fv.getName()) : ""),
                safeIndex(iv), escapeCsv(iv != null ? nullSafe(iv.getName()) : ""),
                escapeCsv(avInfo[0]), escapeCsv(avInfo[1]),
                t.getAssociatedCommits() == null ? 0 : t.getAssociatedCommits().size());
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
        Files.createDirectories(file.getParent());
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            writer.println("VersionIndex,VersionName,MethodFQN,LOC,NumParameters,NumBranches,NestingDepth," +
                    "NumCodeSmells,NumLocalVariables,NumRevisions,NumAuthors,TotalStmtAdded,TotalStmtDeleted," +
                    "MaxChurn,AvgChurn,HasFixHistory,Buggy,BodyHash");

            if (methods == null) return;

            List<Method> sorted = new ArrayList<>(methods);
            sorted.sort(Comparator
                    .comparingInt((Method m) -> safeIndex(m.getVersion()))
                    .thenComparing(m -> nullSafe(m.getFullyQualifiedName())));

            for (Method m : sorted) {
                if (m == null) continue;
                writer.println(formatMethodRow(m));
            }
        }
    }

    private static String formatMethodRow(Method m) {
        Version v = m.getVersion();
        Metrics met = m.getMetrics();

        return String.format(Locale.US, "%d,%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.6f,%d,%s,%s",
                safeIndex(v),
                escapeCsv(v != null ? nullSafe(v.getName()) : ""),
                escapeCsv(nullSafe(m.getFullyQualifiedName())),
                met != null ? met.getLoc() : 0,
                met != null ? met.getParameterCount() : 0,
                met != null ? met.getNumBranches() : 0,
                met != null ? met.getNestingDepth() : 0,
                met != null ? met.getNumCodeSmells() : 0,
                met != null ? met.getNumLocalVariables() : 0,
                met != null ? met.getNumRevisions() : 0,
                met != null ? met.getNumAuthors() : 0,
                met != null ? met.getTotalStmtAdded() : 0,
                met != null ? met.getTotalStmtDeleted() : 0,
                met != null ? met.getMaxChurn() : 0,
                met != null ? met.getAvgChurn() : 0.0,
                met != null ? met.getHasFixHistory() : 0,
                m.isBuggy() ? "yes" : "no",
                escapeCsv(nullSafe(m.getBodyHash())));
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
        int i = 0;

        while (i < line.length()) {
            char ch = line.charAt(i);
            int step = inQuotes
                    ? handleQuotedChar(ch, line, i, cur)
                    : handleUnquotedChar(ch, cur, out);

            inQuotes = updateQuoteState(inQuotes, ch, line, i);
            i += (step > 0) ? step : 1;
        }

        out.add(cur.toString());
        return out;
    }

    private static int handleQuotedChar(char ch, String line, int i, StringBuilder cur) {
        if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
            cur.append('"');
            return 2;
        }
        if (ch != '"') {
            cur.append(ch);
        }
        return 1;
    }

    private static int handleUnquotedChar(char ch, StringBuilder cur, List<String> out) {
        if (ch == ',') {
            out.add(cur.toString());
            cur.setLength(0);
        } else if (ch != '"') {
            cur.append(ch);
        }
        return 1;
    }

    private static boolean updateQuoteState(boolean inQuotes, char ch, String line, int i) {
        if (ch != '"') {
            return inQuotes;
        }
        if (inQuotes) {
            return i + 1 < line.length() && line.charAt(i + 1) == '"';
        }
        return true;
    }

    // ================================================================
    //                    SAFE PARSING
    // ================================================================

    private static String safeStr(List<String> fields, int i) {
        if (fields == null || i < 0 || i >= fields.size()) return "";
        String s = fields.get(i);
        return s == null ? "" : s.trim();
    }

    private static int safeInt(List<String> fields, int i, int def) {
        try {
            String s = safeStr(fields, i);
            if (s.isEmpty()) return def;
            return (int) Math.round(Double.parseDouble(s));
        } catch (NumberFormatException _) {
            return def;
        }
    }

    private static double safeDouble(List<String> fields, int i) {
        try {
            String s = safeStr(fields, i);
            if (s.isEmpty()) return Double.NaN;
            return Double.parseDouble(s);
        } catch (NumberFormatException _) {
            return Double.NaN;
        }
    }

    /**
     * Parsa un double da una lista di campi, restituendo null se non valido.
     */
    public static Double parseDoubleSafe(List<String> fields, int idx) {
        if (fields == null || idx < 0 || idx >= fields.size()) return null;
        String s = fields.get(idx);
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private static double parseDoubleOrNaN(String s) {
        if (s == null || s.trim().isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException _) {
            return Double.NaN;
        }
    }

    /**
     * Parsa il campo Buggy (yes/no) restituendo 1.0, 0.0 o null.
     */
    public static Double parseBuggy(List<String> fields, int idx) {
        if (fields == null || idx < 0 || idx >= fields.size()) return null;
        String s = fields.get(idx);
        if (s == null) return null;
        s = s.trim().toLowerCase(Locale.ROOT);
        if ("yes".equals(s)) return 1.0;
        if ("no".equals(s)) return 0.0;
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

    private static String escapeCsvSimple(String s) {
        if (s == null) return "";
        return s.replace(",", " ");
    }

    private static int requireColumnIndex(Map<String, Integer> idx, String colName) {
        Integer v = idx.get(colName);
        if (v == null) {
            throw new IllegalArgumentException(String.format("CSV missing required column: %s", colName));
        }
        return v;
    }

    public static String sanitizeFilename(String s) {
        if (s == null) return "x";
        return s.replaceAll("[^A-Za-z0-9_\\-.]", "_");
    }
}