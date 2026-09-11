# operator quickstart — bunker

**この文書のコマンドは全部、書いてあるとおりに実行して出力を実測した**
（2026-09-02、macOS / Clojure CLI）。踏めない手順は書いていない。

一致するかは機械で確かめられる:

```
nbb scripts/verify-quickstart.cljk
```

検査器はこのファイルの `console` ブロックだけを読み、1 行目の `$ ` に続く
コマンドを repo ルートで実行して、残りの行と突き合わせる。

- exit `0` — 全ブロック一致
- exit `1` — どれかが食い違った（差分を出す）
- exit `2` — **REFUSED**。この文書が読めない・ブロックが 0 本・`clojure` が
  PATH に無い等、*測れなかった*とき。合格と同じ値を返さないための床

**正規化はちょうど 1 つだけ**適用する: clj-kondo の `linting took <N>ms` の
経過時間（実行ごとに変わる。実測 696ms / 743ms）。適用したブロックは
`(normalized: lint-timing)` と報告するので、「完全一致」と「正規化して一致」は
出力から区別できる。

## 前提

- `clojure`（Clojure CLI）。初回だけ `deps.edn` の依存を取りに行くので
  ネットワークが要る。2 回目以降はローカルキャッシュで足りる
- 検査器を回すなら `nbb`
- JDK が無い環境では **1 と 2 が実行できない**。その場合 3〜5 も動かない
  （どれも `clojure` を使う）

## 1. テストを通す

`cell-specs` を走査する契約テスト。cell 名を hardcode していないので、
manifest が宣言する cell が増減してもそのまま効く。

```console
$ clojure -M:test

Running tests in #{"test"}

Testing bunker.murakumo-test

Ran 9 tests containing 252 assertions.
0 failures, 0 errors.
```

## 2. lint を通す

`--fail-level error` なので warning では落ちない。**warning 1 件は既知**
（`records-for` の `:as input` が本体で使われていない、`murakumo.cljc:180`）。

```console
$ clojure -M:lint
src/bunker/murakumo.cljk:180:14: warning: unused binding input
linting took <N>ms, errors: 0, warnings: 1
```

## 3. 何が在るかを数える

18 の cell、7 つの gate、コード上の actor DID、collection の接頭辞。
接頭辞が `com.etzhayyim.bunker.` であって `com.etzhayyim.apps.bunker.` では
ないことは README の食い違い 3 で扱っている。

```console
$ clojure -M -e '(require (quote [bunker.murakumo :as m])) (println "cells  " (count m/cell-specs)) (println "gates  " (count m/common-gates)) (println "actor  " m/actor-did) (println "prefix " (m/collection "<cell>"))'
cells   18
gates   7
actor   did:web:bunker.etzhayyim.com
prefix  com.etzhayyim.bunker.<cell>
```

## 4. gate が欠けているとき、何も出さないことを見る

attestation を渡さずに `:health` cell の計画を立てる。**`:blocked` で
effect が 0 本**になり、欠けている gate が 7 つ全部数え上げられる。
部分的に書き込むことはしない。

```console
$ clojure -M -e '(require (quote [bunker.murakumo :as m])) (let [p (m/cell-plan :health {})] (println "status       " (:status p)) (println "effects      " (count (:effects p))) (println "missing-gates" (count (:missing-gates p))))'
status        :blocked
effects       0
missing-gates 7
```

## 5. gate が揃ったとき、ちょうど 1 本出すことを見る

同じ cell に 7 gate 全部を attest して渡す。**`:ready` になり、宣言された
collection 1 つにつき effect が 1 本**出る。`:rkey` は渡した `:request-id`
から `safe-rkey` を通って決まる。

4 と 5 を続けて実行することが、この不変条件の両方向の実演である —— 片方だけ
見ても「いつも blocked を返す関数」と区別が付かない。

```console
$ clojure -M -e '(require (quote [bunker.murakumo :as m])) (let [att (zipmap m/common-gates (repeat true)) p (m/cell-plan :health {:attestations att :request-id "demo-1"}) e (first (:effects p))] (println "status       " (:status p)) (println "effects      " (count (:effects p))) (println "missing-gates" (count (:missing-gates p))) (println "op           " (:op e)) (println "collection   " (:collection e)) (println "rkey         " (:rkey e)))'
status        :ready
effects       1
missing-gates 0
op            :mst/put-record
collection    com.etzhayyim.bunker.health
rkey          demo-1
```

## ここでは動かないもの

- `actor-manifest.test.ts` — `package.json` が無く vitest も無いので実行経路が
  無い。しかも `it("8 pipelines")` と主張しているが manifest の pipeline は
  10 本ある（README の食い違い 4）
- `actor-manifest.jsonld` の pipeline 群 — 実行するのは別 runtime
  （`k8s-langserver` + `sveltekit-proxy`）であって、この repo ではない
