package org.example.cmd;

import org.example.controller.*;
import org.example.model.ClassifierEvaluation;
import org.example.model.Method;
import org.example.model.Ticket;
import org.example.model.Version;
import org.example.utilities.CsvExporter;
import org.example.utilities.DatasetDiagnostics;

import java.util.List;

public class Execution {
    public static void analyzeProject(String projectName) {
        try {
            JiraRetriever jiraRetriever = new JiraRetriever(projectName);
            Proportion proportion = new Proportion();

            List<Version> versionList = jiraRetriever.retrieveVersions();
            List<Ticket> tempTicketList = jiraRetriever.retrieveTickets(versionList);
            List<Ticket> finalTicketList = proportion.processProportion(tempTicketList, versionList);

            String repoUrl = "https://github.com/apache/" + projectName.toLowerCase() + ".git";
            GitRetriever gitRetriever = new GitRetriever(projectName, repoUrl, versionList, finalTicketList);

            List<Method> methods = gitRetriever.extractMethodsAndMetrics();
            System.out.println("Metodi estratti: " + methods.size());

            DatasetDiagnostics.analyzeLabelingQuality(projectName, methods, finalTicketList, versionList);
            CsvExporter.exportAll(projectName, versionList, finalTicketList, methods);

            WekaProcessor wekaProcessor = new WekaProcessor(projectName, methods);
            List<ClassifierEvaluation> evaluations = wekaProcessor.runPredictionPipeline();
            System.out.println("Pipeline WEKA completata. Numero valutazioni generate: " + evaluations.size());
            System.out.println("Risultati salvati in output/csv/" + projectName.toUpperCase()
                    + " e output/arff/" + projectName.toUpperCase());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
