(ns raster.compiler.passes.parallel.device
  "Device helpers for compiler backend selection and allocation policy.

  Device ids are concrete keywords such as :cpu:0, :cuda:0, :hip:0, :ze:0, and :ocl:0.
  The compiler uses these concrete ids directly rather than a second layer of
  :cpu/:cuda pseudo-devices."
  (:require [clojure.string :as str]
            [raster.compiler.core.dtype :as dtype]))

;; ================================================================
;; Declared parameter types (source ABI facts)
;; ================================================================

(defn derive-param-types
  "Canonical home (moved from raster.compiler.backend.gpu.opencl-pass so the
  JVM/bytecode path uses it without depending on the GPU backend).
  Declared scalar + array element types, read from a deftm's params + tags
  (the typed-dispatch system already knows these — we read them instead of letting the emitter
  guess from parameter names). Scalar params retain their resolved declared width, including
  Float and Double independently of the selected tensor dtype. Physical narrowing belongs to a scheduled
  ABI conversion with a range check, not source type derivation.
  Array params: the tag's element dtype, with float-family (float/double)
  mapped to a floating-point KERNEL dtype (a single-precision kernel reads float buffers regardless of a
  parametric (All [T]) param's default).
  Integral kernel policies retain declared floating types rather than turning scales into bytes.
  With :preserve-declared-array-storage? true, resolved array tags instead retain their
  physical element dtype; scalar computation precision is unchanged.
  Returns {:scalar-types {sym kw} :array-types {sym kw}}, attached as form metadata that
  opencl-pass reads. ONE derivation, both compile paths."
  ([params tags dtype] (derive-param-types params tags dtype {}))
  ([params tags dtype {:keys [preserve-declared-array-storage?]}]
   (when (and params tags)
     {:scalar-types (into {} (keep (fn [[p t]]
                                     (case (dtype/dtype-for-scalar-tag t)
                                       :long [p :long]
                                       :int [p :int]
                                       (:double :float) [p (dtype/dtype-for-scalar-tag t)]
                                       nil))
                                   (map vector params tags)))
      :array-types (into {} (keep (fn [[p t]]
                                    (when-let [dt (dtype/dtype-for-array-tag t)]
                                      [p (if (and (not preserve-declared-array-storage?)
                                                  (#{:float :double} dt) (dtype/fp-dtype? dtype))
                                           dtype dt)]))
                                  (map vector params tags)))})))

(defn device-type
  "Extract device type from a concrete device-id keyword."
  [device-id]
  (when device-id
    (let [s (name device-id)]
      (cond
        (= s "cpu")                :cpu
        (= s "cuda")               :cuda
        (= s "hip")                :hip
        (= s "ze")                 :ze
        (= s "ocl")                :ocl
        (str/starts-with? s "cpu")  :cpu
        (str/starts-with? s "cuda") :cuda
        (str/starts-with? s "hip")  :hip
        (str/starts-with? s "ze")   :ze
        (str/starts-with? s "ocl")  :ocl
        :else (keyword (first (str/split s #":")))))))

(defn gpu-target?
  "True when device-id selects a GPU backend family."
  [device-id]
  (contains? #{:cuda :hip :ze :ocl} (device-type device-id)))

(defn level-zero-target?
  "True when device-id selects a Level Zero target family."
  [device-id]
  (= :ze (device-type device-id)))

(defn preferred-dtype
  "Return the preferred default dtype for a target device, or nil when there is
  no device-specific preference."
  [device-id]
  (when (level-zero-target? device-id)
    :float))

;; ================================================================
;; Dtype-polymorphic allocation
;; ================================================================

(defn dtype->array-ctor
  "Return array constructor symbol for dtype."
  [dtype]
  (case dtype
    :double 'double-array
    :float  'float-array
    :long   'long-array
    :int    'int-array
    (throw (ex-info (str "dtype->array-ctor: unknown dtype `" dtype "`. Expected :double, :float, :long, or :int.")
                    {:dtype dtype}))))

(defn alloc-expr
  "Generate (ctor size) allocation S-expression for dtype."
  [dtype size-expr]
  (list (dtype->array-ctor dtype) size-expr))

;; ================================================================
;; Device-aware allocation
;; ================================================================

(defn select-backend
  "Select backend for a par form based on device and size hint.
  Returns :cuda, :hip, :opencl, :simd, or :scalar.

  device-id: keyword like :cpu:0, :cuda:0, :hip:0, :ze:0, or :ocl:0
  size-hint: known or estimated element count (may be nil)"
  [device-id size-hint]
  (let [dt (device-type (or device-id :cpu:0))]
    (cond
      (= :cuda dt)                         :cuda
      (= :hip dt)                          :hip
      (= :ze dt)                           :opencl
      (= :ocl dt)                          :opencl
      (and size-hint (>= size-hint 8))     :simd
      (nil? size-hint)                     :simd
      :else                                :scalar)))

(defn select-runtime-backend
  "Choose the runtime backend from user options.

  target-device wins when present; otherwise simd? controls the CPU fallback."
  [target-device simd? size-hint]
  (if target-device
    (select-backend target-device size-hint)
    (if simd? :simd :scalar)))
