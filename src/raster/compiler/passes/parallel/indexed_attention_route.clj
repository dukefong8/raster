(ns raster.compiler.passes.parallel.indexed-attention-route
  "Structured routing for recognized indexed graph-attention plans."
  (:require [raster.compiler.ir.segmented-weighted-reduction :as swr]
            [raster.compiler.passes.parallel.indexed-weighted-reduction-capability
             :as indexed-capability]))

;; ================================================================
;; Lazy indexed-attention GPU emitter resolution
;; ================================================================
;; This namespace is required from src only by the GPU OpenCL/SPIR-V routing seam
;; (segmented-weighted-reduction-route's leaf registry), and by device tests; a JVM/bytecode
;; deployment therefore never loads backend.gpu.indexed-attention. Each call site is inside a
;; leaf that only emits after capability admission, so the resolve cannot fire on a decline.
;; A missing emitter is a loud failure AT THE EMISSION SITE — never a silent scalar fallback.
;;
;; `@` is required: requiring-resolve returns a VAR, and invoking a Var applies its root value
;; (the fn) — `@` derefs it. Without it the call receives arity 0.

(def ^:private ia-emit-reference
  (delay (requiring-resolve 'raster.compiler.backend.gpu.indexed-attention/emit-reference)))
(def ^:private ia-emit-dynamic-reference
  (delay (requiring-resolve 'raster.compiler.backend.gpu.indexed-attention/emit-dynamic-reference)))
(def ^:private ia-emit-dynamic-score-reuse
  (delay (requiring-resolve 'raster.compiler.backend.gpu.indexed-attention/emit-dynamic-score-reuse)))
(def ^:private ia-kernel-graph
  (delay (requiring-resolve 'raster.compiler.backend.gpu.indexed-attention/kernel-graph)))
(def ^:private ia-dynamic-kernel-graph
  (delay (requiring-resolve 'raster.compiler.backend.gpu.indexed-attention/dynamic-kernel-graph)))

(defn- decline
  ([plan reason data] (decline plan :indexed-edge-list-reference reason data))
  ([plan leaf reason data]
   {:operation plan
    :strategy nil
    :reference? false
    :declines [{:leaf leaf :reason reason :data data}]}))

(defn route
  "Try the direct fused correctness leaf using concrete values for symbolic plan extents."
  ([plan shape-env] (route plan shape-env nil))
  ([plan shape-env desc]
   (try
     (let [{:keys [operands output accumulator-dtype] :as plan} (swr/validate! plan)
           storage-dtypes (mapv :dtype (conj operands output))]
       (cond
         (and desc (not= :gpu (:device-type desc)))
         (decline plan :indexed-attention-requires-gpu
                  {:device-type (:device-type desc)})

         (not (contains? #{:float :double} accumulator-dtype))
         (decline plan :indexed-attention-accumulator-unsupported
                  {:required #{:float :double} :actual accumulator-dtype})

         (not= [accumulator-dtype accumulator-dtype accumulator-dtype
                :long :long accumulator-dtype]
               storage-dtypes)
         (decline plan :indexed-attention-storage-unsupported
                  {:required [accumulator-dtype accumulator-dtype accumulator-dtype
                              :long :long accumulator-dtype]
                   :actual storage-dtypes})

         :else
         (let [artifact (@ia-emit-reference plan shape-env desc)]
           {:operation plan
            :plan plan
            :strategy :indexed-segmented-reduction-reference
            :reference? true
            :declines []
            :artifact artifact
            :graph (@ia-kernel-graph plan shape-env artifact)
            :schedule {:workgroup-size (:workgroup-size (:launch artifact))
                       :group-count (:group-count (:launch artifact))}})))
     (catch clojure.lang.ExceptionInfo e
       (decline plan (or (:reason (ex-data e)) :indexed-segmented-reduction-unsupported)
                (dissoc (ex-data e) :reason))))))

(defn route!
  "Route indexed attention or fail with its machine-readable decline trail."
  ([plan shape-env] (route! plan shape-env nil))
  ([plan shape-env desc]
   (let [result (route plan shape-env desc)]
     (if (:strategy result)
       result
       (throw (ex-info "no executable indexed-attention kernel route"
                       {:reason :indexed-attention-no-kernel-route :route result}))))))

(defn route-dynamic
  "Route a recognized plan to the shape-polymorphic resident/staging artifact."
  ([plan] (route-dynamic plan nil))
  ([plan desc]
   (try
     (let [{:keys [operands output accumulator-dtype] :as plan} (swr/validate! plan)
           storage-dtypes (mapv :dtype (conj operands output))]
       (cond
         (and desc (not= :gpu (:device-type desc)))
         (decline plan :indexed-attention-requires-gpu
                  {:device-type (:device-type desc)})

         (not (contains? #{:float :double} accumulator-dtype))
         (decline plan :indexed-attention-accumulator-unsupported
                  {:required #{:float :double} :actual accumulator-dtype})

         (not= [accumulator-dtype accumulator-dtype accumulator-dtype
                :long :long accumulator-dtype]
               storage-dtypes)
         (decline plan :indexed-attention-storage-unsupported
                  {:required [accumulator-dtype accumulator-dtype accumulator-dtype
                              :long :long accumulator-dtype]
                   :actual storage-dtypes})

         :else
         (let [artifact (@ia-emit-dynamic-reference plan desc)]
           {:operation plan :plan plan
            :strategy :indexed-segmented-reduction-reference
            :reference? true :dynamic-shape? true :declines []
            :artifact artifact
            :graph (@ia-dynamic-kernel-graph plan artifact)
            :schedule {:workgroup-size (:workgroup-size (:launch artifact))
                       :group-count (:group-count (:launch artifact))}})))
     (catch clojure.lang.ExceptionInfo e
       (decline plan (or (:reason (ex-data e)) :indexed-segmented-reduction-unsupported)
                (dissoc (ex-data e) :reason))))))

(defn route-dynamic!
  ([plan] (route-dynamic! plan nil))
  ([plan desc]
   (let [result (route-dynamic plan desc)]
     (if (:strategy result)
       result
       (throw (ex-info "no executable dynamic indexed-attention kernel route"
                       {:reason :indexed-attention-no-kernel-route :route result}))))))

(defn route-dynamic-score-reuse
  "Route to the destination/head subgroup leaf that shares each score across component lanes."
  ([plan] (route-dynamic-score-reuse plan nil))
  ([plan desc]
   (let [leaf :indexed-edge-list-subgroup-score-reuse]
     (try
       (let [plan (swr/validate! plan)
             {:keys [status width reason data]} (indexed-capability/score-reuse plan desc)]
         (if (= :declined status)
           (decline plan leaf reason data)
           ;; The emitter still reads the compatibility key. Keep it pinned to the normalized
           ;; width that passed capability admission rather than independently defaulting to 16.
           (let [artifact (@ia-emit-dynamic-score-reuse
                           plan (assoc desc :subgroup-size width))]
             {:operation plan :plan plan
              :strategy :indexed-segmented-reduction-subgroup-score-reuse
              :reference? false :dynamic-shape? true :declines []
              :artifact artifact
              :graph (@ia-dynamic-kernel-graph plan artifact)
              :schedule {:workgroup-size (:workgroup-size (:launch artifact))
                         :group-count (:group-count (:launch artifact))}})))
       (catch clojure.lang.ExceptionInfo e
         (decline plan leaf
                  (or (:reason (ex-data e)) :indexed-score-reuse-unsupported)
                  (dissoc (ex-data e) :reason)))))))
