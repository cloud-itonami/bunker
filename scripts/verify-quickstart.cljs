#!/usr/bin/env nbb
;; docs/operator-quickstart.md に書かれた出力が、いま本当にそう出るかを確かめる。
;;
;; 設計の要点は 1 つだけ —— **測れなかったことを、測って問題が無かったことと
;; 同じ値で返さない**（superproject CLAUDE.md「検査を書く前・緑を信じる前の 6 問」）。
;; そのために exit は 3 値:
;;
;;   0  全ブロック一致
;;   1  どれかが食い違った（差分を出す）
;;   2  REFUSED —— 文書が読めない / ブロックが 0 本 / `clojure` が PATH に無い。
;;      「合格」を名乗らずに終わる
;;
;; 正規化はちょうど 1 つ（clj-kondo の経過時間）。適用したブロックはそう報告する
;; ので、「完全一致」と「正規化して一致」が出力から区別できる。
(ns verify-quickstart
  (:require [kotoba.lang.text :as str]
            ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]))

(def ^:private doc-rel "docs/operator-quickstart.md")

(defn- repo-marker?
  "repo ルートの判定は 2 つ揃っていること —— deps.edn と、この repo の source。
  片方だけで判定すると、別の Clojure repo の中から回したときに黙って
  そちらを root と読む。

  **判定に doc-rel を使わない。** 使うと『文書が無い』が『root を特定
  できない』に化けて、2 つの原因が同じ 1 つのメッセージに潰れる。"
  [dir]
  (and (fs/existsSync (path/join dir "deps.edn"))
       (fs/existsSync (path/join dir "src" "bunker" "murakumo.cljc"))))

(defn- find-repo-root
  "起点から上へ最大 8 段たどる。見つからなければ nil —— 推測で返さない
  （返してしまうと、後段の『文書が無い』が root 誤認と区別できなくなる）。"
  [start]
  (loop [dir (path/resolve start) n 0]
    (cond
      (repo-marker? dir)             dir
      (>= n 8)                       nil
      (= dir (path/dirname dir))     nil
      :else (recur (path/dirname dir) (inc n)))))

(def ^:private script-dir
  (some-> (->> (.-argv js/process) (filter #(str/ends-with? % ".cljs")) first)
          path/resolve
          path/dirname))

(def ^:private repo-root
  (or (some-> script-dir find-repo-root)
      (find-repo-root (.cwd js/process))))

(defn- refuse! [msg]
  (println (str "REFUSED: " msg))
  (println "  合格とは報告しない —— この検査は答えを出せなかった。")
  (js/process.exit 2))

;; ---------------------------------------------------------------- parse

(defn- parse-blocks
  "```console フェンスを [{:cmd :expected}] にする。1 行目は `$ ` で始まること。"
  [text]
  (->> (re-seq #"(?m)^```console\n([\s\S]*?)^```" text)
       (map second)
       (keep (fn [body]
               (let [lines (str/split body #"\n" -1)
                     head  (first lines)]
                 (when (str/starts-with? head "$ ")
                   {:cmd      (subs head 2)
                    :expected (->> (rest lines)
                                   (str/join "\n")
                                   (#(str/replace % #"\n+$" "")))}))))
       vec))

;; ---------------------------------------------------------------- normalize

;; 実行ごとに変わるのは clj-kondo の経過時間だけ。ここを広げると検査が緩む。
(def ^:private lint-timing #"linting took \d+ms")

(defn- normalize [s]
  (if (re-find lint-timing s)
    [(str/replace s lint-timing "linting took <N>ms") true]
    [s false]))

;; ---------------------------------------------------------------- run

(defn- run [cmd]
  (try
    (let [out (cp/execSync cmd #js {:cwd repo-root
                                    :encoding "utf8"
                                    :stdio #js ["ignore" "pipe" "pipe"]
                                    :timeout (* 600 1000)})]
      {:out (str out) :status 0})
    (catch :default e
      ;; 非ゼロ終了でも stdout/stderr は突き合わせる —— 落ちたこと自体が
      ;; documented な出力であることがある
      {:out    (str (or (some-> (.-stdout e) str) "")
                    (or (some-> (.-stderr e) str) ""))
       :status (or (.-status e) 1)
       :error  (.-message e)})))

(defn- diff-report [expected actual]
  (let [e (str/split expected #"\n" -1)
        a (str/split actual #"\n" -1)
        n (max (count e) (count a))]
    (->> (range n)
         (keep (fn [i]
                 (let [ev (get e i ::absent) av (get a i ::absent)]
                   (when (not= ev av)
                     (str "    line " (inc i) "\n"
                          "      expected: " (if (= ev ::absent) "<absent>" (pr-str ev)) "\n"
                          "      actual:   " (if (= av ::absent) "<absent>" (pr-str av)))))))
         (take 12)
         (str/join "\n"))))

;; ---------------------------------------------------------------- main

(defn -main []
  (when-not repo-root
    (refuse! (str "repo ルートを特定できなかった（deps.edn と " doc-rel
                  " の両方を持つ祖先ディレクトリが無い）。この repo の中から実行すること")))
  (let [doc-path (path/join repo-root doc-rel)]
    (when-not (fs/existsSync doc-path)
      (refuse! (str doc-rel " が無い（" doc-path "）")))
    (let [text   (fs/readFileSync doc-path "utf8")
          blocks (parse-blocks text)]
      ;; evidence floor: 0 本を clean にしない
      (when (zero? (count blocks))
        (refuse! (str doc-rel " から console ブロックを 1 本も取れなかった。"
                      "フェンスの書式が変わった可能性がある")))
      ;; 実行できるかを先に確かめる —— 全ブロックが `clojure` を使う
      (let [probe (run "command -v clojure")]
        (when (not= 0 (:status probe))
          (refuse! "`clojure` が PATH に無い。このドキュメントは 1 本も検証できない")))
      (println (str "quickstart: " doc-rel))
      (println (str "blocks:     " (count blocks)))
      (println (str "cwd:        " repo-root))
      (println "")
      (let [results
            (doall
             (map-indexed
              (fn [i {:keys [cmd expected]}]
                (let [{:keys [out]}   (run cmd)
                      [exp-n norm-e?] (normalize expected)
                      [act-n norm-a?] (normalize (str/replace out #"\n+$" ""))
                      ok?             (= exp-n act-n)]
                  (println (str (if ok? "OK   " "DIFF ")
                                (inc i) "/" (count blocks) "  " cmd
                                (when (or norm-e? norm-a?) "   (normalized: lint-timing)")))
                  (when-not ok?
                    (println (diff-report exp-n act-n)))
                  ok?))
              blocks))
            bad (count (remove true? results))]
        (println "")
        (if (zero? bad)
          (do (println (str "PASS " (count blocks) "/" (count blocks)))
              (js/process.exit 0))
          (do (println (str "MISMATCH " bad "/" (count blocks)))
              (js/process.exit 1)))))))

(-main)
