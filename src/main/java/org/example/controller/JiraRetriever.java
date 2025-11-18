package org.example.controller;

import org.example.model.Ticket;
import org.example.model.Version;
import org.example.utilities.JiraUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.example.utilities.JsonUtilities.readJsonFromUrl;

public class JiraRetriever {
    private String projectName;

    public JiraRetriever(String projectName) {
        this.projectName = projectName;
    }

    public List<Version> retrieveVersions() throws JSONException, IOException {
        List<Version> out = new ArrayList<>();
        String url = "https://issues.apache.org/jira/rest/api/2/project/" + projectName;
        JSONObject json = readJsonFromUrl(url);
        JSONArray versions = json.getJSONArray("versions");

        for (int i = 0; i < versions.length(); i++) {
            JSONObject vj = versions.getJSONObject(i);
            if (!vj.optBoolean("released", false)) continue;
            if (!vj.has("releaseDate")) continue;

            String name = vj.optString("name", "");
            String id   = vj.optString("id", name);
            LocalDate date = LocalDate.parse(vj.getString("releaseDate"));
            out.add(new Version(id, name, date));
        }

        // Ordina dalla più vecchia alla più recente
        out.sort(Comparator.comparing(Version::getDate));
        int j=0;
        for(Version v:out){
            v.setIndex(++j);
        }
        return out;
    }


    public List<Ticket> retrieveTickets(List<Version> versionList){
        List<Ticket> retrievedTickets = new ArrayList<>();
        int i = 0, total;
        do {
            int j = i + 1000;
            String url = "https://issues.apache.org/jira/rest/api/2/search?jql=" +
                    "project%3D%22" + projectName + "%22%20AND%20" +
                    "issuetype%3DBug%20AND%20" +
                    "(status%3DClosed%20OR%20status%3DResolved)%20AND%20" +
                    "resolution%3DFixed&fields=key,resolutiondate,versions,created&startAt=" + i + "&maxResults=" + j;
            try {
                JSONObject json = readJsonFromUrl(url);
                JSONArray issues = json.getJSONArray("issues");
                total = json.getInt("total");

                for (; i < total && i < j; i++) {
                    JSONObject o = issues.getJSONObject(i % 1000);
                    String key = o.getString("key");
                    String created = o.getJSONObject("fields").optString("created", null);
                    String resolved = o.getJSONObject("fields").optString("resolutiondate", null);
                    if (created == null || resolved == null) continue;

                    LocalDate creation = LocalDate.parse(created.substring(0, 10));
                    LocalDate resolution = LocalDate.parse(resolved.substring(0, 10));

                    Ticket t = new Ticket();
                    t.setKey(key);
                    t.setCreationDate(creation);
                    t.setResolutionDate(resolution);

                    JSONArray affectedVersionList = o.getJSONObject("fields").getJSONArray("versions");
                    //per ottenere tutte le affected version
                    List<Version> av = JiraUtils.getAffectedVersions(affectedVersionList, versionList);

                    t.setAffectedVersions(av);
                    t.setInjectedVersionTemp();

                    //per ottenere opening version:creation date, per la fix version: resolution date
                    Version ov = JiraUtils.getReleaseAfterOrEqualDate(creation, versionList);
                    Version fv = JiraUtils.getReleaseAfterOrEqualDate(resolution, versionList);
                    t.setOpeningVersion(ov);
                    t.setFixedVersion(fv);
                    t.setAssociatedCommits(new ArrayList<>());

                    // check di validità sul ticket
                    if (!av.isEmpty() && ov != null && fv != null &&
                            (!av.get(0).getDate().isBefore(ov.getDate())
                                    || ov.getDate().isAfter(fv.getDate()))) {
                        continue;
                    }
                    if (ov != null
                            && fv != null
                            && ov.getId() != versionList.get(0).getId()) {
                        retrievedTickets.add(t);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                total = i; // esci in caso di errore pagina
            }
        } while (i < total);

        retrievedTickets.sort(Comparator.comparing(Ticket::getResolutionDate));
        return retrievedTickets;
    }
}
