package org.example.model;

/**
 * Contiene tutte le metriche statiche e di processo per una istanza di Method.
 */
public class Metrics {

    // ========= Metriche statiche =========
    private int loc;
    private int cyclomaticComplexity;
    private int nestingDepth;
    private int numCodeSmells;
    private int parameterCount;

    // ========= Metriche di processo =========
    private int numAuthors;
    private int maxChurn;
    private double avgChurn;
    private int totalStmtAdded;
    private int totalStmtDeleted;
    private int numRevisions;
    /** 0 / 1 a seconda che il metodo abbia una storia di fix. */
    private int hasFixHistory;

    // --- getter / setter ---

    public int getLoc() {
        return loc;
    }

    public void setLoc(int loc) {
        this.loc = loc;
    }

    public int getCyclomaticComplexity() {
        return cyclomaticComplexity;
    }

    public void setCyclomaticComplexity(int cyclomaticComplexity) {
        this.cyclomaticComplexity = cyclomaticComplexity;
    }

    public int getNestingDepth() {
        return nestingDepth;
    }

    public void setNestingDepth(int nestingDepth) {
        this.nestingDepth = nestingDepth;
    }

    public int getNumCodeSmells() {
        return numCodeSmells;
    }

    public void setNumCodeSmells(int numCodeSmells) {
        this.numCodeSmells = numCodeSmells;
    }

    public int getParameterCount() {
        return parameterCount;
    }

    public void setParameterCount(int parameterCount) {
        this.parameterCount = parameterCount;
    }

    public int getNumAuthors() {
        return numAuthors;
    }

    public void setNumAuthors(int numAuthors) {
        this.numAuthors = numAuthors;
    }

    public int getMaxChurn() {
        return maxChurn;
    }

    public void setMaxChurn(int maxChurn) {
        this.maxChurn = maxChurn;
    }

    public double getAvgChurn() {
        return avgChurn;
    }

    public void setAvgChurn(double avgChurn) {
        this.avgChurn = avgChurn;
    }

    public int getTotalStmtAdded() {
        return totalStmtAdded;
    }

    public void addTotalStmtAdded(int added) {
        this.totalStmtAdded += added;
    }

    public int getTotalStmtDeleted() {
        return totalStmtDeleted;
    }

    public void addTotalStmtDeleted(int deleted) {
        this.totalStmtDeleted += deleted;
    }

    public int getNumRevisions() {
        return numRevisions;
    }

    public void incrementNumRevisions() {
        this.numRevisions++;
    }

    public int getHasFixHistory() {
        return hasFixHistory;
    }

    public void setHasFixHistory(int hasFixHistory) {
        this.hasFixHistory = hasFixHistory;
    }
}
