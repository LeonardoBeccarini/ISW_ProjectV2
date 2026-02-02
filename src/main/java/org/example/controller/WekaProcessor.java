package org.example.controller;

import org.example.model.ClassifierEvaluation;
import org.example.model.EvaluationFile;
import org.example.model.Method;
import org.example.utilities.ArffExporter;
import weka.attributeSelection.BestFirst;
import weka.attributeSelection.CfsSubsetEval;
import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.lazy.IBk;
import weka.classifiers.meta.CostSensitiveClassifier;
import weka.classifiers.meta.FilteredClassifier;
import weka.classifiers.trees.RandomForest;
import weka.core.AttributeStats;
import weka.core.Instance;
import weka.core.Instances;
import weka.classifiers.CostMatrix;
import weka.filters.Filter;
import weka.filters.MultiFilter;
import weka.filters.supervised.attribute.AttributeSelection;
import weka.filters.supervised.instance.Resample;
import weka.filters.supervised.instance.SMOTE;
import weka.filters.unsupervised.attribute.Normalize;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Motore di classificazione WEKA (walk-forward temporale), allineato al progetto reference.
 * Differenze chiave rispetto alla versione precedente:
 * - Dataset per WEKA senza VersionIndex come feature (niente data leakage).
 * - Classificatori e strategie 1:1 con reference:
 *     baseline + feature selection (CFS+BestFirst backward) + SMOTE + cost-sensitive.
 * - Solo 3 modelli base: RandomForest, NaiveBayes, IBk.
 * - Niente tuning di soglia / Standardize / combinazioni SMOTE+CostSensitive.
 */
public class WekaProcessor {

    private static final Logger LOGGER = Logger.getLogger(WekaProcessor.class.getName());

    private static final String FS_NONE = "none";
    private static final String FS_BEST_FIRST = "BestFirst (backward)";
    private static final String SAMPLING_NONE = "none";
    private static final String SAMPLING_SMOTE = "SMOTE";
    private static final String COST_NONE = "none";
    private static final String COST_SENSITIVE = "CostSensitive";

    private static final String MODEL_RANDOM_FOREST = "RandomForest";
    private static final String MODEL_NAIVE_BAYES = "NaiveBayes";
    private static final String MODEL_IBK = "IBk";
    private static final String CLASS_YES = "yes";

    private record EvalSpec(Classifier classifier,
                            String modelName,
                            String featureSelection,
                            String sampling,
                            String costSensitive) {
    }

    // Usato solo nella parte "what-if"
    private record Agg(double aucSum, int aucN, double mccSum, int mccN) {
    }

    private final String projectName;
    private final List<Method> allMethods;

    public WekaProcessor(String projectName, List<Method> allMethods) {
        this.projectName = (projectName == null || projectName.isBlank()) ? "PROJECT" : projectName;
        this.allMethods = (allMethods == null) ? new ArrayList<>() : new ArrayList<>(allMethods);
    }

    /**
     * Esegue la pipeline di predizione temporale (walk-forward) e salva:
     * - output/arff/<PROJ>/temporal/iteration_i/{training,testing}.arff
     * - output/csv/<PROJ>/weka_walkforward_details.csv
     * - output/csv/<PROJ>/weka_walkforward.csv
     */
    public List<ClassifierEvaluation> runPredictionPipeline() {
        List<ClassifierEvaluation> evaluations = new ArrayList<>();
        if (allMethods.isEmpty()) {
            return evaluations;
        }

        sortMethodsDeterministic();

        java.util.Map<Integer, List<Method>> byRelease = groupMethodsByRelease(allMethods);
        List<Integer> releaseIds = sortedReleaseIds(byRelease);

        if (releaseIds.size() < 2) {
            return evaluations;
        }

        boolean isStorm = "STORM".equalsIgnoreCase(projectName);
        final int windowSize = 5;

        // STORM: parto solo quando posso avere 5 release in training + 1 in test
        int startPos = isStorm ? (windowSize - 1) : 0;

        logFormat(Level.INFO,
                "Walk-forward: %d releases disponibili, startPos=%d, isStorm=%b",
                releaseIds.size(), startPos, isStorm);

        executeWalkForward(startPos, releaseIds, byRelease, isStorm, windowSize, evaluations);

        saveEvaluationCSVs(evaluations);
        return evaluations;
    }

    private void sortMethodsDeterministic() {
        // Ordine deterministico: versione, poi nome metodo
        allMethods.sort(Comparator
                .comparingInt((Method m) -> (m.getVersion() != null) ? m.getVersion().getIndex() : Integer.MAX_VALUE)
                .thenComparing(Method::getFullyQualifiedName, Comparator.nullsLast(String::compareTo)));
    }

    private static java.util.Map<Integer, List<Method>> groupMethodsByRelease(List<Method> methods) {
        return methods.stream()
                .filter(m -> m.getVersion() != null)
                .collect(Collectors.groupingBy(m -> m.getVersion().getIndex()));
    }

    private static List<Integer> sortedReleaseIds(java.util.Map<Integer, List<Method>> byRelease) {
        List<Integer> releaseIds = new ArrayList<>(byRelease.keySet());
        releaseIds.sort(Integer::compareTo);
        return releaseIds;
    }

    private void executeWalkForward(int startPos,
                                    List<Integer> releaseIds,
                                    java.util.Map<Integer, List<Method>> byRelease,
                                    boolean isStorm,
                                    int windowSize,
                                    List<ClassifierEvaluation> evaluations) {

        // Contatore sequenziale per le iterazioni (1, 2, 3, ...)
        int iterationCounter = 1;

        for (int pos = startPos; pos < releaseIds.size() - 1; pos++) {
            int nextReleaseId = releaseIds.get(pos + 1);

            List<Integer> trainReleaseIds = isStorm
                    ? releaseIds.subList(pos - windowSize + 1, pos + 1) // SEMPRE 5 release presenti
                    : releaseIds.subList(0, pos + 1);                   // cumulative

            // (Sonar) toList(): lista non modificata dopo la creazione
            List<Method> trainingMethods = trainReleaseIds.stream()
                    .flatMap(rid -> byRelease.getOrDefault(rid, List.of()).stream())
                    .toList();

            List<Method> testingMethods = byRelease.getOrDefault(nextReleaseId, List.of());

            logFormat(Level.INFO,
                    "Iteration %d: train releases %s (%d methods), test release %d (%d methods)",
                    iterationCounter, trainReleaseIds, trainingMethods.size(), nextReleaseId, testingMethods.size());

            if (trainingMethods.isEmpty() || testingMethods.isEmpty()) {
                logFormat(Level.WARNING, "Skipping iteration %d: empty train/test set", iterationCounter);
            } else {
                executeIteration(iterationCounter, trainReleaseIds, nextReleaseId, trainingMethods, testingMethods, evaluations);
            }

            // Conta comunque per mantenere coerenza con pos (come nell'originale)
            iterationCounter++;
        }
    }

    private void executeIteration(int iteration,
                                  List<Integer> trainReleaseIds,
                                  int nextReleaseId,
                                  List<Method> trainingMethods,
                                  List<Method> testingMethods,
                                  List<ClassifierEvaluation> evaluations) {
        try {
            // Usa iterationCounter per la directory (sequenziale) - identico all'originale
            String iterDir = String.format("output/arff/%s/temporal/iteration_%d",
                    projectName.toUpperCase(), iteration);
            Path iterPath = Paths.get(iterDir);
            Files.createDirectories(iterPath);

            Instances trainingSet = ArffExporter.methodsToInstances(trainingMethods, "training");
            Instances testingSet = ArffExporter.methodsToInstances(testingMethods, "testing");

            // Salva sempre per inspection (identico all'originale)
            ArffExporter.saveInstancesAsArff(trainingSet, iterPath.resolve("training.arff").toString());
            ArffExporter.saveInstancesAsArff(testingSet, iterPath.resolve("testing.arff").toString());

            if (testingSet.isEmpty()) {
                logFormat(Level.WARNING, "Skipping iteration %d: testingSet empty after conversion", iteration);
                return;
            }

            // Usa iteration (sequenziale) invece di currentReleaseId (come nell'originale)
            runAllClassifiersForIteration(iteration, trainingSet, testingSet, evaluations);

        } catch (Exception e) {
            logFormat(Level.SEVERE,
                    e,
                    "Failed temporal iteration %d (train releases=%s, test=%d)",
                    iteration, trainReleaseIds, nextReleaseId);
        }
    }

    /**
     * Evita chiamate costose (String.format) se il livello non è abilitato.
     */
    private static void logFormat(Level level, String format, Object... args) {
        if (!LOGGER.isLoggable(level)) {
            return;
        }
        LOGGER.log(level, String.format(format, args));
    }

    private static void logFormat(Level level, Throwable thrown, String format, Object... args) {
        if (!LOGGER.isLoggable(level)) {
            return;
        }
        LOGGER.log(level, String.format(format, args), thrown);
    }


    /* =========================================================
       =                 CLASSIFIER GRID                        =
       ========================================================= */

    private void runAllClassifiersForIteration(int iteration,
                                               Instances trainingSet,
                                               Instances testingSet,
                                               List<ClassifierEvaluation> out) {

        // Baseline
        evaluateAndAppend(iteration, trainingSet, testingSet,
                new EvalSpec(createRandomForest(), MODEL_RANDOM_FOREST, FS_NONE, SAMPLING_NONE, COST_NONE), out);
        evaluateAndAppend(iteration, trainingSet, testingSet,
                new EvalSpec(createNaiveBayes(), MODEL_NAIVE_BAYES, FS_NONE, SAMPLING_NONE, COST_NONE), out);
        evaluateAndAppend(iteration, trainingSet, testingSet,
                new EvalSpec(createIBk(), MODEL_IBK, FS_NONE, SAMPLING_NONE, COST_NONE), out);

        // Feature Selection (CFS + BestFirst backward)
        evaluateAndAppend(iteration, trainingSet, testingSet,
                new EvalSpec(wrapWithFeatureSelection(createRandomForest()), MODEL_RANDOM_FOREST, FS_BEST_FIRST, SAMPLING_NONE, COST_NONE), out);
        evaluateAndAppend(iteration, trainingSet, testingSet,
                new EvalSpec(wrapWithFeatureSelection(createNaiveBayes()), MODEL_NAIVE_BAYES, FS_BEST_FIRST, SAMPLING_NONE, COST_NONE), out);
        evaluateAndAppend(iteration, trainingSet, testingSet,
                new EvalSpec(wrapWithFeatureSelection(createIBk()), MODEL_IBK, FS_BEST_FIRST, SAMPLING_NONE, COST_NONE), out);

        // SMOTE
        evaluateAndAppend(iteration, trainingSet, testingSet,
                new EvalSpec(wrapWithSmote(trainingSet, createRandomForest()), MODEL_RANDOM_FOREST, FS_NONE, SAMPLING_SMOTE, COST_NONE), out);
        evaluateAndAppend(iteration, trainingSet, testingSet,
                new EvalSpec(wrapWithSmote(trainingSet, createNaiveBayes()), MODEL_NAIVE_BAYES, FS_NONE, SAMPLING_SMOTE, COST_NONE), out);
        evaluateAndAppend(iteration, trainingSet, testingSet,
                new EvalSpec(wrapWithSmote(trainingSet, createIBk()), MODEL_IBK, FS_NONE, SAMPLING_SMOTE, COST_NONE), out);

        // Feature Selection + SMOTE
        evaluateAndAppend(iteration, trainingSet, testingSet,
                new EvalSpec(wrapWithFeatureSelectionAndSmote(trainingSet, createRandomForest()),
                        MODEL_RANDOM_FOREST, FS_BEST_FIRST, SAMPLING_SMOTE, COST_NONE), out);

        evaluateAndAppend(iteration, trainingSet, testingSet,
                new EvalSpec(wrapWithFeatureSelectionAndSmote(trainingSet, createNaiveBayes()),
                        MODEL_NAIVE_BAYES, FS_BEST_FIRST, SAMPLING_SMOTE, COST_NONE), out);

        evaluateAndAppend(iteration, trainingSet, testingSet,
                new EvalSpec(wrapWithFeatureSelectionAndSmote(trainingSet, createIBk()),
                        MODEL_IBK, FS_BEST_FIRST, SAMPLING_SMOTE, COST_NONE), out);

        // Cost Sensitive
        evaluateAndAppend(iteration, trainingSet, testingSet,
                new EvalSpec(wrapWithCostSensitive(createRandomForest()), MODEL_RANDOM_FOREST, FS_NONE, SAMPLING_NONE, COST_SENSITIVE), out);
        evaluateAndAppend(iteration, trainingSet, testingSet,
                new EvalSpec(wrapWithCostSensitive(createNaiveBayes()), MODEL_NAIVE_BAYES, FS_NONE, SAMPLING_NONE, COST_SENSITIVE), out);
        evaluateAndAppend(iteration, trainingSet, testingSet,
                new EvalSpec(wrapWithCostSensitive(createIBk()), MODEL_IBK, FS_NONE, SAMPLING_NONE, COST_SENSITIVE), out);
    }

    private void evaluateAndAppend(int iteration,
                                   Instances trainingSet,
                                   Instances testingSet,
                                   EvalSpec spec,
                                   List<ClassifierEvaluation> out) {
        try {
            ClassifierEvaluation e = evaluateClassifier(iteration, trainingSet, testingSet, spec);
            if (e != null) {
                out.add(e);
            }
        } catch (Exception _) {
            LOGGER.log(Level.WARNING, "Could not evaluate classifier {0} at iteration {1}",
                    new Object[]{spec.modelName(), iteration});
        }
    }

    private ClassifierEvaluation evaluateClassifier(int iteration,
                                                    Instances trainingSet,
                                                    Instances testingSet,
                                                    EvalSpec spec) throws Exception {
        if (trainingSet == null || testingSet == null || trainingSet.isEmpty() || testingSet.isEmpty()) {
            return null;
        }

        Classifier classifier = spec.classifier();

        trainingSet.setClassIndex(trainingSet.numAttributes() - 1);
        testingSet.setClassIndex(testingSet.numAttributes() - 1);

        int posIndex = trainingSet.classAttribute().indexOfValue(CLASS_YES);
        if (posIndex < 0) posIndex = 1; // fallback
        int negIndex = (posIndex == 0) ? 1 : 0;

        //normalizzo le feature
        // PREPROCESSING: Normalize (fit su training, apply su testing)
        Instances[] norm = normalizeTrainTestNoLeakage(trainingSet, testingSet);
        trainingSet = norm[0];
        testingSet  = norm[1];

        classifier.buildClassifier(trainingSet);
        Evaluation eval = new Evaluation(trainingSet);
        eval.evaluateModel(classifier, testingSet);

        double[][] cm = eval.confusionMatrix();
        int tp = safeInt(cm, posIndex, posIndex);
        int fn = safeInt(cm, posIndex, negIndex);
        int fp = safeInt(cm, negIndex, posIndex);
        int tn = safeInt(cm, negIndex, negIndex);

        double precision = eval.precision(posIndex);
        double recall = eval.recall(posIndex);
        double f1 = eval.fMeasure(posIndex);
        double auc = eval.areaUnderROC(posIndex);
        double kappa = eval.kappa();
        double mcc = eval.matthewsCorrelationCoefficient(posIndex);

        double specificity = (tn + fp) > 0 ? ((double) tn / (double) (tn + fp)) : 0.0;
        double balancedAcc = (recall + specificity) / 2.0;
        double gMean = Math.sqrt(Math.max(0.0, recall) * Math.max(0.0, specificity));

        ClassifierEvaluation ce = new ClassifierEvaluation(projectName, iteration);
        ce.setTrainingSize(trainingSet.numInstances());
        ce.setTestingSize(testingSet.numInstances());
        ce.setClassifier(spec.modelName());
        ce.setFeatureSelection(spec.featureSelection());
        ce.setSampling(spec.sampling());
        ce.setCostSensitive(spec.costSensitive());

        // nessun tuning soglia (default)
        ce.setThresholdStrategy("DEFAULT");
        ce.setThreshold(0.5);

        ce.setPrecision(precision);
        ce.setRecall(recall);
        ce.setF1(f1);
        ce.setAuc(auc);
        ce.setKappa(kappa);
        ce.setMcc(mcc);
        ce.setSpecificity(specificity);
        ce.setBalancedAccuracy(balancedAcc);
        ce.setGMean(gMean);
        ce.setTp(tp);
        ce.setFp(fp);
        ce.setTn(tn);
        ce.setFn(fn);

        return ce;
    }

    private static int safeInt(double[][] m, int r, int c) {
        if (m == null) return 0;
        if (r < 0 || c < 0) return 0;
        if (r >= m.length) return 0;
        if (c >= m[r].length) return 0;
        return (int) Math.round(m[r][c]);
    }

    /* =========================================================
       =                 WRAPPERS / FILTERS                    =
       ========================================================= */

    // normalizziamooo
    private static Instances[] normalizeTrainTestNoLeakage(Instances train, Instances test) throws Exception {
        Normalize norm = new Normalize();
        norm.setInputFormat(train);

        Instances trainOut = applyTrainedFilter(norm, train);

        Filter normCopy = Filter.makeCopy(norm);
        Instances testOut = applyTrainedFilter(normCopy, test);

        trainOut.setClassIndex(trainOut.numAttributes() - 1);
        testOut.setClassIndex(testOut.numAttributes() - 1);
        return new Instances[]{trainOut, testOut};
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



    private static Classifier createRandomForest() {
        RandomForest rf = new RandomForest();
        rf.setSeed(1);
        return rf;
    }

    private static Classifier createNaiveBayes() {
        return new NaiveBayes();
    }

    private static Classifier createIBk() {
        return new IBk();
    }

    private static FilteredClassifier wrapWithFeatureSelection(Classifier base) {
        FilteredClassifier fc = new FilteredClassifier();
        fc.setFilter(createFeatureSelectionFilter());
        fc.setClassifier(base);
        return fc;
    }

    private static FilteredClassifier wrapWithSmote(Instances trainingSet, Classifier base) {
        FilteredClassifier fc = new FilteredClassifier();
        fc.setFilter(createSmoteFilter(trainingSet));
        fc.setClassifier(base);
        return fc;
    }

    private static CostSensitiveClassifier wrapWithCostSensitive(Classifier base) {
        CostSensitiveClassifier cs = new CostSensitiveClassifier();
        cs.setClassifier(base);
        cs.setCostMatrix(createCostMatrix());
        cs.setMinimizeExpectedCost(false);
        return cs;
    }

    private static FilteredClassifier wrapWithFeatureSelectionAndSmote(Instances trainingSet, Classifier base) {
        FilteredClassifier fc = new FilteredClassifier();
        fc.setFilter(createFeatureSelectionAndSmoteFilter(trainingSet));
        fc.setClassifier(base);
        return fc;
    }

    private static Filter createFeatureSelectionAndSmoteFilter(Instances trainingSet) {
        MultiFilter mf = new MultiFilter();
        mf.setFilters(new Filter[] {
                createFeatureSelectionFilter(),
                createSmoteFilter(trainingSet)
        });
        return mf;
    }

    private static Filter createFeatureSelectionFilter() {
        AttributeSelection attributeSelection = new AttributeSelection();
        attributeSelection.setEvaluator(new CfsSubsetEval());

        BestFirst bestFirst = new BestFirst();
        try {
            // -D 0 => backward (come reference)
            bestFirst.setOptions(new String[]{"-D", "0"});
        } catch (Exception _) {
            // fallback: default BestFirst
        }
        attributeSelection.setSearch(bestFirst);
        return attributeSelection;
    }

    private static Filter createSmoteFilter(Instances trainingSet) {
        // In caso di dataset non nominale o senza entrambe le classi, fallback a Resample
        if (trainingSet == null || trainingSet.classIndex() < 0) {
            return new Resample();
        }

        AttributeStats stats = trainingSet.attributeStats(trainingSet.classIndex());
        int[] counts = (stats != null) ? stats.nominalCounts : null;
        if (counts == null || counts.length < 2) {
            return new Resample();
        }

        int majority = Math.max(counts[0], counts[1]);
        int minority = Math.min(counts[0], counts[1]);
        if (minority <= 0) {
            return new Resample();
        }

        double smotePercentage = ((double) (majority - minority) / (double) minority) * 100.0;
        if (smotePercentage <= 0.0) {
            return new Resample();
        }

        SMOTE smote = new SMOTE();
        smote.setPercentage(smotePercentage);
        smote.setRandomSeed(1);
        // -C default 0: auto-detect non-empty minority class
        return smote;
    }

    private static CostMatrix createCostMatrix() {
        CostMatrix cm = new CostMatrix(2);
        // no error on correct classification
        cm.setCell(0, 0, 0.0);
        cm.setCell(1, 1, 0.0);
        // FP cost
        cm.setCell(0, 1, 1.0);
        // FN cost (miss a buggy method) â€“ fixed 10 as reference
        cm.setCell(1, 0, 10.0);
        return cm;
    }

    /* =========================================================
       =                    CSV OUTPUT                         =
       ========================================================= */

    private void saveEvaluationCSVs(List<ClassifierEvaluation> evaluations) {
        try {
            Path outDir = Paths.get("output", "csv", projectName.toUpperCase());
            Files.createDirectories(outDir);

            // dettagli
            new EvaluationFile(projectName, evaluations, "details")
                    .createANewFile(outDir.resolve("weka_walkforward_details.csv").toString());

            // senza TR/TE size
            new EvaluationFile(projectName, evaluations, "summary")
                    .createANewFile(outDir.resolve("weka_walkforward.csv").toString());
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Could not write evaluation CSV files", e);
        }
    }

    /* =========================================================
       =                    FOR 'WHAT IF' ANALYSIS              =
       ========================================================= */

    public record ModelSpec(String classifier, String featureSelection, String sampling, String costSensitive) {

        @Override
        public String toString() {
            return classifier + " | FS=" + featureSelection + " | Sampling=" + sampling + " | Cost=" + costSensitive;
        }
    }

    public static ModelSpec pickBestSpec(List<org.example.model.ClassifierEvaluation> evals) {
        if (evals == null || evals.isEmpty()) {
            return new ModelSpec(MODEL_RANDOM_FOREST, FS_NONE, SAMPLING_NONE, COST_NONE);
        }

        java.util.Map<String, Agg> aggregates = aggregateBySpec(evals);
        String bestKey = selectBestKey(aggregates);

        if (bestKey == null) {
            return new ModelSpec(MODEL_RANDOM_FOREST, FS_NONE, SAMPLING_NONE, COST_NONE);
        }
        return specFromKey(bestKey);
    }

    private static java.util.Map<String, Agg> aggregateBySpec(List<org.example.model.ClassifierEvaluation> evals) {
        java.util.Map<String, Agg> m = new java.util.HashMap<>();

        for (org.example.model.ClassifierEvaluation e : evals) {
            String key = buildSpecKey(e);
            double auc = parseOrNaN(e.getAuc());
            double mcc = parseOrNaN(e.getMcc());

            Agg current = m.getOrDefault(key, new Agg(0, 0, 0, 0));
            m.put(key, updatedAgg(current, auc, mcc));
        }

        return m;
    }

    private static String buildSpecKey(org.example.model.ClassifierEvaluation e) {
        return String.join("|",
                nz(e.getClassifier()),
                nz(e.getFeatureSelection()),
                nz(e.getSampling()),
                nz(e.getCostSensitive())
        );
    }

    private static Agg updatedAgg(Agg current, double auc, double mcc) {
        double aucSum = current.aucSum();
        int aucN = current.aucN();
        if (!Double.isNaN(auc)) {
            aucSum += auc;
            aucN += 1;
        }

        double mccSum = current.mccSum();
        int mccN = current.mccN();
        if (!Double.isNaN(mcc)) {
            mccSum += mcc;
            mccN += 1;
        }

        return new Agg(aucSum, aucN, mccSum, mccN);
    }

    private static String selectBestKey(java.util.Map<String, Agg> aggregates) {
        String bestKey = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (var ent : aggregates.entrySet()) {
            double score = score(ent.getValue());
            if (score > bestScore) {
                bestScore = score;
                bestKey = ent.getKey();
            }
        }

        return bestKey;
    }

    private static double score(Agg a) {
        double meanAuc = mean(a.aucSum(), a.aucN());
        if (!Double.isNaN(meanAuc)) {
            return meanAuc;
        }

        double meanMcc = mean(a.mccSum(), a.mccN());
        if (!Double.isNaN(meanMcc)) {
            return meanMcc;
        }

        return Double.NEGATIVE_INFINITY;
    }

    private static double mean(double sum, int n) {
        return (n > 0) ? (sum / n) : Double.NaN;
    }

    private static ModelSpec specFromKey(String key) {
        String[] p = key.split("\\|", -1);
        return new ModelSpec(p[0], p[1], p[2], p[3]);
    }

    public weka.classifiers.Classifier buildClassifier(weka.core.Instances training, ModelSpec spec) {
        weka.classifiers.Classifier base;
        switch (spec.classifier) {
            case MODEL_NAIVE_BAYES -> base = createNaiveBayes();
            case MODEL_IBK -> base = createIBk();
            default -> base = createRandomForest();
        }

        weka.classifiers.Classifier model = base;

        boolean fs = FS_BEST_FIRST.equals(spec.featureSelection);
        boolean smote = SAMPLING_SMOTE.equals(spec.sampling);
        boolean cs = COST_SENSITIVE.equals(spec.costSensitive);

        if (fs && smote) model = wrapWithFeatureSelectionAndSmote(training, model);
        else if (fs) model = wrapWithFeatureSelection(model);
        else if (smote) model = wrapWithSmote(training, model);

        if (cs) model = wrapWithCostSensitive(model);

        return model;
    }

    private static String nz(String s) {
        return (s == null) ? "" : s;
    }

    private static double parseOrNaN(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception _) {
            return Double.NaN;
        }
    }

}
