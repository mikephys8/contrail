(ns tracer.core
  (:require [richelieu.core :as richelieu]
            [clojure.pprint :as pprint]))

(def ^:dynamic *trace-level*
  "Within a `report-before-fn` or `report-after-fn`, `*trace-level*`
  will be bound to the number of traced functions currently on the
  stack, excluding the function being reported upon."
  0)

(def ^:dynamic *trace-indent-per-level*
  "When using the default reporting functions, this var controls the
  number of spaces to indent per level of nested trace
  output. Defaults to 2."
  2)

(def ^:dynamic *force-eager-evaluation*
  "When true (the default), return values of traced functions are
  always realized immediately to ensure that trace output is printed
  in logical order and that `*trace-level*` is always bound to the
  logically-correct value."
  true)

(defn- trace-indent []
  (* *trace-indent-per-level* *trace-level*))

(defonce ^:private traced-vars (atom '{}))

(defn traced?
  "Returns true if the function bound to var `f` is traced, otherwise
  returns false."
  [f]
  (boolean (some #{f} (keys @traced-vars))))

(defn untrace
  "When called with no arguments, untraces all traced functions. When
  passed a var `f`, untraces the function bound to that var."
  ([]
   (doseq [[f _] @traced-vars]
     (untrace f)))
  ([f]
   (richelieu/unadvise-var f (@traced-vars f))
   (println "Untracing" f)
   (swap! traced-vars dissoc f)))

(defn untrace-ns [ns]
  "Untraces every traced var in the namespace `ns`"
  (doseq [traced-fn (filter #(= (:ns (meta %)) ns) @traced-vars)]
    (untrace traced-fn)))

(defn report-before
  "Prints a nicely-formatted list of the currently-traced function
  with its `args`, indented based on the current `*trace-level*`"
  [args]
  (pprint/cl-format true "~&~vt~d: (~s ~{~s~^ ~})~%" (trace-indent) *trace-level* richelieu/*current-advised* args))

(defn report-after
  "Prints a nicely-formatted list of the currently-traced function
  with its `retval`, indented based on the current `*trace-level*`."
  [retval]
  (pprint/cl-format true "~&~vt~d: ~s returned ~s~%" (trace-indent) *trace-level* richelieu/*current-advised* retval))

(defn- maybe-force-eager-evaluation [thunk]
  (if (and *force-eager-evaluation* (seq? thunk))
    (doall thunk)
    thunk))

(defn- get-wrapped-fn [f when-fn report-before-fn report-after-fn]
  (let [report-before-fn (or report-before-fn report-before)
        report-after-fn (or report-after-fn report-after)
        trace-report-fn (fn [f & args]
                          (report-before-fn args)
                          (let [retval (binding [*trace-level* (inc *trace-level*)]
                                         (maybe-force-eager-evaluation (apply f args)))]
                            (report-after-fn retval)
                            retval))]
    (if when-fn
      (fn [f & args]
        (if (apply when-fn args)
          (apply trace-report-fn f args)
          (apply f args)))
      trace-report-fn)))

(defn trace
  "Turns on tracing for the function bound to F, a var.

  If `when-fn` is provided, the trace reporting described below will
  only occur when `when-fn` returns a truthy value when called with
  the same args as the traced functions. If `when-fn` is not provided,
  every call to the traced function will be reported.

  If `report-before-fn` is provided, it will be called before the
  traced function is called, with the same arguments as the traced
  function, and should print some useful output. It defaults to
  `tracer.core/report-before` if not provided.

  If `report-after-fn` is provided, it will be called after the traced
  function is called, with that function's return value as its
  argument, and should print some useful output. It defaults to
  `trace.core/report-after` if not provided."
  [f & {:keys [when-fn report-before-fn report-after-fn]}]
  {:pre [(var? f)
         (fn? @f)]}
  (when (traced? f)
    (println f "already traced, untracing first.")
    (untrace f))
  (let [advice-fn (get-wrapped-fn f when-fn report-before-fn report-after-fn)]
    (richelieu/advise-var f advice-fn)
    (swap! traced-vars assoc f advice-fn)))
