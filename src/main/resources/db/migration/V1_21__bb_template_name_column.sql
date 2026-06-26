-- Rename BB template registry identity from agent_bank to template_name.
-- Facility/submission agent_bank columns are separate business fields and remain unchanged.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'bb_templates'
          AND column_name = 'agent_bank'
    )
    AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'bb_templates'
          AND column_name = 'template_name'
    ) THEN
        ALTER TABLE bb_templates RENAME COLUMN agent_bank TO template_name;
    END IF;
END $$;

DROP INDEX IF EXISTS idx_bb_templates_bank_class;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = current_schema()
          AND indexname = 'idx_bb_templates_name_class'
    ) THEN
        CREATE UNIQUE INDEX idx_bb_templates_name_class
            ON bb_templates (LOWER(template_name), template_class);
    END IF;
END $$;
