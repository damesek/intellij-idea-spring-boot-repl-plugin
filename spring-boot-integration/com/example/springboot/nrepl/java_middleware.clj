;; Place this file under src/main/resources/com/example/springboot/nrepl/java_middleware.clj
;; so that RT.loadResourceScript can find it.
(ns com.example.springboot.nrepl.java-middleware
  (:require [nrepl.server :as server]
            [nrepl.transport :as transport]
            [nrepl.misc :refer [response-for]]
            [nrepl.middleware :refer [set-descriptor!]]))

(defn java-eval-middleware [handler]
  (fn [{:keys [op code] :as msg}]
    (if (and (= op "eval") (string? code) (.startsWith ^String code "//!java"))
      (do
        (com.example.springboot.nrepl.JavaMiddleware/handle msg)
        ;; Java side sends all responses, no need to call handler
        nil)
      (handler msg))))

(set-descriptor! #'java-eval-middleware
  {:expects #{}
   :requires #{}
   :handles {"eval" {:doc "Evaluates Java code when code starts with //!java"}}})

