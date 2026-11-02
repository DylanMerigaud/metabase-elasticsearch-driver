(ns build
  "Builds the plugin jar: the driver sources and the plugin manifest, nothing else (Metabase
  provides every dependency at runtime).

    clojure -T:build jar   ;; => target/elasticsearch.metabase-driver.jar"
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.github.dylanmerigaud/elasticsearch-driver)

(def ^:private class-dir "target/classes")
(def ^:private jar-file "target/elasticsearch.metabase-driver.jar")

(defn clean "Delete target/." [_]
  (b/delete {:path "target"}))

(defn jar "Build target/elasticsearch.metabase-driver.jar." [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println jar-file))
