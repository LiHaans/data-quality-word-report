package com.example.dqreport.model;

import java.time.LocalDate;

public class IssueRecord {
    private final String id;
    private final String issueName;
    private final String issueType;
    private final String systemName;
    private final String severity;
    private final String status;
    private final LocalDate foundDate;
    private final String owner;

    public IssueRecord(String id,
                       String issueName,
                       String issueType,
                       String systemName,
                       String severity,
                       String status,
                       LocalDate foundDate,
                       String owner) {
        this.id = id;
        this.issueName = issueName;
        this.issueType = issueType;
        this.systemName = systemName;
        this.severity = severity;
        this.status = status;
        this.foundDate = foundDate;
        this.owner = owner;
    }

    public String getId() {
        return id;
    }

    public String getIssueName() {
        return issueName;
    }

    public String getIssueType() {
        return issueType;
    }

    public String getSystemName() {
        return systemName;
    }

    public String getSeverity() {
        return severity;
    }

    public String getStatus() {
        return status;
    }

    public LocalDate getFoundDate() {
        return foundDate;
    }

    public String getOwner() {
        return owner;
    }
}
