(ns leiningen.compile
  "Compile the namespaces listed in project.clj or all namespaces in src."
  (:require lancet)
  (:use  [leiningen.deps :only [deps]]
         [leiningen.core :only [ns->path]]
         [leiningen.classpath :only [make-path find-lib-jars get-classpath]]
         [clojure.java.io :only [file]]
         [leiningen.util.ns :only [namespaces-in-dir]])
  (:refer-clojure :exclude [compile])
  (:import (org.apache.tools.ant.taskdefs Java)
           (java.lang.management ManagementFactory)
           java.util.regex.Pattern
           (org.apache.tools.ant.types Environment$Variable)))

(declare compile)

(def *silently* false)

(def *suppress-err* false)

(defn- regex?
  "Returns true if we have regex class"
  [str-or-re]
  (instance? java.util.regex.Pattern str-or-re))

(defn- separate
  "copy of separate function from c.c.seq-utils"
  [f s]
  [(filter f s) (filter (complement f) s) ])

(defn- find-namespaces-by-regex
  "Trying to generate list of namespaces, matching to given regexs"
  [project nses]
  (let [[res syms] (separate regex? nses)]
    (if (seq res)
      (set (for [re res n (namespaces-in-dir (:source-path project))
                 :when (re-find re (name n))]
             n))
      nses)))

(defn- compilable-namespaces
  "Returns a seq of the namespaces that are compilable, regardless of whether
  their class files are present and up-to-date."
  [project]
  (let [nses (or (:aot project) (:namespaces project))
        nses (if (= :all nses)
               (namespaces-in-dir (:source-path project))
               (find-namespaces-by-regex project nses))]
    (if (:main project)
      (conj nses (:main project))
      nses)))

(defn- stale-namespaces
  "Return a seq of namespaces that are both compilable and that have missing or
  out-of-date class files."
  [project]
  (filter
   (fn [n]
     (let [clj-path (ns->path n)
           class-file (file (:compile-path project)
                            (.replace clj-path "\\.clj" "__init.class"))]
       (or (not (.exists class-file))
           (> (.lastModified (file (:source-path project) clj-path))
              (.lastModified class-file)))))
   (compilable-namespaces project)))

(defn- get-by-pattern
  "Gets a value from map m, but uses the keys as regex patterns, trying
   to match against k instead of doing an exact match."
  [m k]
  (m (first (drop-while #(nil? (re-find (re-pattern %) k))
                        (keys m)))))

(def ^:private native-names
     {"Mac OS X" :macosx
      "Windows" :windows
      "Linux" :linux
      "FreeBSD" :freebsd
      "SunOS" :solaris
      "OpenBSD" :openbsd
      "amd64" :x86_64
      "x86_64" :x86_64
      "x86" :x86
      "i386" :x86
      "arm" :arm
      "sparc" :sparc})

(defn- get-os
  "Returns a keyword naming the host OS."
  []
  (get-by-pattern native-names (System/getProperty "os.name")))

(defn- get-arch
  "Returns a keyword naming the host architecture"
  []
  (get-by-pattern native-names (System/getProperty "os.arch")))

(defn- find-native-lib-path
  "Returns a File representing the directory where native libs for the
  current platform are located."
  [project]
  (when (and (get-os) (get-arch))
    (let [osdir (name (get-os))
          archdir (name (get-arch))
          f (file "native" osdir archdir)]
      (if (.exists f)
        f
        nil))))

(defn- get-jvm-args
  [project]
  (concat (.getInputArguments (ManagementFactory/getRuntimeMXBean))
          (:jvm-opts project)))

(defn- get-readable-form [java project form]
  (let [cp (str (.getClasspath (.getCommandLine java)))
        form `(do (def ~'*classpath* ~cp)
                  (set! ~'*warn-on-reflection*
                        ~(:warn-on-reflection project))
                  ~form)]
    ;; work around java's command line handling on windows
    ;; http://bit.ly/9c6biv This isn't perfect, but works for what's
    ;; currently being passed; see
    ;; http://www.perlmonks.org/?node_id=300286 for some of the
    ;; landmines involved in doing it properly
    (if (= (get-os) :windows)
      (pr-str (pr-str form))
      (prn-str form))))

(defn wall-hack-method [class-name name- params obj & args]
    (-> class-name (.getDeclaredMethod (name name-)
                                        (into-array Class params))
            (doto (.setAccessible true))
                (.invoke obj (into-array Object args))))

;; TODO: split this function up
(defn eval-in-project
  "Executes form in an isolated classloader with the classpath and compile path
  set correctly for the project. Pass in a handler function to have it called
  with the java task right before executing if you need to customize any of its
  properties (classpath, library-path, etc)."
  [project form & [init skip-auto-compile]]
  (when (and (not skip-auto-compile)
             (empty? (.list (file (:compile-path project)))))
    (binding [*silently* true]
      (compile project)))
  (when (empty? (find-lib-jars project))
    (deps project))
  (let [cp (for [i (get-classpath project)
                 :let [f (if (string? i) (file i) i)]]
             (str (.getAbsolutePath f)
                  (when (.isDirectory f)
                    "/")))
        cp (for [c cp] (java.net.URL. (format "file://%s" c)))
        cp (into-array java.net.URL cp)
        cl (java.net.URLClassLoader.
            cp
            (proxy [ClassLoader] []
              (findClass [name]
                         (throw (ClassNotFoundException. name)))
              (getParent [] this)
              (getResource [_] nil)
              (getResourceAsStream [_] nil)
              (getResources [_] nil)
              (loadClass
               ([name]
                  (.loadClass this name false))
               ([name x]
                  (if (or (.startsWith name "java.")
                          (.startsWith name "sun.")
                          (.startsWith name "org.xml"))
                    (proxy-super loadClass name x)
                    (throw (ClassNotFoundException. name)))))))
        old-cl (.getContextClassLoader (Thread/currentThread))]
    (try
      (.setContextClassLoader (Thread/currentThread) cl)
      (let [rt (.loadClass cl "clojure.lang.RT")
            compiler (.loadClass cl "clojure.lang.Compiler")
            read-string (fn [s] (wall-hack-method
                                 rt :readString [String] nil s))
            eval (fn [f]
                   (wall-hack-method
                    compiler :eval [Object] nil f))]
        (println init)
        (prn form)
        (try
          (eval
           (read-string
            (pr-str
             `(do ~init
                  (binding [*warn-on-reflection* false
                            *compile-path* ~(:compile-path project)
                            *use-context-classloader* true]
                    ~form)))))
          0
          (catch Throwable t
            (.printStackTrace t)
            1)))
      (finally
       (.setContextClassLoader (Thread/currentThread) old-cl)))))

(defn- platform-nullsink []
  (file (if (= :windows (get-os))
          "NUL"
          "/dev/null")))

(defn- status [code msg]
  (when-not *silently*
    (.write (if (zero? code) *out* *err*) (str msg "\n")))
  code)

(def ^:private success (partial status 0))
(def ^:private failure (partial status 1))

(defn compile
  "Ahead-of-time compile the namespaces given under :aot in project.clj or
those given as command-line arguments."
  ([project]
     (.mkdir (file (:compile-path project)))
     (if (seq (compilable-namespaces project))
       (if-let [namespaces (seq (stale-namespaces project))]
         (if (zero? (eval-in-project project
                                     `(doseq [namespace# '~namespaces]
                                        (when-not ~*silently*
                                          (println "Compiling" namespace#))
                                        (clojure.core/compile namespace#))
                                     (when *suppress-err*
                                       #(.setError % (platform-nullsink)))
                                     :skip-auto-compile))
           (success "Compilation succeeded.")
           (failure "Compilation failed."))
         (success "All namespaces already :aot compiled."))
       (success "No namespaces to :aot compile listed in project.clj.")))
  ([project & namespaces]
     (compile (assoc project
                :aot (if (= namespaces [":all"])
                       :all
                       (map symbol namespaces))))))
