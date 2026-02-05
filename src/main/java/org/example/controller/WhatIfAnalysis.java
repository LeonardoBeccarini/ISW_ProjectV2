package org.example.controller;

import org.example.model.ClassifierEvaluation;
import org.example.utilities.CsvExporter;
import org.example.utilities.CsvExporter.RefactorDelta;
import org.example.utilities.CsvExporter.WhatIfContext;
import org.example.utilities.CsvExporter.WhatIfResult;
import weka.classifiers.Classifier;
import weka.classifiers.rules.ZeroR;
import weka.core.*;
import weka.core.converters.CSVLoader;
import weka.core.converters.CSVSaver;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.Normalize;
import weka.filters.unsupervised.attribute.Remove;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * What-If analysis (method-level) allineata al report.
 *<p></p>
 * Responsabilità UNICA: logica di analisi What-If con modelli ML.
 * Delega TUTTO l'I/O CSV a CsvExporter.
 */
public class WhatIfAnalysis {

    private static final String COL_VERSION_INDEX = "VersionIndex";
    private static final String COL_VERSION_NAME = "VersionName";
    private static final String COL_METHOD_FQN = "MethodFQN";
    private static final String COL_BODY_HASH = "BodyHash";
    private static final String COL_BUGGY = "Buggy";
    private static final String NUM_CODESMELLS= "NumCodeSmells";
    private static final double DEFAULT_TOP_QUANTILE_FOR_BPLUS = 0.75;
    private static final double EPS = 1e-9;

    private final String projectName;
    private final String actionableFeature;
    private final String baseMethodName;

    public WhatIfAnalysis(String projectNameUpperCase, String actionableFeature, String baseMethodName) {
        this.projectName = Objects.requireNonNull(projectNameUpperCase, "projectNameUpperCase").trim().toUpperCase();
        this.actionableFeature = Objects.requireNonNull(actionableFeature, "actionableFeature").trim();
        this.baseMethodName = Objects.requireNonNull(baseMethodName, "baseMethodName").trim();
        if (this.actionableFeature.isBlank()) throw new IllegalArgumentException("actionableFeature is blank");
        if (this.baseMethodName.isBlank()) throw new IllegalArgumentException("baseMethodName is blank");
    }

    // ================================================================
    //                    ENTRY POINT
    // ================================================================

    public void execute() throws Exception {
        Path projectCsvDir = Paths.get("output", "csv", projectName);
        ExecuteContext ctx = loadAndValidateInputs(projectCsvDir);

        // Temporal split and filtering
        SplitResult splitResult = performTemporalSplit(ctx.raw, ctx.versionAttr, ctx.latestVersion);
        Instances train = applyRemoveMetaFilter(splitResult.trainRaw);
        Instances test = applyRemoveMetaFilter(splitResult.testRaw, splitResult.trainRaw);

        // Build B+, B, C from TEST
        Attribute aFeatAttr = validateActionableFeature(test);
        int aFeatIndex = aFeatAttr.index();

        double threshold = chooseBPlusThreshold(test, actionableFeature, DEFAULT_TOP_QUANTILE_FOR_BPLUS);
        BPlusSplit bPlusSplit = buildBPlusSets(test, aFeatIndex, threshold);

        // B = copy of B+ with AFeature transformed
        Instances b = new Instances(bPlusSplit.bPlus);
        applyWhatIfTransformation(b, aFeatIndex, ctx.delta, actionableFeature);

        // Normalize and evaluate
        NormalizedSets normSets = normalizeAllSets(train, test, bPlusSplit.bPlus, b, bPlusSplit.c);
        EvaluationResults evalResults = evaluateAndCompute(normSets, ctx.evalPath);

        // Save outputs (delega a CsvExporter)
        saveAllOutputs(projectCsvDir, normSets, evalResults, ctx.delta, bPlusSplit.threshold);
    }

    // ================================================================
    //                    CONTEXT CLASSES
    // ================================================================

    private static final class ExecuteContext {
        Instances raw;
        Attribute versionAttr;
        int latestVersion;
        RefactorDelta delta;
        Path evalPath;
    }

    private static final class SplitResult {
        Instances trainRaw;
        Instances testRaw;
    }

    private static final class BPlusSplit {
        Instances bPlus;
        Instances c;
        double threshold;
    }

    private static final class NormalizedSets {
        Instances trainN;
        Instances testN;
        Instances bPlusN;
        Instances bN;
        Instances cN;
    }

    private static final class EvaluationResults {
        WhatIfResult rA;
        WhatIfResult rBPlus;
        WhatIfResult rB;
        WhatIfResult rC;
        WekaProcessor.ModelSpec bestSpec;
        double deltaExpectedProb;
        double relOnBPlusProb;
        double relOnAProb;
        int deltaEstimatedClassify;
    }

    // ================================================================
    //                    INPUT LOADING (usa CsvExporter)
    // ================================================================

    private ExecuteContext loadAndValidateInputs(Path projectCsvDir) throws IOException {
        ExecuteContext ctx = new ExecuteContext();
        Path datasetPath = projectCsvDir.resolve("dataset.csv");
        ctx.evalPath = projectCsvDir.resolve("weka_walkforward.csv");
        Path refPath = projectCsvDir.resolve("refactor_metrics_" + baseMethodName + ".csv");

        if (!Files.exists(datasetPath)) throw new FileNotFoundException("Missing dataset: " + datasetPath);
        if (!Files.exists(ctx.evalPath)) throw new FileNotFoundException("Missing evaluations: " + ctx.evalPath);
        if (!Files.exists(refPath)) throw new FileNotFoundException("Missing refactor metrics: " + refPath);

        ctx.raw = loadCsvAsInstances(datasetPath);
        if (ctx.raw.isEmpty()) throw new IllegalStateException("Dataset is empty: " + datasetPath);

        ctx.versionAttr = ctx.raw.attribute(COL_VERSION_INDEX);
        Attribute buggyAttr = ctx.raw.attribute(COL_BUGGY);
        if (ctx.versionAttr == null) throw new IllegalArgumentException("Dataset missing column: " + COL_VERSION_INDEX);
        if (buggyAttr == null) throw new IllegalArgumentException("Dataset missing column: " + COL_BUGGY);

        ctx.latestVersion = findMaxInt(ctx.raw, ctx.versionAttr);
        if (ctx.latestVersion < 2)
            throw new IllegalStateException("Not enough releases for what-if (latestVersion=" + ctx.latestVersion + ")");

        // Delega lettura refactor delta a CsvExporter
        ctx.delta = CsvExporter.readRefactorDelta(refPath, actionableFeature);
        return ctx;
    }

    // ================================================================
    //                    TEMPORAL SPLIT
    // ================================================================

    private static SplitResult performTemporalSplit(Instances raw, Attribute versionAttr, int latestVersion) {
        SplitResult result = new SplitResult();
        result.trainRaw = new Instances(raw, 0);
        result.testRaw = new Instances(raw, 0);

        for (int i = 0; i < raw.numInstances(); i++) {
            Instance inst = raw.instance(i);
            int v = (int) Math.round(inst.value(versionAttr));
            if (v < latestVersion) result.trainRaw.add(inst);
            else if (v == latestVersion) result.testRaw.add(inst);
        }

        if (result.trainRaw.isEmpty() || result.testRaw.isEmpty()) {
            throw new IllegalStateException("Temporal split produced empty train or test: train=" +
                    result.trainRaw.size() + ", test=" + result.testRaw.size());
        }
        return result;
    }

    // ================================================================
    //                    FILTERING
    // ================================================================

    private static Instances applyRemoveMetaFilter(Instances data) throws Exception {
        Remove rm = buildRemoveMetaFilter(data);
        Instances filtered = Filter.useFilter(data, rm);
        ensureClassIsLastAndSet(filtered, COL_BUGGY);
        return filtered;
    }

    private static Instances applyRemoveMetaFilter(Instances data, Instances trainRaw) throws Exception {
        Remove rm = buildRemoveMetaFilter(trainRaw);
        Remove rmCopy = (Remove) Filter.makeCopy(rm);
        rmCopy.setInputFormat(data);
        Instances filtered = Filter.useFilter(data, rmCopy);
        ensureClassIsLastAndSet(filtered, COL_BUGGY);
        return filtered;
    }

    private Attribute validateActionableFeature(Instances test) {
        Attribute aFeatAttr = test.attribute(actionableFeature);
        if (aFeatAttr == null)
            throw new IllegalArgumentException("Actionable feature not found in dataset: " + actionableFeature);
        if (!aFeatAttr.isNumeric())
            throw new IllegalArgumentException("Actionable feature is not numeric: " + actionableFeature);
        return aFeatAttr;
    }

    private BPlusSplit buildBPlusSets(Instances test, int aFeatIndex, double threshold) {
        BPlusSplit result = new BPlusSplit();
        result.threshold = threshold;

        Instances bPlus = new Instances(test, 0);
        Instances c = new Instances(test, 0);

        for (int i = 0; i < test.numInstances(); i++) {
            Instance src = test.instance(i);
            double v = src.value(aFeatIndex);
            boolean inBPlus = isInBPlus(actionableFeature, v, threshold);
            if (inBPlus) bPlus.add((Instance) src.copy());
            else c.add((Instance) src.copy());
        }

        if (bPlus.isEmpty() || bPlus.size() == test.size()) {
            SplitBC split = splitByTopK(test, aFeatIndex, DEFAULT_TOP_QUANTILE_FOR_BPLUS);
            result.bPlus = split.bPlus;
            result.c = split.c;
            result.threshold = split.effectiveThreshold;
        } else {
            result.bPlus = bPlus;
            result.c = c;
        }
        return result;
    }

    private static NormalizedSets normalizeAllSets(Instances train, Instances test,
                                                   Instances bPlus, Instances b, Instances c) throws Exception {
        NormalizedSets sets = new NormalizedSets();

        Normalize norm = new Normalize();
        norm.setInputFormat(train);

        sets.trainN = applyTrainedFilter(norm, train);
        sets.testN = applyTrainedFilter(Filter.makeCopy(norm), test);
        sets.bPlusN = applyTrainedFilter(Filter.makeCopy(norm), bPlus);
        sets.bN = applyTrainedFilter(Filter.makeCopy(norm), b);
        sets.cN = applyTrainedFilter(Filter.makeCopy(norm), c);

        return sets;
    }

    // ================================================================
    //                    EVALUATION
    // ================================================================

    private EvaluationResults evaluateAndCompute(NormalizedSets sets, Path evalPath) throws Exception {
        EvaluationResults results = new EvaluationResults();

        // Delega lettura evaluations a CsvExporter
        List<ClassifierEvaluation> evals = CsvExporter.readEvaluationsCsv(projectName, evalPath);
        results.bestSpec = WekaProcessor.pickBestSpec(evals);

        WekaProcessor wp = new WekaProcessor(projectName, Collections.emptyList());
        Classifier model = buildSafeClassifier(wp, sets.trainN, results.bestSpec);

        int posIndex = positiveClassIndex(sets.trainN, "yes");

        results.rA = evaluateDataset("A_latest", sets.testN, model, posIndex);
        results.rBPlus = evaluateDataset("B_plus", sets.bPlusN, model, posIndex);
        results.rB = evaluateDataset("B", sets.bN, model, posIndex);
        results.rC = evaluateDataset("C", sets.cN, model, posIndex);

        results.deltaExpectedProb = results.rBPlus.expectedDefectsSum() - results.rB.expectedDefectsSum();
        results.relOnBPlusProb = (results.rBPlus.expectedDefectsSum() > EPS)
                ? (results.deltaExpectedProb / results.rBPlus.expectedDefectsSum()) : 0.0;
        results.relOnAProb = (results.rA.expectedDefectsSum() > EPS)
                ? (results.deltaExpectedProb / results.rA.expectedDefectsSum()) : 0.0;

        results.deltaEstimatedClassify = results.rBPlus.estimatedBuggyClassify() - results.rB.estimatedBuggyClassify();

        return results;
    }

    private static WhatIfResult evaluateDataset(String name, Instances data, Classifier model, int posIndex) throws Exception {
        int n = data.numInstances();
        int actualBuggy = countActualBuggy(data, posIndex);

        double sumProb = 0.0;
        int hardPredThreshold = 0;
        int hardPredClassify = 0;

        for (int i = 0; i < n; i++) {
            Instance inst = data.instance(i);

            double[] dist = model.distributionForInstance(inst);
            double pYes = (dist != null && dist.length > posIndex) ? dist[posIndex] : 0.0;
            if (Double.isNaN(pYes) || Double.isInfinite(pYes)) pYes = 0.0;

            sumProb += pYes;
            if (pYes >= 0.5) hardPredThreshold++;

            double cls = model.classifyInstance(inst);
            if ((int) Math.round(cls) == posIndex) hardPredClassify++;
        }

        return new WhatIfResult(name, n, actualBuggy, sumProb, hardPredThreshold, hardPredClassify);
    }

    // ================================================================
    //                    OUTPUT (delega a CsvExporter)
    // ================================================================

    private void saveAllOutputs(Path projectCsvDir, NormalizedSets sets, EvaluationResults results,
                                RefactorDelta delta, double threshold) throws IOException {
        Path outDir = projectCsvDir.resolve(Paths.get("whatif",
                CsvExporter.sanitizeFilename(actionableFeature) + "_" + CsvExporter.sanitizeFilename(baseMethodName)));
        Files.createDirectories(outDir);

        saveInstancesAsCsv(sets.testN, outDir.resolve("A_latest.csv"));
        saveInstancesAsCsv(sets.bPlusN, outDir.resolve("B_plus.csv"));
        saveInstancesAsCsv(sets.bN, outDir.resolve("B.csv"));
        saveInstancesAsCsv(sets.cN, outDir.resolve("C.csv"));

        // Delega scrittura risultati a CsvExporter
        WhatIfContext ctx = new WhatIfContext(
                projectName,
                actionableFeature,
                baseMethodName,
                results.bestSpec.toString(),
                delta,
                threshold,
                results.deltaExpectedProb,
                results.relOnBPlusProb,
                results.relOnAProb,
                results.deltaEstimatedClassify
        );

        CsvExporter.writeWhatIfResults(
                outDir.resolve("whatif_results.csv"),
                ctx,
                results.rA,
                results.rBPlus,
                results.rB,
                results.rC
        );
    }

    // ================================================================
    //                    WEKA HELPERS
    // ================================================================

    private static Instances loadCsvAsInstances(Path csvPath) throws IOException {
        CSVLoader loader = new CSVLoader();
        loader.setSource(csvPath.toFile());
        return loader.getDataSet();
    }

    private static void saveInstancesAsCsv(Instances data, Path outPath) throws IOException {
        CSVSaver saver = new CSVSaver();
        saver.setInstances(data);
        saver.setFile(outPath.toFile());
        try {
            saver.writeBatch();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to save CSV: " + outPath, e);
        }
    }

    private static int findMaxInt(Instances data, Attribute attr) {
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < data.numInstances(); i++) {
            int v = (int) Math.round(data.instance(i).value(attr));
            if (v > max) max = v;
        }
        return max;
    }

    private static Remove buildRemoveMetaFilter(Instances trainRaw) throws Exception {
        List<String> metaNames = List.of(COL_VERSION_INDEX, COL_VERSION_NAME, COL_METHOD_FQN, COL_BODY_HASH);

        List<Integer> idx1Based = new ArrayList<>();
        for (String name : metaNames) {
            Attribute a = trainRaw.attribute(name);
            if (a != null) idx1Based.add(a.index() + 1);
        }

        Remove rm = new Remove();
        if (!idx1Based.isEmpty()) {
            idx1Based.sort(Integer::compareTo);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < idx1Based.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(idx1Based.get(i));
            }
            rm.setAttributeIndices(sb.toString());
            rm.setInvertSelection(false);
        } else {
            rm.setAttributeIndices("1");
            rm.setInvertSelection(true);
        }
        rm.setInputFormat(trainRaw);
        return rm;
    }

    private static void ensureClassIsLastAndSet(Instances data, String className) {
        Attribute cls = data.attribute(className);
        if (cls == null) throw new IllegalArgumentException("Missing class attribute: " + className);

        int clsIndex = cls.index();
        int last = data.numAttributes() - 1;

        if (clsIndex != last) {
            Instances rebuilt = rebuildWithClassLast(data, clsIndex, cls);
            replaceDatasetInPlace(data, rebuilt);
        }

        data.setClassIndex(data.numAttributes() - 1);
    }

    private static Instances rebuildWithClassLast(Instances data, int clsIndex, Attribute cls) {
        ArrayList<Attribute> attrs = buildReorderedAttributes(data, clsIndex, cls);
        Instances rebuilt = new Instances(data.relationName(), attrs, data.numInstances());
        copyInstancesWithReorderedClass(data, rebuilt, clsIndex);
        return rebuilt;
    }

    private static ArrayList<Attribute> buildReorderedAttributes(Instances data, int clsIndex, Attribute cls) {
        ArrayList<Attribute> attrs = new ArrayList<>();
        for (int i = 0; i < data.numAttributes(); i++) {
            if (i != clsIndex) attrs.add((Attribute) data.attribute(i).copy());
        }
        attrs.add((Attribute) cls.copy());
        return attrs;
    }

    private static void copyInstancesWithReorderedClass(Instances source, Instances target, int clsIndex) {
        for (int i = 0; i < source.numInstances(); i++) {
            Instance old = source.instance(i);
            double[] vals = new double[target.numAttributes()];

            int k = 0;
            for (int j = 0; j < source.numAttributes(); j++) {
                if (j != clsIndex) vals[k++] = old.value(j);
            }
            vals[vals.length - 1] = old.value(clsIndex);

            DenseInstance ni = new DenseInstance(old.weight(), vals);
            ni.setDataset(target);
            target.add(ni);
        }
    }

    private static void replaceDatasetInPlace(Instances data, Instances rebuilt) {
        while (data.numInstances() > 0) data.delete(0);
        for (int i = 0; i < rebuilt.numInstances(); i++) data.add(rebuilt.instance(i));
        for (int i = 0; i < rebuilt.numAttributes(); i++) data.renameAttribute(i, rebuilt.attribute(i).name());
    }

    private static int positiveClassIndex(Instances data, String positiveLabel) {
        int pos = data.classAttribute().indexOfValue(positiveLabel);
        if (pos >= 0) return pos;
        return Math.min(1, data.classAttribute().numValues() - 1);
    }

    private static Instances applyTrainedFilter(Filter trained, Instances data) throws Exception {
        Instances out = new Instances(trained.getOutputFormat(), 0);

        for (int i = 0; i < data.numInstances(); i++) {
            trained.input(data.instance(i));
            Instance processed;
            while ((processed = trained.output()) != null) {
                out.add(processed);
            }
        }
        trained.batchFinished();

        Instance processed;
        while ((processed = trained.output()) != null) {
            out.add(processed);
        }

        out.setClassIndex(out.numAttributes() - 1);
        return out;
    }

    private static Classifier buildSafeClassifier(WekaProcessor wp, Instances train, WekaProcessor.ModelSpec spec) throws Exception {
        AttributeStats stats = train.attributeStats(train.classIndex());
        int[] counts = (stats != null) ? stats.nominalCounts : null;
        if (counts == null || counts.length < 2 || counts[0] == 0 || counts[1] == 0) {
            ZeroR zr = new ZeroR();
            zr.buildClassifier(train);
            return zr;
        }

        Classifier c = wp.buildClassifier(train, spec);
        c.buildClassifier(train);
        return c;
    }

    private static int countActualBuggy(Instances data, int posIndex) {
        int c = 0;
        for (int i = 0; i < data.numInstances(); i++) {
            if ((int) Math.round(data.instance(i).classValue()) == posIndex) c++;
        }
        return c;
    }

    // ================================================================
    //                    B+/B/C LOGIC
    // ================================================================

    private static boolean isInBPlus(String aFeature, double value, double threshold) {
        if (NUM_CODESMELLS.equalsIgnoreCase(aFeature)) return value > 0.0;
        return value >= threshold;
    }

    private static double chooseBPlusThreshold(Instances test, String aFeature, double topQuantile) {
        Attribute a = test.attribute(aFeature);
        if (a == null) throw new IllegalArgumentException("Missing attribute: " + aFeature);
        if (NUM_CODESMELLS.equalsIgnoreCase(aFeature)) return 0.0;
        return quantile(test, a, topQuantile);
    }

    private static double quantile(Instances data, Attribute attr, double q) {
        double[] vals = new double[data.numInstances()];
        for (int i = 0; i < data.numInstances(); i++) vals[i] = data.instance(i).value(attr);
        Arrays.sort(vals);
        if (vals.length == 0) return 0.0;

        double qq = Math.clamp(q, 0.0, 1.0);
        int idx = (int) Math.floor(qq * (vals.length - 1));
        idx = Math.clamp(idx, 0, vals.length - 1);
        return vals[idx];
    }

    private static final class SplitBC {
        Instances bPlus;
        Instances c;
        double effectiveThreshold;
    }

    private static SplitBC splitByTopK(Instances test, int aFeatIndex, double topQuantile) {
        int n = test.numInstances();
        int k = Math.max(1, (int) Math.ceil((1.0 - topQuantile) * n));

        List<Integer> idx = new ArrayList<>(n);
        for (int i = 0; i < n; i++) idx.add(i);

        idx.sort((i1, i2) -> Double.compare(test.instance(i2).value(aFeatIndex), test.instance(i1).value(aFeatIndex)));

        Set<Integer> top = new HashSet<>(idx.subList(0, Math.min(k, n)));

        Instances bPlus = new Instances(test, 0);
        Instances c = new Instances(test, 0);

        double thr = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            Instance src = test.instance(i);
            if (top.contains(i)) {
                bPlus.add((Instance) src.copy());
                thr = Math.min(thr, src.value(aFeatIndex));
            } else {
                c.add((Instance) src.copy());
            }
        }

        SplitBC out = new SplitBC();
        out.bPlus = bPlus;
        out.c = c;
        out.effectiveThreshold = (thr == Double.POSITIVE_INFINITY) ? 0.0 : thr;
        return out;
    }

    private static void applyWhatIfTransformation(Instances b, int aFeatIndex, RefactorDelta d, String aFeatureName) {
        for (int i = 0; i < b.numInstances(); i++) {
            Instance inst = b.instance(i);
            double oldV = inst.value(aFeatIndex);
            double newV;

            // Per NumCodeSmells: azzera come nel paper
            if (NUM_CODESMELLS.equalsIgnoreCase(aFeatureName)) {
                newV = 0.0;
            } else {
                // Per altre feature: usa il fattore del refactoring
                newV = oldV * d.factor();
            }

            if (Double.isNaN(newV) || Double.isInfinite(newV)) newV = oldV;
            if (newV < 0.0) newV = 0.0;
            inst.setValue(aFeatIndex, newV);
        }
    }
}