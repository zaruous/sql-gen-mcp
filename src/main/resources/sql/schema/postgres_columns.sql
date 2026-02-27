SELECT ordinal_position as POS, column_name as NAME, data_type as TYPE, character_maximum_length as LEN, 'N' as PK, is_nullable as NULLABLE, '' as REMARK 
FROM information_schema.columns 
WHERE table_name = ? 
AND table_schema = 'public' 
ORDER BY ordinal_position
