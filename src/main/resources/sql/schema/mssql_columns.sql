SELECT c.column_id as POS, c.name as NAME, TYPE_NAME(c.user_type_id) as TYPE, c.max_length as LEN, 
ISNULL((SELECT 'Y' FROM sys.index_columns ic JOIN sys.indexes i ON ic.object_id = i.object_id AND ic.index_id = i.index_id WHERE i.is_primary_key = 1 AND ic.object_id = c.object_id AND ic.column_id = c.column_id), 'N') as PK, 
(CASE WHEN c.is_nullable = 1 THEN 'Y' ELSE 'N' END) as NULLABLE, CAST(p.value AS VARCHAR) as REMARK 
FROM sys.columns c 
LEFT JOIN sys.extended_properties p ON c.object_id = p.major_id AND c.column_id = p.minor_id AND p.name = 'MS_Description' 
WHERE c.object_id = OBJECT_ID(?) 
ORDER BY c.column_id
