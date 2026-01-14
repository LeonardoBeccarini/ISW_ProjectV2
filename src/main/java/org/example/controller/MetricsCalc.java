package org.example.controller;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.*;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.Report;
import net.sourceforge.pmd.RuleViolation;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.LanguageVersion;
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
import org.example.model.Method;
import org.example.model.Metrics;
import org.example.model.Ticket;
import org.example.model.Version;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Calcolo metriche statiche e di processo.
 */
public class MetricsCalc {

    private static final String JAVA_EXTENSION = ".java";
    private static final String TEST_DIR_FRAGMENT = "/test/";

    private final Repository repository;
    private final Map<String, Version> commitToVersion;
    private final List<Ticket> ticketList;

    // Parser tollerante (evita di buttare via file interi quando c'è qualche problema)
    private final JavaParser javaParser;

    private static final String PMD_RULESET_PATH = "rulesets/java/quickstart.xml";
    private final LanguageVersion pmdJavaLv;

    public MetricsCalc(Repository repository,
                       Map<String, Version> commitToVersion,
                       List<Ticket> ticketList) {
        this.repository = repository;
        this.commitToVersion = commitToVersion;
        this.ticketList = (ticketList != null) ? ticketList : new ArrayList<>();

        // usa la stessa configurazione impostata in GitRetriever (BLEEDING_EDGE)
        this.javaParser = new JavaParser(StaticJavaParser.getConfiguration());

        this.pmdJavaLv = LanguageRegistry.findLanguageByTerseName("java").getVersion("11");
    }

    /* =========================================================
       =             FIRME (metodi + costruttori)               =
       ========================================================= */

    /**
     * Firma univoca nel file includendo catena dei tipi contenitori.
     * Gestisce anche anonymous class per evitare collisioni.
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
     * Firma univoca per costruttori.
     * Esempio: Outer$Inner#<init>(int,String)
     */
    public String buildConstructorSignature(ConstructorDeclaration cd) {
        String owner = buildEnclosingTypeChain(cd);

        String params = cd.getParameters().stream()
                .map(p -> eraseGenericType(p.getType().asString()))
                .collect(Collectors.joining(","));

        return owner + "#<init>(" + params + ")";
    }

    /* =========================================================
       =             METRICHE STATICHE (metodi)                 =
       ========================================================= */

    public void computeStaticMetricsForMethod(Method method,
                                              MethodDeclaration md,
                                              Map<Integer, Integer> codeSmellsByLine) {
        computeStaticMetricsForCallable(method, md, codeSmellsByLine);
    }

    public void computeStaticMetricsForConstructor(Method method,
                                                   ConstructorDeclaration cd,
                                                   Map<Integer, Integer> codeSmellsByLine) {
        computeStaticMetricsForCallable(method, cd, codeSmellsByLine);
    }

    private void computeStaticMetricsForCallable(Method method,
                                                 CallableDeclaration<?> cd,
                                                 Map<Integer, Integer> codeSmellsByLine) {
        Metrics metrics = method.getMetrics();

        int loc = calculateLOC(cd);
        int params = cd.getParameters().size();
        int branches = calculateNumBranches(cd);
        int cc = branches + 1;
        int nesting = calculateNestingDepth(cd);
        int localVars = calculateNumLocalVariables(cd);
        int codeSmells = estimateCodeSmellsForCallable(cd, codeSmellsByLine);

        metrics.setLoc(loc);
        metrics.setParameterCount(params);
        metrics.setNumBranches(branches);
        metrics.setCyclomaticComplexity(cc);
        metrics.setNestingDepth(nesting);
        metrics.setNumLocalVariables(localVars);
        metrics.setNumCodeSmells(codeSmells);

        method.setBodyHash(calculateBodyHash(cd));
    }

    private Optional<BlockStmt> getBody(CallableDeclaration<?> cd) {
        if (cd instanceof MethodDeclaration md) {
            return md.getBody();
        }
        if (cd instanceof ConstructorDeclaration c) {
            return Optional.ofNullable(c.getBody());
        }
        return Optional.empty();
    }

    private int calculateNumLocalVariables(CallableDeclaration<?> cd) {
        Optional<BlockStmt> body = getBody(cd);
        if (body.isEmpty()) return 0;
        return body.get()
                .findAll(com.github.javaparser.ast.body.VariableDeclarator.class)
                .size();
    }

    /* =========================================================
       =                METRICHE DI PROCESSO                    =
       ========================================================= */

    public void addProcessMetrics(List<Method> allMethods,
                                  Map<String, List<Method>> methodsByFqn,
                                  List<RevCommit> sortedCommits) throws IOException {

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

        Map<String, CallableDeclaration<?>> oldCallables = parseCallables(oldFileContents.getOrDefault(diff.getOldPath(), ""));
        Map<String, CallableDeclaration<?>> newCallables = parseCallables(newFileContents.getOrDefault(diff.getNewPath(), ""));

        for (Map.Entry<String, CallableDeclaration<?>> entry : newCallables.entrySet()) {
            String signature = entry.getKey();
            CallableDeclaration<?> newCd = entry.getValue();
            CallableDeclaration<?> oldCd = oldCallables.get(signature);

            String newBodyHash = calculateBodyHash(newCd);
            String oldBodyHash = (oldCd != null) ? calculateBodyHash(oldCd) : null;

            if (oldCd == null || !newBodyHash.equals(oldBodyHash)) {
                String fqn = filePath + "/" + signature;
                List<Method> methodsToUpdate = methodsByFqn.get(fqn);
                if (methodsToUpdate != null && !methodsToUpdate.isEmpty()) {
                    updateMethodMetricsForCommit(methodsToUpdate, commit, newCd, oldCd, newBodyHash);
                }
            }
        }
    }

    private void updateMethodMetricsForCommit(List<Method> methodsToUpdate,
                                              RevCommit commit,
                                              CallableDeclaration<?> currentAst,
                                              CallableDeclaration<?> oldAst,
                                              String newBodyHash) {

        Version commitVersion = commitToVersion.get(commit.getName());
        if (commitVersion == null) {
            return;
        }
        int commitVersionIndex = commitVersion.getIndex();

        for (Method method : methodsToUpdate) {
            if (method.getVersion().getIndex() >= commitVersionIndex) {
                Metrics metrics = method.getMetrics();

                if (!method.getCommits().contains(commit)) {
                    method.getCommits().add(commit);
                    metrics.incrementNumRevisions();
                }

                method.setBodyHash(newBodyHash);

                int locNew = calculateLOC(currentAst);
                int locOld = (oldAst != null) ? calculateLOC(oldAst) : 0;

                int added = 0;
                int deleted = 0;

                if (oldAst == null) {
                    added = locNew;
                } else {
                    if (locNew > locOld) {
                        added = locNew - locOld;
                    } else if (locOld > locNew) {
                        deleted = locOld - locNew;
                    } else {
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

    public void setMethodBuggyness(List<Method> allMethods) throws IOException {
        if (ticketList == null || ticketList.isEmpty()) {
            return;
        }

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

                    Map<String, CallableDeclaration<?>> newCallables = parseCallables(newContent);
                    Map<String, CallableDeclaration<?>> oldCallables = parseCallables(oldContent);

                    for (Map.Entry<String, CallableDeclaration<?>> entry : newCallables.entrySet()) {
                        String signature = entry.getKey();
                        CallableDeclaration<?> newCd = entry.getValue();
                        CallableDeclaration<?> oldCd = oldCallables.get(signature);

                        String newHash = calculateBodyHash(newCd);
                        String oldHash = (oldCd != null) ? calculateBodyHash(oldCd) : null;

                        if (oldCd == null || !newHash.equals(oldHash)) {
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

            // D: rename/move robusto
            df.setDetectRenames(true);

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
                    String txt = new String(loader.getBytes(), StandardCharsets.UTF_8);
                    contents.put(path, sanitizeFileContent(txt));
                } catch (MissingObjectException ignored) {
                    // oggetto mancante: ignora file
                }
            }
        }
        return contents;
    }

    /**
     * Parsing tollerante + include metodi e costruttori.
     */
    private Map<String, CallableDeclaration<?>> parseCallables(String content) {
        Map<String, CallableDeclaration<?>> callables = new HashMap<>();
        if (content == null || content.isEmpty()) {
            return callables;
        }

        content = sanitizeFileContent(content);

        try {
            ParseResult<CompilationUnit> pr = javaParser.parse(content);
            if (pr.getResult().isEmpty()) return callables;

            CompilationUnit cu = pr.getResult().get();

            for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
                callables.put(buildMethodSignature(md), md);
            }
            for (ConstructorDeclaration cd : cu.findAll(ConstructorDeclaration.class)) {
                callables.put(buildConstructorSignature(cd), cd);
            }
        } catch (Exception ignored) {
            // parsing fallito: ignora file
        }
        return callables;
    }

    private String sanitizeFileContent(String s) {
        if (s == null) return "";
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        s = s.replace("\u0000", "");
        return s;
    }

    /* =========================================================
       =             UTILITY: METRICHE STATICHE                 =
       ========================================================= */

    private int calculateLOC(CallableDeclaration<?> cd) {
        Optional<BlockStmt> body = getBody(cd);
        if (body.isEmpty()) {
            return 0;
        }

        String[] lines = body.get().toString().split("\\r?\\n");
        boolean inMulti = false;
        int loc = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (inMulti) {
                if (trimmed.contains("*/")) {
                    inMulti = false;
                }
                continue;
            }

            if (trimmed.startsWith("/*")) {
                if (!trimmed.contains("*/")) {
                    inMulti = true;
                }
                continue;
            }

            if (trimmed.startsWith("//")) {
                continue;
            }

            loc++;
        }
        return loc;
    }

    private int calculateNumBranches(CallableDeclaration<?> cd) {
        Optional<BlockStmt> body = getBody(cd);
        if (body.isEmpty()) return 0;

        Node b = body.get();
        int count = 0;

        count += b.findAll(IfStmt.class).size();
        count += b.findAll(ForStmt.class).size();
        count += b.findAll(ForEachStmt.class).size();
        count += b.findAll(WhileStmt.class).size();
        count += b.findAll(DoStmt.class).size();
        count += b.findAll(SwitchEntry.class).size(); // ogni case/default
        count += b.findAll(CatchClause.class).size();
        count += b.findAll(ConditionalExpr.class).size();

        return count;
    }

    private int calculateNestingDepth(CallableDeclaration<?> cd) {
        Optional<BlockStmt> body = getBody(cd);
        if (body.isEmpty()) return 0;
        return calculateNestingDepth(body.get(), 0);
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

    private String calculateBodyHash(CallableDeclaration<?> cd) {
        Optional<BlockStmt> bodyOpt = getBody(cd);
        if (cd == null || bodyOpt.isEmpty()) {
            return "NULL_METHOD_HASH";
        }
        String body = bodyOpt.get().toString();
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
       =             PMD CODE SMELLS (PMD 6.55)                 =
       ========================================================= */

    public Map<Integer, Integer> calculateCodeSmellsByLine(String fileContent) {
        Map<Integer, Integer> result = new HashMap<>();
        if (fileContent == null || fileContent.isBlank()) {
            return result;
        }

        PMDConfiguration cfg = new PMDConfiguration();
        cfg.setDefaultLanguageVersion(pmdJavaLv);
        cfg.setFailOnViolation(false);
        cfg.setThreads(1);
        cfg.addRuleSet(PMD_RULESET_PATH);

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

    private int estimateCodeSmellsForCallable(CallableDeclaration<?> cd,
                                              Map<Integer, Integer> codeSmellsByLine) {
        if (codeSmellsByLine.isEmpty()) {
            return 0;
        }
        int begin = cd.getBegin().map(p -> p.line).orElse(-1);
        int end = cd.getEnd().map(p -> p.line).orElse(-1);
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
       =             UTILITY PER LA FIRMA UNIVOCA               =
       ========================================================= */

    /**
     * Costruisce una catena di tipi contenitori.
     * Include anche identificatori per anonymous class per evitare collisioni.
     */
    private String buildEnclosingTypeChain(Node start) {
        List<String> parts = new ArrayList<>();

        Node n = start;
        while (n != null) {
            if (n instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration c) {
                parts.add(c.getNameAsString());
            } else if (n instanceof com.github.javaparser.ast.body.EnumDeclaration e) {
                parts.add(e.getNameAsString());
            } else if (n instanceof com.github.javaparser.ast.body.RecordDeclaration r) {
                parts.add(r.getNameAsString());
            } else if (n instanceof com.github.javaparser.ast.body.AnnotationDeclaration a) {
                parts.add(a.getNameAsString());
            } else if (n instanceof ObjectCreationExpr oce && oce.getAnonymousClassBody().isPresent()) {
                parts.add(buildAnonymousQualifier(oce));
            }
            n = n.getParentNode().orElse(null);
        }

        Collections.reverse(parts);

        if (parts.isEmpty()) {
            return "<unknownType>";
        }
        return String.join("$", parts);
    }

    private String buildAnonymousQualifier(ObjectCreationExpr oce) {
        String typeName = eraseGenericType(oce.getType().asString());
        String ctx = "pos";

        Node parent = oce.getParentNode().orElse(null);
        if (parent instanceof com.github.javaparser.ast.body.VariableDeclarator vd) {
            ctx = vd.getNameAsString();
        } else if (parent instanceof com.github.javaparser.ast.expr.AssignExpr ae) {
            ctx = ae.getTarget().toString();
        } else if (parent instanceof com.github.javaparser.ast.body.FieldDeclaration fd
                && !fd.getVariables().isEmpty()) {
            ctx = fd.getVariable(0).getNameAsString();
        } else if (parent instanceof com.github.javaparser.ast.expr.MethodCallExpr mce) {
            int idx = mce.getArguments().indexOf(oce);
            ctx = "arg" + idx;
        } else if (parent instanceof ObjectCreationExpr oce2) {
            int idx = oce2.getArguments().indexOf(oce);
            ctx = "arg" + idx;
        } else {
            int line = oce.getBegin().map(p -> p.line).orElse(-1);
            int col = oce.getBegin().map(p -> p.column).orElse(-1);
            ctx = "pos" + line + "_" + col;
        }

        ctx = ctx.replaceAll("[^a-zA-Z0-9_.$-]", "_");
        if (ctx.length() > 40) {
            ctx = ctx.substring(0, 40);
        }

        return "<anon:" + typeName + "@" + ctx + ">";
    }

    /**
     * "Erasure" semplice: rimuove tutto ciò che è tra <...> gestendo nesting.
     * Esempio: Map<String, List<Integer>> -> Map
     */
    private String eraseGenericType(String typeStr) {
        if (typeStr == null) return "";

        String s = typeStr.replaceAll("\\s+", "");
        StringBuilder out = new StringBuilder(s.length());

        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '<') {
                depth++;
                continue;
            }
            if (ch == '>') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0) {
                out.append(ch);
            }
        }
        String res = out.toString();
        // normalizza varargs
        res = res.replace("...", "[]");
        return res;
    }
}
