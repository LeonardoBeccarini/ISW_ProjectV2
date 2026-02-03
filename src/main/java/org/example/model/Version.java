package org.example.model;


import org.eclipse.jgit.revwalk.RevCommit;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class Version {
    private int index;
    private String id;
    private String name;
    private LocalDate date;
    private final List<RevCommit>  commitList;

    public Version( String id, String name, LocalDate date) {
        this.id = id;
        this.name = name;
        this.date = date;
        this.commitList = new ArrayList<>();
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public int getIndex() {
        return index;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public LocalDate getDate() {
        return date;
    }

    public List<RevCommit> getCommitList() {
        return commitList;
    }
}
