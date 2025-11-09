(defproject java-repl-server "0.1.0-SNAPSHOT"
  :description "nREPL server with Java code evaluation support"
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [nrepl/nrepl "1.1.0"]]
  :main java-repl-server
  :repl-options {:init-ns java-repl-server})