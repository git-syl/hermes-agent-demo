# PostgreSQL 初始化脚本说明

## 脚本执行顺序

容器首次启动时(```docker compose up -d 或者docker-compose up -d```)，会按照文件名的字母顺序自动执行 `pgsql_init/` 目录中的 SQL 脚本：  



## 自定义初始化脚本

### 添加新脚本

在 `pgsql_init/` 目录中创建新的 `.sql` 文件即可，建议使用数字前缀控制执行顺序：

```bash
pgsql_init/
├── 01-create-tables.sql
├── 02-insert-sample-data.sql
├── 03-enable-extensions.sql
├── 04-your-custom-script.sql  # 您的自定义脚本
└── 99-final-setup.sql         # 最后执行的脚本
```




## 重新初始化数据库

⚠️ **注意**：初始化脚本仅在容器首次创建数据库时执行。

如果需要重新执行初始化脚本：

```bash
# 1. 停止并删除容器  (新版本 docker compose 不加-)
docker-compose -f docker-compose.yml down

# 2. 删除数据目录
rm -rf volumes/../

# 3. 重新启动（会自动执行初始化脚本）
docker-compose -f docker-compose.yml up -d
```

## 查看初始化结果

```bash
# 连接到数据库
docker exec -it pgvector-db psql -U postgres -d ai-sport

# 查看所有表
\dt

# 查看用户数据
SELECT * FROM users;

# 查看文章数据
SELECT * FROM articles;

# 查看启用的扩展
\dx

# 查看向量表
SELECT id, content, metadata FROM embeddings;

# 退出
\q
```

## 常用 PostgreSQL 命令

```sql
-- 查看所有表
\dt

-- 查看表结构
\d table_name

-- 查看所有数据库
\l

-- 查看所有扩展
\dx

-- 切换数据库
\c database_name

-- 查看当前用户
\du

-- 执行 SQL 文件
\i /path/to/file.sql

-- 导出数据
\copy (SELECT * FROM users) TO '/tmp/users.csv' CSV HEADER;
```

## 向量搜索示例

```sql
-- 查找相似向量（余弦相似度）
SELECT 
    id,
    content,
    1 - (embedding <=> '[0.1, 0.1, ...]'::vector) AS similarity
FROM embeddings
ORDER BY embedding <=> '[0.1, 0.1, ...]'::vector
LIMIT 10;

-- 查找相似向量（L2 距离）
SELECT 
    id,
    content,
    embedding <-> '[0.1, 0.1, ...]'::vector AS distance
FROM embeddings
ORDER BY embedding <-> '[0.1, 0.1, ...]'::vector
LIMIT 10;
```  


关于 Docker 镜像加速，有两种方式：

## 方式一：配置 registry-mirrors（推荐）

在 Docker daemon 配置文件中设置镜像加速器后，拉取镜像时会**自动使用加速器**，不需要修改镜像名称：

```json
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://mirror.ccs.tencentyun.com"
  ]
}
```

这样直接使用 `pgvector/pgvector:pg16` 就会自动走加速。

## 方式二：直接加代理前缀

**部分镜像加速器**支持直接在镜像名前加前缀，例如：

```yaml
pgvector:
  image: docker.m.daocloud.io/pgvector/pgvector:pg16
```

或者：

```yaml
pgvector:
  image: dockerpull.org/pgvector/pgvector:pg16
```

## 代理注意事项

1. **不是所有加速器都支持前缀方式**：


## 注意事项

1. **脚本执行顺序**：使用数字前缀（01-, 02-）控制执行顺序
2. **权限**：脚本使用 `POSTGRES_USER` 环境变量指定的用户执行
3. **错误处理**：脚本执行出错会中断初始化过程、此时建议删除数据目录重新启动容器 (```database-docker/pgsql/volumes```)
4. **只执行一次**：初始化脚本仅在数据库首次创建时执行

