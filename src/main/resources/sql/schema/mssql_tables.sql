SELECT t.name AS TABLE_NAME, CAST(p.value AS VARCHAR) AS REMARK 
FROM sys.tables t 
LEFT JOIN sys.extended_properties p ON t.object_id = p.major_id AND p.minor_id = 0 AND p.name = 'MS_Description'
