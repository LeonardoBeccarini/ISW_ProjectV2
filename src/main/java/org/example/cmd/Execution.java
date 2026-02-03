package org.example.cmd;

import org.example.controller.*;
import org.example.model.ClassifierEvaluation;
import org.example.model.Method;
import org.example.model.Ticket;
import org.example.model.Version;
import org.example.utilities.CsvExporter;
import org.example.utilities.SpearmanDatasetAnalyzer;
import org.example.utilities.TargetSelector;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Execution {
    private Execution(){
        // to hide the public one
    }
    private static final Logger LOGGER = Logger.getLogger(Execution.class.getName());

    public static void analyzeProject(String projectName) {
        try {
            //------------------ CREAZIONE DATASET ------------------
            JiraRetriever jiraRetriever = new JiraRetriever(projectName);
            Proportion proportion = new Proportion();

            List<Version> versionList = jiraRetriever.retrieveVersions();
            List<Ticket> tempTicketList = jiraRetriever.retrieveTickets(versionList);
            List<Ticket> finalTicketList = proportion.processProportion(tempTicketList, versionList);

            String repoUrl = "https://github.com/apache/" + projectName.toLowerCase() + ".git";
            GitRetriever gitRetriever = new GitRetriever(projectName, repoUrl, versionList, finalTicketList);

            List<Method> methods = gitRetriever.extractMethodsAndMetrics();
            LOGGER.log(Level.INFO, "Metodi estratti: {0}", methods.size());

            CsvExporter.exportAll(projectName, versionList, finalTicketList, methods);

             //------------------ PREDIZIONE ------------------
            WekaProcessor wekaProcessor = new WekaProcessor(projectName, methods);
            List<ClassifierEvaluation> evaluations = wekaProcessor.runPredictionPipeline();
            LOGGER.log(Level.INFO, "Pipeline WEKA completata. Numero valutazioni generate: {0}" ,  evaluations.size());
            LOGGER.log(Level.INFO, "Risultati salvati in output/csv/{0} e output/arff/{0}" ,  projectName.toUpperCase());

            //------------------ REFACTORING + WHATIF ANALYSIS ------------------
            SpearmanDatasetAnalyzer.computeCorrelation(projectName.toUpperCase());
            String aFeatureName;
            if(projectName.equals("BOOKKEEPER")){
                aFeatureName = "NumBranches";
            }else{
                aFeatureName = "NestingDepth";
            }
            String mehodName = TargetSelector.selectAFMethod(projectName.toUpperCase(), aFeatureName);
            RefactorAnalyzer refactorAnalyzer = new RefactorAnalyzer(projectName.toUpperCase(), mehodName);
            WhatIfAnalysis whatIfAnalysis = new WhatIfAnalysis(projectName.toUpperCase(), aFeatureName, mehodName);
            refactorAnalyzer.execute();
            whatIfAnalysis.execute();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Sollevata eccezione durante Execution: {0}" ,  e.getMessage());
        }
    }
}
