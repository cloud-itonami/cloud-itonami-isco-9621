(ns courier.governor
  "CourierRouteGovernor — the independent safety/scope layer gating
  every delivery-route scheduling/logistics proposal an advisor may
  make for a messenger/package-deliverer/luggage-porter crew. The
  governor never dispatches hardware itself, never performs delivery or
  portering work on the route itself, and never finalizes a
  delivery-execution decision (e.g. deciding to authorize a specific
  delivery route or luggage-handling operation) or a route-safety-
  clearance decision (e.g. declaring a route cleared for safety), and
  never overrides a route safety supervisor's judgment — those are
  permanently out of this actor's scope and remain a route safety
  supervisor's exclusive judgment (README's 'Robotics premise': this
  actor coordinates ROUTE SCHEDULING/LOGISTICS ONLY — it never performs
  delivery or portering work or makes route-safety-clearance decisions
  itself). Modeled closely on cloud-itonami-isco-9611's
  wastecollect.governor for the outdoor-route hazard-domain shape,
  extended with a second, independent delivery-traffic/manual-lifting
  hazard-scope dimension (messengers, package deliverers and luggage
  porters travel delivery routes by bicycle/vehicle/on foot in traffic
  and manually carry packages/luggage, so delivery-traffic-hazard and
  manual-lifting-hazard stakes stack on top of the outdoor-route
  hazard).

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. worker provenance     — the crew member must be independently
                                verified/registered before any action.
    2. route provenance      — the delivery route must be
                                independently verified/registered
                                before any action.
    3. no-actuation           — proposal :effect must be :propose (the
                                governor never dispatches hardware and
                                never performs delivery or portering
                                work itself; it only gates what the
                                advisor may coordinate).
    4. closed op-allowlist    — only :log-work-record,
                                :schedule-crew-operation,
                                :flag-safety-concern and
                                :coordinate-supply-order may ever be
                                proposed; anything else is refused.
    5. scope-excluded action  — any proposal to directly finalize a
                                delivery-execution decision (e.g.
                                authorizing a specific delivery route or
                                luggage-handling operation to proceed),
                                or to directly finalize a route-safety-
                                clearance decision (e.g. declaring a
                                route cleared for safety), or to
                                override a route safety supervisor's
                                judgment, is a hard, permanent block
                                (checked both against the proposed :op
                                and, defense-in-depth, against the
                                proposal's :rationale text — matched as
                                full finalization/execution ACTION
                                phrases such as \"authorize the delivery
                                route to proceed\" / \"declare the
                                route safety cleared\" / \"override the
                                route safety supervisor's judgment\",
                                never as bare nouns like \"package\",
                                \"luggage\", \"bicycle\", \"route\" or
                                \"safety\", so the check can never
                                self-trip on the advisor's own routine
                                rationale text, e.g. \"logged work
                                record for worker …\" or \"scheduled
                                crew operation for delivery route task
                                …\" or \"…routed for route safety
                                supervisor review\" — all three
                                legitimately contain those bare nouns
                                but none is a finalization action, and
                                all are exercised by
                                `governor-test/default-mock-advisor-proposals-never-self-trip-on-scope-exclusion`).
  ESCALATION invariants (:escalate? true, ALWAYS human sign-off
  regardless of confidence):
    6. :op :flag-safety-concern (a delivery-traffic-hazard /
                                manual-lifting-hazard /
                                equipment-condition concern always
                                escalates to a human, never
                                auto-commits).
    7. :op :coordinate-supply-order above `supply-cost-threshold`.
    8. low confidence (< `confidence-floor`)."
  (:require [kotoba.lang.text :as str]
            [courier.store :as store]))

(def confidence-floor 0.6)
(def supply-cost-threshold 2000)

(def allowed-ops
  #{:log-work-record :schedule-crew-operation
    :flag-safety-concern :coordinate-supply-order})

;; Defense-in-depth: none of these ops are ever in `allowed-ops` above,
;; so they are already refused by the closed-allowlist check below; they
;; are named again here — as explicit finalization/execution ACTIONS,
;; never bare nouns — so a future allowlist edit cannot silently re-open
;; either of these two independent out-of-scope paths (delivery-
;; execution finalization, route-safety-clearance finalization) without
;; also touching this list.
(def ^:private scope-excluded-ops
  #{:authorize-delivery-route-operation :authorize-luggage-handling-operation
    :finalize-delivery-execution-decision :approve-delivery-route-dispatch
    :declare-route-safety-cleared :finalize-route-safety-clearance
    :clear-route-for-safety
    :override-safety-supervisor-judgment :override-route-safety-supervisor-judgment})

;; Full finalization/execution ACTION phrases only — never bare nouns
;; ("package", "luggage", "bicycle", "route", "safety", "route safety
;; supervisor") — so this can never match inside the mock advisor's own
;; default rationale text (which legitimately contains those bare
;; nouns, e.g. "delivery route task" / "route safety supervisor
;; review"). See
;; `governor-test/default-mock-advisor-proposals-never-self-trip-on-scope-exclusion`.
(def ^:private scope-excluded-phrases
  ["authorize the delivery route to proceed" "authorize the luggage handling operation to proceed"
   "finalize the delivery route decision" "finalize the delivery execution decision"
   "approve the delivery route for dispatch"
   "declare the route safety cleared" "declare the route cleared for safety"
   "finalize the route safety clearance" "clear the route for safety"
   "override the route safety supervisor's judgment"
   "override the safety supervisor's judgment"
   "override route safety supervisor judgment"
   "override safety supervisor judgment"])

(defn- contains-excluded-phrase? [s]
  (let [s (str/lower (or s ""))]
    (boolean (some #(str/includes? s %) scope-excluded-phrases))))

(defn- hard-violations [proposal worker-record route-record]
  (let [{:keys [op rationale]} proposal]
    (cond-> []
      (nil? worker-record)
      (conj {:rule :no-worker
             :detail "未登録 worker への提案は不可（worker record は独立して検証・登録済みでなければならない）"})

      (nil? route-record)
      (conj {:rule :no-route
             :detail "未登録 route への提案は不可（route record は独立して検証・登録済みでなければならない）"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation
             :detail "effect は :propose のみ許可（governor は現場作業を直接実行しない）"})

      (not (contains? allowed-ops op))
      (conj {:rule :unknown-op
             :detail (str op " は closed op-allowlist に無い — 提案不可")})

      (or (contains? scope-excluded-ops op) (contains-excluded-phrase? rationale))
      (conj {:rule :scope-excluded-action
             :detail "配達実行判断（配達ルート・荷物ハンドリング作業の許可を含む）の確定、route safety clearance 判断の確定、route safety supervisor の判断の上書きは、この actor の権限外 — 常に永続ブロック"}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a `store`
  implementing `courier.store/Store`. Pure — never mutates the store,
  never dispatches a delivery-route operation, never finalizes a
  route-safety-clearance decision."
  [request _context proposal store]
  (let [worker-record (store/worker store (:worker-id request))
        route-record (some->> (:route-id proposal) (store/route store))
        hard (hard-violations proposal worker-record route-record)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        supply-order-over-threshold?
        (and (= :coordinate-supply-order (:op proposal))
             (number? (:cost proposal))
             (> (:cost proposal) supply-cost-threshold))
        always-risky? (or (= :flag-safety-concern (:op proposal))
                           supply-order-over-threshold?)]
    {:ok? (and (not hard?) (not low?) (not always-risky?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? always-risky?))}))
