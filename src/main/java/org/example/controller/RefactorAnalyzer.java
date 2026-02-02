package org.example.controller;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.example.model.Method;
import org.example.model.Metrics;
import org.example.model.Version;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Analizza refactor/<PROJECT>/case.java contenente un metodo BEFORE/AFTER (suffix _before/_after),
 * calcola le metriche con MetricsCalc e produce un CSV con:
 *  - BEFORE
 *  - AFTER
 *  - HELPERS (tutti i metodi diversi dal BEFORE)
 *  - AGG_AFTER_PLUS_HELPERS (somma LOC/branches/smells/vars/CC, max nesting)
 *<p></p>
 * Output: output/csv/<PROJECT>/refactor_metrics_<baseMethodName>.csv
 */
public class RefactorAnalyzer {

    private static final String SANDBOX = "SANDBOX";
    private static final String AFTER = "AFTER";
    private final String projectName;     // atteso uppercase
    private final String baseMethodName;  // es. dispatchReceivedMessagesToSubscribers

    private static final Logger LOGGER = Logger.getLogger(RefactorAnalyzer.class.getName());

    public RefactorAnalyzer(String projectNameUpperCase, String baseMethodName) {
        this.projectName = Objects.requireNonNull(projectNameUpperCase, "projectNameUpperCase").trim().toUpperCase();
        this.baseMethodName = Objects.requireNonNull(baseMethodName, "baseMethodName").trim();
        if (this.baseMethodName.isBlank()) {
            throw new IllegalArgumentException("baseMethodName is blank");
        }
    }

    public void execute() throws IOException {
        String originalMethodName = baseMethodName + "_before";
        String refactoredMethodName = baseMethodName + "_after";

        Path inputFile = Paths.get("refactor", projectName, "case.java");
        if (!Files.exists(inputFile)) {
            // fallback opzionale (se in alcuni casi hai Case.java)
            Path alt = Paths.get("refactor", projectName, "Case.java");
            if (Files.exists(alt)) {
                inputFile = alt;
            } else {
                LOGGER.log(Level.SEVERE, "ERROR: Input file not found: {0}", inputFile);
                return;
            }
        }

        Path outDir = Paths.get("output", "csv", projectName);
        Files.createDirectories(outDir);

        Path outputFile = outDir.resolve("refactor_metrics_" + baseMethodName + ".csv");
        LOGGER.log(Level.INFO, "Analyzing file: {0}", inputFile);
        LOGGER.log(Level.INFO, "Saving report to: {0}", inputFile);
        String rawCode = Files.readString(inputFile, StandardCharsets.UTF_8);

        // Prova parse diretto; se fallisce (tipico di file con metodi top-level), wrappa in wrapper
        CompilationUnit cu = tryParse(rawCode);
        String codeToParse = rawCode;

        if (cu == null) {
            codeToParse = wrapInDummyClassKeepingPackageAndImports(rawCode);
            cu = tryParse(codeToParse);
        }

        if (cu == null) {
            LOGGER.log(Level.SEVERE, "PARSING ERROR: Check if file {0} contains valid Java code", inputFile);
            return;
        }

        // Cerca metodi by name in tutto il CU (anche se wrappato)
        Optional<MethodDeclaration> originalMethodOpt = cu.findAll(MethodDeclaration.class).stream()
                .filter(md -> md.getNameAsString().equals(originalMethodName))
                .findFirst();

        Optional<MethodDeclaration> refactoredEntryPointOpt = cu.findAll(MethodDeclaration.class).stream()
                .filter(md -> md.getNameAsString().equals(refactoredMethodName))
                .findFirst();

        if (originalMethodOpt.isEmpty() || refactoredEntryPointOpt.isEmpty()) {
            LOGGER.log(Level.SEVERE,
                    "ERROR: Impossible to find methods {0} and/or {1} in file.",
                    new Object[]{originalMethodName, refactoredMethodName});
            return;
        }

        // Helpers = tutti i metodi tranne BEFORE (include AFTER + extracted helpers)
        List<MethodDeclaration> allRefactoredMethods = cu.findAll(MethodDeclaration.class).stream()
                .filter(md -> !md.getNameAsString().equals(originalMethodName))
                .toList();

        // ===== METRICS ENGINE: usa MetricsCalc (PMD incluso) =====
        MetricsCalc mc = new MetricsCalc(null, Collections.emptyMap(), Collections.emptyList());
        Map<Integer, Integer> smellsByLine = mc.calculateCodeSmellsByLine(codeToParse);

        try (FileWriter fileWriter = new FileWriter(outputFile.toFile());
             PrintWriter writer = new PrintWriter(fileWriter)) {

            writer.println("MethodName,Tag,LOC,NumParameters,NumBranches,CyclomaticComplexity,NestingDepth,NumCodeSmells,NumLocalVariables");

            // BEFORE
            String before = "BEFORE";
            printMetrics(mc, smellsByLine, originalMethodOpt.get(), before, writer);

            printMetrics(mc, smellsByLine, refactoredEntryPointOpt.get(), AFTER, writer);

            // Helpers + aggregate
            printHelpersAndAggregate(mc, smellsByLine, refactoredEntryPointOpt.get(), allRefactoredMethods, baseMethodName, writer);

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "ERROR: Impossible to write CSV file: {0}", e.getMessage());
        }

        LOGGER.log(Level.INFO, "Analysis completed. CSV report generated successfully");
    }

    private static CompilationUnit tryParse(String code) {
        try {
            return StaticJavaParser.parse(code);
        } catch (Exception _) {
            return null;
        }
    }

    /**
     * Wrapper robusto:
     * - tiene fuori package + import
     * - mette tutto il resto dentro DummyWrapperClass
     * Utile quando case.java contiene metodi top-level.
     */
    private static String wrapInDummyClassKeepingPackageAndImports(String original) {
        StringBuilder head = new StringBuilder();
        StringBuilder body = new StringBuilder();

        String[] lines = original.split("\\R", -1);
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("package ") || t.startsWith("import ")) {
                head.append(line).append("\n");
            } else {
                body.append(line).append("\n");
            }
        }

        return head +
                "\nclass DummyWrapperClass {\n" +
                body +
                "\n}\n";
    }

    private static void printMetrics(MetricsCalc mc,
                                     Map<Integer, Integer> smellsByLine,
                                     MethodDeclaration md,
                                     String tag,
                                     PrintWriter writer) {

        Version dummy = new Version(SANDBOX, SANDBOX, LocalDate.now());
        dummy.setIndex(0);

        Method tmp = new Method("Dummy/" + md.getNameAsString(), dummy);
        mc.computeStaticMetricsForMethod(tmp, md, smellsByLine);

        Metrics m = tmp.getMetrics();

        writer.printf("%s,%s,%d,%d,%d,%d,%d,%d,%d%n",
                md.getNameAsString(),
                tag,
                m.getLoc(),
                m.getParameterCount(),
                m.getNumBranches(),
                m.getCyclomaticComplexity(),
                m.getNestingDepth(),
                m.getNumCodeSmells(),
                m.getNumLocalVariables());
    }

    private static void printHelpersAndAggregate(MetricsCalc mc,
                                                 Map<Integer, Integer> smellsByLine,
                                                 MethodDeclaration mainAfter,
                                                 List<MethodDeclaration> allNonBefore,
                                                 String baseMethodName,
                                                 PrintWriter writer) {

        int totalLoc = 0;
        int totalBranches = 0;
        int totalCC = 0;
        int maxNesting = 0;
        int totalSmells = 0;
        int totalVars = 0;

        // Dettaglio helpers (escludo AFTER dalla lista helper, ma lo considero comunque nell'aggregato)
        for (MethodDeclaration md : allNonBefore) {
            String tag = md.getNameAsString().equals(mainAfter.getNameAsString())
                    ? AFTER     // ridondante ma utile se vuoi vedere che AFTER è incluso nel “sistema”
                    : "HELPER";

            if (!tag.equals(AFTER)) {
                printMetrics(mc, smellsByLine, md, tag, writer);
            }

            // Metriche per aggregato
            Version dummy = new Version(SANDBOX, SANDBOX, LocalDate.now());
            dummy.setIndex(0);
            Method tmp = new Method("Dummy/" + md.getNameAsString(), dummy);
            mc.computeStaticMetricsForMethod(tmp, md, smellsByLine);

            Metrics mm = tmp.getMetrics();

            totalLoc += mm.getLoc();
            totalBranches += mm.getNumBranches();
            totalCC += mm.getCyclomaticComplexity();
            maxNesting = Math.max(maxNesting, mm.getNestingDepth());
            totalSmells += mm.getNumCodeSmells();
            totalVars += mm.getNumLocalVariables();
        }

        int afterParams = mainAfter.getParameters().size();

        // Riga aggregata: AFTER + HELPERS
        writer.printf("%s,%s,%d,%d,%d,%d,%d,%d,%d%n",
                baseMethodName,
                "AGG_AFTER_PLUS_HELPERS",
                totalLoc,
                afterParams,
                totalBranches,
                totalCC,
                maxNesting,
                totalSmells,
                totalVars);
    }
}
