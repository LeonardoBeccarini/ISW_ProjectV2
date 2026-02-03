package org.example.controller;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.example.model.Method;
import org.example.model.Ticket;
import org.example.model.Version;
import org.example.utilities.JiraUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;
/**
 * Recupera informazioni dal repository Git e calcola le metriche richieste
 * per ogni metodo Java in ogni Version.
 */
public class GitRetriever {

    private static final Logger LOGGER = Logger.getLogger(GitRetriever.class.getName());

    private static final String JAVA_EXTENSION = ".java";
    private static final String TEST_DIR_FRAGMENT = "/test/";
    private static final double ANALYSIS_FRACTION = 0.5; // Prima porzione di release da usare per l'analisi

    // Regole di esclusione versioni (warning + skip)
    // - Se la versione non ha commit associati → warning + esclusione dall'analisi
    // - Se la versione produce un numero di metodi drasticamente inferiore alla baseline → warning + esclusione dall'analisi
    private static final int MIN_METHODS_ABS = 200;
    private static final int MIN_BASELINE_FOR_RATIO = 1000;
    private static final int BASELINE_WINDOW = 5;
    private static final double LOW_METHOD_RATIO = 0.20;

    private final String projectName;
    private final List<Version> versionList; // lista completa delle versioni
    private final List<Version> analysisVersionList = new ArrayList<>(); // sottoinsieme usato per l'analisi
    private final List<Ticket> ticketList;

    private final Repository repository;
    private final Git git;

    // Parser tollerante (usa ParseResult invece di eccezioni).
    private final JavaParser javaParser;

    // Tutti i commit del repository, ordinati cronologicamente.
    private final List<RevCommit> allCommits = new ArrayList<>();

    // Mapping commitId -> Version (calcolato in associateCommitToVersion).
    private final Map<String, Version> commitToVersion = new HashMap<>();

    // Componente dedicata al calcolo delle metriche.
    private final MetricsCalc metricsCalc;

    public GitRetriever(String projectName,
                        String repoUrl,
                        List<Version> versionList,
                        List<Ticket> ticketList) throws IOException, GitAPIException {

        this.projectName = projectName;
        this.versionList = (versionList != null) ? versionList : new ArrayList<>();
        this.ticketList = (ticketList != null) ? ticketList : new ArrayList<>();

        // Parser config piÃ¹ permissiva (riduce i casi di file scartati)
        ParserConfiguration parserConfiguration = new ParserConfiguration();
        parserConfiguration.setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);

        StaticJavaParser.setConfiguration(parserConfiguration);
        this.javaParser = new JavaParser(parserConfiguration);

        String pathName = "repos/" + projectName.toLowerCase() + "Clone";
        File dir = new File(pathName);
        if (dir.exists()) {
            this.repository = new FileRepositoryBuilder()
                    .setGitDir(new File(dir, ".git"))
                    .build();
            this.git = new Git(repository);
        } else {
            this.git = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(dir)
                    .call();
            this.repository = git.getRepository();
        }

        // Inizializza il calcolatore di metriche con i riferimenti necessari
        this.metricsCalc = new MetricsCalc(this.repository, this.commitToVersion, this.ticketList);
    }


    /* =========================================================
       =                 COMMIT --> VERSION                       =
       ========================================================= */

    public void associateCommitToVersion() throws GitAPIException, IOException {
        if (versionList == null || versionList.isEmpty()) {
            return;
        }

        loadAllCommits();

        for (Version v : versionList) {
            if (v.getCommitList() != null) {
                v.getCommitList().clear();
            }
        }
        commitToVersion.clear();

        for (RevCommit c : allCommits) {
            LocalDate d = Instant.ofEpochSecond(c.getCommitTime())
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate();
            Version v = JiraUtils.getReleaseAfterOrEqualDate(d, versionList);
            if (v != null) {
                if (v.getCommitList() != null) {
                    v.getCommitList().add(c);
                }
                commitToVersion.put(c.getName(), v);
            }
        }

        for (Version v : versionList) {
            if (v.getCommitList() != null) {
                v.getCommitList().sort(Comparator.comparingInt(RevCommit::getCommitTime));
            }
        }
    }

    private void prepareAnalysisVersionList() {
        analysisVersionList.clear();
        if (versionList == null || versionList.isEmpty()) {
            return;
        }

        int maxFixedVersionIndex = maxFixedVersionIndex(ticketList);
        List<Version> ordered = orderedVersionsForAnalysis(versionList, maxFixedVersionIndex);

        List<Version> candidatesWithCommits = filterVersionsWithCommits(
                ordered,
                "0 commit associati alla release (baseline/milestone o bucket temporale vuoto)"
        );

        if (candidatesWithCommits.isEmpty()) {
            // Fallback: prova a prendere tutte le versioni con commit senza vincolo su maxFixedVersionIndex
            List<Version> allOrdered = orderedVersionsForAnalysis(versionList, 0);
            candidatesWithCommits = filterVersionsWithCommits(allOrdered, "0 commit associati alla release (fallback)");
        }

        int numToConsider = numAnalysisVersionsToConsider(candidatesWithCommits.size());
        if (numToConsider > 0) {
            analysisVersionList.addAll(candidatesWithCommits.subList(0, numToConsider));
        }
    }

    private static int maxFixedVersionIndex(List<Ticket> tickets) {
        if (tickets == null || tickets.isEmpty()) {
            return 0;
        }
        // Trova l'indice massimo della fixed version tra i ticket (se presente).
        return tickets.stream()
                .filter(Objects::nonNull)
                .map(Ticket::getFixedVersion)
                .filter(Objects::nonNull)
                .mapToInt(Version::getIndex)
                .max()
                .orElse(0);
    }

    private static List<Version> orderedVersionsForAnalysis(List<Version> versions, int maxFixedVersionIndex) {
        if (versions == null || versions.isEmpty()) {
            return List.of();
        }

        List<Version> filtered = versions.stream()
                .filter(Objects::nonNull)
                .filter(v -> maxFixedVersionIndex <= 0 || v.getIndex() <= maxFixedVersionIndex)
                .sorted(Comparator.comparingInt(Version::getIndex))
                .toList();

        if (!filtered.isEmpty()) {
            return filtered;
        }

        // Nessuna versione entro maxFixedVersionIndex: usa tutte le versioni disponibili.
        return versions.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(Version::getIndex))
                .toList();
    }

    private List<Version> filterVersionsWithCommits(List<Version> ordered, String warnReasonIfNoCommits) {
        List<Version> out = new ArrayList<>();
        if (ordered == null || ordered.isEmpty()) {
            return out;
        }

        for (Version v : ordered) {
            if (hasCommits(v)) {
                out.add(v);
            } else {
                warnSkipVersion(v, warnReasonIfNoCommits);
            }
        }

        return out;
    }

    private static boolean hasCommits(Version v) {
        List<RevCommit> commits = (v != null) ? v.getCommitList() : null;
        return commits != null && !commits.isEmpty();
    }

    private static int numAnalysisVersionsToConsider(int candidateCount) {
        if (candidateCount <= 0) {
            return 0;
        }
        int numToConsider = (int) Math.ceil(candidateCount * GitRetriever.ANALYSIS_FRACTION);
        return Math.max(1, numToConsider);
    }

    /* =========================================================
       =                 COMMIT --> TICKET                        =
       ========================================================= */

    void associateCommitToTicket(List<Ticket> tickets) throws GitAPIException, IOException {
        if (tickets == null || tickets.isEmpty()) {
            return;
        }

        loadAllCommits();

        final int DATE_SLACK_DAYS = 3;
        TicketIndex index = buildTicketIndex(tickets, DATE_SLACK_DAYS);
        if (index.byKey.isEmpty()) {
            return;
        }

        Pattern pattern = Pattern.compile(projectName + "-\\d+", Pattern.CASE_INSENSITIVE);

        Iterable<RevCommit> log = git.log().add(repository.resolve("HEAD")).call();
        for (RevCommit commit : log) {
            if (commit != null) {
                attachCommitIfMatchesTickets(commit, index, pattern);
            }
        }
    }

    private record TicketIndex(Map<String, Ticket> byKey, LocalDate minCreation, LocalDate maxResolution,
                               int slackDays) {
    }

    private TicketIndex buildTicketIndex(List<Ticket> tickets, int slackDays) {
        Map<String, Ticket> byKey = new LinkedHashMap<>();
        LocalDate minC = null;
        LocalDate maxR = null;

        for (Ticket t : tickets) {
            String key = normalizeTicketKey(t);
            if (key == null) {
                continue;
            }

            byKey.put(key, t);
            minC = minDate(minC, t.getCreationDate());
            maxR = maxDate(maxR, t.getResolutionDate());

            // Reset indici solo per ticket validi
            resetAssociatedCommits(t);
        }

        return new TicketIndex(byKey, minC, maxR, slackDays);
    }


    private void attachCommitIfMatchesTickets(RevCommit commit, TicketIndex index, Pattern pattern) {
        if (commit.getParentCount() == 0) {
            return;
        }

        LocalDate commitDate = commitDate(commit);
        if (!isWithinDateWindow(commitDate, index.minCreation, index.maxResolution, index.slackDays)) {
            return;
        }

        String msg = safeCommitMessage(commit);
        Matcher matcher = pattern.matcher(msg);
        while (matcher.find()) {
            String key = matcher.group().toUpperCase(Locale.ROOT);
            Ticket ticket = index.byKey.get(key);
            if (isWithinTicketWindow(commitDate, ticket, index.slackDays)) {
                ticket.getAssociatedCommits().add(commit);
            }
        }
    }

    private static String safeCommitMessage(RevCommit commit) {
        String m = (commit != null) ? commit.getFullMessage() : null;
        return (m != null) ? m : "";
    }

    private static LocalDate commitDate(RevCommit commit) {
        return Instant.ofEpochSecond(commit.getCommitTime())
                .atZone(ZoneOffset.UTC)
                .toLocalDate();
    }

    private static boolean isWithinDateWindow(LocalDate d, LocalDate min, LocalDate max, int slackDays) {
        if (d == null) {
            return false;
        }
        if (min != null && d.isBefore(min.minusDays(slackDays))) {
            return false;
        }
        return max == null || !d.isAfter(max.plusDays(slackDays));
    }

    private static boolean isWithinTicketWindow(LocalDate commitDate, Ticket t, int slackDays) {
        if (t == null || commitDate == null) {
            return false;
        }

        LocalDate cmin = t.getCreationDate();
        LocalDate cmax = t.getResolutionDate();

        return (cmin == null || !commitDate.isBefore(cmin.minusDays(slackDays)))
                && (cmax == null || !commitDate.isAfter(cmax.plusDays(slackDays)));
    }

    private static String normalizeTicketKey(Ticket t) {
        if (t == null) {
            return null;
        }
        String raw = t.getKey();
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private static LocalDate minDate(LocalDate current, LocalDate candidate) {
        if (candidate == null) {
            return current;
        }
        return (current == null || candidate.isBefore(current)) ? candidate : current;
    }

    private static LocalDate maxDate(LocalDate current, LocalDate candidate) {
        if (candidate == null) {
            return current;
        }
        return (current == null || candidate.isAfter(current)) ? candidate : current;
    }

    private static void resetAssociatedCommits(Ticket t) {
        t.setAssociatedCommits(new ArrayList<>());
    }

    /* =========================================================
       =              PIPELINE PRINCIPALE                       =
       ========================================================= */

    public List<Method> extractMethodsAndMetrics() throws IOException, GitAPIException {
        associateCommitToVersion();
        prepareAnalysisVersionList();
        associateCommitToTicket(this.ticketList);

        // analysisVersionList deve rappresentare SOLO le versioni davvero usate nel dataset:
        // quindi iteriamo su una copia e poi ricostruiamo analysisVersionList con le versioni accettate.
        List<Version> candidates = new ArrayList<>(analysisVersionList);
        analysisVersionList.clear();

        List<Method> allMethods = new ArrayList<>();
        Map<String, List<Method>> methodsByFqn = new HashMap<>();

        // Baseline dinamica: usiamo la mediana degli ultimi BASELINE_WINDOW conteggi accettati
        // per identificare versioni con un numero di metodi anomalo (troppo basso).
        List<Integer> acceptedMethodCounts = new ArrayList<>();

        for (Version version : candidates) {
            RevCommit snapshotCommit = latestCommitOrNull(version);
            if (snapshotCommit == null) {
                continue;
            }

            VersionExtraction extraction = extractStaticMetricsAtCommit(version, snapshotCommit);
            handleVersionExtraction(version, extraction, acceptedMethodCounts, allMethods, methodsByFqn);
        }

        // allCommits è già ordinata cronologicamente da loadAllCommits()
        metricsCalc.addProcessMetrics(allMethods, methodsByFqn, allCommits);

        metricsCalc.calculateHasFixHistory(allMethods);
        metricsCalc.setMethodBuggyness(allMethods);

        return allMethods;
    }

    private static final class VersionExtraction {
        final List<Method> methods = new ArrayList<>();
        final Map<String, List<Method>> methodsByFqn = new HashMap<>();
    }

    private RevCommit latestCommitOrNull(Version version) {
        List<RevCommit> versionCommits = (version != null) ? version.getCommitList() : null;
        if (versionCommits == null || versionCommits.isEmpty()) {
            // In teoria già filtrato in prepareAnalysisVersionList(), ma meglio essere sicuri.
            warnSkipVersion(version, "0 commit associati alla release");
            return null;
        }
        return versionCommits.getLast();
    }

    private VersionExtraction extractStaticMetricsAtCommit(Version version, RevCommit snapshotCommit) throws IOException {
        VersionExtraction extraction = new VersionExtraction();

        try (TreeWalk treeWalk = new TreeWalk(repository)) {
            treeWalk.addTree(snapshotCommit.getTree());
            treeWalk.setRecursive(true);

            while (treeWalk.next()) {
                String path = treeWalk.getPathString();
                if (!isAnalyzableJavaFile(path)) {
                    continue;
                }
                processJavaFile(treeWalk, version, path, extraction.methods, extraction.methodsByFqn);
            }
        }

        return extraction;
    }

    private static boolean isAnalyzableJavaFile(String path) {
        return path != null
                && path.endsWith(JAVA_EXTENSION)
                && !path.contains(TEST_DIR_FRAGMENT);
    }

    private void handleVersionExtraction(Version version,
                                         VersionExtraction extraction,
                                         List<Integer> acceptedMethodCounts,
                                         List<Method> allMethods,
                                         Map<String, List<Method>> methodsByFqn) {

        int extractedCount = (extraction != null) ? extraction.methods.size() : 0;

        if (shouldSkipByMethodCount(extractedCount, acceptedMethodCounts)) {
            warnOutlierVersion(version, extractedCount, acceptedMethodCounts);
            return;
        }

        acceptVersionExtraction(version, extraction, extractedCount, acceptedMethodCounts, allMethods, methodsByFqn);
    }

    private void warnOutlierVersion(Version version, int extractedCount, List<Integer> acceptedMethodCounts) {
        String baselineStr = baselineString(acceptedMethodCounts);
        String reason = "metodi estratti=" + extractedCount
                + " (baseline mediana=" + baselineStr + "). Versione esclusa dall'analisi.";
        warnSkipVersion(version, reason);
    }

    private String baselineString(List<Integer> acceptedMethodCounts) {
        if (acceptedMethodCounts == null || acceptedMethodCounts.size() < BASELINE_WINDOW) {
            return "n/a";
        }
        return String.valueOf(medianOfLast(acceptedMethodCounts));
    }

    private void acceptVersionExtraction(Version version,
                                         VersionExtraction extraction,
                                         int extractedCount,
                                         List<Integer> acceptedMethodCounts,
                                         List<Method> allMethods,
                                         Map<String, List<Method>> methodsByFqn) {

        // Versione accettata
        analysisVersionList.add(version);

        if (extraction != null) {
            allMethods.addAll(extraction.methods);

            for (Map.Entry<String, List<Method>> e : extraction.methodsByFqn.entrySet()) {
                methodsByFqn
                        .computeIfAbsent(e.getKey(), k -> new ArrayList<>(e.getValue().size()))
                        .addAll(e.getValue());
            }
        }

        acceptedMethodCounts.add(extractedCount);
    }



    /* =========================================================
       =        ESTRAZIONE METRICHE STATICHE PER FILE           =
       ========================================================= */

    private void processJavaFile(TreeWalk treeWalk,
                                 Version version,
                                 String filePath,
                                 List<Method> allMethods,
                                 Map<String, List<Method>> methodsByFqn) throws IOException {

        ObjectLoader loader = repository.open(treeWalk.getObjectId(0));
        String fileContent;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            loader.copyTo(out);
            fileContent = out.toString(StandardCharsets.UTF_8);
        }

        fileContent = sanitizeFileContent(fileContent);

        Map<Integer, Integer> codeSmellsByLine = metricsCalc.calculateCodeSmellsByLine(fileContent);

        // Parsing tollerante: se c'è un AST parziale si usa comunque
        try {
            ParseResult<CompilationUnit> pr = javaParser.parse(fileContent);
            Optional<CompilationUnit> cuOpt = pr.getResult();
            if (cuOpt.isEmpty()) return;

            CompilationUnit cu = cuOpt.get();

            // 1) Metodi
            for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
                String signature = metricsCalc.buildMethodSignature(md);
                String fqn = filePath + "/" + signature;

                Method method = new Method(fqn, version);
                metricsCalc.computeStaticMetricsForMethod(method, md, codeSmellsByLine);

                allMethods.add(method);
                methodsByFqn.computeIfAbsent(fqn, k -> new ArrayList<>()).add(method);
            }

            // 2) Costruttori
            for (ConstructorDeclaration cd : cu.findAll(ConstructorDeclaration.class)) {
                String signature = metricsCalc.buildConstructorSignature(cd);
                String fqn = filePath + "/" + signature;

                Method ctor = new Method(fqn, version);
                metricsCalc.computeStaticMetricsForConstructor(ctor, cd, codeSmellsByLine);

                allMethods.add(ctor);
                methodsByFqn.computeIfAbsent(fqn, k -> new ArrayList<>()).add(ctor);
            }

        } catch (ParseProblemException | StackOverflowError _) {
            // Se proprio fallisce tutto, ignora il file
        }
    }

    private String sanitizeFileContent(String s) {
        if (s == null) return "";
        // BOM UTF-8
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        // caratteri NULL (a volte presenti in file sporchi)
        s = s.replace("\u0000", "");
        return s;
    }

    /* =========================================================
       =   FILTRI ANALISI: WARNING + ESCLUSIONE VERSIONI        =
       ========================================================= */

    private void warnSkipVersion(Version v, String reason) {
        if (!LOGGER.isLoggable(Level.WARNING)) {
            return;
        }

        String name = (v != null && v.getName() != null) ? v.getName() : "<unknown>";
        int idx = (v != null) ? v.getIndex() : -1;

        LOGGER.log(Level.WARNING,
                "[{0}] Versione {1} ({2}) esclusa dall''analisi: {3}",
                new Object[]{projectName, idx, name, reason});
    }


    private boolean shouldSkipByMethodCount(int extractedCount, List<Integer> acceptedCounts) {
        int hardMin = hardMinMethodsForProject();

        // Hard floor: se sotto soglia, la versione è inutilizzabile (v2/v5 di STORM)
        if (extractedCount < hardMin) return true;

        // Finché non ho abbastanza storia, NON scarto per ratio
        if (acceptedCounts == null || acceptedCounts.size() < BASELINE_WINDOW) return false;

        int baseline = medianOfLast(acceptedCounts);

        if (baseline < MIN_BASELINE_FOR_RATIO) return false;

        int threshold = Math.max(hardMin, (int) Math.floor(baseline * LOW_METHOD_RATIO));
        return extractedCount < threshold;
    }


    private int medianOfLast(List<Integer> values) {
        if (values == null || values.isEmpty() || GitRetriever.BASELINE_WINDOW <= 0) return 0;

        int n = Math.min(GitRetriever.BASELINE_WINDOW, values.size());
        int start = values.size() - n;

        int[] buf = new int[n];
        for (int i = 0; i < n; i++) {
            buf[i] = values.get(start + i);
        }
        Arrays.sort(buf);

        if ((n & 1) == 1) {
            return buf[n / 2];
        }
        // n pari: media dei due centrali (int division ok)
        return (buf[(n / 2) - 1] + buf[n / 2]) / 2;
    }



    private int hardMinMethodsForProject() {
        // STORM: tipicamente migliaia di metodi per release, quindi 1000 è conservativo ma elimina v2/v5
        if ("STORM".equalsIgnoreCase(projectName)) return 1000;

        // default: usa la soglia già presente nel progetto (o un valore basso)
        return MIN_METHODS_ABS;
    }

    /* =========================================================
       =             UTILITY: LETTURA COMMIT                    =
       ========================================================= */

    private void loadAllCommits() throws GitAPIException, IOException {
        if (!allCommits.isEmpty()) {
            return;
        }
        Iterable<RevCommit> log = git.log().add(repository.resolve("HEAD")).call();
        for (RevCommit c : log) {
            allCommits.add(c);
        }
        allCommits.sort(Comparator.comparingInt(RevCommit::getCommitTime));
    }
}