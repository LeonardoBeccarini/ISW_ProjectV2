package org.example.cmd;

import org.example.controller.RefactorAnalyzer;
import org.example.controller.WhatIfAnalysis;
import org.example.utilities.SpearmanDatasetAnalyzer;
import org.example.utilities.TargetSelector;

import java.io.IOException;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) {
        try {
           // SpearmanDatasetAnalyzer.computeCorrelation("BOOKKEEPER");
            Execution.analyzeProject("STORM");
            SpearmanDatasetAnalyzer.computeCorrelation("STORM");
             String mehodName = TargetSelector.selectAFMethod("STORM", "NestingDepth");
             System.out.println(mehodName);
            //  RefactorAnalyzer refactorAnalyzer = new RefactorAnalyzer("BOOKKEEPER", mehodName);
            // WhatIfAnalysis whatIfAnalysis = new WhatIfAnalysis("BOOKKEEPER", "NumBranches", mehodName);
            // refactorAnalyzer.execute();
            // whatIfAnalysis.execute();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        //Execution.analyzeProject("BOOKKEEPER");
        //Execution.analyzeProject("STORM");
    }
}