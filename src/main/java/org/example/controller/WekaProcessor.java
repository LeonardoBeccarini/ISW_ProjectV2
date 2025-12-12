package org.example.controller;

import org.example.model.ClassifierEvaluation;
import org.example.model.EvaluationFile;
import org.example.model.Method;
import org.example.utilities.ArffExporter;
import weka.attributeSelection.BestFirst;
import weka.attributeSelection.CfsSubsetEval;
import weka.classifiers.Classifier;
import weka.classifiers.CostMatrix;
import weka.classifiers.Evaluation;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.functions.Logistic;
import weka.classifiers.functions.SMO;
import weka.classifiers.lazy.IBk;
import weka.classifiers.meta.CostSensitiveClassifier;
import weka.classifiers.trees.J48;
import weka.classifiers.trees.RandomForest;
import weka.core.Instances;
import weka.filters.Filter;
import weka.filters.supervised.attribute.AttributeSelection;
import weka.filters.supervised.instance.SMOTE;
import weka.filters.unsupervised.attribute.Remove;
import weka.filters.unsupervised.attribute.Standardize;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

public class WekaProcessor {

    private final String projectName;
    private final List<Method> methods;

    private static final boolean APPLY_STANDARDIZE = true;

    private static final boolean[] FEATURE_SELECTION = new boolean[]{false, true};
    private static final boolean[] USE_SMOTE = new boolean[]{false, true};
    private static final boolean[] USE_COST_SENSITIVE = new boolean[]{false, true};

    private static final String THRESHOLD_STRATEGY = "WEKA_ARGMAX";
    private static final double THRESHOLD_VALUE = 0.5;

    private static final List<ModelSpec> MODELS = List.of(
            new ModelSpec("RandomForest", () -> {
                RandomForest rf = new RandomForest();
                rf.setNumIterations(200);
                rf.setSeed(1);
                return rf;
            }),
            new ModelSpec("Logistic", Logistic::new),
            new ModelSpec("J48", () -> {
                J48 t = new J48();
                t.setUnpruned(false);
                return t;
            }),
            new ModelSpec("NaiveBayes", NaiveBayes::new),
            new ModelSpec("SMO", SMO::new),
            new ModelSpec("IBk", () -> {
                IBk k = new IBk();
                k.setKNN(5);
                return k;
            })
    );

    private record ModelSpec(String name, Supplier<Classifier> supplier) {}

    public WekaProcessor(String projectName, List<Method> methods) {
        this.projectName = projectName;
        this.methods = (methods == null) ? Collections.emptyList() : methods;
    }

    public List<ClassifierEvaluation> runPredictionPipeline() throws IOException {

        // directory output coerente con Execution (output/csv/PROJECTNAME)
        Path arffBase = Path.of("output", "arff", projectName.toUpperCase(), "temporal");
        Path csvBase = Path.of("output", "csv", projectName.toUpperCase());
        Files.createDirectories(arffBase);
        Files.createDirectories(csvBase);

        String outCsv = csvBase.resolve("weka_walkforward_details.csv").toString();
        List<ClassifierEvaluation> results = new ArrayList<>();

        // Se non ho metodi, scrivo comunque un CSV (header) e ritorno
        if (methods.isEmpty()) {
            new EvaluationFile(projectName, results, "details").createANewFile(outCsv);
            return results;
        }

        Instances full;
        try {
            full = ArffExporter.methodsToInstances(methods, projectName + "_full");
            full.setClassIndex(full.numAttributes() - 1);
        } catch (Exception e) {
            // anche in caso di errore, prova a creare comunque il CSV
            new EvaluationFile(projectName, results, "details").createANewFile(outCsv);
            throw e;
        }

        int maxVersion = findMaxVersionIndex(full);
        if (maxVersion < 2) {
            new EvaluationFile(projectName, results, "details").createANewFile(outCsv);
            return results;
        }

        for (int i = 1; i <= maxVersion - 1; i++) {

            int windowStart = 1;
            if ("SYNCOPE".equalsIgnoreCase(projectName)) {
                int windowSize = 5;
                windowStart = Math.max(1, i - windowSize + 1);
            }

            Instances trainRaw = subsetByVersionRange(full, windowStart, i);
            Instances testRaw  = subsetByVersionRange(full, i + 1, i + 1);
            if (trainRaw.isEmpty() || testRaw.isEmpty()) continue;

            // salva ARFF raw per debug
            Path iterDir = arffBase.resolve("iteration_" + i);
            Files.createDirectories(iterDir);
            ArffExporter.saveInstancesAsArff(trainRaw, iterDir.resolve("training.arff").toString());
            ArffExporter.saveInstancesAsArff(testRaw,  iterDir.resolve("testing.arff").toString());

            for (ModelSpec model : MODELS) {
                for (boolean fs : FEATURE_SELECTION) {
                    for (boolean smote : USE_SMOTE) {
                        for (boolean cost : USE_COST_SENSITIVE) {

                            try {
                                Instances[] pp = preprocessTrainTest(trainRaw, testRaw, fs);
                                Instances train = pp[0];
                                Instances test = pp[1];

                                String balancingDesc = "none";
                                if (smote) {
                                    train = applySmote(train);
                                    balancingDesc = "SMOTE";
                                }

                                Classifier cls = buildClassifier(model, cost);
                                String costDesc = cost ? "FNx10" : "none";

                                cls.buildClassifier(train);

                                Evaluation eval = new Evaluation(train);
                                eval.evaluateModel(cls, test);

                                int posIndex = positiveClassIndex(test);
                                int negIndex = 1 - posIndex;

                                double[][] cm = eval.confusionMatrix();
                                int tn = (int) Math.round(cm[negIndex][negIndex]);
                                int fp = (int) Math.round(cm[negIndex][posIndex]);
                                int fn = (int) Math.round(cm[posIndex][negIndex]);
                                int tp = (int) Math.round(cm[posIndex][posIndex]);

                                double precision = eval.precision(posIndex);
                                double recall = eval.recall(posIndex);
                                double f1 = eval.fMeasure(posIndex);
                                double auc = eval.areaUnderROC(posIndex);
                                double kappa = eval.kappa();
                                double mcc = eval.matthewsCorrelationCoefficient(posIndex);

                                double specificity = (tn + fp) > 0 ? (double) tn / (tn + fp) : 0.0;
                                double balancedAccuracy = 0.5 * (recall + specificity);
                                double gMean = Math.sqrt(Math.max(0.0, recall * specificity));

                                ClassifierEvaluation e = new ClassifierEvaluation(projectName, i);
                                e.setTrainingSize(trainRaw.numInstances());
                                e.setTestingSize(testRaw.numInstances());

                                e.setModel(model.name);
                                e.setFeatureSelection(fs ? "CFS_BestFirst" : "none");
                                e.setBalancing(balancingDesc);
                                e.setCostSensitive(costDesc);

                                e.setThresholdStrategy(THRESHOLD_STRATEGY);
                                e.setThreshold(THRESHOLD_VALUE);

                                e.setPrecision(precision);
                                e.setRecall(recall);
                                e.setF1(f1);
                                e.setSpecificity(specificity);
                                e.setBalancedAccuracy(balancedAccuracy);
                                e.setGMean(gMean);
                                e.setMcc(mcc);
                                e.setKappa(kappa);
                                e.setAuc(auc);

                                e.setTp(tp);
                                e.setFp(fp);
                                e.setTn(tn);
                                e.setFn(fn);

                                results.add(e);

                            } catch (Exception ignored) {
                                // una config può fallire, non bloccare tutto
                            }
                        }
                    }
                }
            }
        }

        // Scrivi SEMPRE CSV in output/csv/PROJECTNAME/
        new EvaluationFile(projectName, results, "details").createANewFile(outCsv);
        return results;
    }

    private static int findMaxVersionIndex(Instances data) {
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < data.numInstances(); i++) {
            int v = (int) Math.round(data.instance(i).value(0));
            if (v > max) max = v;
        }
        return (max == Integer.MIN_VALUE) ? -1 : max;
    }

    private static Instances subsetByVersionRange(Instances data, int fromInclusive, int toInclusive) {
        Instances out = new Instances(data, 0);
        out.setClassIndex(data.classIndex());
        for (int i = 0; i < data.numInstances(); i++) {
            int v = (int) Math.round(data.instance(i).value(0)); // VersionIndex è attr 0
            if (v >= fromInclusive && v <= toInclusive) {
                out.add(data.instance(i));
            }
        }
        return out;
    }

    private static Instances[] preprocessTrainTest(Instances trainRaw, Instances testRaw, boolean useFeatureSelection)
            throws Exception {

        Instances train = new Instances(trainRaw);
        Instances test = new Instances(testRaw);
        train.setClassIndex(train.numAttributes() - 1);
        test.setClassIndex(test.numAttributes() - 1);

        // Remove VersionIndex (attr 1 in WEKA 1-based)
        Remove rm = new Remove();
        rm.setAttributeIndices("1");
        rm.setInputFormat(train);
        train = Filter.useFilter(train, rm);
        test = Filter.useFilter(test, rm);
        train.setClassIndex(train.numAttributes() - 1);
        test.setClassIndex(test.numAttributes() - 1);

        if (APPLY_STANDARDIZE) {
            Standardize st = new Standardize();
            st.setInputFormat(train);
            train = Filter.useFilter(train, st);
            test = Filter.useFilter(test, st);
            train.setClassIndex(train.numAttributes() - 1);
            test.setClassIndex(test.numAttributes() - 1);
        }

        if (useFeatureSelection) {
            AttributeSelection as = new AttributeSelection();
            as.setEvaluator(new CfsSubsetEval());
            as.setSearch(new BestFirst());
            as.setInputFormat(train);
            train = Filter.useFilter(train, as);
            test = Filter.useFilter(test, as);
            train.setClassIndex(train.numAttributes() - 1);
            test.setClassIndex(test.numAttributes() - 1);
        }

        return new Instances[]{train, test};
    }

    private static Instances applySmote(Instances train) throws Exception {
        SMOTE smote = buildSmoteFilter(train);
        if (smote == null) return train;

        smote.setInputFormat(train);
        Instances out = Filter.useFilter(train, smote);
        out.setClassIndex(out.numAttributes() - 1);
        return out;
    }

    private static SMOTE buildSmoteFilter(Instances train) {
        int[] counts = classCounts(train);
        if (counts.length != 2) return null;

        int posIndex = positiveClassIndex(train);
        int negIndex = 1 - posIndex;
        int pos = counts[posIndex];
        int neg = counts[negIndex];

        if (pos == 0 || neg == 0) return null;

        double ratio = (double) Math.max(pos, neg) / Math.max(1, Math.min(pos, neg));
        double perc = Math.max(0.0, (ratio - 1.0) * 100.0);
        perc = Math.min(perc, 500.0);

        SMOTE smote = new SMOTE();
        smote.setPercentage(perc);
        smote.setNearestNeighbors(5);
        smote.setClassValue("2"); // assume {false,true}
        return smote;
    }

    private static Classifier buildClassifier(ModelSpec spec, boolean costSensitive) {
        Classifier base = spec.supplier.get();
        if (!costSensitive) return base;

        CostSensitiveClassifier cs = new CostSensitiveClassifier();
        cs.setClassifier(base);
        cs.setCostMatrix(defaultBugCostMatrix());
        cs.setMinimizeExpectedCost(true);
        return cs;
    }

    private static CostMatrix defaultBugCostMatrix() {
        CostMatrix cm = new CostMatrix(2);
        cm.setElement(0, 0, 0.0);
        cm.setElement(0, 1, 1.0);   // FP
        cm.setElement(1, 0, 10.0);  // FN
        cm.setElement(1, 1, 0.0);
        return cm;
    }

    private static int positiveClassIndex(Instances data) {
        int idx = data.classAttribute().indexOfValue("true");
        return (idx >= 0) ? idx : 1;
    }

    private static int[] classCounts(Instances data) {
        int[] c = new int[data.numClasses()];
        for (int i = 0; i < data.numInstances(); i++) {
            int y = (int) data.instance(i).classValue();
            if (y >= 0 && y < c.length) c[y]++;
        }
        return c;
    }
}
