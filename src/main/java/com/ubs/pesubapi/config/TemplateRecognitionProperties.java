package com.ubs.pesubapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Scoring weights for Agent-BB template recognition. A candidate template accumulates points per
 * matched signal; a total below {@link #minScore} means "no confident match", and the engine falls
 * back to auto-detecting the sheet and header row.
 *
 * <p>Externalised so the recognition engine can be retuned per environment without a rebuild —
 * defaults live in {@code application.yml}, never in the annotations or the service.
 */
@ConfigurationProperties(prefix = "app.template-recognition")
public class TemplateRecognitionProperties {

    private int minScore;
    private int scoreFilename;
    private int scoreTitle;
    private int scoreDetectKey;
    private int scoreNamedTab;
    private int scoreAgentBank;

    public int getMinScore() { return minScore; }
    public void setMinScore(int v) { this.minScore = v; }

    public int getScoreFilename() { return scoreFilename; }
    public void setScoreFilename(int v) { this.scoreFilename = v; }

    public int getScoreTitle() { return scoreTitle; }
    public void setScoreTitle(int v) { this.scoreTitle = v; }

    public int getScoreDetectKey() { return scoreDetectKey; }
    public void setScoreDetectKey(int v) { this.scoreDetectKey = v; }

    public int getScoreNamedTab() { return scoreNamedTab; }
    public void setScoreNamedTab(int v) { this.scoreNamedTab = v; }

    public int getScoreAgentBank() { return scoreAgentBank; }
    public void setScoreAgentBank(int v) { this.scoreAgentBank = v; }
}
