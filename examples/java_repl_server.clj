(ns java-repl-server
  (:require [nrepl.server :as server]
            [nrepl.middleware :refer [set-descriptor!]]
            [nrepl.transport :as transport]
            [nrepl.misc :refer [response-for]])
  (:import [javax.tools ToolProvider SimpleJavaFileObject JavaFileObject$Kind]
           [java.io StringWriter PrintWriter ByteArrayOutputStream]
           [java.net URI URLClassLoader URL]
           [java.lang.reflect Method]))

(defn compile-and-run-java
  "Compiles and runs Java code, returns the result"
  [code]
  (try
    (let [class-name "DynamicJavaClass"
          ;; Wrap the code in a class if it's not already
          full-code (if (.contains code "class ")
                      code
                      (str "public class " class-name " {\n"
                           "    public static Object run() throws Exception {\n"
                           "        " code "\n"
                           "        return null;\n"
                           "    }\n"
                           "}"))
          
          ;; Create Java source file in memory
          source-file (proxy [SimpleJavaFileObject]
                        [(URI/create (str "string:///" class-name ".java"))
                         JavaFileObject$Kind/SOURCE]
                        (getCharContent [_] full-code))
          
          ;; Get Java compiler
          compiler (ToolProvider/getSystemJavaCompiler)
          
          ;; Setup compilation
          diag-collector (.getDiagnosticCollector compiler)
          file-manager (.getStandardFileManager compiler diag-collector nil nil)
          
          ;; Compile output to byte array
          byte-out (ByteArrayOutputStream.)
          
          ;; Create compilation task
          compilation-task (.getTask compiler
                                    nil
                                    file-manager
                                    diag-collector
                                    nil
                                    nil
                                    [source-file])
          
          ;; Compile
          success (.call compilation-task)]
      
      (if success
        ;; Load and run the compiled class
        (let [class-loader (URLClassLoader. (into-array URL []))
              clazz (.loadClass class-loader class-name)
              method (.getMethod clazz "run" (into-array Class []))
              result (.invoke method nil (object-array []))]
          {:status :done
           :value (str result)})
        
        ;; Compilation failed - return errors
        {:status :done
         :err (str "Compilation failed:\n"
                   (->> (.getDiagnostics diag-collector)
                        (map str)
                        (clojure.string/join "\n")))}))
    
    (catch Exception e
      {:status :done
       :err (str "Error: " (.getMessage e) "\n"
                (with-out-str (.printStackTrace e)))})))

(defn java-eval
  "Middleware handler for Java evaluation"
  [{:keys [op code transport] :as msg}]
  (when (and (= op "eval")
             code
             (.startsWith code "//!java"))
    (let [;; Remove the //!java prefix
          java-code (-> code
                       (clojure.string/split #"\n" 2)
                       second
                       (or ""))
          
          ;; Capture stdout
          out-writer (StringWriter.)
          err-writer (StringWriter.)
          
          ;; Run the Java code
          result (binding [*out* (PrintWriter. out-writer)
                          *err* (PrintWriter. err-writer)]
                   (compile-and-run-java java-code))
          
          ;; Get captured output
          stdout (str out-writer)
          stderr (str err-writer)]
      
      ;; Send response
      (when-not (empty? stdout)
        (transport/send transport
                       (response-for msg {:out stdout})))
      
      (when-not (empty? stderr)
        (transport/send transport
                       (response-for msg {:err stderr})))
      
      (when (:value result)
        (transport/send transport
                       (response-for msg {:value (:value result)})))
      
      (when (:err result)
        (transport/send transport
                       (response-for msg {:err (:err result)})))
      
      (transport/send transport
                     (response-for msg {:status :done}))
      
      ;; Mark as handled
      ::handled)))

(defn java-middleware
  "nREPL middleware for Java evaluation"
  [handler]
  (fn [msg]
    (let [result (java-eval msg)]
      (when-not (= result ::handled)
        (handler msg)))))

(set-descriptor! #'java-middleware
  {:requires #{}
   :expects #{"eval"}
   :handles {"java-eval" {:doc "Evaluates Java code"
                          :requires {"code" "The Java code to evaluate"}
                          :optional {}
                          :returns {"status" "done"}}}})

;; Server indítása
(defn start-server []
  (println "Starting nREPL server with Java support on port 5557...")
  (server/start-server
    :port 5557
    :handler (server/default-handler #'java-middleware)))

;; Főfüggvény
(defn -main []
  (let [server (start-server)]
    (println "nREPL server started on port 5557")
    (println "Java code evaluation enabled (prefix with //!java)")
    ;; Keep the server running
    @(promise)))

;; Példa használat:
(comment
  ;; Indítsd el a szervert:
  (def server (start-server))
  
  ;; Állítsd le:
  (.close server))