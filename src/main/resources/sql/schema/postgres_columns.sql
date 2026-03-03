SELECT 
    c.ordinal_position as POS, 
    c.column_name as NAME, 
    c.data_type as TYPE, 
    COALESCE(c.character_maximum_length, c.numeric_precision) as LEN, 
    CASE WHEN pk.column_name IS NOT NULL THEN 'Y' ELSE 'N' END as PK, 
    c.is_nullable as NULLABLE, 
    '' as REMARK 
FROM information_schema.columns c
LEFT JOIN (
    SELECT ku.column_name, ku.table_name
    FROM information_schema.table_constraints tc
    JOIN information_schema.key_column_usage ku 
      ON tc.constraint_name = ku.constraint_name 
     AND tc.table_schema = ku.table_schema
    WHERE tc.constraint_type = 'PRIMARY KEY' 
      AND tc.table_schema = 'public'
) pk ON c.table_name = pk.table_name AND c.column_name = pk.column_name
WHERE c.table_name = ? 
AND c.table_schema = 'public' 
ORDER BY c.ordinal_position
