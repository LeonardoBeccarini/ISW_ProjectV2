package org.example.controller;

import net.sourceforge.pmd.*;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.Report;
import net.sourceforge.pmd.RuleViolation;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.LanguageVersion;

// JavaParser Imports
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.*;

// JGit Imports
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.io.DisabledOutputStream;

// Model Imports
import org.example.model.Method;
import org.example.model.Metrics;
import org.example.model.Ticket;
import org.example.model.Version;

// Standard Java Imports
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Classe dedicata al calcolo di tutte le metriche (statiche e di processo)
 * per i metodi estratti dal repository Git.
 *
 * NON modifica la logica originale di GitRetriever, ma ne isola le responsabilità
 * relative al calcolo delle metriche.
 */
public class MetricsCalc {

    private static final String JAVA_EXTENSION = ".java";
    private static final String TEST_DIR_FRAGMENT = "/test/";

    private final Repository repository;
    private final Map<String, Version> commitToVersion;
    private final List<Ticket> ticketList;

    // Ruleset standard PMD (classpath resource)
    private static final String PMD_RULESET_PATH = "rulesets/java/quickstart.xml";

    // Versione Java usata da PMD
    private final LanguageVersion pmdJavaLv;

    public MetricsCalc(Repository repository,
                       Map<String, Version> commitToVersion,
                       List<Ticket> ticketList) {
        this.repository = repository;
        this.commitToVersion = commitToVersion;
        this.ticketList = (ticketList != null) ? ticketList : new ArrayList<>();

        this.pmdJavaLv = LanguageRegistry.findLanguageByTerseName("java").getVersion("11");

    }

    /* =========================================================
       =             METRICHE STATICHE PER METODO               =
       ========================================================= */

    /**
     * Costruisce una firma univoca nel file includendo anche la catena dei tipi contenitori.
     * Esempio: Outer$Inner#foo(int,String)
     */
    public String buildMethodSignature(MethodDeclaration md) {
        String owner = buildEnclosingTypeChain(md);

        String params = md.getParameters().stream()
                .map(p -> eraseGenericType(p.getType().asString()))
                .collect(Collectors.joining(","));

        return owner + "#" + md.getNameAsString() + "(" + params + ")";
    }

    /**
     * Calcola e popola tutte le metriche statiche di un metodo.
     */
    public void computeStaticMetricsForMethod(Method method,
                                              MethodDeclaration md,
                                              Map<Integer, Integer> codeSmellsByLine) {
        Metrics metrics = method.getMetrics();

        int loc = calculateLOC(md);
        int params = md.getParameters().size();
        int branches = calculateNumBranches(md);
        int cc = branches + 1;
        int nesting = calculateNestingDepth(md);
        int codeSmells = estimateCodeSmellsForMethod(md, codeSmellsByLine);

        metrics.setLoc(loc);
        metrics.setParameterCount(params);
        metrics.setCyclomaticComplexity(cc);
        metrics.setNestingDepth(nesting);
        metrics.setNumCodeSmells(codeSmells);

        method.setBodyHash(calculateBodyHash(md));
    }

    /* =========================================================
       =                METRICHE DI PROCESSO                    =
       ========================================================= */

    public void addProcessMetrics(List<Method> allMethods,
                                  Map<String, List<Method>> methodsByFqn,
                                  List<RevCommit> sortedCommits) throws IOException, GitAPIException {

        if (sortedCommits == null || sortedCommits.isEmpty()) {
            return;
        }

        for (RevCommit commit : sortedCommits) {
            if (commit.getParentCount() == 0) continue;

            RevCommit parent = commit.getParent(0);
            List<DiffEntry> diffs = getDiffEntries(parent, commit);

            Map<String, String> oldFileContents = getFileContents(diffs, true);
            Map<String, String> newFileContents = getFileContents(diffs, false);

            for (DiffEntry diff : diffs) {
                processDiffEntryForMetrics(diff, commit, methodsByFqn, oldFileContents, newFileContents);
            }
        }

        // Calcolo NAuth e AvgChurn
        for (Method m : allMethods) {
            Metrics metrics = m.getMetrics();

            if (!m.getCommits().isEmpty()) {
                Set<String> authors = m.getCommits().stream()
                        .map(c -> c.getAuthorIdent().getName())
                        .collect(Collectors.toSet());
                metrics.setNumAuthors(authors.size());
            } else {
                metrics.setNumAuthors(0);
            }

            if (metrics.getNumRevisions() > 0) {
                double avg = (double) (metrics.getTotalStmtAdded() + metrics.getTotalStmtDeleted())
                        / (double) metrics.getNumRevisions();
                metrics.setAvgChurn(avg);
            } else {
                metrics.setAvgChurn(0.0);
            }
        }
    }

    private void processDiffEntryForMetrics(DiffEntry diff,
                                            RevCommit commit,
                                            Map<String, List<Method>> methodsByFqn,
                                            Map<String, String> oldFileContents,
                                            Map<String, String> newFileContents) {

        String filePath = diff.getChangeType() == DiffEntry.ChangeType.DELETE
                ? diff.getOldPath()
                : diff.getNewPath();

        if (!filePath.endsWith(JAVA_EXTENSION) || filePath.contains(TEST_DIR_FRAGMENT)) {
            return;
        }

        Map<String, MethodDeclaration> oldMethods = parseMethods(oldFileContents.getOrDefault(diff.getOldPath(), ""));
        Map<String, MethodDeclaration> newMethods = parseMethods(newFileContents.getOrDefault(diff.getNewPath(), ""));

        for (Map.Entry<String, MethodDeclaration> entry : newMethods.entrySet()) {
            String signature = entry.getKey();
            MethodDeclaration newMd = entry.getValue();
            MethodDeclaration oldMd = oldMethods.get(signature);

            String newBodyHash = calculateBodyHash(newMd);
            String oldBodyHash = (oldMd != null) ? calculateBodyHash(oldMd) : null;

            if (oldMd == null || !newBodyHash.equals(oldBodyHash)) {
                String fqn = filePath + "/" + signature;
                List<Method> methodsToUpdate = methodsByFqn.get(fqn);
                if (methodsToUpdate != null && !methodsToUpdate.isEmpty()) {
                    updateMethodMetricsForCommit(methodsToUpdate, commit, newMd, oldMd, newBodyHash);
                }
            }
        }
    }

    private void updateMethodMetricsForCommit(List<Method> methodsToUpdate,
                                              RevCommit commit,
                                              MethodDeclaration currentMdAst,
                                              MethodDeclaration oldMdAst,
                                              String newBodyHash) {

        Version commitVersion = commitToVersion.get(commit.getName());
        if (commitVersion == null) {
            return;
        }
        int commitVersionIndex = commitVersion.getIndex();

        for (Method method : methodsToUpdate) {
            // Aggiorna solo se il metodo appartiene a una release successiva o uguale al commit
            if (method.getVersion().getIndex() >= commitVersionIndex) {
                Metrics metrics = method.getMetrics();

                // Aggiungiamo il commit solo se non è già presente (per sicurezza)
                if (!method.getCommits().contains(commit)) {
                    method.getCommits().add(commit);
                    metrics.incrementNumRevisions();
                }

                method.setBodyHash(newBodyHash);

                int locNew = calculateLOC(currentMdAst);
                int locOld = (oldMdAst != null) ? calculateLOC(oldMdAst) : 0;

                int added = 0;
                int deleted = 0;

                if (oldMdAst == null) {
                    // Metodo nuovo
                    added = locNew;
                } else {
                    // Metodo modificato
                    if (locNew > locOld) {
                        added = locNew - locOld;
                    } else if (locOld > locNew) {
                        deleted = locOld - locNew;
                    } else {
                        // FIX: Le LOC sono uguali, ma l'hash è diverso (modifica interna).
                        // Stimiamo un churn minimo per non avere 0.
                        // Oppure, idealmente, si userebbe la Levenshtein distance sulle stringhe.
                        // Qui applichiamo una euristica semplice: si considera una "touched line".
                        added = 1;
                        deleted = 1;
                    }
                }

                metrics.addTotalStmtAdded(added);
                metrics.addTotalStmtDeleted(deleted);

                int churn = added + deleted;
                if (churn > metrics.getMaxChurn()) {
                    metrics.setMaxChurn(churn);
                }
            }
        }
    }

    /* =========================================================
       =                  HAS FIX HISTORY                       =
       ========================================================= */

    public void calculateHasFixHistory(List<Method> allMethods) {
        if (ticketList == null || ticketList.isEmpty()) {
            return;
        }

        Map<String, Ticket> commitNameToTicket = new HashMap<>();
        for (Ticket ticket : ticketList) {
            if (ticket.getAssociatedCommits() == null) continue;
            for (RevCommit commit : ticket.getAssociatedCommits()) {
                commitNameToTicket.put(commit.getName(), ticket);
            }
        }

        for (Method method : allMethods) {
            Metrics metrics = method.getMetrics();
            Version methodVersion = method.getVersion();

            for (RevCommit commit : method.getCommits()) {
                Ticket t = commitNameToTicket.get(commit.getName());
                if (t == null) continue;

                Version commitVersion = commitToVersion.get(commit.getName());
                if (commitVersion != null && commitVersion.getIndex() < methodVersion.getIndex()) {
                    metrics.setHasFixHistory(1);
                    break;
                }
            }
        }
    }

    /* =========================================================
       =                     LABELING BUGGY                     =
       ========================================================= */

    public void setMethodBuggyness(List<Method> allMethods) throws IOException, GitAPIException {
        if (ticketList == null || ticketList.isEmpty()) {
            return;
        }

        // Per accesso rapido: FQN -> lista Method
        Map<String, List<Method>> methodsByFqn = allMethods.stream()
                .collect(Collectors.groupingBy(Method::getFullyQualifiedName));

        for (Ticket ticket : ticketList) {
            Version injectedVersion = ticket.getInjectedVersion();
            Version fixedVersion = ticket.getFixedVersion();
            if (injectedVersion == null || fixedVersion == null) {
                continue;
            }

            List<RevCommit> fixCommits = ticket.getAssociatedCommits();
            if (fixCommits == null || fixCommits.isEmpty()) {
                continue;
            }

            for (RevCommit fixCommit : fixCommits) {
                if (fixCommit.getParentCount() == 0) continue;

                RevCommit parent = fixCommit.getParent(0);
                List<DiffEntry> diffs = getDiffEntries(parent, fixCommit);

                Map<String, String> oldFileContents = getFileContents(diffs, true);
                Map<String, String> newFileContents = getFileContents(diffs, false);

                for (DiffEntry diff : diffs) {
                    String filePath = diff.getNewPath();
                    if (!filePath.endsWith(JAVA_EXTENSION) || filePath.contains(TEST_DIR_FRAGMENT)) {
                        continue;
                    }

                    String newContent = newFileContents.getOrDefault(filePath, "");
                    String oldContent = oldFileContents.getOrDefault(diff.getOldPath(), "");

                    Map<String, MethodDeclaration> newMethods = parseMethods(newContent);
                    Map<String, MethodDeclaration> oldMethods = parseMethods(oldContent);

                    for (Map.Entry<String, MethodDeclaration> entry : newMethods.entrySet()) {
                        String signature = entry.getKey();
                        MethodDeclaration newMd = entry.getValue();
                        MethodDeclaration oldMd = oldMethods.get(signature);

                        String newHash = calculateBodyHash(newMd);
                        String oldHash = (oldMd != null) ? calculateBodyHash(oldMd) : null;

                        if (oldMd == null || !newHash.equals(oldHash)) {
                            String fqn = filePath + "/" + signature;
                            labelBuggyMethods(fqn, injectedVersion, fixedVersion, methodsByFqn);
                        }
                    }
                }
            }
        }
    }

    private void labelBuggyMethods(String fixedMethodFqn,
                                   Version injectedVersion,
                                   Version fixedVersion,
                                   Map<String, List<Method>> methodsByFqn) {

        List<Method> methods = methodsByFqn.get(fixedMethodFqn);
        if (methods == null || methods.isEmpty()) {
            return;
        }

        int ivIndex = injectedVersion.getIndex();
        int fvIndex = fixedVersion.getIndex();

        for (Method method : methods) {
            int methodIndex = method.getVersion().getIndex();
            if (methodIndex >= ivIndex && methodIndex < fvIndex) {
                method.setBuggy(true);
            }
        }
    }

    /* =========================================================
       =             UTILITY: LETTURA DIFF/FILE                 =
       ========================================================= */

    private List<DiffEntry> getDiffEntries(RevCommit parent, RevCommit commit) throws IOException {
        try (DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            df.setRepository(repository);
            df.setDiffComparator(RawTextComparator.DEFAULT);
            df.setContext(0);
            return df.scan(parent.getTree(), commit.getTree());
        }
    }

    private Map<String, String> getFileContents(List<DiffEntry> diffs, boolean useOldPath) throws IOException {
        Map<String, String> contents = new HashMap<>();
        try (ObjectReader reader = repository.newObjectReader()) {
            for (DiffEntry diff : diffs) {
                String path = useOldPath ? diff.getOldPath() : diff.getNewPath();
                ObjectId id = useOldPath ? diff.getOldId().toObjectId() : diff.getNewId().toObjectId();
                if (DiffEntry.DEV_NULL.equals(path)) {
                    continue;
                }
                try {
                    ObjectLoader loader = reader.open(id);
                    contents.put(path, new String(loader.getBytes(), StandardCharsets.UTF_8));
                } catch (MissingObjectException ignored) {
                    // oggetto mancante: ignoriamo il file
                }
            }
        }
        return contents;
    }

    private Map<String, MethodDeclaration> parseMethods(String content) {
        Map<String, MethodDeclaration> methods = new HashMap<>();
        if (content == null || content.isEmpty()) {
            return methods;
        }
        try {
            CompilationUnit cu = StaticJavaParser.parse(content);
            for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
                methods.put(buildMethodSignature(md), md);
            }
        } catch (ParseProblemException e) {
            // problemi di parsing, ignoriamo
        } catch (Exception e) {
            // altri problemi di parsing, ignoriamo
        }
        return methods;
    }

    /* =========================================================
       =             UTILITY: METRICHE STATICHE                 =
       ========================================================= */

    private int calculateLOC(MethodDeclaration md) {
        if (!md.getBody().isPresent()) {
            return 0;
        }

        String[] lines = md.getBody().get().toString().split("\\r?\\n");
        boolean inMulti = false;
        int loc = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("/*")) {
                inMulti = true;
                if (trimmed.endsWith("*/") && trimmed.length() > 2) {
                    inMulti = false;
                }
                continue;
            }
            if (trimmed.endsWith("*/")) {
                inMulti = false;
                continue;
            }
            if (inMulti) continue;
            if (trimmed.isEmpty()
                    || trimmed.startsWith("//")
                    || "{".equals(trimmed)
                    || "}".equals(trimmed)) {
                continue;
            }
            loc++;
        }
        return loc;
    }

    private int calculateNumBranches(MethodDeclaration md) {
        if (!md.getBody().isPresent()) {
            return 0;
        }
        int branches = 0;
        branches += md.findAll(IfStmt.class).size();
        branches += md.findAll(ConditionalExpr.class).size();
        branches += md.findAll(ForStmt.class).size();
        branches += md.findAll(ForEachStmt.class).size();
        branches += md.findAll(WhileStmt.class).size();
        branches += md.findAll(DoStmt.class).size();
        for (SwitchStmt sw : md.findAll(SwitchStmt.class)) {
            branches += sw.getEntries().size();
        }
        branches += md.findAll(CatchClause.class).size();
        return branches;
    }

    private int calculateNestingDepth(MethodDeclaration md) {
        if (!md.getBody().isPresent()) {
            return 0;
        }
        return calculateNestingDepth(md.getBody().get(), 0);
    }

    private int calculateNestingDepth(Node node, int currentDepth) {
        int maxDepth = currentDepth;
        for (Node child : node.getChildNodes()) {
            int nextDepth = currentDepth;
            if (child instanceof IfStmt
                    || child instanceof ForStmt
                    || child instanceof ForEachStmt
                    || child instanceof WhileStmt
                    || child instanceof DoStmt
                    || child instanceof SwitchStmt
                    || child instanceof TryStmt) {
                nextDepth = currentDepth + 1;
            }
            int childDepth = calculateNestingDepth(child, nextDepth);
            if (childDepth > maxDepth) {
                maxDepth = childDepth;
            }
        }
        return maxDepth;
    }

    private String calculateBodyHash(MethodDeclaration md) {
        if (md == null || !md.getBody().isPresent()) {
            return "NULL_METHOD_HASH";
        }
        String body = md.getBody().get().toString();
        body = body.replaceAll("//.*|/\\*(?s:.*?)\\*/", "");
        body = body.replaceAll("\\s+", " ").trim();
        if (body.isEmpty()) {
            return "EMPTY_BODY_HASH";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(2 * encoded.length);
            for (byte b : encoded) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 non disponibile", e);
        }
    }

    /* =========================================================
           =             UTILITY: PMD CODE SMELLS (OTTIMIZZATO)     =
           ========================================================= */

    public Map<Integer, Integer> calculateCodeSmellsByLine(String fileContent) {
        Map<Integer, Integer> result = new HashMap<>();
        if (fileContent == null || fileContent.isBlank()) {
            return result;
        }

        PMDConfiguration cfg = new PMDConfiguration();
        cfg.setDefaultLanguageVersion(pmdJavaLv);
        cfg.setFailOnViolation(false);
        cfg.setThreads(1); // più stabile e spesso più veloce su singolo file
        cfg.addRuleSet(PMD_RULESET_PATH); // carica ruleset via RuleSetLoader :contentReference[oaicite:3]{index=3}

        try (PmdAnalysis pmd = PmdAnalysis.create(cfg)) {
            pmd.files().addSourceFile(fileContent, "Analysis.java");

            Report report = pmd.performAnalysisAndCollectReport();

            for (RuleViolation rv : report.getViolations()) {
                result.merge(rv.getBeginLine(), 1, Integer::sum);
            }
        } catch (Exception e) {
            System.err.println("Errore PMD: " + e.getMessage());
        }

        return result;
    }


    /**
     * Restituisce il numero di code smell all'interno del metodo sommando
     * le violazioni PMD sulle linee comprese tra inizio e fine del metodo.
     */
    private int estimateCodeSmellsForMethod(MethodDeclaration md,
                                            Map<Integer, Integer> codeSmellsByLine) {
        if (!md.getBody().isPresent() || codeSmellsByLine.isEmpty()) {
            return 0;
        }
        int begin = md.getBegin().map(p -> p.line).orElse(-1);
        int end = md.getEnd().map(p -> p.line).orElse(-1);
        if (begin < 0 || end < begin) {
            return 0;
        }

        int count = 0;
        for (Map.Entry<Integer, Integer> e : codeSmellsByLine.entrySet()) {
            int line = e.getKey();
            if (line >= begin && line <= end) {
                count += e.getValue();
            }
        }
        return count;
    }


        /* =========================================================
           =             UTILITY PER LA FIRMA UNIVOCA     =
           ========================================================= */
        private String buildEnclosingTypeChain(MethodDeclaration md) {
            List<String> parts = new ArrayList<>();

            Node n = md;
            while (n != null) {
                if (n instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration c) {
                    parts.add(c.getNameAsString());
                } else if (n instanceof com.github.javaparser.ast.body.EnumDeclaration e) {
                    parts.add(e.getNameAsString());
                } else if (n instanceof com.github.javaparser.ast.body.RecordDeclaration r) {
                    parts.add(r.getNameAsString());
                } else if (n instanceof com.github.javaparser.ast.body.AnnotationDeclaration a) {
                    parts.add(a.getNameAsString());
                }
                n = n.getParentNode().orElse(null);
            }

            Collections.reverse(parts);

            // Fallback (raro): metodo in contesti strani/non-nominati
            if (parts.isEmpty()) {
                return "<unknownType>";
            }
            return String.join("$", parts);
        }

    /**
     * "Erasure" semplice: rimuove tutto ciò che è tra <...> gestendo nesting.
     * Esempio: Map<String, List<Integer>> -> Map
     */
    private String eraseGenericType(String typeStr) {
        if (typeStr == null) return "";

        String s = typeStr.replaceAll("\\s+", ""); // niente spazi
        StringBuilder out = new StringBuilder(s.length());

        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '<') {
                depth++;
                continue;
            }
            if (ch == '>') {
                if (depth > 0) depth--;
                continue;
            }
            if (depth == 0) {
                out.append(ch);
            }
        }
        return out.toString();
    }

}
