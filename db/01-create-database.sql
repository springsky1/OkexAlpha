-- 幂等创建数据库 okex_alpha（需以超级用户/有 CREATEDB 权限的角色执行）
SELECT 'CREATE DATABASE okex_alpha'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'okex_alpha')
\gexec

SELECT 'database okex_alpha ready' AS status;
