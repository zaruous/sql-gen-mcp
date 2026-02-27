SELECT relname as TABLE_NAME, obj_description(oid) as REMARK 
FROM pg_class 
WHERE relkind = 'r' 
AND relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')
