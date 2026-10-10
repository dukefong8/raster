(ns build
  "Build tasks for raster.

  Usage:
    clj -T:build clean
    clj -T:build jar          ; full source tree (GPU, Vulkan, ABM, ODE, …)
    clj -T:build lean-jar     ; JVM/bytecode + WASM + Vulkan/WebGPU — the showcase-game surface
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
;; The lean jar ships the JVM/bytecode surface plus the two cross-platform
;; verticals that the showcase games are built on:
;;
;;   * wasm emission + the cljs↔wasm build half (raster.compiler.cljs-emit) —
;;     Asteroids and Valley compile their deftm kernels to WebAssembly for the
;;     browser build;
;;   * the data-oriented Vulkan wrapper raster.vk.* + raster.render.vulkan —
;;     the desktop renderer both games run on.
;;
;; It is DERIVED from the static `:require` graph rather than hand-listed, because a
;; hand-maintained file list is the thing that silently drifts the moment a new require appears.
;;
;; The compute GPU stack (raster.gpu.* runtime, backend.gpu.* OpenCL/Level-Zero/WGSL emitters,
;; backend.cpu.* native-C backend) is reached only through `requiring-resolve` behind
;; device-gated branches, so it stays OUT of this closure by construction — that property is
;; asserted, not assumed (see `lean-closure-gaps!`).

(def lean-entry-namespaces
  "Roots that define the packaged user surface: the JVM/bytecode path, WASM emission, the
  cljs↔wasm build half, and the Vulkan renderer (desktop backend for raster.render)."
  '[raster.core
    raster.arrays
    raster.par
    raster.ad
    raster.ad.reverse
    raster.linalg.blas
    raster.linalg.lapack
    raster.linalg.core
    raster.noise
    raster.runtime.hardware
    raster.runtime.callsites
    raster.compiler.pipeline
    ;; JVM backend family: the bytecode/Vector-API backends arrive through pipeline's pass
    ;; references; the typed-scalar reference runner has no static consumer on the lean path
    ;; (it is reached via compiler.equation-first, a GPU-side namespace), so name it explicitly.
    raster.compiler.backend.jvm.typed-scalar
    raster.compiler.cljs-emit
    raster.compiler.backend.wasm.emit
    ;; WebGPU compute emitter — the browser sibling of the Vulkan renderer. It is a member of
    ;; the otherwise-excluded backend.gpu family (see `excluded-family-exceptions`), shipped so
    ;; the public `compile-wgsl` entry resolves in a lean deployment.
    raster.compiler.backend.gpu.wgsl
    ;; The compiler's effect-classification pass consults raster.dl.loss at load time to
    ;; register the pure forward loss reductions (mse/cross-entropy/huber/l1). Shipping it keeps
    ;; those registrations — and their DCE benefit — available, and silences the warn-only
    ;; fallback a lean deployment would otherwise print on the first compile.
    raster.dl.loss
    raster.tooling.inspect
    raster.params
    raster.render.vulkan
    ;; Browser WebGPU backend (.cljs) — the cljs counterpart of render.vulkan. It ships so the
    ;; renderer verticals travel together in the lean jar; its only external dep is
    ;; applied-science/js-interop (a cljs library, not a raster source file).
    raster.render.webgpu])

(def ^:private excluded-family-re
  "Namespaces the lean closure must never reach: the compute GPU stack (Level Zero/OpenCL/
  CUDA runtimes and emitters) and the native-C backend — separate toolchains, reached only
  through lazy device-gated branches."
  #"^(raster\.gpu\.|raster\.compiler\.backend\.gpu\.|raster\.compiler\.backend\.cpu\.)")

(def ^:private excluded-family-exceptions
  "Deliberately-shipped members of an otherwise-excluded family.

  `backend.gpu.wgsl` is a public pipeline entry (WebGPU, the browser sibling of the Vulkan
  renderer) whose dependencies are all on the shipped middle end, so `compile-wgsl` must
  resolve in a lean deployment. The compute runtimes/emitters stay excluded."
  #{'raster.compiler.backend.gpu.wgsl})

(defn- excluded-ns? [ns-sym]
  (and (not (contains? excluded-family-exceptions ns-sym))
       (boolean (re-find excluded-family-re (str ns-sym)))))

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

(defn- require-specs
  "Namespace symbols named by the `:require` clauses of an ns form.

  Libspec grammar handled:
    sym                       -> sym
    [ns]                      -> ns                     (single-element vector, no options)
    [ns :as a :refer [...]]   -> ns
    [prefix child1 child2]    -> prefix.child1 prefix.child2   (prefix list)
  The single-element `[ns]` case is NOT a prefix list with zero children — conflating the two
  silently dropped requires like `[raster.dl.nn]`, and a closure derived from a lossy parser is
  a jar that fails to load."
  [ns-form]
  (let [clauses (drop 2 ns-form)]
    (into #{}
          (comp (filter #(and (seq? %) (= :require (first %))))
                (mapcat rest)
                (mapcat (fn [spec]
                          (cond
                            (symbol? spec) [spec]
                            (and (sequential? spec) (symbol? (first spec)))
                            (cond
                              (keyword? (second spec)) [(first spec)]
                              (empty? (rest spec)) [(first spec)]
                              (every? symbol? (rest spec))
                              (mapv #(symbol (str (first spec) "." %)) (rest spec))
                              :else [(first spec)])
                            :else []))))
          clauses)))

(defn- lazy-require-targets
  "Namespace symbols referenced LAZILY by a source file: `(requiring-resolve 'ns/name)` and
  `(resolve 'ns/name)`.

  These are load-time-invisible edges — a static `:require` closure cannot see them, and the
  JVM path depends on some (e.g. `compiler.core.closure` -> `passes.scalar.validate-types`, hit
  by every `hoist-and-compile`). A textual scan is deliberate: several files contain
  syntax-quoted aliased symbols that a full-form `read` cannot parse in a foreign `*ns*`
  (e.g. `raster/core.clj`), and a scan must not silently drop those files' edges.

  Targets in the excluded families are dropped here: they are device-gated lazy paths that a
  lean deployment cannot run by design (`:target :c`, `:target-device`, GPU session entry)."
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

(defn- require-graph
  "ns-symbol -> set of ns-symbols it requires: static `:require` edges plus lazy
  `requiring-resolve`/`resolve` edges (only those with a source file, for the static half)."
  []
  (into {}
        (map (fn [[ns-sym ^java.io.File file]]
               (let [form (try
                            (with-open [r (java.io.PushbackReader. (io/reader file))]
                              (binding [*read-eval* false]
                                (read {:eof nil :read-cond :allow :features #{:clj}} r)))
                            (catch Exception _ nil))
                     static (if (and (seq? form) (= 'ns (first form)))
                              (require-specs form)
                              #{})
                     lazy (lazy-require-targets file)]
                 [ns-sym (into static lazy)])))
        (source-file-map)))

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

(defn lean-roots
  "`lean-entry-namespaces` plus every `raster.vk.*` namespace in the source tree.

  The vk wrapper is included as a FAMILY (the whole data-oriented LWJGL layer), not just the
  subset raster.render.vulkan happens to require, so a new vk ns is packaged automatically."
  []
  (let [graph (require-graph)]
    (concat lean-entry-namespaces
            (sort-by str (filter #(re-find #"^raster\.vk\." (str %)) (keys graph))))))

(defn lean-closure []
  "The set of namespaces a lean deployment can load: the transitive static-require closure
  of `lean-roots`."
  []
  (let [graph (require-graph)]
    (transitive-closure graph (lean-roots))))

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
  "Print the derived lean closure: the vk family roots, entry namespaces, per-namespace requires
  and the resulting file set. Read-only — writes nothing."
  [_]
  (let [closure (lean-closure)
        files (lean-source-files)
        roots (lean-roots)]
    (println "lean roots:" (count roots) (pr-str (remove (set lean-entry-namespaces) roots)))
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
  "Build the lean jar: the transitive static-require closure of `lean-roots` — the JVM/bytecode
  path, WASM emission + the cljs↔wasm build half, the raster.vk.* Vulkan renderer, and the
  WebGPU (WGSL) compute emitter — with the compiler-build manifest naming exactly those
  namespaces.

  The compute GPU stack (Level Zero/OpenCL/CUDA runtimes and emitters) and the native-C backend
  stay out because they are reached only through requiring-resolve behind device-gated branches;
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
      ;; Stable path for the `:lean` deps.edn alias and the Valley uberjar build —
      ;; neither can know the git-revision-derived version in the filename.
      (io/copy (io/file versioned) (io/file lean-jar-file))
      (println "wrote" versioned "and" lean-jar-file))))

(defn- ensure-lean-jar!
  "Refresh `lean-jar-file` when absent; the Valley uberjar packages raster from it."
  []
  (if (.exists (io/file lean-jar-file))
    (println "using" lean-jar-file)
    (do (println lean-jar-file "missing — building it first…")
        (lean-jar nil))))

;; ================================================================
;; Valley AOT uberjar
;; ================================================================
;; `valley-uber` builds target/valley.jar: the Valley game AOT-compiled together with
;; every dependency (including the LWJGL natives, so the jar is self-contained), plus a
;; generated entry namespace `valley-aot-main`. On macOS the entry re-execs itself with
;; -XstartOnFirstThread (GLFW needs the process's first thread), so the plain
;; `java -jar target/valley.jar` command works there too.

(def valley-jar-file "target/valley.jar")
(def valley-class-dir "target/valley-classes")
(def valley-aot-src "target/valley-aot-src")

(def valley-aot-targets
  "Namespaces compiled to class files in the jar.

  AOT approach: the compile subprocess first `require`s the WHOLE tree with compilation
  off, then `(compile n)`s only these targets. Clojure's compile re-loads the target file,
  but its requires are already satisfied, so no dependency is dragged into the class files.

  valley.core and valley.walk are loaded from source but deliberately never compiled:
  deftm keeps each walked body in an in-memory `walked-body-registry` populated at
  MACRO-EXPANSION time, and the first-call JIT upgrade re-reads it. A compiled class fills
  the registry in the build JVM only, so a fresh runtime JVM would find it empty and the
  bytecode upgrade would fail (\"void body for primitive-returning method\"). Loading them
  from source in the jar re-runs macroexpansion at startup, which repopulates it."
  '[valley.kernels valley.chunk valley.cam valley.tex valley.sky valley.swarm
    valley.ui valley.water valley.shell valley.vk-walk valley-aot-main])

(def ^:private valley-source-only
  "Loaded from source in the jar — see `valley-aot-targets` for why."
  '[valley.core valley.walk])

(def ^:private valley-main-source
  "(ns valley-aot-main
  \"AOT jar entry for Valley (generated by build/valley-uber).\"
  (:gen-class :main true))

(defn- jar-mode?
  ;; plain `java -jar valley.jar`: classpath is exactly the jar, no separators
  []
  (let [cp (System/getProperty \"java.class.path\")]
    (and (.endsWith cp \".jar\")
         (not (.contains cp (java.io.File/pathSeparator))))))

(defn- mac? []
  (.contains (System/getProperty \"os.name\") \"Mac\"))

(defn -main [& args]
  (if (and (mac?)
           (jar-mode?)
           (nil? (System/getenv \"VALLEY_FIRST_THREAD\")))
    ;; GLFW needs the process's first thread on macOS: re-exec once with the flag
    (let [java (str (System/getProperty \"java.home\") \"/bin/java\")
          pb (doto (ProcessBuilder.
                    (into-array
                     (concat [java \"--add-modules=jdk.incubator.vector\"
                              \"-Xss8m\" \"-XstartOnFirstThread\"
                              \"-jar\" (System/getProperty \"java.class.path\")]
                             args)))
                 (.inheritIO))]
      (.put (.environment pb) \"VALLEY_FIRST_THREAD\" \"1\")
      (System/exit (.waitFor (.start pb))))
    (apply (requiring-resolve 'valley.vk-walk/-main) args)))
")

(defn- write-valley-main! []
  (let [file (io/file valley-aot-src "valley_aot_main.clj")]
    (io/make-parents file)
    (spit file valley-main-source)))

(def aot-explain-file
  "Stage-by-stage explain-pipeline report for every Valley deftm kernel, written by
  `valley-uber` (via `explain-kernels-form`)."
  "target/aot-explain.txt")

(defn- explain-kernels-form
  "Quoted form run inside the AOT subprocess: after the game is loaded from source (which
  populates the deftm registry) and before anything is compiled, write a stage-by-stage
  `explain-pipeline` report for every deftm kernel to `aot-explain-file`.

  The kernels are found by their dispatch-table metadata, so the report follows the code:
  add a deftm and it appears; a kernel that cannot be explained is recorded in the report
  rather than failing the build."
  []
  `(let [kernels# (->> '~valley-source-only
                       (mapcat (comp vals ns-interns the-ns))
                       (filter (fn [v#] (:raster.core/dispatch-table (meta v#))))
                       (sort-by str))
         runs# (mapv (fn [v#]
                       (try
                         [v# (with-out-str
                               ((requiring-resolve 'raster.compiler.pipeline/explain-pipeline)
                                v#))
                          nil]
                         (catch Throwable e#
                           [v# nil (.getMessage e#)])))
                     kernels#)]
     (when (empty? kernels#)
       (throw (ex-info "no Valley deftm kernels found — the explain report would be empty"
                       {:namespaces '~valley-source-only})))
     (spit ~aot-explain-file
           (apply str (interpose "\n\n"
                                 (map (fn [[v# text# err#]]
                                        (str "=== " v# " ===\n"
                                             (or text# (str "(explain failed: " err# ")"))))
                                      runs#))))
     (println "explain:" (count kernels#) "kernels →" ~aot-explain-file)
     (doseq [[v# ~'_ err#] runs# :when err#]
       (println "  explain FAILED:" (str v#) "-" err#))))

(defn- aot-valley!
  "Compile `valley-aot-targets` into `valley-class-dir` via a subprocess on the
  valley-aware classpath: load everything from source first (compilation off), then
  compile only the targets — see `valley-aot-targets`. raster comes from the lean jar,
  so the AOT pass also checks that the lean closure genuinely covers the game.
  Before compiling, the subprocess writes the per-kernel explain-pipeline report
  (`aot-explain-file`)."
  []
  (let [script (pr-str `(do
                          (require ~@(map (fn [s] (list 'quote s))
                                          (concat valley-source-only valley-aot-targets)))
                          ~(explain-kernels-form)
                          (binding [*compile-path* ~(str valley-class-dir)]
                            (doseq [~'n '~valley-aot-targets]
                              (compile ~'n)))
                          (println "aot: compiled"
                                   ~(count valley-aot-targets) "namespaces")))
        {:keys [exit]} (b/process {:command-args ["clojure" "-J-Xmx4g"
                                                  "-Sdeps" (str "{:paths [\"examples\""
                                                                " \"target/valley-aot-src\""
                                                                " \"target/valley-classes\"]"
                                                                " :deps {raster/raster"
                                                                " {:local/root \"" lean-jar-file "\"}}}")
                                                  "-M:vulkan:vulkan-macos-arm64:valhalla"
                                                  "-e" script]})]
    (when-not (zero? exit)
      (throw (ex-info (str "valley AOT compile failed (exit " exit ")")
                      {:exit exit})))))

(defn valley-uber
  "Build target/valley.jar: AOT-compiled Valley + all deps (LWJGL natives included),
  entry valley-aot-main. raster itself is packaged from `lean-jar-file` — the jar does
  not contain the compute-GPU/ABM/ODE/… source outside the lean closure.
  Also writes `aot-explain-file`: the explain-pipeline lowering report for every
  Valley deftm kernel.
  Run:  java -jar target/valley.jar"
  [_]
  (ensure-lean-jar!)
  (b/delete {:path valley-class-dir})
  (b/delete {:path valley-aot-src})
  (write-valley-main!)
  (let [basis (b/create-basis {:project "deps.edn"
                               :aliases [:vulkan :vulkan-macos-arm64 :valhalla]
                               :extra {:paths ["examples" lean-jar-file
                                               valley-aot-src valley-class-dir]}})]
    (when (some #(= "src" (str %)) (:paths basis))
      (throw (ex-info "valley uber basis still lists src — :extra :paths must replace it"
                      {:paths (:paths basis)})))
    (b/copy-dir {:src-dirs ["examples" valley-aot-src]
                 :target-dir valley-class-dir})
    ;; `examples/` is a classpath root, so its sibling projects land at the top level of the
    ;; class dir — examples/raster/** even lands inside the `raster` package (a latent
    ;; shadowing hazard for real sources). Only examples/valley belongs in this jar; drop
    ;; the rest BEFORE the lean jar is unpacked.
    (doseq [d ["asteroids" "doom" "raster" "reaction_diffusion"]]
      (b/delete {:path (str valley-class-dir "/" d)}))
    ;; raster sources come from the lean jar, not from src/
    (b/unzip {:zip-file lean-jar-file
              :target-dir valley-class-dir})
    (aot-valley!)
    (b/uber {:class-dir valley-class-dir
             :basis basis
             :uber-file valley-jar-file
             :main 'valley-aot-main}))
  (println "wrote" valley-jar-file)
  (println "run:  java -jar" valley-jar-file))

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
