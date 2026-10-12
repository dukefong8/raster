(ns raster.backend-guard
  "Deployment-capability gate for tests that need optionally-shipped backend
  namespaces (the lean jar omits the compute-GPU/native-C toolchains).

  Unlike a bare `requiring-resolve` (FileNotFoundException on lean) or a silent
  `when-let` (zero assertions, invisible in output), a skip here PRINTS a marker
  and records a passing assertion — the `opencl-skip!` convention (see
  raster.gpu.device-probe, whose device probes cannot run on lean at all because
  the runtime namespaces themselves are absent, hence this classpath check)."
  (:require [clojure.test :as t]))

(defn resolve-or-skip!
  "Resolve `var-sym` (requiring its namespace first). Returns the Var, or records
  a visible skip and returns nil when the backend isn't on the classpath."
  [test-label var-sym]
  (let [v (try (requiring-resolve var-sym) (catch Exception _ nil))]
    (if v
      v
      (do (println (str "  [BACKEND SKIP] " test-label " — " var-sym
                        " unavailable (lean deployment?)"))
          (t/is true "backend-skip-marker")
          nil))))
