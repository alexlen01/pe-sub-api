-- Real Agent BB workbooks carry values that exceed the original column widths, failing
-- Match Queue submission / Shadow BB commit with "value too long for character varying(N)".
-- Extracted values must be stored verbatim — never truncated or rounded — so widen every
-- column that receives workbook-derived data, sized by content type:
--   * free-text labels (types, classifications, ranges, regions)       -> VARCHAR(255)
--   * formatted dollar display strings ("$12,102,000,000")             -> VARCHAR(64)
--     (calculations read the exact *_num NUMERIC(20,2) columns first; the string is display-only)
--   * full-precision percent/rate strings ("0.000448047260586146%")    -> VARCHAR(50)
--   * agency ratings incl. outlook qualifiers                          -> VARCHAR(50)
-- Enum-like app-controlled columns (status, decision, tab_role, agent_cls_source,
-- template_class) keep their widths.

ALTER TABLE lp_records
    ALTER COLUMN investor_type      TYPE VARCHAR(255),
    ALTER COLUMN inst_vs_hnw        TYPE VARCHAR(255),
    ALTER COLUMN region_location    TYPE VARCHAR(255),
    ALTER COLUMN classification     TYPE VARCHAR(255),
    ALTER COLUMN classification_tag TYPE VARCHAR(255),
    ALTER COLUMN agent_cls          TYPE VARCHAR(255),
    ALTER COLUMN aum                TYPE VARCHAR(255),
    ALTER COLUMN nav                TYPE VARCHAR(255),
    ALTER COLUMN pension            TYPE VARCHAR(255),
    ALTER COLUMN pension_funded     TYPE VARCHAR(255),
    ALTER COLUMN cap_commit         TYPE VARCHAR(64),
    ALTER COLUMN called_cap         TYPE VARCHAR(64),
    ALTER COLUMN uncalled_capital   TYPE VARCHAR(64),
    ALTER COLUMN agent_excess_conc  TYPE VARCHAR(64),
    ALTER COLUMN ubs_excess_conc    TYPE VARCHAR(64),
    ALTER COLUMN agent_bb           TYPE VARCHAR(64),
    ALTER COLUMN ubs_bb             TYPE VARCHAR(64),
    ALTER COLUMN recallable_dist    TYPE VARCHAR(64),
    ALTER COLUMN pct_cap_commit     TYPE VARCHAR(50),
    ALTER COLUMN pct_uncalled      TYPE VARCHAR(50),
    ALTER COLUMN pct_called         TYPE VARCHAR(50),
    ALTER COLUMN agent_conc         TYPE VARCHAR(50),
    ALTER COLUMN ubs_conc           TYPE VARCHAR(50),
    ALTER COLUMN agent_rate         TYPE VARCHAR(50),
    ALTER COLUMN ubs_rate           TYPE VARCHAR(50),
    ALTER COLUMN sp                 TYPE VARCHAR(50),
    ALTER COLUMN mdy                TYPE VARCHAR(50),
    ALTER COLUMN fitch              TYPE VARCHAR(50);

ALTER TABLE lp_master
    ALTER COLUMN investor_type          TYPE VARCHAR(255),
    ALTER COLUMN inst_vs_hnw            TYPE VARCHAR(255),
    ALTER COLUMN region_location        TYPE VARCHAR(255),
    ALTER COLUMN ubs_classification     TYPE VARCHAR(255),
    ALTER COLUMN aum                    TYPE VARCHAR(255),
    ALTER COLUMN nav                    TYPE VARCHAR(255),
    ALTER COLUMN pension                TYPE VARCHAR(255),
    ALTER COLUMN pension_funded         TYPE VARCHAR(255),
    ALTER COLUMN ubs_default_adv_rate   TYPE VARCHAR(50),
    ALTER COLUMN ubs_default_conc_limit TYPE VARCHAR(50),
    ALTER COLUMN sp                     TYPE VARCHAR(50),
    ALTER COLUMN mdy                    TYPE VARCHAR(50),
    ALTER COLUMN fitch                  TYPE VARCHAR(50);

-- template_version stores the recognised fund template name (bb_templates.template_name
-- is VARCHAR(255)); template_format mirrors it for structural fallbacks.
ALTER TABLE submission_extractions
    ALTER COLUMN template_format  TYPE VARCHAR(255),
    ALTER COLUMN template_version TYPE VARCHAR(255);
