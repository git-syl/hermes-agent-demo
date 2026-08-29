CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Spring AI PgVectorStore 默认表 vector_store。
-- embedding 维度需与 application.yaml 的 embedding 模型实际输出一致：
-- 当前 dashscope text-embedding-ada-002 实际输出 1024 维。换模型时同步改这里并重建库。
CREATE TABLE IF NOT EXISTS vector_store (
    id uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
    content text,
    metadata json,
    embedding vector(1024)
);

CREATE INDEX IF NOT EXISTS vector_store_embedding_idx
    ON vector_store USING HNSW (embedding vector_cosine_ops);

-- 混合检索（chat.rag.hybrid）的关键词腿：对 content 建全文检索 GIN 索引。
-- 索引里的配置（这里是 'simple'）必须与 chat.rag.hybrid.ts-config 一致，否则查询用不上索引。
-- 中文若改用 zhparser/pg_jieba，请把下面与配置项里的 'simple' 同步替换为对应配置名。
CREATE INDEX IF NOT EXISTS vector_store_content_fts_idx
    ON vector_store USING GIN (to_tsvector('simple', content));
