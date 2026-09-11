# bunker

**`bunker` は「船舶燃料（バンカー）」であって、退避壕でも秘密保管庫でもない。**
海運の燃料調達 —— BDN（Bunker Delivery Note）の受け渡し、燃料サンプルの品質試験
（硫黄分 / バナジウム / キャットファイン）、MARPOL 附属書 VI の硫黄分上限
（一般海域 0.50%、ECA 0.10%）への適合、船舶ごとの燃費と CO₂ 排出の集計 —— を
担う actor repo である。

名前が機能を示さないので、ここで名乗る（superproject CLAUDE.md「名前が機能を
示さない repo は README 冒頭で名乗る」）。

- **actor DID（コード上）**: `did:web:bunker.etzhayyim.com`
- **actor DID（公開文書上）**: `did:web:etzhayyim.com:actor:bunker`
- **この 2 つは一致していない。**下記「実測した食い違い」を読むこと。

## この repo に何が在るか

| パス | 何か | 動くか |
|---|---|---|
| `src/bunker/murakumo.cljk` | 純 cljc の actor 境界。cell ごとの計画を立て、gate が揃うまで effect を出さない | **動く**（`kbb -M -e` で呼べる） |
| `test/bunker/murakumo_test.cljk` | 上の契約テスト。cell 名を hardcode せず `cell-specs` を走査する | **動く**（`kbb -M:test` で 9 tests / 252 assertions） |
| `actor-manifest.jsonld` | 旧 runtime（`k8s-langserver` + `sveltekit-proxy`）向けの宣言。pipeline 10 本、actor 5 本、capability 5 個 | この repo からは**実行されない**（runtime は別） |
| `actor-manifest.test.ts` | 上の manifest に対する vitest | **動かない**（後述） |
| `.well-known/did.json` | 公開 DID 文書の repo 側の写し | 静的ファイル |
| `NOTICE` | Apache-2.0 + etzhayyim Charter Compliance Rider v3.1 | — |
| `deps.edn` | `:test`（cognitect test-runner）と `:lint`（clj-kondo）の 2 alias | **動く** |

**`src/` は scaffold である。**`murakumo.cljc` は自分でそう名乗っている
（各 cell の `:ceiling` が *"Manifest-driven migration scaffold; explicit
execution stays in runtime methods"*、生成される record が `:scaffold true` /
`:constitutionalStatus "attested-plan"`）。つまりこれは **計画を返す純関数**で
あって、実際に MST へ書き込むものではない —— 書き込みは runtime 側が
`:mst/put-record` effect を受け取ってから行う。

## 核になっている不変条件 —— gate が揃うまで effect を出さない

`cell-plan` は 18 の cell それぞれについて、7 つの gate

```
:council-charter-attestation      :no-platform-held-key-baseline
:no-probing-baseline              :murakumo-only-inference-baseline
:did-primary-baseline             :append-only-gate-baseline
:kotoba-only-substrate-baseline
```

が **全部** attest されているかを見る。1 つでも欠けていれば
`{:status :blocked :effects []}` を返す —— 部分的に書き込むことはしない。
これを実際に両方向で見る手順が `docs/operator-quickstart.md`（3 と 4）に在る。

## 使いはじめる

`docs/operator-quickstart.md` を読む。5 つのコマンドと、それぞれの**実測済みの
出力**が書いてある。手元の出力と一致するかは検査器で機械的に確かめられる:

```
kbb --backend sci scripts/verify-quickstart.cljk
```

exit は 3 値 —— `0` 全ブロック一致 / `1` どれかが食い違った / `2` **REFUSED**
（doc が読めない・ブロックが 0 本・`clojure` が PATH に無い等、*測れなかった*）。
「測れなかった」を「問題なし」と同じ値で返さないための床である。

## 実測した食い違い（2026-09-02 測定、直していない）

**これらは記録であって修正ではない。**どれも identity か runtime 契約の所有者が
決めることで、docs の反復で黙って書き換えるものではない。

### 1. actor DID が 2 つ在り、コードが使っている方は解決しない

| 出所 | DID | 解決 |
|---|---|---|
| `.well-known/did.json` の `id` | `did:web:etzhayyim.com:actor:bunker` | `https://etzhayyim.com/actor/bunker/did.json` → **200** |
| `actor-manifest.jsonld` の `@id`、`murakumo.cljc` の `actor-did` | `did:web:bunker.etzhayyim.com` | `bunker.etzhayyim.com` が **DNS で引けない**（NXDOMAIN） |

commit `b0f7729`（"migrate did:web to etzhayyim.com scheme"）が触ったのは
`.well-known/did.json` **1 ファイルだけ**で、manifest と cljc は据え置かれた。
旧値 `did:web:etzhayyim.github.io:com-etzhayyim-bunker` は `alsoKnownAs` に
保存されているが、**`did:web:bunker.etzhayyim.com` はどこにも別名として
載っていない**（`at://bunker.etzhayyim.com` は AT-proto handle であって DID
ではない）。つまり `murakumo.cljc` が全 record に押している `:actorDid` は、
公開文書から辿れない識別子である。

### 2. 公開されている DID 文書は、repo の写しと違う

`https://etzhayyim.com/actor/bunker/did.json` が実際に返すものは
`.well-known/did.json` と一致しない（2026-09-02 実測）:

- `alsoKnownAs` — repo は 4 件、公開側は **空**
- PDS endpoint — repo は `https://pds.etzhayyim.com`、公開側は `https://pds.aozora.app`
- 公開側だけが `#xrpc-libp2p` service と `_meta` を持つ

repo の `.well-known/did.json` は **配信されていない写し**である
（`.nojekyll` が在るので GitHub Pages 配信を意図した形跡はあるが、いま
`did:web` を解決したときに読まれるのは etzhayyim.com 側）。

### 3. collection の名前空間が 2 系統に割れている

- `murakumo.cljc` の `collection` → `com.etzhayyim.bunker.<cell>`
- `actor-manifest.jsonld` が購読・宣言するもの → `com.etzhayyim.apps.bunker.*`、
  `com.etzhayyim.apps.vessel.*`、`com.etzhayyim.apps.standard.*`

`apps.` の 1 セグメント分ずれているので、**scaffold が計画する書き込み先と、
manifest が購読する場所は交わらない**。なお公開 DID 文書の
`_meta.primaryLexicon` は **`com.etzhayyim.bunker`** で、こちらは cljc 側と
一致する —— ずれているのは manifest の方かもしれない、という以上のことは
ここでは決めない。

### 4. `actor-manifest.test.ts` は実行できず、しかも主張が古い

- **実行できない** —— `package.json` が無く、vitest も依存に無い。
  `deps.edn` の alias にも入っていない。
- **主張が古い** —— `it("8 pipelines", ...)` と書いてあるが、manifest の
  `pipelines` は **10 本**在る（`xrpc` 7 / `cron` 2 / `subscribeRepos` 1）。
  走らせれば落ちるテストが、走らないので緑にも赤にもならないまま置かれている。

これは superproject CLAUDE.md が「測れなかった検査が、測って問題が無かった検査と
同じ値を返す」と呼ぶ形の一例である。ここでは**消さずに記録する** ——
manifest 側を 8 本に戻すのか、テストを 10 本に直すのかは、この repo の docs 反復が
決めることではない。

### 5. `complianceDocs` が指す 2 つのパスはこの repo に無い

`90-docs/rules/compliance/per-did-kyumei-shinka-autonomy.md` と
`90-docs/platform/260403-live-data-kyumei-shinka-consolidated.md` は
`actor-manifest.jsonld` が参照しているが、この repo には `90-docs/` 自体が無い。
移行元（`etzhayyimcojp/20-actors`、`NOTICE` 参照）側のパスと思われる。

## 由来

`NOTICE` のとおり `etzhayyimcojp/20-actors` から 2026-05-21 に切り出された
（ADR-2606231200）。ライセンスは Apache-2.0 + etzhayyim Charter Compliance
Rider v3.1。
