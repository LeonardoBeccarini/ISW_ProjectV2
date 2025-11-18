package org.example.cmd;

import org.example.controller.JiraRetriever;
import org.example.controller.Proportion;
import org.example.model.Ticket;
import org.example.model.Version;
import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Execution {
    public static void analyzeProject(String projectName){
        try{
            JiraRetriever jiraRetriever = new JiraRetriever(projectName);
            Proportion proportion = new Proportion();
            List<Version> versionList = jiraRetriever.retrieveVersions();
            List<Ticket> tempTicketList = jiraRetriever.retrieveTickets(versionList);
            List<Ticket> finalTicketList = proportion.processProportion(tempTicketList,versionList);



        }catch (JSONException | IOException e) {
            e.printStackTrace();
        }
    }
}
