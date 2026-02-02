package org.example.controller;

import org.example.model.Ticket;
import org.example.model.Version;
import org.example.utilities.JiraUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.example.utilities.JsonUtilities.readJsonFromUrl;

public class JiraRetriever {
    private final String projectName;

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
            parseVersion(vj).ifPresent(out::add);
        }

        out.sort(Comparator.comparing(Version::getDate));
        int j = 0;
        for (Version v : out) {
            v.setIndex(++j);
        }
        return out;
    }

    private Optional<Version> parseVersion(JSONObject vj) throws JSONException {
        if (!vj.has("releaseDate") || !vj.has("name")) {
            return Optional.empty();
        }

        String name = vj.getString("name");
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        String id = vj.optString("id", name);
        String releaseDateStr = vj.optString("releaseDate", null);
        if (releaseDateStr == null || releaseDateStr.isBlank()) {
            return Optional.empty();
        }

        try {
            LocalDate date = LocalDate.parse(releaseDateStr);
            return Optional.of(new Version(id, name, date));
        } catch (DateTimeParseException _) {
            return Optional.empty();
        }
    }

    public List<Ticket> retrieveTickets(List<Version> versionList) {
        List<Ticket> retrievedTickets = new ArrayList<>();

        final int pageSize = 1000;
        int startAt = 0;
        int total = Integer.MAX_VALUE;

        while (startAt < total) {
            String url = buildTicketSearchUrl(startAt, pageSize);

            try {
                JSONObject json = readJsonFromUrl(url);
                JSONArray issues = json.getJSONArray("issues");
                total = json.getInt("total");

                for (int k = 0; k < issues.length(); k++) {
                    JSONObject o = issues.getJSONObject(k);
                    parseTicket(o, versionList).ifPresent(retrievedTickets::add);
                }

                startAt += issues.length();
            } catch (IOException | JSONException e) {
                throw new JiraRetrievalException("Failed to retrieve tickets from JIRA", e);
            }
        }

        retrievedTickets.sort(Comparator.comparing(Ticket::getResolutionDate));
        return retrievedTickets;
    }

    private String buildTicketSearchUrl(int startAt, int pageSize) {
        return "https://issues.apache.org/jira/rest/api/2/search?jql=" +
                "project%3D%22" + projectName + "%22%20AND%20" +
                "issuetype%3DBug%20AND%20" +
                "(status%3DClosed%20OR%20status%3DResolved)%20AND%20" +
                "resolution%3DFixed" +
                "&fields=key,resolutiondate,versions,created" +
                "&startAt=" + startAt +
                "&maxResults=" + pageSize;
    }

    private Optional<Ticket> parseTicket(JSONObject o, List<Version> versionList) throws JSONException {
        String key = o.getString("key");
        JSONObject fields = o.getJSONObject("fields");

        String created = fields.optString("created", null);
        String resolved = fields.optString("resolutiondate", null);
        if (created == null || resolved == null) {
            return Optional.empty();
        }

        LocalDate creation = LocalDate.parse(created.substring(0, 10));
        LocalDate resolution = LocalDate.parse(resolved.substring(0, 10));

        Version ov = JiraUtils.getReleaseAfterOrEqualDate(creation, versionList);
        Version fv = JiraUtils.getReleaseAfterOrEqualDate(resolution, versionList);

        boolean isInvalidTicket = (ov == null) || (fv == null) || (fv.getIndex() < ov.getIndex());
        if (isInvalidTicket) {
            return Optional.empty();
        }

        JSONArray avArray = fields.optJSONArray("versions");
        List<Version> av = (avArray != null)
                ? JiraUtils.getAffectedVersions(avArray, versionList)
                : new ArrayList<>();

        Ticket t = new Ticket(key, creation, resolution, av);
        t.setOpeningVersion(ov);
        t.setFixedVersion(fv);
        t.setInjectedVersionTemp();

        return Optional.of(t);
    }

    public static class JiraRetrievalException extends RuntimeException {
        public JiraRetrievalException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}