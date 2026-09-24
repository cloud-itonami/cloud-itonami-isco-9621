# physai-isco-9621 — 配達・メッセンジャー・荷物運搬（配達機材の運用調整） の physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-isco-9621`、ISCO 9621 配達員・メッセンジャー・ポーター）に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README の Robotics premise: ルートのスケジューリング/物流調整ロボットが、配達員・荷物運搬の作業員の編成・配達の記録・配達機材と消耗品の調達調整を行う（配達そのものはしない）。この bot が測るのは、その調整が前提にしている配達機材の物理 —— 歩道配達ロボットが区間の坂を上れるか、荷物をロッカーの上段へ入れる持ち上げの負荷（手作業の持ち上げ危険の目安）。
その物理的な仕事を `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、
`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:sidewalk-robot-up-grade` | transport | 歩道配達ロボット（50 kg）が 20 kg の荷物を歩道 80 m 運ぶ（勾配を変える） | 1 区間の所要時間 | 70 s（estimate） |
| `:parcel-into-upper-locker` | manipulator | アームが荷物を配達ロボットの荷室から宅配ロッカーの上段へ入れる | 肩関節ピークトルク | 100 N·m（estimate） |

測定の入口: `kbb -M:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:physai-test`（`test-physai/courier/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する。
この alias は repo 自身の `test/` の `.cljk` も kbb の runner で一緒に走らせる）。

## 測って分かったこと・限界（成長の第一候補）

1. **歩道の坂**: 勾配 0〜6° では 52.40 s のまま（加速度上限 0.5 m/s² が効いている）。8° で駆動力が効いて 54.73 s、10° 以上で **停止**。境界は **約 8.96°**。
   エネルギーは 0° で 901 J、8° で 8415 J。solver の転倒余裕は 0° で 0.871、8° で 0.693（判定量ではない）。
2. **ロッカーの上段**: 肩トルクは 1 kg で 41.4 N·m、5 kg で 70.9 N·m、10 kg で 107.7 N·m（限界超え）。限界 100 N·m に達するのは **8.95 kg** —— それより重い荷物は下段へ入れる必要がある。
3. **estimate のままの値**（成長候補）: 区間所要時間 70 s（配達時間枠の計画で置き換える）、肩トルク上限 100 N·m（アームの仕様書）、
   配達ロボットの駆動力 120 N・歩道の転がり抵抗係数 0.015（メーカー仕様）。

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この職種のロボットがする別の物理的な仕事を 1 case 足す（例: スーツケースの持ち上げ、保冷ボックスの温度、段差の乗り越え）。
   `:kind` は :transport / :manipulator / :material / :thermal / :tank-drain / :pipe-flow。README の premise と docs から根拠を取る。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-isco-9621 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:physai-test → kbb -M:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-isco-9621 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
