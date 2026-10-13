(ns build
  "Build tasks for raster.

  Usage:
    clj -T:build clean
    clj -T:build jar          ; full source tree (GPU, Vulkan, ABM, ODE, …)
    clj -T:build lean-jar     ; JVM/bytecode + WASM + WebGPU — the browser-game surface
    clj -T:build lean-report  ; print the derived lean namespace closure
    clj -T:build deploy
    clj -T:build release
    clj -T:build install"
  (:refer-clojure :exclude [test])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [borkdude.gh-release-artifact :as gh]
            [deps-deploy.deps-deploy :as dd])
  (:import [clojure.lang ExceptionInfo]))

(def org "replikativ")
(def lib 'org.replikativ/raster)
(def current-commit (b/git-process {:git-args "rev-parse HEAD"}))
(def version (format "0.2.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn- dependency-coordinate [[library coordinate]]
  [library
   (cond
     (:mvn/version coordinate) {:mvn/version (:mvn/version coordinate)}
     (:git/sha coordinate) {:git/sha (:git/sha coordinate)}
     :else {:unversioned true})])

(defn- source-namespace [file]
  (with-open [reader (java.io.PushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (let [form (read {:eof nil :read-cond :allow :features #{:clj}} reader)]
        (when (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
          (second form))))))

(defn- packaged-source-namespaces []
  (->> (file-seq (io/file "src"))
       (filter #(and (.isFile %) (re-find #"\.clj[cs]?$" (.getName %))))
       (keep source-namespace)
       distinct
       (sort-by str)
       vec))

;; ================================================================
;; Lean source closure
;; ================================================================
;; The lean jar ships the JVM/bytecode surface plus the browser vertical:
;;
;;   * wasm emission + the cljs↔wasm build half (raster.compiler.cljs-emit) —
;;     Asteroids and Valley compile their deftm kernels to WebAssembly for the
;;     browser build.
;;
;; The desktop Vulkan renderer (raster.vk.* + raster.render.vulkan) is NOT
;; shipped — Valley runs in the browser via WebGPU (raster.render.webgpu).
;;
;; It is DERIVED from the static `:require` graph rather than hand-listed, because a
;; hand-maintained file list is the thing that silently drifts the moment a new require appears.
;;
;; The compute GPU stack (raster.gpu.* runtime, backend.gpu.* OpenCL/Level-Zero/WGSL emitters,
;; backend.cpu.* native-C backend) is reached only through `requiring-resolve` behind
;; device-gated branches, so it stays OUT of this closure by construction — that property is
;; asserted, not assumed (see `lean-closure-gaps!`).

(def lean-entry-namespaces
  "Roots of the packaged user surface, grouped like src/raster/. The closure
  is DERIVED (transitive static requires), so this list names entry points,
  not files. gpu/ and vk/ ship nothing (see exclusions below)."
  '[;; Foundations: macros (core), numeric tower (numeric/math), arrays, par.
    raster.core raster.numeric raster.math raster.arrays raster.par
    ;; ad/: reverse + forward AD.
    raster.ad
    raster.ad.reverse
    ;; compiler/: pipeline, backends that ship (JVM/bytecode, WASM, WGSL,
    ;; C-SIMD/C-emit, native-C toolchain, TC extensions).
    raster.compiler.pipeline
    raster.compiler.backend.jvm.typed-scalar
    raster.compiler.cljs-emit
    raster.compiler.backend.wasm.emit
    raster.compiler.backend.gpu.wgsl
    raster.compiler.backend.cpu.csimd
    raster.compiler.backend.gpu.c-emit
    raster.compiler.backend.cpu.quant
    raster.compiler.backend.cpu.codegen
    raster.compiler.backend.cpu.aot
    raster.compiler.core.tc-extensions
    ;; ode/: solvers + AMR companions (execution/lowering/state stay OUT: GPU refs).
    raster.ode.core
    raster.ode.pde
    raster.ode.sde
    raster.ode.amr-transfer
    raster.ode.finite-volume
    raster.ode.multilevel
    ;; linalg/: LAPACK-via-Panama BLAS/LAPACK/core plus pure factorizations.
    raster.linalg.blas
    raster.linalg.lapack
    raster.linalg.core
    raster.linalg.sparse
    raster.linalg.contract
    raster.linalg.eigen
    raster.linalg.iterative
    raster.linalg.pca
    raster.linalg.qr
    raster.linalg.svd
    ;; sci/: roots/optim/quadrature/interpolation/fft/distributions/stats.
    raster.sci.roots
    raster.sci.optim
    raster.sci.quadrature
    raster.sci.interpolation
    raster.sci.fft
    raster.sci.distributions
    raster.sci.stats
    ;; dl/: losses, diffusion, array primitives, attention, einsum.
    raster.dl.loss
    raster.dl.diffusion
    raster.dl.array-ops
    raster.dl.gsdm
    raster.dl.einsum
    ;; nn: dense-layer library (examples/notebooks).
    raster.nn
    ;; sym/: function algebra + Taylor series.
    raster.sym.fn-algebra
    raster.sym.taylor
    ;; ga/: multivector core arrives via types.complex; compile ships too
    ;; (needs a Valhalla-capable JVM at use time, not at load).
    raster.ga.compile
    ;; types/: complex/int8/number-types/traits (algebraic-types/promote
    ;; arrive transitively).
    raster.types.complex
    raster.types.int8
    raster.types.number-types
    raster.types.traits
    ;; Supporting surface: noise, runtime probes, kNN, inspect, params, WebGPU.
    raster.noise
    raster.runtime.hardware
    raster.runtime.callsites
    raster.spatial.nndescent
    raster.tooling.inspect
    raster.params
    raster.render.webgpu])

(def ^:private excluded-family-prefixes
  "Namespace prefixes the lean closure must never reach: the compute GPU stack
  (Level Zero/OpenCL/CUDA runtimes and emitters) and the native-C backend —
  separate toolchains, reached only through lazy device-gated branches."
  ["raster.gpu."
   "raster.compiler.backend.gpu."
   "raster.compiler.backend.cpu."])

(def ^:private excluded-family-exceptions
  "Deliberately-shipped members of an otherwise-excluded family.

  `backend.gpu.wgsl` is a public pipeline entry (WebGPU browser compute) whose dependencies
  are all on the shipped middle end, so `compile-wgsl` must resolve in a lean deployment.
  `backend.cpu.csimd` + `backend.gpu.c-emit` are pure source emitters over the same
  middle end (no native toolchain at load); they ship so backend-agnostic refusal
  tests execute rather than skip on lean deployments. `backend.cpu.quant` +
  `backend.cpu.codegen` are the same class (quantization formats + shared C
  backend, pure data and source emission) for the csimd quantization tests.
  The compute runtimes/emitters stay excluded, except the completed backend.cpu family."
  #{'raster.compiler.backend.gpu.wgsl
    'raster.compiler.backend.cpu.csimd
    'raster.compiler.backend.gpu.c-emit
    'raster.compiler.backend.cpu.quant
    'raster.compiler.backend.cpu.codegen
    'raster.compiler.backend.cpu.aot})

(defn- excluded-ns? [ns-sym]
  (let [n (str ns-sym)]
    (and (not (contains? excluded-family-exceptions ns-sym))
         (boolean (some #(str/starts-with? n %) excluded-family-prefixes)))))

(defn- clj-source? [file]
  (re-find #"\.clj[cs]?$" (.getName ^java.io.File file)))

(defn- source-file-map []
  (into {}
        (map (fn [^java.io.File file]
               (let [rel (-> (.getPath file) (str/replace #"^src/" "")
                             (str/replace #"\.clj[cs]?$" "")
                             (str/replace "_" "-")
                             (str/replace "/" "."))]
                 [(symbol rel) file])))
        (filter clj-source? (file-seq (io/file "src")))))

(defn- expand-libspec
  "One `:require` libspec -> namespace symbol(s).

  Libspec grammar handled:
    sym                       -> sym
    [ns]                      -> ns                     (single-element vector, no options)
    [ns :as a :refer [...]]   -> ns
    [prefix child1 child2]    -> prefix.child1 prefix.child2   (prefix list)
  The single-element `[ns]` case is NOT a prefix list with zero children — conflating the two
  silently dropped requires like `[raster.dl.nn]`, and a closure derived from a lossy parser is
  a jar that fails to load."
  [spec]
  (cond
    (symbol? spec) [spec]
    (and (sequential? spec) (symbol? (first spec)))
    (cond
      (keyword? (second spec)) [(first spec)]
      (empty? (rest spec)) [(first spec)]
      (every? symbol? (rest spec))
      (mapv #(symbol (str (first spec) "." %)) (rest spec))
      :else [(first spec)])
    :else []))

(defn- require-specs
  "Namespace symbols named by the `:require` clauses of an ns form."
  [ns-form]
  (into #{}
        (comp (filter #(and (seq? %) (= :require (first %))))
              (mapcat rest)
              (mapcat expand-libspec))
        (drop 2 ns-form)))

(defn- lazy-require-targets
  "Namespace symbols referenced LAZILY by a source file: `(requiring-resolve 'ns/name)` and
  `(resolve 'ns/name)`. See `file-edges` for why this is a textual scan rather than a form
  walk. Targets in the excluded families are dropped here: they are device-gated lazy
  paths that a lean deployment cannot run by design (`:target :c`, `:target-device`,
  GPU session entry)."
  [^java.io.File file]
  (let [content (slurp file)]
    (into #{}
          (comp
           (map second)
           (remove nil?)
           (map symbol)
           (remove excluded-ns?))
          (concat (re-seq #"requiring-resolve\s+'([A-Za-z0-9._!?-]+)" content)
                  (re-seq #"\(\s*resolve\s+'([A-Za-z0-9._!?-]+)" content)))))

(defn- file-edges
  "ns-symbol -> set of ns-symbols one source file requires: static `:require` edges
  from its ns form plus lazy `requiring-resolve`/`resolve` edges."
  [[ns-sym ^java.io.File file]]
  (let [form (try
               (with-open [r (java.io.PushbackReader. (io/reader file))]
                 (binding [*read-eval* false]
                   (read {:eof nil :read-cond :allow :features #{:clj}} r)))
               (catch Exception _ nil))
        static (if (and (seq? form) (= 'ns (first form)))
                 (require-specs form)
                 #{})]
    [ns-sym (into static (lazy-require-targets file))]))

(defn- require-graph
  "ns-symbol -> set of ns-symbols it requires."
  []
  (into {} (map file-edges) (source-file-map)))

(defn- transitive-closure
  "Breadth-first closure of `roots` over the static require graph."
  [graph roots]
  (loop [seen #{} queue (vec roots)]
    (if (empty? queue)
      seen
      (let [n (peek queue)
            queue (pop queue)]
        (if (contains? seen n)
          (recur seen queue)
          (recur (conj seen n)
                 (into queue (get graph n #{}))))))))

(defn lean-closure []
  "The set of namespaces a lean deployment can load: the transitive static-require closure
  of `lean-entry-namespaces`."
  []
  (let [graph (require-graph)]
    (transitive-closure graph lean-entry-namespaces)))

(defn lean-closure-gaps!
  "Fail loud when the lean closure is not lean-shaped.

  Two invariants, both checkable from the same graph the closure came from:
    1. no compute-GPU/native-C namespace is reachable;
    2. no namespace in the closure still statically requires one (a new static GPU require in
       the middle end would otherwise silently re-inflate the jar)."
  [closure]
  (let [graph (require-graph)
        reachable-gpu (sort (filter excluded-ns? closure))
        leaking (into (sorted-map)
                      (keep (fn [[ns-sym requires]]
                              (let [cross (seq (filter excluded-ns? requires))]
                                (when cross [ns-sym (sort cross)]))))
                      (select-keys graph closure))]
    (when (seq reachable-gpu)
      (throw (ex-info (str "lean closure reached excluded namespaces: " (pr-str reachable-gpu))
                      {:reason :lean-closure-reaches-excluded :namespaces reachable-gpu})))
    (when (seq leaking)
      (throw (ex-info (str "lean closure namespaces still statically require excluded backends: "
                           (pr-str (into {} leaking)))
                      {:reason :lean-closure-static-excluded-require :edges leaking})))
    closure))

(defn lean-source-files
  "Relative paths (under src) of the files in the validated lean closure.

  The closure is intersected with the source-file map: a namespace in the closure with no
  source file under src is a foreign library (clojure.*, org.typedclojure.*, …) which is a
  dependency of the jar, not a file to copy."
  []
  (let [files (source-file-map)]
    (->> (lean-closure-gaps! (lean-closure))
         (keep files)
         (map #(-> ^java.io.File % (.getPath) (str/replace #"^src/" "")))
         (sort)
         vec)))

(defn- compiler-build-manifest
  "Compiler build identity for a packaged artifact. `source-namespaces` MUST describe the
  namespaces actually in the jar — a manifest that claims more than it ships makes the cached
  source-dependency manifest look complete when it is not."
  ([] (compiler-build-manifest (packaged-source-namespaces)))
  ([source-namespaces]
   {:schema-version 2
    :library lib
    :version version
    :revision current-commit
    :runtime {:java-version (System/getProperty "java.version")
              :clojure-version (clojure-version)}
    :source-namespaces source-namespaces
    :dependencies (into (sorted-map) (map dependency-coordinate) (:libs basis))}))

(defn- write-compiler-build-manifest!
  ([] (write-compiler-build-manifest! (packaged-source-namespaces)))
  ([source-namespaces]
   (let [file (io/file class-dir "raster" "compiler-build.edn")]
     (io/make-parents file)
     (spit file (str (pr-str (compiler-build-manifest source-namespaces)) "\n")))))

(defn- copy-selected-sources!
  "Copy exactly `paths` (relative to src) into the class dir."
  [paths]
  (b/delete {:path class-dir})
  (doseq [rel paths]
    (let [src (io/file "src" rel)
          dst (io/file class-dir rel)]
      (io/make-parents dst)
      (io/copy src dst))))

(defn clean
  [_]
  (b/delete {:path "target"}))

(defn jar
  [_]
  (b/write-pom {:class-dir class-dir
                :src-pom "./template/pom.xml"
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]})
  (b/copy-dir {:src-dirs ["src"]
               :target-dir class-dir})
  (write-compiler-build-manifest!)
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn lean-report
  "Print the lean entry namespaces, per-namespace requires and the resulting file set.
  Read-only — writes nothing."
  [_]
  (let [closure (lean-closure)
        files (lean-source-files)]
    (println "lean entries:" (pr-str lean-entry-namespaces))
    (println "lean namespaces:" (count closure))
    (println "lean source files:" (count files))
    (doseq [[ns-sym requires] (sort-by (comp str key) (require-graph))
            :when (contains? closure ns-sym)
            :when (seq requires)]
      (println " " ns-sym "->" (pr-str (sort-by str requires))))
    files))

(def lean-jar-file "target/raster-lean.jar")

(defn lean-jar
  "Build the lean jar: the transitive static-require closure of `lean-entry-namespaces` — the JVM/bytecode
  path, WASM emission + the cljs↔wasm build half, and the WebGPU (WGSL) compute emitter —
  with the compiler-build manifest naming exactly those namespaces.

  The desktop Vulkan renderer (raster.vk.* + raster.render.vulkan), the compute GPU stack
  (Level Zero/OpenCL/CUDA runtimes and emitters) and the native-C backend stay out because
  they are reached only through requiring-resolve behind device-gated branches;
  `lean-closure-gaps!` asserts that instead of trusting it."
  [_]
  (let [paths (lean-source-files)
        namespaces (->> paths
                        (map #(-> % (str/replace #"\.clj[cs]?$" "")
                                  (str/replace "_" "-")
                                  (str/replace "/" ".")))
                        (map symbol)
                        (sort-by str)
                        vec)]
    (println "lean jar:" (count paths) "source files, " (count namespaces) "namespaces")
    (b/write-pom {:class-dir class-dir
                  :src-pom "./template/pom.xml"
                  :lib lib
                  :version version
                  :basis basis
                  :src-dirs ["src"]})
    (copy-selected-sources! paths)
    (write-compiler-build-manifest! namespaces)
    (let [versioned (str (str/replace jar-file #"\.jar$" "-lean.jar"))]
      (b/jar {:class-dir class-dir
              :jar-file versioned})
      ;; Stable path for the `:lean` deps.edn alias, which cannot know the
      ;; git-revision-derived version in the filename.
      (io/copy (io/file versioned) (io/file lean-jar-file))
      (println "wrote" versioned "and" lean-jar-file))))

(defn deploy
  "Don't forget to set CLOJARS_USERNAME and CLOJARS_PASSWORD env vars."
  [_]
  (dd/deploy {:installer :remote :artifact jar-file
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))

(defn fib [a b]
  (lazy-seq (cons a (fib b (+ a b)))))

(defn retry-with-fib-backoff [retries exec-fn test-fn]
  (loop [idle-times (take retries (fib 1 2))]
    (let [result (exec-fn)]
      (if (test-fn result)
        (do (println "Returned: " result)
            (if-let [sleep-ms (first idle-times)]
              (do (println "Retrying with remaining back-off times (in s): " idle-times)
                  (Thread/sleep (* 1000 sleep-ms))
                  (recur (rest idle-times)))
              result))
        result))))

(defn try-release []
  (try (gh/overwrite-asset {:org org
                            :repo (name lib)
                            :tag version
                            :commit current-commit
                            :file jar-file
                            :content-type "application/java-archive"
                            :draft false})
       (catch ExceptionInfo e
         (assoc (ex-data e) :failure? true))))

(defn release
  [_]
  (println "Trying to release artifact...")
  (let [ret (retry-with-fib-backoff 10 try-release :failure?)]
    (if (:failure? ret)
      (do (println "GitHub release failed!")
          (System/exit 1))
      (println (:url ret)))))

(defn install
  [_]
  (clean nil)
  (jar nil)
  (b/install {:basis (b/create-basis {})
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))
