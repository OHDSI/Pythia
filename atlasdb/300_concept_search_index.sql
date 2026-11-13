-- WebAPI concept search filters with lower(concept_name) LIKE '%term%', which
-- no btree index serves: on a full vocabulary every search scans millions of
-- rows, and a classroom of simultaneous searches times out. Trigram indexes
-- make those filters index scans.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

DO $$
DECLARE
  vocab_schema text;
BEGIN
  FOR vocab_schema IN
    SELECT DISTINCT d.table_qualifier
    FROM webapi.source_daimon d
    WHERE d.daimon_type = 1
      AND EXISTS (
        SELECT 1 FROM information_schema.tables t
        WHERE t.table_schema = d.table_qualifier AND t.table_name = 'concept'
      )
  LOOP
    EXECUTE format(
      'CREATE INDEX IF NOT EXISTS idx_concept_name_trgm ON %I.concept USING gin (lower(concept_name) gin_trgm_ops)',
      vocab_schema);
    EXECUTE format(
      'CREATE INDEX IF NOT EXISTS idx_concept_code_trgm ON %I.concept USING gin (lower(concept_code) gin_trgm_ops)',
      vocab_schema);
  END LOOP;
END $$;
