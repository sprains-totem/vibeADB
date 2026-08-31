# Worker 部署（一次性 ~2 分钟）

```bash
cd worker
npm install

# 1. 创建 KV namespace，把返回的 id 填进 wrangler.toml
npx wrangler kv namespace create KV

# 2. 设置写令牌（自己生成一个随机串，例如 openssl rand -hex 24）
npx wrangler secret put WRITE_TOKEN

# 3. 部署
npx wrangler deploy
```

部署成功后记下输出的域名（如 `vibeadb-mailbox.<account>.workers.dev`），
填到手机 App 设置的「Worker 地址」和「写令牌」里。

CI 产出的 `vibeadb-worker.zip` 内含构建好的 `dist/index.js`、`wrangler.toml`，
也可用 `npx wrangler deploy --dist dist`（需本地安装 wrangler）部署。
