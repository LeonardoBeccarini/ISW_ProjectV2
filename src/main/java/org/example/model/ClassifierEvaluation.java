package org.example.model;

/**
 * Plain container for evaluation results, made robust against
 * older/newer processor code by exposing both legacy and new
 * getters/setters (aliases included).
 */
public class ClassifierEvaluation {

    // Identification
    private final String projName;
    private final int walkForwardIterationIndex;

    // Dataset sizes (optional, may be 0 if not set)
    private int trainingSize;
    private int testingSize;

    // Model & pipeline descriptors
    private String classifier;          // aka "model"
    private String featureSelection;
    private String sampling;            // aka "balancing"
    private String costSensitive;       // "None" or description

    // Thresholding
    private String thresholdStrategy;
    private double threshold;

    // Metrics
    private double precision;
    private double recall;
    private double f1;
    private double specificity;
    private double balancedAccuracy;
    private double gMean;
    private double mcc;
    private double kappa;
    private double auc;

    // Confusion matrix
    private int tp;
    private int fp;
    private int tn;
    private int fn;

    /** Convenience constructor with project and WF index. */
    public ClassifierEvaluation(String projName, int walkForwardIterationIndex) {
        this.projName = projName;
        this.walkForwardIterationIndex = walkForwardIterationIndex;
    }

    /* ----------------------- Setters (with aliases) ----------------------- */

    public void setTrainingSize(int trainingSize) { this.trainingSize = trainingSize; }
    public void setTestingSize(int testingSize) { this.testingSize = testingSize; }

    // model/classifier aliases
    public void setModel(String model) { this.classifier = model; }
    public void setClassifier(String classifier) { this.classifier = classifier; }

    public void setFeatureSelection(String featureSelection) { this.featureSelection = featureSelection; }

    // sampling/balancing aliases
    public void setSampling(String sampling) { this.sampling = sampling; }
    public void setBalancing(String balancing) { this.sampling = balancing; }

    public void setCostSensitive(String costSensitive) { this.costSensitive = costSensitive; }

    public void setThresholdStrategy(String thresholdStrategy) { this.thresholdStrategy = thresholdStrategy; }
    public void setThreshold(double threshold) { this.threshold = threshold; }

    public void setPrecision(double precision) { this.precision = precision; }
    public void setRecall(double recall) { this.recall = recall; }
    public void setF1(double f1) { this.f1 = f1; }
    public void setSpecificity(double specificity) { this.specificity = specificity; }
    public void setBalancedAccuracy(double balancedAccuracy) { this.balancedAccuracy = balancedAccuracy; }
    public void setGMean(double gMean) { this.gMean = gMean; }
    public void setMcc(double mcc) { this.mcc = mcc; }
    public void setKappa(double kappa) { this.kappa = kappa; }
    public void setAuc(double auc) { this.auc = auc; }

    public void setTp(int tp) { this.tp = tp; }
    public void setFp(int fp) { this.fp = fp; }
    public void setTn(int tn) { this.tn = tn; }
    public void setFn(int fn) { this.fn = fn; }

    /* ----------------------- Getters ----------------------- */

    public String getProjName() { return projName; }
    public int getWalkForwardIterationIndex() { return walkForwardIterationIndex; }

    public int getTrainingSize() { return trainingSize; }
    public int getTestingSize() { return testingSize; }
    public String getClassifier() { return classifier; }

    public String getFeatureSelection() { return featureSelection; }

    public String getSampling() { return sampling; }

    public String getCostSensitive() { return costSensitive; }

    public String getThresholdStrategy() { return thresholdStrategy; }
    public String getThreshold() { return Double.toString(threshold); }

    public String getPrecision() { return Double.toString(precision); }
    public String getRecall() { return Double.toString(recall); }
    public String getF1() { return Double.toString(f1); }
    public String getSpecificity() { return Double.toString(specificity); }
    public String getBalancedAccuracy() { return Double.toString(balancedAccuracy); }
    public String getGMean() { return Double.toString(gMean); }
    public String getMcc() { return Double.toString(mcc); }
    public String getKappa() { return Double.toString(kappa); }
    public String getAuc() { return Double.toString(auc); }

    public String getTp() { return Integer.toString(tp); }
    public String getFp() { return Integer.toString(fp); }
    public String getTn() { return Integer.toString(tn); }
    public String getFn() { return Integer.toString(fn); }

    // Also expose numeric getters if needed elsewhere
    public double precision() { return precision; }
    public double f1() { return f1; }

    public int tp() { return tp; }
    public int fp() { return fp; }
    public int tn() { return tn; }
    public int fn() { return fn; }
}
