(defproject adgoji.aws.cloudformation.malli (or (System/getenv "VERSION") "none")  
  :dependencies []
  :jvm-opts ["-Dmalli.registry/type=custom"]
  :plugins [[nrepl/lein-nrepl "0.3.2"]
            [lein-auto "0.1.3"]
  
            ;; Use lein-tools-deps with the right exclusions https://github.com/RickMoynihan/lein-tools-deps/pull/93/files
  
            [lein-tools-deps "0.4.5" :exclusions [org.clojure/tools.deps.alpha org.clojure/clojure]]
            [org.clojure/tools.deps.alpha "0.7.541" #_"0.9.755"]]
  
  :middleware [lein-tools-deps.plugin/resolve-dependencies-with-deps-edn]
  :lein-tools-deps/config {:config-files [#_:install #_:user :project]}
  
  :profiles {:dev 
             {:resource-paths ["dev-resources"]}})