;; Lean-jar gate: the test namespaces verified to load and pass against
;; target/raster-lean.jar (no src/ on the classpath).
;;
;; Run: clojure -M:lean -m raster-lean-test (exit 0 iff all green).
;;
;; Top level defines data only (no requires), so test-runner discovery can
;; require this file harmlessly. -main requires and runs each namespace with
;; clojure.test/test-vars, skipping ^:perf/^:statistical vars - the same
;; selection as -M:test. Source of truth: .tmp/test-lean-coverage.md,
;; regenerated from one-by-one runs against the jar.
(ns raster-lean-test
  "Test namespaces green against the lean jar, plus the gate runner."
  (:require [clojure.test :as t]))

(def green-namespaces
  "Test namespaces green against the lean jar (sorted)."
  '[raster.ad.carrier-tuple-test raster.ad.compiled-value-grad-test raster.ad.composable-test
    raster.ad.core-test raster.ad.forward-template-authority-test raster.ad.jet-test raster.ad.normalized-indexed-map-test
    raster.ad.normalized-lazy-callee-test raster.ad.normalized-map-element-test raster.ad.normalized-map-execution-test raster.ad.normalized-map-lane-test
    raster.ad.normalized-map-program-test raster.ad.normalized-program-assembly-test raster.ad.normalized-surface-map-test raster.ad.ordered-normalization-test
    raster.ad.purity-test raster.ad.rad-test raster.ad.read-operand-identity-test raster.ad.rrule-test
    raster.ad.scan-gather-test raster.ad.templates-test raster.ad.vjp-definition-cache-test raster.arrays-test
    raster.ci.raw-skip-manifest-test raster.compiler.ad.bind-ctx-test raster.compiler.ad.flatten-test raster.compiler.ad.mode-select-test
    raster.compiler.auto-buffer-cache-test raster.compiler.backend.cpu.codegen-test raster.compiler.backend.cpu.native-call-plan-test raster.compiler.backend.cpu.native-call-safety-test
    raster.compiler.backend.cpu.native-marshalling-test raster.compiler.backend.cpu.quant-encode-test raster.compiler.backend.cpu.quant-k-test raster.compiler.backend.gpu.c-emit-test
    raster.compiler.backend.gpu.wgsl-test raster.compiler.backend.jvm.bytecode-test raster.compiler.backend.jvm.checked-long-test raster.compiler.backend.jvm.hoisted-test
    raster.compiler.backend.jvm.jit-ad-test raster.compiler.backend.jvm.loop-carrier-test raster.compiler.backend.jvm.par-simd-test raster.compiler.backend.jvm.typed-scalar-test
    raster.compiler.backend.jvm.untyped-array-test raster.compiler.backend.jvm.wide-call-test raster.compiler.bc-correctness-test raster.compiler.buffer-allocation-legality-test
    raster.compiler.contract-load-transform-test raster.compiler.core.auto-specialization-test raster.compiler.core.calibration-admission-test raster.compiler.core.call-signature-test
    raster.compiler.core.contraction-scope-test raster.compiler.core.cross-vendor-descriptor-test raster.compiler.core.dtype-test raster.compiler.core.hardware-roofline-test
    raster.compiler.core.hardware-scalar-capability-test raster.compiler.core.hardware-test raster.compiler.core.inference-test raster.compiler.core.inline-pure-lets-test
    raster.compiler.core.intel-block-io-test raster.compiler.core.layout-test raster.compiler.core.macroexpand-test raster.compiler.core.numeric-constant-test
    raster.compiler.core.op-descriptor-test raster.compiler.core.params-flatten-test raster.compiler.core.params-test raster.compiler.core.type-flow-test
    raster.compiler.core.util-test raster.compiler.devirtualization-test raster.compiler.effect-cache-validity-test raster.compiler.frontend-failure-test
    raster.compiler.ir.abstract-value-test raster.compiler.ir.axis-map-test raster.compiler.ir.buffer-view-test raster.compiler.ir.contract-stages-test
    raster.compiler.ir.effect-region-scope-test raster.compiler.ir.extent-proof-test raster.compiler.ir.form-test raster.compiler.ir.index-algebra-test
    raster.compiler.ir.invariants-test raster.compiler.ir.invocation-plan-test raster.compiler.ir.kernel-artifact-test raster.compiler.ir.kernel-body-test
    raster.compiler.ir.kernel-graph-test raster.compiler.ir.kernel-launch-test raster.compiler.ir.layout-stage-test raster.compiler.ir.nested-effect-region-test
    raster.compiler.ir.parallel-program-test raster.compiler.ir.result-effect-region-test raster.compiler.ir.retained-equations-test raster.compiler.ir.scalar-range-test
    raster.compiler.ir.scan-test raster.compiler.ir.scheduled-graph-refinement-test raster.compiler.ir.segop-test raster.compiler.ir.semantic-fingerprint-test
    raster.compiler.ir.soac-dialect-test raster.compiler.ir.soac-projection-test raster.compiler.ir.soac-shape-remap-test raster.compiler.ir.soac-test
    raster.compiler.ir.structured-control-test raster.compiler.lexical-unknown-mask-test raster.compiler.no-inline-test raster.compiler.parametric-dispatch-test
    raster.compiler.passes.parallel.auxiliary-semantic-witness-test raster.compiler.passes.parallel.compound-detect-test raster.compiler.passes.parallel.contract-lower-test raster.compiler.passes.parallel.device-test
    raster.compiler.passes.parallel.dot-reduce-test raster.compiler.passes.parallel.effect-source-test raster.compiler.passes.parallel.functional-fresh-write-test raster.compiler.passes.parallel.fusion-placement-test
    raster.compiler.passes.parallel.guarded-store-proof-test raster.compiler.passes.parallel.index-expression-test raster.compiler.passes.parallel.liveness-map-recursion-test raster.compiler.passes.parallel.loop-lift-test
    raster.compiler.passes.parallel.map-read-requirements-test raster.compiler.passes.parallel.schedule-support-test raster.compiler.passes.parallel.structured-control-frontend-test raster.compiler.passes.parallel.typed-butterfly-test
    raster.compiler.passes.parallel.typed-segmented-reduction-test raster.compiler.passes.parallel.typed-soac-frontend-test raster.compiler.passes.parallel.typed-soac-fusion-test raster.compiler.passes.parallel.typed-soac-resident-test
    raster.compiler.passes.parallel.zero-contract-epilogue-test raster.compiler.passes.region-copy-test raster.compiler.passes.scalar.cse-test raster.compiler.passes.scalar.dce-test
    raster.compiler.passes.scalar.effects-test raster.compiler.passes.scalar.host-abstract-value-test raster.compiler.passes.scalar.inline-test raster.compiler.passes.scalar.mem-merge-test
    raster.compiler.passes.scalar.rewalk-test raster.compiler.passes.scalar.scope-capture-test raster.compiler.passes.scalar.simplify-test raster.compiler.passes.scalar.typed-arithmetic-simplification-test
    raster.compiler.pe-test raster.compiler.pipeline-extractor-test raster.compiler.pipeline-test raster.compiler.recur-lub-test
    raster.compiler.report-test raster.compiler.required-jit-walk-test raster.compiler.review-regression-test raster.compiler.segop-boundary-test
    raster.compiler.simplify-test raster.compiler.specialization-return-test raster.compiler.valhalla-test raster.core-test
    raster.deftm-tree-compose-test raster.dl.diffusion-test raster.dl.loss-test raster.dl.norm-ad-test
    raster.dl.optim-test raster.dl.scatter-test raster.gpu.device-probe-test raster.gpu.ze-runtime-test
    raster.hardware-fixture-test raster.linalg.blas-test raster.linalg.core-test raster.linalg.dense-test
    raster.linalg.native-library-test raster.math-test raster.numeric-test raster.par-contract-test
    raster.par-gather-test raster.par-scatter-test raster.par-test raster.params-e2e-mlp-test
    raster.params-test raster.polymorphic-scalar-dtype-test raster.render.shader-test raster.runtime.native-abi-test
    raster.runtime.native-call-test raster.sci.special-test raster.shape-test raster.spatial.nndescent-test
    raster.sym.core-test raster.types.complex-test raster.types.float-test raster.types.hierarchy-test
    raster.types.int8-test raster.types.number-types-test raster.types.promote-test raster.types.traits-test])

(defn- run-one [ns-sym]
  (let [load-err (try (require ns-sym) nil (catch Throwable e (str (ex-message e))))]
    (if load-err
      (do (println "LOAD-FAILED" ns-sym "-" load-err)
          {:ns ns-sym :status :load-failed})
      (let [ns-obj (the-ns ns-sym)
            vars (filter (fn [v] (let [m (meta v)]
                                   (and (:test m)
                                        (not (:perf m))
                                        (not (:statistical m)))))
                         (vals (ns-interns ns-obj)))]
        (binding [t/*report-counters* (ref t/*initial-report-counters*)]
          (t/do-report {:type :begin-test-ns :ns ns-obj})
          (t/test-vars (vec vars))
          (let [c (deref t/*report-counters*)]
            (println "RESULT" ns-sym c)
            (assoc c :ns ns-sym :status :ran)))))))

(defn -main [& _]
  (let [{:keys [fail error load-failed runner-threw] :as summary}
        (reduce (fn [acc ns-sym]
                  (let [r (try (run-one ns-sym)
                               (catch Throwable e
                                 (println "RUNNER-THREW" ns-sym "-" (ex-message e))
                                 {:ns ns-sym :status :runner-threw}))]
                    (-> acc
                        (update :total inc)
                        (update :fail + (get r :fail 0))
                        (update :error + (get r :error 0))
                        (update :pass + (get r :pass 0))
                        (update :load-failed + (if (= :load-failed (:status r)) 1 0))
                        (update :runner-threw + (if (= :runner-threw (:status r)) 1 0)))))
                {:total 0 :fail 0 :error 0 :pass 0 :load-failed 0 :runner-threw 0}
                green-namespaces)]
    (println "LEAN-GATE" (select-keys summary [:total :pass :fail :error :load-failed :runner-threw]))
    (System/exit (if (zero? (+ fail error load-failed runner-threw)) 0 1))))
