package com.ubs.pesubapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits for profiling an unrecognised Agent-BB workbook into a candidate template definition.
 *
 * <p>Externalised so a bank format with an unusually deep preamble or an unusually wide group
 * block can be accommodated by configuration rather than a rebuild — defaults live in
 * {@code application.yml}.
 */
@ConfigurationProperties(prefix = "app.template-profiler")
public class TemplateProfilerProperties {

    /** Header cells a row must match before it is accepted as the LP-grid header row. */
    private int minHeaderMatches;

    /** How many rows from the top are scanned while looking for that header row. */
    private int headerScanRows;

    /** Upper bound on classification groups derived from one workbook. */
    private int maxGroups;

    public int getMinHeaderMatches() { return minHeaderMatches; }
    public void setMinHeaderMatches(int v) { this.minHeaderMatches = v; }

    public int getHeaderScanRows() { return headerScanRows; }
    public void setHeaderScanRows(int v) { this.headerScanRows = v; }

    public int getMaxGroups() { return maxGroups; }
    public void setMaxGroups(int v) { this.maxGroups = v; }
}
