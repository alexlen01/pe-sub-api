package com.ubs.pesubapi.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.ubs.pesubapi.dto.CollateralReportDto;
import com.ubs.pesubapi.dto.ComputedLpRecord;
import com.ubs.pesubapi.repository.BbSnapshotRepository;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
public class CollateralPdfService {
    private final TemplateEngine templates;
    private final BbSnapshotRepository snapshots;

    public record Options(String detail, String includeLps, boolean catSummary, boolean coverageTrend,
                          boolean concAnalysis, boolean reclass, boolean quality) {}

    public CollateralPdfService(TemplateEngine templates, BbSnapshotRepository snapshots) {
        this.templates = templates;
        this.snapshots = snapshots;
    }

    public byte[] create(CollateralReportDto report, String watermark, Options options) {
        var selected = snapshots.findById(report.snapshotId()).orElseThrow();
        List<ComputedLpRecord> allLps = selected.getResult().lps() == null ? List.of() : selected.getResult().lps();
        List<ComputedLpRecord> shownLps = "all".equals(options.includeLps())
            ? allLps : allLps.stream().filter(lp -> lp != null && lp.inc()).toList();
        List<ComputedLpRecord> reclassified = allLps.stream().filter(lp -> lp != null && lp.rcl()).toList();
        double highQualityM = allLps.stream().filter(lp -> lp != null && lp.highQuality()).mapToDouble(lp -> lp.uecM()).sum();
        double otherQualityM = allLps.stream().filter(lp -> lp != null && !lp.highQuality()).mapToDouble(lp -> lp.uecM()).sum();

        Context context = new Context(Locale.US);
        context.setVariable("report", report);
        context.setVariable("summary", report.summary());
        context.setVariable("options", options);
        context.setVariable("watermark", watermark);
        context.setVariable("lps", shownLps);
        context.setVariable("reclassified", reclassified);
        context.setVariable("breaches", selected.getResult().breaches() == null ? List.of() : selected.getResult().breaches());
        context.setVariable("trend", snapshots.findByFacilityIdOrderByCalculatedAtAsc(report.facilityId()));
        context.setVariable("highQualityCount", allLps.stream().filter(lp -> lp != null && lp.highQuality()).count());
        context.setVariable("otherQualityCount", allLps.stream().filter(lp -> lp != null && !lp.highQuality()).count());
        context.setVariable("highQualityM", highQualityM);
        context.setVariable("otherQualityM", otherQualityM);
        context.setVariable("dateFmt", DateTimeFormatter.ofPattern("MMM d, yyyy, hh:mm a"));
        context.setVariable("fmt", new CurrencyFormat());

        String html = templates.process("reports/collateral-certificate", context);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            new PdfRendererBuilder().useFastMode().withHtmlContent(html, null).toStream(out).run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to render collateral certificate PDF", e);
        }
    }

    public static final class CurrencyFormat {
        public String millions(double value) {
            double dollars = value * 1_000_000;
            return String.format(Locale.US, "$%,.0f", Math.round(dollars) == 0 ? 0d : dollars);
        }

        public String amount(String value) {
            double dollars = BbCalculationService.parseMoney(value) * 1_000_000;
            return String.format(Locale.US, "$%,.0f", Math.round(dollars) == 0 ? 0d : dollars);
        }

        public String signedMillions(double value) {
            double dollars = Math.abs(value) * 1_000_000;
            if (Math.round(dollars) == 0) return "$0";
            return String.format(Locale.US, "%s$%,.0f", value >= 0 ? "+" : "-", dollars);
        }

        public boolean isNegativeAmount(String value) {
            return BbCalculationService.parseMoney(value) < 0;
        }

        public boolean isPositiveAmount(String value) {
            return BbCalculationService.parseMoney(value) > 0;
        }
    }
}
